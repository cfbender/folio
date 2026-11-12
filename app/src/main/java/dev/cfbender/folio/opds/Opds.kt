package dev.cfbender.folio.opds

import dev.cfbender.folio.epub.EpubBook
import dev.cfbender.folio.epub.local
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Element
import java.io.File
import java.io.IOException

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String?,
    val search: OpdsSearch?,
)

/** How a feed advertises search: a URL template, or an OpenSearch description to fetch for one. */
data class OpdsSearch(val url: String, val isTemplate: Boolean)

data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val coverUrl: String?,
    /** Feed to open for a navigation entry; null for a book. */
    val navigationUrl: String?,
    val downloads: List<OpdsDownload>,
) {
    val epub: OpdsDownload? get() = downloads.firstOrNull { it.type.startsWith(EPUB_TYPE) }

    private companion object {
        const val EPUB_TYPE = "application/epub+zip"
    }
}

data class OpdsDownload(val url: String, val type: String)

/** Parser for OPDS 1.x Atom feeds. */
object OpdsParser {
    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val OPENSEARCH = "application/opensearchdescription+xml"
    private const val ATOM = "application/atom+xml"

    fun parseFeed(xml: String, baseUrl: String): OpdsFeed {
        val doc = EpubBook.parseXml(xml)
        val feed = doc.children().firstOrNull { it.local == "feed" } ?: throw IOException("Not an OPDS feed")
        val base = baseUrl.toHttpUrlOrNull()
        fun resolve(href: String): String? = href.takeIf { it.isNotBlank() }?.let { base?.resolve(it)?.toString() ?: it }

        val links = feed.direct("link")
        val searchLink = links.filter { it.attr("rel") == "search" }.let { candidates ->
            // A direct Atom template saves a round trip over an OpenSearch description.
            candidates.firstOrNull { it.attr("type").startsWith(ATOM) } ?: candidates.firstOrNull { it.attr("type").startsWith(OPENSEARCH) }
        }
        return OpdsFeed(
            title = feed.direct("title").firstOrNull()?.text()?.trim().orEmpty(),
            entries = feed.direct("entry").map { entry(it, ::resolve) },
            nextUrl = links.firstOrNull { it.attr("rel") == "next" }?.let { resolve(it.attr("href")) },
            search = searchLink?.let { link ->
                // Templates contain braces, which URL resolution would escape; resolve around them.
                val href = link.attr("href")
                val isTemplate = link.attr("type").startsWith(ATOM)
                (if (isTemplate) resolveTemplate(href, baseUrl) else resolve(href))?.let { OpdsSearch(it, isTemplate) }
            },
        )
    }

    /** Extracts the Atom search template from an OpenSearch description document. */
    fun parseSearchTemplate(xml: String, baseUrl: String): String? {
        val urls = EpubBook.parseXml(xml).allElements.filter { it.local == "Url" }
        val url = urls.firstOrNull { it.attr("type").startsWith(ATOM) } ?: urls.firstOrNull() ?: return null
        return resolveTemplate(url.attr("template"), baseUrl)
    }

    fun searchUrl(template: String, query: String): String =
        template
            .replace("{searchTerms}", java.net.URLEncoder.encode(query, "UTF-8"))
            .replace(Regex("\\{[^}]*\\?}"), "") // optional parameters
            .replace(Regex("\\{startPage}|\\{startIndex}"), "1")
            .replace(Regex("\\{[^}]*}"), "")

    private fun resolveTemplate(template: String, baseUrl: String): String? {
        if (template.isBlank()) return null
        if (template.contains("://")) return template
        val base = baseUrl.toHttpUrlOrNull() ?: return template
        val origin = "${base.scheme}://${base.host}${if (base.port != okhttp3.HttpUrl.defaultPort(base.scheme)) ":${base.port}" else ""}"
        return if (template.startsWith("/")) origin + template
        else origin + base.encodedPath.substringBeforeLast('/') + "/" + template
    }

    private fun entry(el: Element, resolve: (String) -> String?): OpdsEntry {
        val links = el.direct("link")
        val downloads = links
            .filter { it.attr("rel").startsWith(ACQUISITION) }
            .mapNotNull { link -> resolve(link.attr("href"))?.let { OpdsDownload(it, link.attr("type")) } }
        val thumbnail = links.firstOrNull { it.attr("rel").let { rel -> rel.startsWith(IMAGE) && rel != IMAGE || rel.endsWith("/thumbnail") } }
        val cover = thumbnail ?: links.firstOrNull { it.attr("rel") == IMAGE }
        val navigation = if (downloads.isNotEmpty()) null else links.firstOrNull { it.attr("type").startsWith(ATOM) && it.attr("rel") != "self" }
        val content = el.direct("summary").firstOrNull() ?: el.direct("content").firstOrNull()
        return OpdsEntry(
            id = el.direct("id").firstOrNull()?.text().orEmpty(),
            title = el.direct("title").firstOrNull()?.text()?.trim().orEmpty(),
            author = el.direct("author").mapNotNull { a -> a.direct("name").firstOrNull()?.text()?.trim() }
                .filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null },
            summary = content?.text()?.let { org.jsoup.Jsoup.parse(it).text() }?.trim()?.ifEmpty { null },
            coverUrl = cover?.let { resolve(it.attr("href")) },
            navigationUrl = navigation?.let { resolve(it.attr("href")) },
            downloads = downloads,
        )
    }

    private fun Element.direct(name: String): List<Element> = children().filter { it.local == name }
}

data class OpdsCredentials(val username: String, val password: String) {
    val header: String get() = Credentials.basic(username, password, Charsets.UTF_8)
}

class OpdsException(val code: Int, message: String) : IOException(message)

class OpdsClient(private val http: OkHttpClient) {

    suspend fun feed(url: String, credentials: OpdsCredentials?): OpdsFeed =
        OpdsParser.parseFeed(getText(url, credentials), url)

    /** Resolves a feed's search description into the URL of the results feed for [query]. */
    suspend fun searchUrl(search: OpdsSearch, query: String, credentials: OpdsCredentials?): String {
        val template = if (search.isTemplate) search.url
        else OpdsParser.parseSearchTemplate(getText(search.url, credentials), search.url)
            ?: throw IOException("This catalog's search description has no usable template")
        return OpdsParser.searchUrl(template, query)
    }

    /** Streams [url] into [destination], reporting progress as a fraction when the size is known. */
    suspend fun download(url: String, credentials: OpdsCredentials?, destination: File, onProgress: (Float?) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            http.newCall(request(url, credentials)).execute().use { response ->
                if (!response.isSuccessful) throw OpdsException(response.code, describe(response.code))
                val body = response.body
                val total = body.contentLength()
                destination.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
        }

    private suspend fun getText(url: String, credentials: OpdsCredentials?): String = withContext(Dispatchers.IO) {
        http.newCall(request(url, credentials)).execute().use { response ->
            if (!response.isSuccessful) throw OpdsException(response.code, describe(response.code))
            response.body.string()
        }
    }

    private fun request(url: String, credentials: OpdsCredentials?): Request =
        Request.Builder().url(url).apply { credentials?.let { header("Authorization", it.header) } }.build()

    private fun describe(code: Int) = when (code) {
        401, 403 -> "The server rejected the username or password"
        404 -> "Nothing found at that address"
        else -> "The server answered with HTTP $code"
    }
}
