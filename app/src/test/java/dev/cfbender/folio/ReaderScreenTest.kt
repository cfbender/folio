package dev.cfbender.folio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.cfbender.folio.reader.PAGE_TAG
import dev.cfbender.folio.reader.ReaderScreen
import dev.cfbender.folio.reader.TurnController
import dev.cfbender.folio.reader.rememberTurnController
import dev.cfbender.folio.sync.KoSyncAccount
import dev.cfbender.folio.ui.FolioTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

const val PHONE = "w411dp-h914dp-420dpi"

/** A book-style foldable, unfolded: nearly square and wide enough for two pages. */
const val UNFOLDED = "w851dp-h882dp-400dpi"

val app: FolioApp get() = RuntimeEnvironment.getApplication() as FolioApp

/** The words of the page on screen, with line and paragraph breaks reduced to single spaces. */
fun ComposeContentTestRule.pageText(): String =
    onAllNodes(hasTestTag(PAGE_TAG)).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

/**
 * Waits for something that happens off screen, such as a network call. Unlike a condition that
 * looks at the UI, such a condition does not by itself let work posted to the main thread run.
 */
fun ComposeContentTestRule.waitOffScreen(condition: () -> Boolean) = waitUntil(5_000) {
    ShadowLooper.idleMainLooper()
    condition()
}

/** Lets background work that nothing on screen reports, such as decoding an image, finish. */
fun ComposeContentTestRule.settle(millis: Long = 300) {
    val until = System.currentTimeMillis() + millis
    waitOffScreen { System.currentTimeMillis() > until }
}

/**
 * Puts [TestEpub] in the library with its saved position at the start of [spine], opens it in the
 * reader, and waits for the first page.
 */
