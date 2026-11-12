package dev.cfbender.folio.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class SpineItem(
    /** Path of the content document inside the zip. */
    val path: String,
    /** Uncompressed size in bytes; used as the item's weight in whole-book progress. */
    val size: Long,
)

data class TocEntry(
    val title: String,
    val path: String,
    val fragment: String?,
    val depth: Int,
)

data class EpubMetadata(
    val title: String,
    val author: String?,
    val language: String?,
)

/**
 * A read-only view of an EPUB file. Resources are read straight from the zip on demand, so opening
 * a book costs only the package document and table of contents.
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val metadata: EpubMetadata,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val coverPath: String?,
) : Closeable {

    val totalSize: Long = spine.sumOf { it.size }.coerceAtLeast(1)

    fun read(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    /** Parses a content document as XML, which is what XHTML is; HTML parsing mangles `<a/>`. */
    fun readDocument(path: String): Document? = readText(path)?.let(::parseXhtml)

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    /** Fraction of the book that precedes [spineIndex], by content size. */
    fun fractionBefore(spineIndex: Int): Float {
        var before = 0L
        for (i in 0 until spineIndex.coerceIn(0, spine.size)) before += spine[i].size
        return before.toFloat() / totalSize
    }

    fun fractionOf(spineIndex: Int): Float =
        (spine.getOrNull(spineIndex)?.size ?: 0L).toFloat() / totalSize

    override fun close() = zip.close()

    companion object {
        fun open(file: File): EpubBook {
            val zip = ZipFile(file)
            try {
                return parse(zip)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private fun parse(zip: ZipFile): EpubBook {
            fun text(path: String): String? =
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

            val container = text("META-INF/container.xml") ?: error("Not an EPUB: missing META-INF/container.xml")
            val opfPath = parseXml(container).named("rootfile").firstOrNull()?.attr("full-path")
                ?.takeIf { it.isNotBlank() } ?: error("EPUB container has no rootfile")
            val opf = parseXml(text(opfPath) ?: error("EPUB package document missing: $opfPath"))

            data class ManifestItem(val id: String, val path: String, val type: String, val properties: String)

            val manifest = opf.named("item").map {
                ManifestItem(it.attr("id"), resolvePath(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties"))
            }
            val byId = manifest.associateBy { it.id }

            val spineElement = opf.named("spine").firstOrNull()
            val spine = opf.named("itemref").mapNotNull { ref ->
                val item = byId[ref.attr("idref")] ?: return@mapNotNull null
                val entry = zip.getEntry(item.path) ?: return@mapNotNull null
                SpineItem(item.path, entry.size.coerceAtLeast(1))
            }
            if (spine.isEmpty()) error("EPUB has an empty spine")

            val metadata = EpubMetadata(
                title = opf.named("title").firstOrNull()?.text()?.trim().orEmpty().ifEmpty { "Untitled" },
                author = opf.named("creator").joinToString(", ") { it.text().trim() }.ifEmpty { null },
                language = opf.named("language").firstOrNull()?.text()?.trim()?.ifEmpty { null },
            )

            val coverId = opf.named("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val cover = manifest.firstOrNull { "cover-image" in it.properties.split(' ') }
                ?: byId[coverId]?.takeIf { it.type.startsWith("image/") }
                ?: manifest.firstOrNull { it.type.startsWith("image/") && "cover" in it.path.lowercase() }

            val nav = manifest.firstOrNull { "nav" in it.properties.split(' ') }
            val ncx = byId[spineElement?.attr("toc")] ?: manifest.firstOrNull { it.type == "application/x-dtbncx+xml" }
            val toc = nav?.let { item -> text(item.path)?.let { parseNav(item.path, it) } }?.takeIf { it.isNotEmpty() }
                ?: ncx?.let { item -> text(item.path)?.let { parseNcx(item.path, it) } }
                ?: emptyList()

            return EpubBook(zip, metadata, spine, toc, cover?.path)
        }

        private fun parseNav(navPath: String, xml: String): List<TocEntry> {
            val doc = parseXhtml(xml)
            val navs = doc.named("nav")
            val tocNav = navs.firstOrNull { it.attr("epub:type") == "toc" } ?: navs.firstOrNull() ?: return emptyList()
            val out = ArrayList<TocEntry>()
            fun walk(list: Element, depth: Int) {
                for (li in list.children().filter { it.local == "li" }) {
                    li.children().firstOrNull { it.local == "a" }?.let { a ->
                        val (path, fragment) = splitHref(navPath, a.attr("href"))
                        val title = a.text().trim()
                        if (title.isNotEmpty() && path.isNotEmpty()) out += TocEntry(title, path, fragment, depth)
                    }
                    li.children().firstOrNull { it.local == "ol" }?.let { walk(it, depth + 1) }
                }
            }
            tocNav.children().firstOrNull { it.local == "ol" }?.let { walk(it, 0) }
            return out
        }

        private fun parseNcx(ncxPath: String, xml: String): List<TocEntry> {
            val out = ArrayList<TocEntry>()
            fun walk(parent: Element, depth: Int) {
                for (point in parent.children().filter { it.local == "navPoint" }) {
                    val title = point.children().firstOrNull { it.local == "navLabel" }?.text()?.trim().orEmpty()
                    val src = point.children().firstOrNull { it.local == "content" }?.attr("src").orEmpty()
                    val (path, fragment) = splitHref(ncxPath, src)
                    if (title.isNotEmpty() && path.isNotEmpty()) out += TocEntry(title, path, fragment, depth)
                    walk(point, depth + 1)
                }
            }
            parseXml(xml).named("navMap").firstOrNull()?.let { walk(it, 0) }
            return out
        }

        private fun splitHref(basePath: String, href: String): Pair<String, String?> {
            val hash = href.indexOf('#')
            val file = if (hash >= 0) href.substring(0, hash) else href
            val fragment = if (hash >= 0) href.substring(hash + 1).ifEmpty { null } else null
            return (if (file.isEmpty()) basePath else resolvePath(basePath, file)) to fragment
        }

        /** Resolves [href] against the directory of [basePath], both as zip entry paths. */
        fun resolvePath(basePath: String, href: String): String {
            val clean = href.substringBefore('#').substringBefore('?')
            val decoded = try {
                URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                clean
            }
            val parts = ArrayList<String>()
            if (!decoded.startsWith("/")) parts += basePath.split('/').dropLast(1)
            for (part in decoded.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }

        fun parseXml(xml: String): Document = Jsoup.parse(xml, "", Parser.xmlParser())

        /**
         * Parses XHTML with the XML parser, falling back to the HTML parser for documents that are
         * not well formed enough to yield a body.
         */
        fun parseXhtml(xhtml: String): Document {
            val doc = parseXml(xhtml)
            return if (doc.named("body").isNotEmpty()) doc else Jsoup.parse(xhtml)
        }
    }
}

/** Tag name without its namespace prefix: `dc:title` → `title`. */
val Element.local: String get() = tagName().substringAfter(':')

/** All descendant elements whose local name is [name], ignoring namespace prefixes and case. */
fun Element.named(name: String): List<Element> =
    allElements.filter { it !== this && it.local.equals(name, ignoreCase = true) }
