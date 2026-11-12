package dev.cfbender.folio.render

import org.jsoup.nodes.Element

/*
 * A deliberately small CSS engine: enough of the cascade to honour what book stylesheets use to
 * shape prose (emphasis, alignment, indents, margins, font size, page breaks) and nothing aimed at
 * screen layout (floats, positioning, tables, colours). Unsupported selectors and properties are
 * skipped, so an unusual stylesheet degrades to the built-in book defaults rather than failing.
 */

enum class Display { BLOCK, INLINE, LIST_ITEM, NONE }

/** [DEFAULT] follows the reader's justification preference; the others are explicit. */
enum class Align { DEFAULT, LEFT, CENTER, RIGHT, JUSTIFY }

enum class TextTransform { NONE, UPPERCASE, LOWERCASE }

/** A length with a font-relative part (in root ems) and a part relative to the page width. */
data class Len(val em: Float = 0f, val pct: Float = 0f) {
    fun resolve(emPx: Float, widthPx: Float): Float = em * emPx + pct * widthPx
    operator fun plus(other: Len) = Len(em + other.em, pct + other.pct)

    companion object {
        val ZERO = Len()
    }
}

/** Computed style of one element. Built by [StyleResolver.compute] and not mutated afterwards. */
class Style {
    // Inherited
    var fontScale = 1f
    var italic = false
    var bold = false
    var align = Align.DEFAULT
    var indent = Len(em = 1.2f)
    var smallCaps = false
    var transform = TextTransform.NONE
    var pre = false
    var monospace = false
    var hyphenate = true

    // Not inherited
    var display = Display.INLINE
    var marginTop = Len.ZERO
    var marginBottom = Len.ZERO
    var marginLeft = Len.ZERO
    var marginRight = Len.ZERO
    var underline = false
    var strike = false
    var baseline = 0 // 1 superscript, -1 subscript
    var breakBefore = false
    var breakAfter = false
    var heading = false

    fun inherit(): Style {
        val parent = this
        return Style().apply {
            fontScale = parent.fontScale
            italic = parent.italic
            bold = parent.bold
            align = parent.align
            indent = parent.indent
            smallCaps = parent.smallCaps
            transform = parent.transform
            pre = parent.pre
            monospace = parent.monospace
            hyphenate = parent.hyphenate
            // Decorations are not inherited in CSS but do propagate to descendant text.
            underline = parent.underline
            strike = parent.strike
            baseline = parent.baseline
        }
    }
}

private class Compound(val tag: String?, val id: String?, val classes: List<String>) {
    fun matches(el: Element): Boolean {
        if (tag != null && !el.tagName().equals(tag, ignoreCase = true)) return false
        if (id != null && el.id() != id) return false
        for (c in classes) if (!el.hasClass(c)) return false
        return true
    }
}

private class Rule(val compounds: List<Compound>, val declarations: List<Pair<String, String>>, val order: Int) {
    val specificity: Int = compounds.sumOf { c -> (if (c.id != null) 10_000 else 0) + c.classes.size * 100 + (if (c.tag != null) 1 else 0) }

    /** Matches the rightmost compound against [el] and the rest against its ancestors, in order. */
    fun matches(el: Element): Boolean {
        if (!compounds.last().matches(el)) return false
        var i = compounds.size - 2
        var ancestor = el.parent()
        while (i >= 0 && ancestor != null) {
            if (compounds[i].matches(ancestor)) i--
            ancestor = ancestor.parent()
        }
        return i < 0
    }
}

class Stylesheet private constructor(private val rules: List<Rule>) {
    private val byTag = HashMap<String, MutableList<Rule>>()
    private val byClass = HashMap<String, MutableList<Rule>>()
    private val byId = HashMap<String, MutableList<Rule>>()

    init {
        for (rule in rules) {
            val key = rule.compounds.last()
            when {
                key.id != null -> byId.getOrPut(key.id) { ArrayList() } += rule
                key.classes.isNotEmpty() -> byClass.getOrPut(key.classes[0]) { ArrayList() } += rule
                key.tag != null -> byTag.getOrPut(key.tag) { ArrayList() } += rule
            }
        }
    }

    val isEmpty get() = rules.isEmpty()

