package dev.cfbender.folio.render

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import dev.cfbender.folio.epub.EpubBook
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.concurrent.ConcurrentHashMap

/** Where a stretch of a [TextBlock]'s text came from in the source document. */
class SourceRun(val start: Int, val node: TextNode, val rawStart: Int)

/**
 * One vertically stacked piece of a chapter. Blocks are independent of page size and font size, so
 * a chapter is parsed once and can be re-paginated cheaply when the window is folded or resized.
 */
sealed class Block(
    /** The element this block was produced from; anchors positions for sync. */
    val element: Element,
    /** Gap above this block, already collapsed with the margins of whatever precedes it. */
    val marginTop: Len,
    val breakBefore: Boolean,
) {
    /** Characters of chapter text before this block; set once by [ChapterParser]. */
    var charStart: Int = 0
        internal set
    abstract val charLength: Int
}

class TextBlock(
    element: Element,
    marginTop: Len,
    breakBefore: Boolean,
    val text: CharSequence,
    val runs: List<SourceRun>,
    val align: Align,
    val indent: Len,
    val insetLeft: Len,
    val insetRight: Len,
    val heading: Boolean,
    val hyphenate: Boolean,
) : Block(element, marginTop, breakBefore) {
    override val charLength get() = text.length
}

class ImageBlock(
    element: Element,
    marginTop: Len,
    breakBefore: Boolean,
    val path: String,
) : Block(element, marginTop, breakBefore) {
    override val charLength get() = 1
}

class RuleBlock(element: Element, marginTop: Len) : Block(element, marginTop, false) {
    override val charLength get() = 1
}

class Chapter(
    val spineIndex: Int,
    val blocks: List<Block>,
    /** Element id → index of the block at or after it; targets for TOC and link fragments. */
    val anchors: Map<String, Int>,
    val body: Element?,
) {
    val charCount: Int = blocks.lastOrNull()?.let { it.charStart + it.charLength } ?: 0

    /** Fraction of this chapter's text that precedes the given position. */
    fun fractionAt(block: Int, char: Int): Float {
        val b = blocks.getOrNull(block) ?: return 0f
        return if (charCount == 0) 0f else (b.charStart + char.coerceIn(0, b.charLength)).toFloat() / charCount
    }

    /** Inverse of [fractionAt]: the block and character offset at [fraction] of the chapter. */
    fun positionAt(fraction: Float): Pair<Int, Int> {
        if (blocks.isEmpty()) return 0 to 0
        val target = (fraction.coerceIn(0f, 1f) * charCount).toInt()
        val index = blocks.indexOfLast { it.charStart <= target }.coerceAtLeast(0)
        return index to (target - blocks[index].charStart).coerceIn(0, (blocks[index].charLength - 1).coerceAtLeast(0))
    }
}

/** Enables an OpenType feature such as `smcp` for the spanned text. */
private class FontFeatureSpan(private val feature: String) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.fontFeatureSettings = feature
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.fontFeatureSettings = feature
    }
}

/** Turns spine documents into [Chapter]s. Safe to call from several threads. */
class ChapterParser(private val book: EpubBook) {
    private val stylesheets = ConcurrentHashMap<String, Stylesheet>()

    fun parse(spineIndex: Int): Chapter {
        val path = book.spine[spineIndex].path
        val doc = book.readDocument(path) ?: return Chapter(spineIndex, emptyList(), emptyMap(), null)
        val body = doc.getElementsByTag("body").first() ?: return Chapter(spineIndex, emptyList(), emptyMap(), null)

        val cssPaths = doc.getElementsByTag("link")
            .filter { it.attr("rel").contains("stylesheet", ignoreCase = true) }
            .map { EpubBook.resolvePath(path, it.attr("href")) }
        val inlineCss = doc.getElementsByTag("style").joinToString("\n") { it.data() }
        val key = cssPaths.joinToString("|") + "|" + inlineCss.hashCode()
        val sheet = stylesheets.getOrPut(key) {
            Stylesheet.parse(*(cssPaths.mapNotNull(book::readText) + inlineCss).toTypedArray())
        }

        val walker = Walker(path, StyleResolver(sheet))
        val root = Style().apply { display = Display.BLOCK }
        walker.walk(body, root, BlockContext(body, root, Len.ZERO, Len.ZERO))
        walker.flush()

        var chars = 0
        for (block in walker.blocks) {
            block.charStart = chars
            chars += block.charLength
        }
        return Chapter(spineIndex, walker.blocks, walker.anchors, body)
    }

