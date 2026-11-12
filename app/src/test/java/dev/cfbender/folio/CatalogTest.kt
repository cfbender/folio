package dev.cfbender.folio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cfbender.folio.data.CatalogEntity
import dev.cfbender.folio.ui.FeedScreen
import dev.cfbender.folio.ui.FolioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A stand-in for Grimmory's OPDS server: the first page of its catalog as the real server sent it,
 * a second page, covers, and downloads, all behind Basic auth for `opds` / `secret`.
 */
fun opdsServer(epub: File): MockWebServer {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // "opds:secret"
            if (request.headers["Authorization"] != "Basic b3BkczpzZWNyZXQ=") return MockResponse(code = 401)
            val url = request.url
            val path = url.encodedPath
            return when {
                path == "/api/v1/opds/catalog" && url.queryParameter("page") == "2" -> MockResponse(
                    body = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <feed xmlns="http://www.w3.org/2005/Atom">
                          <id>urn:booklore:catalog</id><title>Grimmory Catalog</title>
                          <entry>
                            <title>Pride and Prejudice</title><id>urn:booklore:book:3</id>
                            <author><name>Jane Austen</name></author>
                            <link href="/api/v1/opds/3/download?fileId=3" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
                            <link rel="http://opds-spec.org/image/thumbnail" href="/api/v1/opds/3/cover" type="image/jpeg"/>
                          </entry>
                        </feed>
                    """.trimIndent(),
                )
                path == "/api/v1/opds/catalog" -> MockResponse(body = OPDS_FIRST_PAGE)
                path == "/api/v1/opds/3/cover" -> MockResponse.Builder().body(Buffer().write(TestEpub.coverPng())).build()
                path.endsWith("/download") -> MockResponse.Builder().body(Buffer().write(epub.readBytes())).build()
                else -> MockResponse(code = 404)
            }
        }
    }
    server.start()
    return server
}

private val OPDS_FIRST_PAGE = TestEpub::class.java.classLoader!!.getResource("opds/catalog.xml")!!.readText()

/** Adds [server] as a saved catalog and shows its feed; returns the ids of books opened from it. */
fun ComposeContentTestRule.openCatalog(server: MockWebServer): List<String> {
    val url = server.url("/api/v1/opds/catalog?page=1&size=2").toString()
    val catalog = runBlocking {
        val catalogs = app.container.database.catalogs()
        catalogs.save(CatalogEntity(title = "Grimmory", url = url, username = "opds", password = "secret"))
        catalogs.catalogs().first().single()
    }
    val read = ArrayList<String>()
    setContent {
        FolioTheme { FeedScreen(catalog.id, url, "Grimmory", onBack = {}, onOpenFeed = { _, _ -> }, onRead = { read += it }) }
    }
    waitUntil(10_000) { onAllNodesWithText("Moby Dick; Or, The Whale").fetchSemanticsNodes().isNotEmpty() }
    return read
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class CatalogTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @After fun stop() = server.close()

    @Test fun aCatalogBehindALoginIsBrowsedAcrossPagesAndABookDownloadedIntoTheLibrary() {
        server = opdsServer(TestEpub.write(temp.newFile()))
        val read = compose.openCatalog(server)

        // The second page of the feed arrives without being asked for.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jane Austen").fetchSemanticsNodes().isNotEmpty() }
        // Titles appear twice: beside the cover, and on it until the cover image arrives.
        compose.onAllNodesWithText("Alice's Adventures in Wonderland").onFirst().assertIsDisplayed()

        compose.onAllNodesWithText("Download").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Read").fetchSemanticsNodes().isNotEmpty() }
        val book = runBlocking { app.container.library.books.first() }.single()
        assertEquals(TestEpub.TITLE, book.title)
        assertEquals(TestEpub.write(temp.newFile()).length(), app.container.library.file(book.id).length())

        compose.onNodeWithText("Read").performClick()
        assertEquals(listOf(book.id), read)
    }
}