    /** Declarations that apply to [el], lowest precedence first. */
    fun declarationsFor(el: Element): List<Pair<String, String>> {
        var candidates: MutableList<Rule>? = null
        fun add(list: List<Rule>?) {
            if (list == null) return
            val out = candidates ?: ArrayList<Rule>().also { candidates = it }
            for (rule in list) if (rule.matches(el)) out += rule
        }
        add(byTag[el.tagName().lowercase()])
        val classAttr = el.attr("class")
        if (classAttr.isNotEmpty()) for (c in el.classNames()) add(byClass[c])
        val id = el.id()
        if (id.isNotEmpty()) add(byId[id])
        val matched = candidates ?: return emptyList()
        matched.sortWith(compareBy<Rule> { it.specificity }.thenBy { it.order })
        return matched.flatMap { it.declarations }
    }

    companion object {
        val EMPTY = Stylesheet(emptyList())

        private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val COMPOUND = Regex("^([a-zA-Z][a-zA-Z0-9]*|\\*)?((?:[.#][-_a-zA-Z0-9]+)*)$")
        private val COMPOUND_PART = Regex("[.#][-_a-zA-Z0-9]+")

        /** Parses the given stylesheet sources as if concatenated in order. */
        fun parse(vararg sources: String): Stylesheet {
            val rules = ArrayList<Rule>()
            for (source in sources) parseInto(COMMENT.replace(source, ""), rules)
            return if (rules.isEmpty()) EMPTY else Stylesheet(rules)
        }

        private fun parseInto(css: String, rules: MutableList<Rule>) {
            var i = 0
            while (i < css.length) {
                val open = css.indexOf('{', i)
                if (open < 0) break
                val prelude = css.substring(i, open).trim()
                if (prelude.startsWith("@")) {
                    // A statement at-rule (`@import …;`) ends at the semicolon before any block.
                    val semi = prelude.indexOf(';')
                    if (semi >= 0) {
                        i += css.substring(i, open).indexOf(';') + 1
                        continue
                    }
                    i = skipBlock(css, open)
                    continue
                }
                val close = css.indexOf('}', open)
                if (close < 0) break
                val declarations = parseDeclarations(css.substring(open + 1, close))
                if (declarations.isNotEmpty()) {
                    for (selector in prelude.split(',')) {
                        parseSelector(selector.trim())?.let { rules += Rule(it, declarations, rules.size) }
                    }
                }
                i = close + 1
            }
        }

        private fun skipBlock(css: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < css.length) {
                when (css[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i + 1
                }
                i++
            }
            return css.length
        }

        /** Returns null for selectors this engine cannot evaluate, which drops the rule. */
        private fun parseSelector(selector: String): List<Compound>? {
            if (selector.isEmpty()) return null
            val parts = selector.replace(">", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val compounds = ArrayList<Compound>(parts.size)
            for (part in parts) {
                val match = COMPOUND.matchEntire(part) ?: return null
                val tag = match.groupValues[1].takeIf { it.isNotEmpty() && it != "*" }?.lowercase()
                var id: String? = null
                val classes = ArrayList<String>()
                for (m in COMPOUND_PART.findAll(match.groupValues[2])) {
                    if (m.value[0] == '#') id = m.value.substring(1) else classes += m.value.substring(1)
                }
                if (tag == null && id == null && classes.isEmpty()) return null
                compounds += Compound(tag, id, classes)
            }
            return compounds.takeIf { it.isNotEmpty() }
        }

        fun parseDeclarations(block: String): List<Pair<String, String>> =
            block.split(';').mapNotNull { decl ->
                val colon = decl.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = decl.substring(0, colon).trim().lowercase()
                val value = decl.substring(colon + 1).replace("!important", "").trim().lowercase()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
    }
}

class StyleResolver(private val sheet: Stylesheet) {

    fun compute(el: Element, parent: Style): Style {
        val style = parent.inherit()
        val tag = el.tagName().lowercase()
        applyDefaults(tag, style)
        if (!sheet.isEmpty) for ((name, value) in sheet.declarationsFor(el)) apply(style, tag, name, value)
        val inline = el.attr("style")
        if (inline.isNotEmpty()) for ((name, value) in Stylesheet.parseDeclarations(inline)) apply(style, tag, name, value)
        when (el.attr("align").lowercase()) {
            "center" -> style.align = Align.CENTER
            "right" -> style.align = Align.RIGHT
        }
        return style
    }

    private fun applyDefaults(tag: String, s: Style) {
        when (tag) {
            "p" -> s.display = Display.BLOCK
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                s.display = Display.BLOCK
                s.heading = true
                s.bold = true
                s.align = Align.CENTER
                s.indent = Len.ZERO
                s.hyphenate = false
                s.fontScale *= when (tag) {
                    "h1" -> 1.5f
                    "h2" -> 1.3f
                    "h3" -> 1.15f
                    else -> 1f
                }
                s.marginTop = Len(em = 1.2f * s.fontScale)
                s.marginBottom = Len(em = 0.6f * s.fontScale)
            }
            "blockquote" -> {
                s.display = Display.BLOCK
                s.marginTop = Len(em = 0.8f)
                s.marginBottom = Len(em = 0.8f)
                s.marginLeft = Len(em = 1.5f)
                s.marginRight = Len(em = 1.5f)
            }
            "ul", "ol", "dd" -> {
                s.display = Display.BLOCK
                s.marginLeft = Len(em = 1.5f)
                if (tag != "dd") {
                    s.marginTop = Len(em = 0.5f)
                    s.marginBottom = Len(em = 0.5f)
                }
            }
            "li" -> {
                s.display = Display.LIST_ITEM
                s.indent = Len.ZERO
            }
            "pre" -> {
                s.display = Display.BLOCK
                s.pre = true
                s.monospace = true
                s.indent = Len.ZERO
                s.align = Align.LEFT
                s.hyphenate = false
                s.fontScale *= 0.85f
                s.marginTop = Len(em = 0.8f)
                s.marginBottom = Len(em = 0.8f)
            }
            "figure" -> {
                s.display = Display.BLOCK
                s.marginTop = Len(em = 0.8f)
                s.marginBottom = Len(em = 0.8f)
            }
            "figcaption", "caption" -> {
                s.display = Display.BLOCK
                s.align = Align.CENTER
                s.indent = Len.ZERO
                s.fontScale *= 0.9f
            }
            "center" -> {
                s.display = Display.BLOCK
                s.align = Align.CENTER
                s.indent = Len.ZERO
            }
            "hr" -> {
                s.display = Display.BLOCK
                s.marginTop = Len(em = 1f)
                s.marginBottom = Len(em = 1f)
            }
            "td", "th", "dt" -> {
                s.display = Display.BLOCK
                s.indent = Len.ZERO
                if (tag == "th") s.bold = true
            }
            "html", "body", "div", "section", "article", "aside", "header", "footer", "nav", "main",
            "address", "dl", "table", "thead", "tbody", "tfoot", "tr", "hgroup", "details", "summary",
            "form", "fieldset" -> s.display = Display.BLOCK
            "head", "script", "style", "title", "template", "meta", "link", "noscript" -> s.display = Display.NONE
            "em", "i", "cite", "dfn", "var" -> s.italic = true
            "strong", "b" -> s.bold = true
            "u", "ins" -> s.underline = true
            "s", "strike", "del" -> s.strike = true
            "small" -> s.fontScale *= 0.85f
            "big" -> s.fontScale *= 1.15f
            "code", "tt", "kbd", "samp" -> s.monospace = true
            "sup" -> s.baseline = 1
            "sub" -> s.baseline = -1
        }
    }

    private fun apply(s: Style, tag: String, name: String, value: String) {
        when (name) {
            "font-style" -> s.italic = value == "italic" || value == "oblique"
            "font-weight" -> s.bold = when (value) {
                "bold", "bolder" -> true
                "normal", "lighter" -> false
                else -> (value.toIntOrNull() ?: 400) >= 600
            }
            "font-size" -> if (tag != "body" && tag != "html") fontSize(value, s)
            "font-variant", "font-variant-caps" -> s.smallCaps = "small-caps" in value
            "font-family" -> if ("monospace" in value || "courier" in value) s.monospace = true
            "text-transform" -> s.transform = when (value) {
                "uppercase" -> TextTransform.UPPERCASE
                "lowercase" -> TextTransform.LOWERCASE
                else -> TextTransform.NONE
            }
            "text-align" -> s.align = when (value) {
                "center" -> Align.CENTER
                "right", "end" -> Align.RIGHT
                "justify" -> Align.JUSTIFY
                // Explicit left alignment on headings is a design choice; on body text it is
                // usually boilerplate, so the reader's justification preference wins.
                "left", "start" -> if (s.heading) Align.LEFT else Align.DEFAULT
                else -> s.align
            }
            "text-indent" -> length(value, s.fontScale)?.let { s.indent = it }
            "text-decoration", "text-decoration-line" -> {
                s.underline = "underline" in value
                s.strike = "line-through" in value
            }
            "vertical-align" -> s.baseline = when (value) {
                "super" -> 1
                "sub" -> -1
                else -> 0
            }
            "white-space" -> s.pre = value.startsWith("pre")
            "hyphens", "-webkit-hyphens", "-epub-hyphens" -> s.hyphenate = value != "none"
            "display" -> s.display = when (value) {
                "none" -> Display.NONE
                "inline", "inline-block" -> Display.INLINE
                "list-item" -> Display.LIST_ITEM
                else -> Display.BLOCK
            }
            "margin" -> {
                val parts = value.split(Regex("\\s+")).map { length(it, s.fontScale) ?: Len.ZERO }
                when (parts.size) {
                    1 -> margins(s, parts[0], parts[0], parts[0], parts[0])
                    2 -> margins(s, parts[0], parts[1], parts[0], parts[1])
                    3 -> margins(s, parts[0], parts[1], parts[2], parts[1])
                    4 -> margins(s, parts[0], parts[1], parts[2], parts[3])
                }
            }
            "margin-top" -> length(value, s.fontScale)?.let { s.marginTop = it }
            "margin-bottom" -> length(value, s.fontScale)?.let { s.marginBottom = it }
            "margin-left", "padding-left" -> length(value, s.fontScale)?.let { s.marginLeft = it }
            "margin-right", "padding-right" -> length(value, s.fontScale)?.let { s.marginRight = it }
            "page-break-before", "break-before" -> s.breakBefore = value in PAGE_BREAKS
            "page-break-after", "break-after" -> s.breakAfter = value in PAGE_BREAKS
        }
    }

    private fun margins(s: Style, top: Len, right: Len, bottom: Len, left: Len) {
        s.marginTop = top
        s.marginRight = right
        s.marginBottom = bottom
        s.marginLeft = left
    }

    private fun fontSize(value: String, s: Style) {
        val scale = when (value) {
            "xx-small" -> 0.6f
            "x-small" -> 0.75f
            "small" -> 0.89f
            "medium" -> 1f
            "large" -> 1.2f
            "x-large" -> 1.5f
            "xx-large" -> 2f
            "smaller" -> s.fontScale * 0.85f
            "larger" -> s.fontScale * 1.2f
            else -> {
                val (number, unit) = splitUnit(value) ?: return
                when (unit) {
                    "em" -> s.fontScale * number
                    "%" -> s.fontScale * number / 100f
                    "rem" -> number
                    "px" -> number / 16f
                    "pt" -> number / 12f
                    else -> return
                }
            }
        }
        s.fontScale = scale.coerceIn(0.7f, 2.5f)
    }

    /** Parses a CSS length into root ems plus a page-width fraction; negatives clamp to zero. */
    private fun length(value: String, fontScale: Float): Len? {
        if (value == "0" || value == "auto") return Len.ZERO
        val (number, unit) = splitUnit(value) ?: return null
        val n = number.coerceAtLeast(0f)
        return when (unit) {
            "em" -> Len(em = n * fontScale)
            "rem" -> Len(em = n)
            "ex", "ch" -> Len(em = n * fontScale * 0.5f)
            "px" -> Len(em = n / 16f)
            "pt" -> Len(em = n / 12f)
            "%" -> Len(pct = n / 100f)
            "" -> if (n == 0f) Len.ZERO else null
            else -> null
        }
    }

    private fun splitUnit(value: String): Pair<Float, String>? {
        var end = 0
        while (end < value.length && (value[end].isDigit() || value[end] == '.' || value[end] == '-' || value[end] == '+')) end++
        val number = value.substring(0, end).toFloatOrNull() ?: return null
        return number to value.substring(end).trim()
    }

    private companion object {
        val PAGE_BREAKS = setOf("always", "page", "left", "right", "recto", "verso")
    }
}