    private class BlockContext(val element: Element, val style: Style, val insetLeft: Len, val insetRight: Len)

    private class Walker(private val chapterPath: String, private val resolver: StyleResolver) {
        val blocks = ArrayList<Block>()
        val anchors = HashMap<String, Int>()

        private var text: SpannableStringBuilder? = null
        private var runs = ArrayList<SourceRun>()
        private var textContext: BlockContext? = null
        private var lastWasSpace = true

        // Adjacent vertical margins collapse to the larger one, as in CSS.
        private var pendingMargin = Len.ZERO
        private var pendingBreak = false
        private val listCounters = ArrayList<Int>()

        fun walk(node: Node, parentStyle: Style, context: BlockContext) {
            when (node) {
                is TextNode -> appendText(node, parentStyle, context)
                is Element -> element(node, parentStyle, context)
            }
        }

        private fun element(el: Element, parentStyle: Style, context: BlockContext) {
            // The body's own style comes from the walk's root; resolving it again is harmless.
            val style = resolver.compute(el, parentStyle)
            val tag = el.tagName().lowercase()
            if (style.display == Display.NONE) return
            // An image is an image however it is displayed; books often make them blocks.
            when (tag) {
                "img" -> return image(el, el.attr("src"), style, context)
                "image" -> return image(el, el.attr("xlink:href").ifEmpty { el.attr("href") }, style, context)
            }
            when (style.display) {
                Display.NONE -> return
                Display.INLINE -> when (tag) {
                    "br" -> lineBreak(context)
                    else -> {
                        anchor(el)
                        for (child in el.childNodes()) walk(child, style, context)
                    }
                }
                Display.BLOCK, Display.LIST_ITEM -> {
                    flush()
                    anchor(el)
                    if (style.breakBefore) pendingBreak = true
                    pendingMargin = larger(pendingMargin, style.marginTop)
                    if (tag == "hr") {
                        blocks += RuleBlock(el, pendingMargin)
                        pendingMargin = style.marginBottom
                        return
                    }
                    val inner = BlockContext(el, style, context.insetLeft + style.marginLeft, context.insetRight + style.marginRight)
                    if (tag == "ol") listCounters += (el.attr("start").toIntOrNull() ?: 1) else if (tag == "ul") listCounters += 0
                    if (style.display == Display.LIST_ITEM) listMarker(inner)
                    for (child in el.childNodes()) walk(child, style, inner)
                    flush()
                    if (tag == "ol" || tag == "ul") listCounters.removeAt(listCounters.size - 1)
                    pendingMargin = larger(pendingMargin, style.marginBottom)
                    if (style.breakAfter) pendingBreak = true
                }
            }
        }

        private fun anchor(el: Element) {
            val id = el.id()
            if (id.isNotEmpty()) anchors.putIfAbsent(id, blocks.size)
        }

        private fun builder(context: BlockContext): SpannableStringBuilder =
            text ?: SpannableStringBuilder().also {
                text = it
                textContext = context
                lastWasSpace = true
            }

        private fun listMarker(context: BlockContext) {
            val counter = listCounters.lastOrNull() ?: 0
            val marker = if (counter > 0) "$counter. " else "• "
            if (counter > 0) listCounters[listCounters.size - 1] = counter + 1
            builder(context).append(marker)
        }

