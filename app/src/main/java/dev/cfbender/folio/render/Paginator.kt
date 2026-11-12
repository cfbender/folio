package dev.cfbender.folio.render

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale
import kotlin.math.min

/** Everything about type that changes how text flows. Changing any of it means re-paginating. */
data class Typography(
    val typeface: Typeface,
    val fontSizePx: Float,
    /** Distance between baselines as a multiple of the font size. */
    val lineSpacing: Float = 1.5f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val locale: Locale? = null,
)

/** Size of the text area of one page, in pixels. */
data class PageSize(val width: Int, val height: Int)

sealed class PageItem(val block: Int) {
    /** Lines [firstLine, endLine) of a paragraph's layout, placed with its first line at [y]. */
    class Text(block: Int, val layout: StaticLayout, val firstLine: Int, val endLine: Int, val x: Float, val y: Float) : PageItem(block)

    class Image(block: Int, val path: String, val bounds: RectF) : PageItem(block)

    class Rule(block: Int, val y: Float) : PageItem(block)
}

class Page(
    val index: Int,
    val items: List<PageItem>,
    /** Block and character offset of the first thing on the page: its stable reading position. */
    val block: Int,
    val char: Int,
)

class ChapterLayout(
    val chapter: Chapter,
    val pages: List<Page>,
    val size: PageSize,
    private val paint: TextPaint,
) {
    /** The page showing the given position: the last page starting at or before it. */
    fun pageOf(block: Int, char: Int): Int {
        var result = 0
        for (page in pages) {
            if (page.block < block || (page.block == block && page.char <= char)) result = page.index else break
        }
        return result
    }

    /** The words on [page] in reading order, for accessibility services. */
    fun text(page: Page): String = buildString {
        for (item in page.items) {
            if (item !is PageItem.Text) continue
            if (isNotEmpty()) append('\n')
            append(item.layout.text, item.layout.getLineStart(item.firstLine), item.layout.getLineStart(item.endLine))
        }
    }

    /**
     * Draws [page] with its text area's top-left corner at the canvas origin. [bitmap] returns a
     * decoded image for a path, or null while it is still loading.
     */
    fun draw(canvas: Canvas, page: Page, textColor: Int, bitmap: (String) -> Bitmap?) {
        paint.color = textColor
        for (item in page.items) {
            when (item) {
                is PageItem.Text -> {
                    val top = item.layout.getLineTop(item.firstLine)
                    val bottom = item.layout.getLineTop(item.endLine)
                    canvas.save()
                    canvas.translate(item.x, item.y - top)
                    canvas.clipRect(-item.x, top.toFloat(), size.width.toFloat(), bottom.toFloat())
                    item.layout.draw(canvas)
                    canvas.restore()
                }
                is PageItem.Image -> {
                    val image = bitmap(item.path)
                    if (image != null) {
                        canvas.drawBitmap(image, null, item.bounds, IMAGE_PAINT)
                    } else {
                        RULE_PAINT.color = textColor and 0x00FFFFFF or 0x14000000
                        canvas.drawRect(item.bounds, RULE_PAINT)
                    }
                }
                is PageItem.Rule -> {
                    RULE_PAINT.color = textColor and 0x00FFFFFF or 0x55000000
                    val inset = size.width * 0.3f
                    canvas.drawRect(inset, item.y, size.width - inset, item.y + 1.5f, RULE_PAINT)
                }
            }
        }
    }

    private companion object {
        val IMAGE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val RULE_PAINT = Paint()
    }
}

/**
 * Flows a chapter's blocks into pages. Text is shaped by the platform's [StaticLayout] (so it gets
 * real hyphenation, justification, and optimal line breaking) and split across pages on line
 * boundaries.
 *
 * @param imageSize intrinsic pixel size of an image in the book, or null if it cannot be decoded.
 * @param density screen density, used to show small images at their natural size.
 */
fun paginate(
    chapter: Chapter,
    size: PageSize,
    typography: Typography,
    density: Float = 1f,
    imageSize: (String) -> Pair<Int, Int>?,
): ChapterLayout = Paginator(size, typography, imageSize, density).paginate(chapter)

