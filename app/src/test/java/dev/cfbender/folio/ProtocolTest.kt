package dev.cfbender.folio

import dev.cfbender.folio.epub.PartialMd5
import dev.cfbender.folio.opds.OpdsParser
import dev.cfbender.folio.sync.KoProgress
import dev.cfbender.folio.sync.KoSyncAccount
import dev.cfbender.folio.sync.KoSyncClient
import dev.cfbender.folio.sync.KoSyncException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The wire formats Folio shares with other software: KOReader's digest and sync API, and OPDS. */
class ProtocolTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = KoSyncClient(OkHttpClient())
    private lateinit var account: KoSyncAccount

    @Before fun start() {
        server.start()
        account = KoSyncAccount(server.url("/api/koreader/").toString(), "reader", KoSyncAccount.userKey("secret"))
    }

    @After fun stop() = server.close()

    // Expected digests were computed with a separate Python implementation of KOReader's
    // util.partialMD5, not with this code.
    @Test fun partialMd5SamplesAtKoreaderOffsets() {
        val file = temp.newFile()
        file.writeBytes(ByteArray(20_000) { ((it * 31 + 7) % 251).toByte() })
        assertEquals("e9f4e6804fad65f5b986d78a5ef0122f", PartialMd5.of(file))
    }

    @Test fun partialMd5OfFileShorterThanOneSample() {
        val file = temp.newFile()
        file.writeText("hello folio")
        assertEquals("aff87fd4e4a769bca21e090b99c76eb7", PartialMd5.of(file))
    }

    @Test fun userKeyIsMd5OfPassword() {
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", KoSyncAccount.userKey("secret"))
    }

    @Test fun opdsNavigationFeedResolvesRelativeLinks() {
        val feed = OpdsParser.parseFeed(resource("opds/root.xml"), "http://books.example:6060/api/v1/opds")
        assertEquals("Grimmory Catalog", feed.title)
        assertEquals(8, feed.entries.size)
        val all = feed.entries.first()
        assertEquals("All Books", all.title)
        assertEquals("http://books.example:6060/api/v1/opds/catalog?page=1&size=50", all.navigationUrl)
        assertEquals("Browse all available books", all.summary)
        assertNull(all.epub)
        assertNull(feed.nextUrl)
        assertEquals("http://books.example:6060/api/v1/opds/search.opds", feed.search?.url)
        assertFalse(feed.search!!.isTemplate)
    }

    @Test fun opdsAcquisitionFeedHasBooksCoversAndNextPage() {
        val feed = OpdsParser.parseFeed(resource("opds/catalog.xml"), "http://books.example:6060/api/v1/opds/catalog?page=1&size=2")
        assertEquals("http://books.example:6060/api/v1/opds/catalog?page=2&size=2", feed.nextUrl)
        assertEquals(listOf("Moby Dick; Or, The Whale", "Alice's Adventures in Wonderland"), feed.entries.map { it.title })
        val moby = feed.entries.first()
        assertEquals("urn:booklore:book:1", moby.id)
        assertEquals("Herman Melville", moby.author)
        assertEquals("http://books.example:6060/api/v1/opds/1/download?fileId=1", moby.epub?.url)
        assertNull(moby.navigationUrl)
        assertTrue(moby.coverUrl!!.startsWith("http://books.example:6060/api/v1/opds/1/cover?"))
    }

    @Test fun opdsSearchTemplateBecomesQueryUrl() {
        val template = OpdsParser.parseSearchTemplate(resource("opds/search.xml"), "http://books.example:6060/api/v1/opds/search.opds")
        assertEquals("http://books.example:6060/api/v1/opds/catalog?q={searchTerms}", template)
        assertEquals(
            "http://books.example:6060/api/v1/opds/catalog?q=moby+dick+%26+co",
            OpdsParser.searchUrl(template!!, "moby dick & co"),
        )
    }

    @Test fun pullSendsCredentialsAndReadsProgress() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"document":"abc","progress":"/body/DocFragment[3]/body/p[2]/text().5","percentage":0.42,
                    "device":"Kobo","device_id":"D1","timestamp":1700000000,"extra":true}""",
            ),
        )
        val pulled = client.pull(account, "abc")
        assertEquals(KoProgress("abc", "/body/DocFragment[3]/body/p[2]/text().5", 0.42f, "Kobo", "D1", 1700000000), pulled)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/koreader/syncs/progress/abc", request.url.encodedPath)
        assertEquals("reader", request.headers["x-auth-user"])
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", request.headers["x-auth-key"])
        assertEquals("application/vnd.koreader.v1+json", request.headers["Accept"])
    }

    @Test fun pullTreatsMissingProgressAsNone() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"message":"Book not found"}""")) // Grimmory
        server.enqueue(MockResponse(body = "{}")) // koreader-sync-server
        assertNull(client.pull(account, "abc"))
        assertNull(client.pull(account, "abc"))
    }

    @Test fun pushPutsKoreaderFields() = runBlocking {
        server.enqueue(MockResponse(body = """{"document":"abc","timestamp":1}"""))
        client.push(account, KoProgress("abc", "/body/DocFragment[2]/body/p/text().0", 0.25f, "Folio", "DEV"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/koreader/syncs/progress", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("document", "progress", "percentage", "device", "device_id"), body.keys)
        assertEquals("abc", body["document"]!!.jsonPrimitive.content)
        assertEquals("/body/DocFragment[2]/body/p/text().0", body["progress"]!!.jsonPrimitive.content)
        assertEquals(0.25, body["percentage"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals("DEV", body["device_id"]!!.jsonPrimitive.content)
    }

    @Test fun rejectedCredentialsSurfaceTheStatus() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"message":"Unauthorized"}"""))
        try {
            client.authorize(account)
            fail("expected KoSyncException")
        } catch (e: KoSyncException) {
            assertEquals(401, e.code)
        }
        assertEquals("/api/koreader/users/auth", server.takeRequest().url.encodedPath)
    }

    @Test fun signingInWithTheAddressOfAGrimmoryServerFindsItsSyncPath() = runBlocking {
        // Grimmory answers addresses outside its API with its web app, and says 200 doing so.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath != "/api/koreader/users/auth" -> MockResponse(body = "<!doctype html><html></html>")
                request.headers["x-auth-key"] == KoSyncAccount.userKey("secret") -> MockResponse(body = """{"username":"reader"}""")
                else -> MockResponse(code = 401, body = """{"status":401}""")
            }
        }
        val address = server.url("/").toString()
        val expected = KoSyncAccount(address + "api/koreader", "reader", KoSyncAccount.userKey("secret"))
        assertEquals(expected, client.signIn(address, " reader ", "secret"))
        assertEquals(expected, client.signIn(address + "api/koreader/", "reader", "secret"))
        try {
            client.signIn(address, "reader", "wrong")
            fail("expected KoSyncException")
        } catch (e: KoSyncException) {
            assertEquals(401, e.code)
        }
    }

    private fun resource(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()
}
