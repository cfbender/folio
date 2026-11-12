package dev.cfbender.folio.render

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A reading position that survives re-pagination: a character of a block of a spine item. */
data class Locator(val spine: Int, val block: Int = 0, val char: Int = 0)

/**
 * KOReader identifies positions in EPUBs with crengine XPointers such as
 * `/body/DocFragment[12]/body/div/p[47]/text().123`: the 1-based spine item, an element path in
 * which a step is indexed only when it has same-named siblings, and optionally a text node with a
 * character offset. These functions convert between that format and [Locator]s.
 */
object XPointer {
    private val FRAGMENT = Regex("^/body/DocFragment(?:\\[(\\d+)])?(?:/body)?(.*)$")
    private val STEP = Regex("^([^\\[.]+?)(?:\\[(\\d+)])?(?:\\.(\\d+))?$")

    /** Spine index an XPointer refers to, or null if it is not a crengine EPUB pointer. */
    fun spineIndex(xpointer: String): Int? {
        val match = FRAGMENT.matchEntire(xpointer.trim()) ?: return null
        return (match.groupValues[1].toIntOrNull() ?: 1) - 1
    }

    fun format(chapter: Chapter, locator: Locator): String {
        val prefix = "/body/DocFragment[${chapter.spineIndex + 1}]/body"
        val body = chapter.body ?: return prefix
        val block = chapter.blocks.getOrNull(locator.block) ?: return prefix
        if (block is TextBlock && block.runs.isNotEmpty()) {
            val run = block.runs.lastOrNull { it.start <= locator.char } ?: block.runs.first()
            val offset = (run.rawStart + (locator.char - run.start).coerceAtLeast(0))
                .coerceIn(0, run.node.wholeText.length)
            return prefix + path(run.node, body) + "." + offset
        }
        return prefix + path(block.element, body)
    }

    /**
     * Finds the position an XPointer refers to in [chapter]. Steps are followed as far as they
     * resolve, so a pointer from a renderer whose DOM differs slightly still lands nearby.
     */
    fun resolve(chapter: Chapter, xpointer: String): Locator {
        val start = Locator(chapter.spineIndex)
        val body = chapter.body ?: return start
        val match = FRAGMENT.matchEntire(xpointer.trim()) ?: return start

        var node: Node = body
        var offset = 0
        for (segment in match.groupValues[2].split('/')) {
            if (segment.isEmpty()) continue
            val step = STEP.matchEntire(segment) ?: break
            val name = step.groupValues[1]
            val index = (step.groupValues[2].toIntOrNull() ?: 1) - 1
            offset = step.groupValues[3].toIntOrNull() ?: 0
            val parent = node as? Element ?: break
            node = if (name == "text()") {
                textChildren(parent).getOrNull(index) ?: break
            } else {
                parent.children().filter { it.tagName().equals(name, ignoreCase = true) }.getOrNull(index) ?: break
            }
        }
        return locate(chapter, node, offset) ?: start
    }

    private fun locate(chapter: Chapter, node: Node, offset: Int): Locator? {
        chapter.blocks.forEachIndexed { index, block ->
            if (block is TextBlock) {
                for (run in block.runs) {
                    if (node === run.node) {
                        val char = run.start + (offset - run.rawStart).coerceAtLeast(0)
                        return Locator(chapter.spineIndex, index, char.coerceIn(0, (block.charLength - 1).coerceAtLeast(0)))
                    }
                    if (node is Element && isWithin(run.node, node)) return Locator(chapter.spineIndex, index, run.start)
                }
            }
            if (node is Element && isWithin(block.element, node)) return Locator(chapter.spineIndex, index)
        }
        return null
    }

    private fun isWithin(node: Node, ancestor: Element): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent()
        }
        return false
    }

    private fun path(node: Node, body: Element): String {
        val segments = ArrayList<String>()
        var current = node
        while (current !== body) {
            val parent = current.parent() as? Element ?: break
            segments += if (current is Element) {
                val name = current.tagName()
                val same = parent.children().filter { it.tagName() == name }
                if (same.size == 1) name else "$name[${same.indexOfFirst { it === current } + 1}]"
            } else {
                val texts = textChildren(parent)
                if (texts.size <= 1) "text()" else "text()[${texts.indexOfFirst { it === current } + 1}]"
            }
            current = parent
        }
        return segments.asReversed().joinToString("") { "/$it" }
    }

    /** crengine drops whitespace-only text nodes, so they do not count as siblings. */
    private fun textChildren(parent: Element): List<TextNode> =
        parent.childNodes().filterIsInstance<TextNode>().filter { !it.isBlank }
}