/** Holds the cursor for one pagination run; use [paginate]. */
private class Paginator(
    private val size: PageSize,
    private val typography: Typography,
    private val imageSize: (String) -> Pair<Int, Int>?,
    private val density: Float,
) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typography.typeface
        textSize = typography.fontSizePx
        typography.locale?.let { textLocale = it }
    }
    private val em = typography.fontSizePx
    private val lineHeight = em * typography.lineSpacing

    // StaticLayout spaces lines as a multiple of the font's own line height, which varies a lot
    // between typefaces, so the multiple is derived from the baseline distance asked for.
    private val spacingMultiplier = lineHeight / paint.fontMetrics.let { it.descent - it.ascent }

    private val pages = ArrayList<Page>()
    private var items = ArrayList<PageItem>()
    private var y = 0f
    private var pageBlock = 0
    private var pageChar = 0

    fun paginate(chapter: Chapter): ChapterLayout {
        val width = size.width.toFloat()
        val height = size.height.toFloat()

        chapter.blocks.forEachIndexed { index, block ->
            if (block.breakBefore && items.isNotEmpty()) newPage()
            // Margins vanish at an unforced page top, but a chapter opening keeps its drop.
            val atTop = items.isEmpty()
            val gap = if (atTop && index != 0 && !block.breakBefore) 0f
            else min(block.marginTop.resolve(em, width), height * 0.3f)

            when (block) {
                is TextBlock -> text(index, block, gap)
                is ImageBlock -> image(index, block, gap)
                is RuleBlock -> {
                    if (y + gap + lineHeight > height && items.isNotEmpty()) newPage()
                    start(index, 0)
                    y += if (items.isEmpty()) 0f else gap
                    items += PageItem.Rule(index, y)
                    y += 2f
                }
            }
        }
        if (items.isNotEmpty() || pages.isEmpty()) newPage()
        return ChapterLayout(chapter, pages, size, paint)
    }

    private fun text(index: Int, block: TextBlock, marginTop: Float) {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val left = min(block.insetLeft.resolve(em, width), width * 0.25f)
        val right = min(block.insetRight.resolve(em, width), width * 0.25f)
        val layout = layout(block, (width - left - right).toInt().coerceAtLeast(1))
        var gap = marginTop

        // Keep a heading with the text that follows it rather than stranding it at a page bottom.
        if (block.heading && items.isNotEmpty() && y + gap + layout.height + 2 * lineHeight > height) {
            newPage()
            gap = 0f
        }

        var line = 0
        while (line < layout.lineCount) {
            val available = height - y - gap
            val top = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= available) end++
            val remaining = layout.lineCount - line
            var fit = end - line
            if (fit < remaining) {
                // Avoid orphans (a lone first line at a page bottom) and widows (a lone last line
                // at a page top) when the paragraph is long enough to rebalance.
                if (fit == 1 && items.isNotEmpty()) fit = 0
                else if (remaining - fit == 1 && fit > 2) fit--
            }
            if (fit == 0) {
                if (items.isEmpty()) {
                    fit = 1 // A single line taller than the page still has to go somewhere.
                } else {
                    newPage()
                    gap = 0f
                    continue
                }
            }
            start(index, layout.getLineStart(line))
            y += gap
            items += PageItem.Text(index, layout, line, line + fit, left, y)
            y += layout.getLineTop(line + fit) - top
            line += fit
            if (line == layout.lineCount) y += trailingSpacing(layout)
            if (line < layout.lineCount) {
                newPage()
                gap = 0f
            }
        }
    }

    private fun image(index: Int, block: ImageBlock, marginTop: Float) {
        val (pxWidth, pxHeight) = imageSize(block.path) ?: return
        if (pxWidth <= 0 || pxHeight <= 0) return
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        var scale = min(density, width / pxWidth)
        scale = min(scale, height / pxHeight)
        // Large illustrations fill the column instead of floating at an arbitrary intrinsic size.
        if (pxWidth * density >= width * 0.6f) scale = min(width / pxWidth, height / pxHeight)
        val w = pxWidth * scale
        val h = pxHeight * scale
        var gap = marginTop
        if (y + gap + h > height && items.isNotEmpty()) {
            newPage()
            gap = 0f
        }
        start(index, 0)
        y += gap
        val x = (width - w) / 2f
        items += PageItem.Image(index, block.path, RectF(x, y, x + w, y + h))
        y += h
    }

    // The Layout.* constants exist at minSdk 26 and have the same values as LineBreaker's (API 29).
    @SuppressLint("WrongConstant")
    private fun layout(block: TextBlock, width: Int): StaticLayout {
        val justify = when (block.align) {
            Align.JUSTIFY -> true
            Align.DEFAULT -> typography.justify && !block.heading
            else -> false
        }
        val indent = block.indent.resolve(em, width.toFloat()).toInt().coerceIn(0, width / 3)
        return StaticLayout.Builder.obtain(block.text, 0, block.text.length, paint, width)
            .setAlignment(
                when (block.align) {
                    Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setLineSpacing(0f, spacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(
                if (typography.hyphenate && block.hyphenate) Layout.HYPHENATION_FREQUENCY_NORMAL
                else Layout.HYPHENATION_FREQUENCY_NONE,
            )
            .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .setIndents(if (indent > 0) intArrayOf(indent, 0) else null, null)
            .build()
    }

    /**
     * The spacing StaticLayout leaves off below a paragraph's last line (it has done so since
     * API 28). Adding it back keeps baselines evenly spaced from one paragraph into the next.
     */
    private fun trailingSpacing(layout: StaticLayout): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0f
        val last = layout.lineCount - 1
        return (layout.getLineBottom(last) - layout.getLineTop(last)) * (spacingMultiplier - 1f)
    }

    /** Records the page's reading position when its first item is placed. */
    private fun start(block: Int, char: Int) {
        if (items.isEmpty()) {
            pageBlock = block
            pageChar = char
        }
    }

    private fun newPage() {
        // A plate alone on its page (a cover, a full-page illustration) sits in the middle of it.
        (items.singleOrNull() as? PageItem.Image)?.bounds?.let { it.offsetTo(it.left, (size.height - it.height()) / 2f) }
        pages += Page(pages.size, items, pageBlock, pageChar)
        items = ArrayList()
        y = 0f
    }
}
