package dev.cfbender.folio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.cfbender.folio.data.ReaderPrefs
import dev.cfbender.folio.data.ReaderTheme
import dev.cfbender.folio.reader.PAGE_TAG
import dev.cfbender.folio.reader.TurnDirection
import dev.cfbender.folio.ui.FolioTheme
import dev.cfbender.folio.ui.LibraryScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the reader as pictures for a person to look at. Run `./gradlew :app:recordRoborazziDebug`
 * and open app/build/outputs/roborazzi. In an ordinary test run these only check that every state
 * draws without failing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test @Config(qualifiers = PHONE)
    fun phonePage() {
        compose.openReader(temp)
        capture("phone_page")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneTurningForward() {
        val controller = compose.openReader(temp)
        val (w, h) = size()
        controller.hold(TurnDirection.FORWARD, h * 0.8f, Offset(w * 0.42f, h * 0.66f))
        capture("phone_turn_forward")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneControls() {
        compose.openReader(temp)
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(width * 0.5f, height * 0.5f)) }
        capture("phone_controls")
    }

    @Test @Config(qualifiers = UNFOLDED)
    fun unfoldedSpread() {
        compose.openReader(temp)
        capture("unfolded_spread")
    }

    @Test @Config(qualifiers = UNFOLDED)
    fun unfoldedTurningForward() {
        val controller = compose.openReader(temp)
        val (w, h) = size()
        for ((name, x, y) in listOf(Triple("early", 0.86f, 0.7f), Triple("middle", 0.5f, 0.6f), Triple("late", 0.16f, 0.72f))) {
            controller.hold(TurnDirection.FORWARD, h * 0.8f, Offset(w * x, h * y))
            capture("unfolded_turn_forward_$name")
        }
    }

    @Test @Config(qualifiers = UNFOLDED)
    fun unfoldedTurningBackward() {
        val controller = compose.openReader(temp)
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        compose.waitUntil(5_000) { !compose.pageText().startsWith("Chapter 1") }
        val (w, h) = size()
        controller.hold(TurnDirection.BACKWARD, h * 0.3f, Offset(w * 0.4f, h * 0.38f))
        capture("unfolded_turn_backward")
    }

    @Test @Config(qualifiers = "w851dp-h882dp-night-400dpi")
    fun unfoldedDark() {
        val controller = compose.openReader(temp)
        val (w, h) = size()
        controller.hold(TurnDirection.FORWARD, h * 0.8f, Offset(w * 0.55f, h * 0.62f))
        capture("unfolded_dark_turn")
    }

    @Test @Config(qualifiers = UNFOLDED)
    fun unfoldedSepiaCover() {
        runBlocking { app.container.settings.setReaderPrefs(ReaderPrefs(theme = ReaderTheme.SEPIA)) }
        try {
            val controller = compose.openReader(temp)
            // The cover is laid out just after the first page appears, so tap until it is there.
            compose.waitUntil(5_000) {
                compose.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(width * 0.1f, height * 0.5f)) }
                compose.pageText().isEmpty()
            }
            compose.waitUntil(5_000) { controller.active == null }
            // Robolectric draws only when asked, and the cover starts decoding when first drawn.
            compose.onRoot().captureToImage()
            compose.settle()
            capture("unfolded_sepia_cover")
        } finally {
            runBlocking { app.container.settings.setReaderPrefs(ReaderPrefs()) }
        }
    }

    @Test @Config(qualifiers = PHONE, sdk = [COVERS_SDK])
    fun phoneLibrary() = library("phone_library")

    @Test @Config(qualifiers = UNFOLDED, sdk = [COVERS_SDK])
    fun unfoldedLibrary() = library("unfolded_library")

    @Test @Config(qualifiers = PHONE)
    fun phoneLibraryEmpty() {
        compose.setContent { FolioTheme { LibraryScreen(onOpen = {}, onCatalogs = {}, onSettings = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        capture("phone_library_empty")
    }

    @Test @Config(qualifiers = PHONE, sdk = [COVERS_SDK])
    fun phoneCatalog() = catalog("phone_catalog")

    @Test @Config(qualifiers = UNFOLDED, sdk = [COVERS_SDK])
    fun unfoldedCatalog() = catalog("unfolded_catalog")

    private companion object {
        /** Robolectric cannot run the decoder the image loader uses from API 29 on, so covers only show below it. */
        const val COVERS_SDK = 28
    }

    private fun library(name: String) {
        runBlocking {
            val library = app.container.library
            val shelf = listOf(
                Triple("Moby Dick; Or, The Whale", "Herman Melville", 0.42f),
                Triple("Alice's Adventures in Wonderland", "Lewis Carroll", 0f),
                Triple("The Count of Monte Cristo", "Alexandre Dumas", 1f),
                Triple("Middlemarch", "George Eliot", 0.08f),
            )
            shelf.forEachIndexed { index, (title, author, progress) ->
                val book = library.import(TestEpub.write(temp.newFile(), paragraphRepeats = index + 1, title = title, author = author, cover = false))
                library.saveProgress(book.id, 1, 0, 0, progress)
            }
            library.import(TestEpub.write(temp.newFile())).also { library.saveProgress(it.id, 2, 0, 0, 0.61f) }
        }
        compose.setContent { FolioTheme { LibraryScreen(onOpen = {}, onCatalogs = {}, onSettings = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Middlemarch").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureToImage() // asks for the cover images, which load in the background
        compose.settle()
        capture(name)
    }

    private fun catalog(name: String) {
        opdsServer(TestEpub.write(temp.newFile())).use { server ->
            compose.openCatalog(server)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Jane Austen").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Download").onFirst().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Read").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().captureToImage()
            compose.settle()
            capture(name)
        }
    }

    private fun size(): Pair<Float, Float> = compose.onRoot().fetchSemanticsNode().size.let { it.width.toFloat() to it.height.toFloat() }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