fun ComposeContentTestRule.openReader(temp: TemporaryFolder, spine: Int = 1, onBack: () -> Unit = {}): TurnController {
    val book = runBlocking {
        val library = app.container.library
        library.import(TestEpub.write(temp.newFile())).also { library.saveProgress(it.id, spine, 0, 0, 0f) }
    }
    lateinit var controller: TurnController
    setContent {
        FolioTheme {
            controller = rememberTurnController()
            ReaderScreen(book.id, onBack, controller)
        }
    }
    waitUntil(20_000) { pageText().startsWith("Chapter $spine") }
    return controller
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ReaderScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val chapter1 = TestEpub.chapterText(1)

    @Test fun opensAtTheSavedPosition() {
        compose.openReader(temp, spine = 2)
        assertTrue(compose.pageText().startsWith("Chapter 2 It is a truth universally acknowledged"))
    }

    @Test fun tappingTheRightEdgeTurnsForwardWithoutLosingText() {
        compose.openReader(temp)
        val pages = mutableListOf(compose.pageText())
        repeat(2) {
            tap(0.9f)
            compose.waitUntil(5_000) { compose.pageText() != pages.last() }
            pages += compose.pageText()
        }
        // Read in order, the pages are the chapter: nothing dropped or repeated at a turn.
        val read = pages.joinToString(" ")
        assertTrue("pages should be far apart in the chapter: $pages", pages.all { it.length > 200 })
        assertEquals(chapter1.take(read.length), read)
    }

    @Test fun tappingTheLeftEdgeTurnsBack() {
        compose.openReader(temp)
        val first = compose.pageText()
        tap(0.9f)
        compose.waitUntil(5_000) { compose.pageText() != first }
        tap(0.1f)
        compose.waitUntil(5_000) { compose.pageText() == first }
    }

    @Test fun swipingTurnsPagesBothWays() {
        compose.openReader(temp)
        val first = compose.pageText()
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.pageText() != first }
        val second = compose.pageText()
        assertEquals(chapter1.take(first.length + 1 + second.length), "$first $second")

        compose.onNodeWithTag(PAGE_TAG).performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.pageText() == first }
    }

    @Test fun aShortDragLetsThePageFallBack() {
        compose.openReader(temp)
        val first = compose.pageText()
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            swipeLeft(startX = right * 0.9f, endX = right * 0.8f, durationMillis = 900)
        }
        compose.waitForIdle()
        assertEquals(first, compose.pageText())
    }

    @Test fun turningBackFromAChapterOpeningShowsThePreviousChapter() {
        compose.openReader(temp, spine = 2)
        // The previous chapter is laid out just after the first page appears, so tap until it is there.
        compose.waitUntil(5_000) {
            tap(0.1f)
            !compose.pageText().startsWith("Chapter 2")
        }
        // The last page of chapter one: it ends with the chapter's last words.
        assertTrue(compose.pageText().isNotEmpty())
        assertTrue(chapter1.endsWith(compose.pageText()))
    }

    @Test fun tappingTheMiddleShowsControlsAndContentsJumpToAChapter() {
        var wentBack = false
        compose.openReader(temp, onBack = { wentBack = true })
        tap(0.5f)
        compose.onNodeWithText(TestEpub.TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Position in book").assertIsDisplayed()

        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Chapter 3").performClick()
        compose.waitUntil(5_000) { compose.pageText().startsWith("Chapter 3") }

        tap(0.5f)
        compose.onNodeWithContentDescription("Back to library").performClick()
        assertTrue(wentBack)
    }

    @Test fun progressFromAnotherDeviceIsOfferedThenFollowedAndLaterPagesArePushed() {
        val server = syncServer()
        server.enqueue(
            MockResponse(
                body = """{"document":"x","progress":"${TestEpub.xpointerAt(3, 0)}","percentage":0.67,"device":"Kobo","device_id":"KOBO1","timestamp":1}""",
            ),
        )
        server.enqueue(MockResponse(body = """{"document":"x","timestamp":2}"""))

        compose.openReader(temp)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Kobo is at 67%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jump there").performClick()
        compose.waitUntil(5_000) { compose.pageText().startsWith("Chapter 3") }
        val opening = compose.pageText()
        tap(0.9f)
        compose.waitUntil(5_000) { compose.pageText() != opening }
        // Progress is pushed once the reader has rested on a page for a few seconds.
        ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS)
        compose.waitOffScreen { server.requestCount == 2 }

        val pull = server.takeRequest()
        val push = server.takeRequest()
        val book = runBlocking { app.container.library.book(pull.url.pathSegments.last())!! }
        assertEquals("GET /api/koreader/syncs/progress/${book.id}", "${pull.method} ${pull.url.encodedPath}")
        assertEquals("PUT /api/koreader/syncs/progress", "${push.method} ${push.url.encodedPath}")
        assertEquals("reader", push.headers["x-auth-user"])

        // The pushed position is the first character of the page on screen.
        val chapter = TestEpub.chapterText(3)
        val at = chapter.indexOf(compose.pageText())
        assertEquals(opening.length + 1, at)
        val sent = Json.parseToJsonElement(push.body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(book.id, sent["document"])
        assertEquals(TestEpub.xpointerAt(3, at), sent["progress"])
        // The cover weighs almost nothing and the three chapters are alike, so chapter 3 is the last third.
        assertEquals(2 / 3.0 + at / 3.0 / chapter.length, sent.getValue("percentage").toDouble(), 0.02)
        assertEquals(runBlocking { app.container.settings.deviceId() }, sent["device_id"])
        assertEquals(3, book.spine)
        assertEquals(sent.getValue("percentage").toDouble(), book.progress.toDouble(), 1e-4)
    }

    @Test fun progressThisDevicePushedIsNotOfferedBack() {
        val server = syncServer()
        val own = runBlocking { app.container.settings.deviceId() }
        server.enqueue(
            MockResponse(body = """{"document":"x","progress":"${TestEpub.xpointerAt(3, 0)}","percentage":0.67,"device":"Folio","device_id":"$own"}"""),
        )
        compose.openReader(temp)
        compose.waitOffScreen { server.requestCount == 1 }
        compose.settle() // time for the reply to come back and be handled
        compose.onNodeWithText("Jump there").assertDoesNotExist()
    }

    @Test fun aFailedPushIsReportedInTheControls() {
        val server = syncServer()
        server.enqueue(MockResponse(code = 404, body = """{"message":"Book not found"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"message":"Unauthorized"}"""))

        compose.openReader(temp)
        val first = compose.pageText()
        tap(0.9f)
        compose.waitUntil(5_000) { compose.pageText() != first }
        ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS)
        compose.waitOffScreen { server.requestCount == 2 }
        tap(0.5f)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Progress not synced: The sync server rejected the username or password").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A sync server the app is signed in to until the test ends. */
    private fun syncServer(): MockWebServer {
        val server = MockWebServer().also { it.start() }
        this.server = server
        runBlocking {
            app.container.settings.setKoSyncAccount(KoSyncAccount(server.url("/api/koreader").toString(), "reader", KoSyncAccount.userKey("secret")))
        }
        return server
    }

    @After fun signOut() {
        // Not runBlocking: a settings write the reader started may be waiting for this thread.
        val done = app.container.scope.launch { app.container.settings.setKoSyncAccount(null) }
        compose.waitOffScreen { done.isCompleted }
        server?.close()
    }

    private var server: MockWebServer? = null

    private fun tap(xFraction: Float) {
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(width * xFraction, height * 0.5f)) }
        compose.waitForIdle()
    }
}
