package dev.cfbender.folio.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cfbender.folio.data.Library
import dev.cfbender.folio.data.Settings
import dev.cfbender.folio.epub.EpubBook
import dev.cfbender.folio.epub.TocEntry
import dev.cfbender.folio.render.Chapter
import dev.cfbender.folio.render.ChapterLayout
import dev.cfbender.folio.render.ChapterParser
import dev.cfbender.folio.render.Locator
import dev.cfbender.folio.render.Page
import dev.cfbender.folio.render.PageSize
import dev.cfbender.folio.render.Typography
import dev.cfbender.folio.render.XPointer
import dev.cfbender.folio.render.paginate
import dev.cfbender.folio.sync.KoProgress
import dev.cfbender.folio.sync.KoSyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything that decides how the book is cut into pages and sheets. */
data class LayoutSpec(val page: PageSize, val spread: Boolean, val typography: Typography, val density: Float)

/** Reading progress another device left on the sync server. */
data class RemoteProgress(val percentage: Float?, val device: String?, val xpointer: String?)

enum class TurnDirection { FORWARD, BACKWARD }

/**
 * Holds an open book: lays out the chapters around the reading position, hands the reader the
 * sheets on either side of the current one so that a turn never waits, and keeps the position
 * saved and synced.
 *
 * State is confined to the main thread; parsing, pagination, and image decoding run off it.
 */
