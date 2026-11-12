package dev.cfbender.folio

import dev.cfbender.folio.net.LocalNetworkAccess
import dev.cfbender.folio.net.isLocalNetworkAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Duration

/** Android 17 blocks the home network until the user allows it; the app has to ask and explain. */
class LocalNetworkAccessTest {
    private val server = MockWebServer()
    private var allowed = false
    private var prompts = 0
    private var requestsSeenBeforePrompt = -1

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    /** A client that believes the mock server lives at [address], asking through [answer]. */
    private fun client(address: String, answer: (() -> Unit)? = { allowed = true }): OkHttpClient {
        val access = LocalNetworkAccess(granted = { allowed }, resolve = { listOf(InetAddress.getByName(address)) })
        if (answer != null) {
            access.prompt = {
                prompts++
                requestsSeenBeforePrompt = server.requestCount
                answer()
            }
        }
        return OkHttpClient.Builder().addInterceptor(access).readTimeout(Duration.ofMillis(300)).build()
    }

    private fun OkHttpClient.get(): Int = newCall(Request.Builder().url(server.url("/opds")).build()).execute().use { it.code }

    @Test fun asksOnceBeforeTheFirstRequestToTheHomeNetwork() {
        server.enqueue(MockResponse(code = 200))
        server.enqueue(MockResponse(code = 200))
        val client = client("10.0.69.69")

        assertEquals(200, client.get())
        assertEquals(200, client.get())

        assertEquals(1, prompts)
        assertEquals("the prompt comes before anything is sent", 0, requestsSeenBeforePrompt)
    }

    @Test fun doesNotAskAgainAfterBeingRefused() {
        server.enqueue(MockResponse(code = 200))
        server.enqueue(MockResponse(code = 200))
        val client = client("192.168.1.20", answer = {})

        client.get()
        client.get()

        assertEquals(1, prompts)
    }

    @Test fun doesNotAskForAServerOnTheInternet() {
        server.enqueue(MockResponse(code = 200))
        assertEquals(200, client("104.21.34.30").get())
        assertEquals(0, prompts)
    }

    @Test fun doesNotAskOnceAllowed() {
        allowed = true
        server.enqueue(MockResponse(code = 200))
        assertEquals(200, client("10.0.69.69").get())
        assertEquals(0, prompts)
    }

    @Test fun explainsATimeoutWhenAccessWasRefused() {
        try {
            client("10.0.69.69", answer = {}).get() // Nothing is queued, so the server never answers.
            fail("expected the request to fail")
        } catch (e: IOException) {
            assertEquals(LocalNetworkAccess.BLOCKED, e.message)
            assertTrue(e.cause is SocketTimeoutException)
        }
    }

    @Test fun leavesOtherTimeoutsAlone() {
        for (client in listOf(client("104.21.34.30"), client("10.0.69.69"))) { // Not local; local but allowed.
            try {
                client.get()
                fail("expected the request to fail")
            } catch (e: IOException) {
                assertTrue("$e", e is SocketTimeoutException)
            }
        }
    }

    @Test fun recognisesAndroidsLocalNetworkRanges() {
        val local = listOf(
            "10.0.69.69", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.10.10",
            "100.64.0.1", "100.127.255.254", "fe80::1", "fd12:3456::1",
        )
        val elsewhere = listOf(
            "127.0.0.1", "::1", "172.15.255.254", "172.32.0.1", "192.169.0.1",
            "100.63.255.254", "100.128.0.1", "104.21.34.30", "2606:4700:3035::ac43:a7d7",
        )
        local.forEach { assertTrue(it, isLocalNetworkAddress(InetAddress.getByName(it))) }
        elsewhere.forEach { assertFalse(it, isLocalNetworkAddress(InetAddress.getByName(it))) }
    }
}