        private fun lineBreak(context: BlockContext) {
            builder(context).append('\n')
            lastWasSpace = true
        }

        private fun appendText(node: TextNode, style: Style, context: BlockContext) {
            val raw = node.wholeText
            if (text == null && raw.isBlank() && !style.pre) return
            val sb = builder(context)
            val start = sb.length
            // Collapsing drops the node's leading whitespace when the text before it ended in a space.
            val rawStart = if (!style.pre && lastWasSpace) raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) else 0
            if (style.pre) {
                sb.append(raw)
            } else {
                for (c in raw) {
                    if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                        if (!lastWasSpace) sb.append(' ')
                        lastWasSpace = true
                    } else {
                        sb.append(c)
                        lastWasSpace = false
                    }
                }
            }
            if (sb.length == start) return
            if (style.transform != TextTransform.NONE) {
                val piece = sb.subSequence(start, sb.length).toString()
                sb.replace(start, sb.length, if (style.transform == TextTransform.UPPERCASE) piece.uppercase() else piece.lowercase())
            }
            runs += SourceRun(start, node, rawStart)
            applySpans(sb, start, sb.length, style)
        }

        private fun applySpans(sb: SpannableStringBuilder, start: Int, end: Int, style: Style) {
            fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (style.monospace) span(TypefaceSpan("monospace"))
            when {
                style.bold && style.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                style.bold -> span(StyleSpan(Typeface.BOLD))
                style.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (style.smallCaps) span(FontFeatureSpan("smcp"))
            if (style.underline) span(UnderlineSpan())
            if (style.strike) span(StrikethroughSpan())
            val scale = if (style.baseline != 0) style.fontScale * 0.72f else style.fontScale
            if (style.baseline > 0) span(SuperscriptSpan()) else if (style.baseline < 0) span(SubscriptSpan())
            if (scale != 1f) span(RelativeSizeSpan(scale))
        }

        private fun image(el: Element, href: String, style: Style, context: BlockContext) {
            if (href.isBlank() || href.startsWith("data:")) return
            flush()
            anchor(el)
            val block = style.display != Display.INLINE
            if (block) {
                if (style.breakBefore) pendingBreak = true
                pendingMargin = larger(pendingMargin, style.marginTop)
            }
            blocks += ImageBlock(el, pendingMargin, takeBreak(), EpubBook.resolvePath(chapterPath, href))
            pendingMargin = if (block) larger(Len(em = 0.4f), style.marginBottom) else Len(em = 0.4f)
            if (block && style.breakAfter) pendingBreak = true
        }

        fun flush() {
            val sb = text ?: return
            val context = textContext!!
            text = null
            textContext = null
            var end = sb.length
            while (end > 0 && (sb[end - 1] == ' ' || sb[end - 1] == '\n')) end--
            if (end < sb.length) sb.delete(end, sb.length)
            val blockRuns = runs
            runs = ArrayList()
            if (sb.isEmpty()) return
            val style = context.style
            blocks += TextBlock(
                element = context.element,
                marginTop = pendingMargin,
                breakBefore = takeBreak(),
                text = sb,
                runs = blockRuns,
                align = style.align,
                indent = if (style.align == Align.CENTER || style.align == Align.RIGHT) Len.ZERO else style.indent,
                insetLeft = context.insetLeft,
                insetRight = context.insetRight,
                heading = style.heading,
                hyphenate = style.hyphenate,
            )
            pendingMargin = Len.ZERO
        }

        private fun takeBreak(): Boolean = pendingBreak.also { pendingBreak = false }

        private fun larger(a: Len, b: Len): Len = if (nominal(b) > nominal(a)) b else a

        /** Compares mixed-unit lengths at a typical page geometry; only the ordering matters. */
        private fun nominal(len: Len): Float = len.em * 16f + len.pct * 360f
    }
}