class ReaderViewModel(
    private val bookId: String,
    private val library: Library,
    private val settings: Settings,
    private val koSync: KoSyncClient,
    /** Outlives the reader, for the final save and sync as it closes. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    var title by mutableStateOf("")
        private set
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The sheet on screen, and the ones a turn in either direction would show. */
    var current by mutableStateOf<Sheet?>(null)
        private set
    var previous by mutableStateOf<Sheet?>(null)
        private set
    var next by mutableStateOf<Sheet?>(null)
        private set

    /** Fraction of the book before the reading position. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Index in [toc] of the entry being read, or -1. */
    var tocIndex by mutableIntStateOf(-1)
        private set

    /** Progress from another device, offered to the reader until accepted or dismissed. */
    var remote by mutableStateOf<RemoteProgress?>(null)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set

    /** Changes when an image finishes decoding, so pages showing its placeholder can redraw. */
    var imageVersion by mutableIntStateOf(0)
        private set

    private class Target(val spine: Int, val resolve: (Chapter) -> Locator)

    private var book: EpubBook? = null
    private var parser: ChapterParser? = null
    private var images: BookImages? = null
    private var spec: LayoutSpec? = null
    private var tocSpines: List<Int> = emptyList()

    private var locator = Locator(0)
    private var pending: Target? = null
    private var moved = false

    private val chapters = ConcurrentHashMap<Int, Chapter>()
    private val layouts = HashMap<Int, ChapterLayout>()
    private val layoutJobs = HashMap<Int, Deferred<ChapterLayout>>()
    private var showJob: Job? = null
    private var neighbourJob: Job? = null
    private var pushJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val entity = library.book(bookId) ?: throw IOException("This book is no longer in the library")
                val opened = withContext(Dispatchers.IO) { EpubBook.open(library.file(bookId)) }
                book = opened
                parser = ChapterParser(opened)
                images = BookImages(opened, viewModelScope) { imageVersion++ }
                title = entity.title
                toc = opened.toc
                tocSpines = opened.toc.map { opened.spineIndexOf(it.path) }
                progress = entity.progress
                val start = Locator(entity.spine.coerceIn(0, opened.spine.lastIndex), entity.block, entity.char)
                locator = start
                show(Target(start.spine) { start })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "This book could not be opened"
                return@launch
            }
            pullRemote()
        }
    }

    /** Tells the reader how big a page is. Re-paginates if that changed, keeping the position. */
    fun setLayout(newSpec: LayoutSpec) {
        val old = spec
        if (old == newSpec) return
        spec = newSpec
        if (old == null || old.copy(spread = newSpec.spread) != newSpec) {
            layoutJobs.values.forEach { it.cancel() }
            layoutJobs.clear()
            layouts.clear()
        }
        current = null
        previous = null
        next = null
        val at = locator
        show(pending ?: Target(at.spine) { at })
    }

    /** Moves to the next or previous sheet. Called when a turn has finished on screen. */
    fun turn(direction: TurnDirection) {
        val target = (if (direction == TurnDirection.FORWARD) next else previous) ?: return
        showJob?.cancel()
        moved = true
        settle(target, Locator(target.spine, target.first.page.block, target.first.page.char))
    }

    fun goTo(entry: TocEntry) {
        val spine = book?.spineIndexOf(entry.path)?.takeIf { it >= 0 } ?: return
        moved = true
        show(
            Target(spine) { chapter ->
                val block = entry.fragment?.let { chapter.anchors[it] } ?: 0
                Locator(spine, block.coerceIn(0, (chapter.blocks.size - 1).coerceAtLeast(0)))
            },
        )
    }

    /** Moves to the given fraction of the whole book. */
    fun goToFraction(fraction: Float) {
        val book = book ?: return
        val f = fraction.coerceIn(0f, 1f)
        var spine = 0
        while (spine < book.spine.lastIndex && book.fractionBefore(spine + 1) <= f) spine++
        val within = ((f - book.fractionBefore(spine)) / book.fractionOf(spine).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        moved = true
        show(
            Target(spine) { chapter ->
                val (block, char) = chapter.positionAt(within)
                Locator(spine, block, char)
            },
        )
    }

    fun acceptRemote() {
        val offered = remote ?: return
        val book = book ?: return
        remote = null
        val pointer = offered.xpointer
        val spine = pointer?.let(XPointer::spineIndex)?.takeIf { it in book.spine.indices }
        when {
            pointer != null && spine != null -> {
                moved = true
                show(Target(spine) { XPointer.resolve(it, pointer) })
            }
            offered.percentage != null -> goToFraction(offered.percentage)
        }
    }

    fun dismissRemote() {
        remote = null
    }

    fun dismissSyncError() {
        syncError = null
    }

    /** A decoded image of the book, or null while it loads; the page redraws once it has. */
    fun bitmap(path: String): Bitmap? = images?.bitmap(path, spec?.page)

    /** Sends unsynced progress now rather than after the usual pause. */
    fun flush() {
        pushJob?.cancel()
        push()
    }

    override fun onCleared() {
        flush()
        book?.close()
    }

    private fun show(target: Target) {
        if (book == null || spec == null) {
            pending = target
            return
        }
        pending = null
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try {
                val layout = layoutOf(target.spine)
                val at = target.resolve(layout.chapter)
                settle(sheetOf(layout, layout.pageOf(at.block, at.char) / pagesPerSheet), at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "This part of the book could not be shown: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun settle(sheet: Sheet, at: Locator) {
        current = sheet
        locator = at
        loadNeighbours(sheet)
        positionChanged(sheet)
    }

    private val pagesPerSheet: Int get() = if (spec?.spread == true) 2 else 1

    private fun sheetCount(layout: ChapterLayout): Int = (layout.pages.size + pagesPerSheet - 1) / pagesPerSheet

    private fun sheetOf(layout: ChapterLayout, index: Int): Sheet {
        val first = layout.pages[index * pagesPerSheet]
        val second = if (pagesPerSheet == 2) layout.pages.getOrNull(index * 2 + 1) else null
        return Sheet(layout.chapter.spineIndex, index, face(layout, first), second?.let { face(layout, it) })
    }

    private fun face(layout: ChapterLayout, page: Page): PageFace {
        val spine = layout.chapter.spineIndex
        val chapterTitle = tocIndexOf(spine).takeIf { it >= 0 }?.let { toc[it].title }
        // As in a printed book: the title over left-hand pages, the chapter over right-hand ones.
        val header = if (pagesPerSheet == 2 && page.index % 2 == 0) title else chapterTitle ?: title
        val percent = (fractionAt(layout.chapter, page.block, page.char) * 100).roundToInt()
        return PageFace(layout, page, header, "${page.index + 1} of ${layout.pages.size}  ·  $percent%")
    }

    private fun fractionAt(chapter: Chapter, block: Int, char: Int): Float {
        val book = book ?: return 0f
        val spine = chapter.spineIndex
        return (book.fractionBefore(spine) + book.fractionOf(spine) * chapter.fractionAt(block, char)).coerceIn(0f, 1f)
    }

    /**
     * The contents entry a spine item belongs to: the first entry of the nearest listed item at or
     * before it, so that a chapter with sections of its own is named for the chapter.
     */
    private fun tocIndexOf(spine: Int): Int {
        val listed = tocSpines.filter { it in 0..spine }.maxOrNull() ?: return -1
        return tocSpines.indexOf(listed)
    }

    private fun loadNeighbours(sheet: Sheet) {
        val lastSpine = book?.spine?.lastIndex ?: return
        val layout = sheet.first.layout
        val before = sheet.spine - 1
        val after = sheet.spine + 1
        // Sheets in the same chapter, or in a chapter already laid out, are ready at once.
        previous = when {
            sheet.index > 0 -> sheetOf(layout, sheet.index - 1)
            else -> layouts[before]?.let { sheetOf(it, sheetCount(it) - 1) }
        }
        next = when {
            sheet.index + 1 < sheetCount(layout) -> sheetOf(layout, sheet.index + 1)
            else -> layouts[after]?.let { sheetOf(it, 0) }
        }
        neighbourJob?.cancel()
        neighbourJob = viewModelScope.launch {
            // Lay out the chapters either side even mid-chapter, so crossing into them is instant.
            for (spine in intArrayOf(after, before)) {
                if (spine !in 0..lastSpine) continue
                val neighbour = try {
                    layoutOf(spine)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // The error surfaces if the reader actually goes there.
                }
                if (current !== sheet) return@launch
                if (spine == after && next == null) next = sheetOf(neighbour, 0)
                if (spine == before && previous == null) previous = sheetOf(neighbour, sheetCount(neighbour) - 1)
            }
            layouts.keys.retainAll { abs(it - sheet.spine) <= KEEP_AROUND }
            chapters.keys.retainAll { abs(it - sheet.spine) <= KEEP_AROUND }
        }
    }

    private suspend fun layoutOf(spine: Int): ChapterLayout {
        layouts[spine]?.let { return it }
        val book = book!!
        val parser = parser!!
        val images = images!!
        val spec = spec!!
        val job = layoutJobs.getOrPut(spine) {
            viewModelScope.async {
                val layout = withContext(Dispatchers.Default) {
                    val chapter = chapters.getOrPut(spine) { parser.parse(spine) }
                    val locale = book.metadata.language?.let(Locale::forLanguageTag)
                    paginate(chapter, spec.page, spec.typography.copy(locale = locale), spec.density, images::size)
                }
                layouts[spine] = layout
                layoutJobs.remove(spine)
                layout
            }
        }
        return job.await()
    }

    private fun positionChanged(sheet: Sheet) {
        val book = book ?: return
        val layout = sheet.first.layout
        val atEnd = sheet.spine == book.spine.lastIndex && sheet.index == sheetCount(layout) - 1
        val fraction = if (atEnd) 1f else fractionAt(layout.chapter, locator.block, locator.char)
        progress = fraction
        tocIndex = tocIndexOf(sheet.spine)
        val at = locator
        appScope.launch { library.saveProgress(bookId, at.spine, at.block, at.char, fraction) }
        if (moved) {
            pushJob?.cancel()
            pushJob = viewModelScope.launch {
                delay(PUSH_DELAY_MS)
                push()
            }
        }
    }

    private fun pullRemote() {
        viewModelScope.launch {
            val account = settings.koSyncAccount.first() ?: return@launch
            try {
                val pulled = koSync.pull(account, bookId) ?: return@launch
                if (pulled.deviceId == settings.deviceId()) return@launch
                // If the reader has already turned pages here, this device is the newer position.
                if (moved) return@launch
                if (pulled.percentage != null && abs(pulled.percentage - progress) < 0.005f) return@launch
                remote = RemoteProgress(pulled.percentage, pulled.device, pulled.progress)
            } catch (e: IOException) {
                syncError = e.message ?: "Sync failed"
            }
        }
    }

    private fun push() {
        if (!moved) return
        val sheet = current ?: return
        val payload = KoProgress(
            document = bookId,
            progress = XPointer.format(sheet.first.layout.chapter, locator),
            percentage = progress,
            device = "Folio (${Build.MODEL})",
        )
        moved = false
        appScope.launch {
            val account = settings.koSyncAccount.first() ?: return@launch
            try {
                koSync.push(account, payload.copy(deviceId = settings.deviceId()))
                syncError = null
            } catch (e: IOException) {
                moved = true
                syncError = e.message ?: "Sync failed"
            }
        }
    }

    private companion object {
        const val KEEP_AROUND = 2
        const val PUSH_DELAY_MS = 3_000L
    }
}

/** Sizes and decoded bitmaps of a book's images. */
private class BookImages(
    private val book: EpubBook,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit,
) {
    private val sizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val loading = HashSet<String>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Intrinsic size of an image, or null if it is missing or not a bitmap format. Thread-safe. */
    fun size(path: String): Pair<Int, Int>? {
        sizes[path]?.let { return it.takeIf { size -> size.first > 0 } }
        val bytes = book.read(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val size = if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else 0 to 0
        sizes[path] = size
        return size.takeIf { it.first > 0 }
    }

    /** Main thread only. Decodes in the background, scaled down to what a page of [fit] can show. */
    fun bitmap(path: String, fit: PageSize?): Bitmap? {
        cache.get(path)?.let { return it }
        if (fit == null || !loading.add(path)) return null
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                val bytes = book.read(path) ?: return@withContext null
                val (width, height) = size(path) ?: return@withContext null
                var sample = 1
                while (width / (sample * 2) >= fit.width && height / (sample * 2) >= fit.height) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (decoded != null) {
                cache.put(path, decoded)
                loading.remove(path)
                onLoaded()
            }
        }
        return null
    }

    private companion object {
        const val CACHE_BYTES = 64 * 1024 * 1024
    }
}
