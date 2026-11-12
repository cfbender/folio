package dev.cfbender.folio

import android.graphics.Typeface
import dev.cfbender.folio.epub.EpubBook
import dev.cfbender.folio.render.Chapter
import dev.cfbender.folio.render.ChapterLayout
import dev.cfbender.folio.render.ChapterParser
import dev.cfbender.folio.render.ImageBlock
import dev.cfbender.folio.render.Locator
import dev.cfbender.folio.render.PageItem
import dev.cfbender.folio.render.PageSize
import dev.cfbender.folio.render.TextBlock
import dev.cfbender.folio.render.Typography
import dev.cfbender.folio.render.XPointer
import dev.cfbender.folio.render.paginate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** EPUB parsing, pagination, and KOReader position pointers, on a book built by [TestEpub]. */
@RunWith(RobolectricTestRunner::class)
class EngineTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var book: EpubBook
    private lateinit var chapter: Chapter

    @Before fun open() {
        book = EpubBook.open(TestEpub.write(temp.newFile("book.epub")))
        chapter = ChapterParser(book).parse(1)
    }

    @After fun close() = book.close()

    @Test fun readsPackageMetadataSpineAndContents() {
        assertEquals(TestEpub.TITLE, book.metadata.title)
        assertEquals(TestEpub.AUTHOR, book.metadata.author)
        assertEquals("en", book.metadata.language)
        assertEquals(
            listOf("OEBPS/text/cover.xhtml", "OEBPS/text/chapter1.xhtml", "OEBPS/text/chapter2.xhtml", "OEBPS/text/chapter3.xhtml"),
            book.spine.map { it.path },
        )
        assertEquals("OEBPS/images/cover.png", book.coverPath)
        assertEquals(listOf("Chapter 1", "A note", "Chapter 2", "Chapter 3"), book.toc.map { it.title })
        assertEquals(listOf(0, 1, 0, 0), book.toc.map { it.depth })
        assertEquals("note", book.toc[1].fragment)
        assertEquals(2, book.spineIndexOf(book.toc[2].path))
    }

    @Test fun spineFractionsCoverTheBookInOrder() {
        assertEquals(0f, book.fractionBefore(0), 0f)
        var total = 0f
        for (i in book.spine.indices) {
            assertEquals(total, book.fractionBefore(i), 1e-4f)
            assertTrue(book.fractionOf(i) > 0f)
            total += book.fractionOf(i)
        }
        assertEquals(1f, total, 1e-4f)
    }

    @Test fun stylesheetShapesBlocks() {
        val heading = chapter.blocks[0] as TextBlock
        assertEquals("Chapter 1", heading.text.toString())
        assertTrue(heading.heading)
        assertTrue(heading.breakBefore)

        val marked = chapter.blocks[2] as TextBlock
        assertEquals("A second paragraph with emphasis inside and a link.", marked.text.toString())
        assertTrue(chapter.blocks.none { it is TextBlock && it.text.contains("never be shown") })
        assertEquals(0, chapter.anchors["c1"])
        assertEquals(2, chapter.anchors["note"])
    }

    @Test fun coverPageIsAnImage() {
        val cover = ChapterParser(book).parse(0)
        assertEquals(listOf("OEBPS/images/cover.png"), cover.blocks.map { (it as ImageBlock).path })
        val layout = paginate(cover, PageSize(400, 700), typography(), 1f) { 600 to 900 }
        val image = layout.pages.single().items.single() as PageItem.Image
        // Scaled to fit the width, keeping its 2:3 shape, and centred.
        assertEquals(400f, image.bounds.width(), 0.5f)
        assertEquals(600f, image.bounds.height(), 0.5f)
        assertEquals(0f, image.bounds.left, 0.5f)
    }

    @Test fun everyLineLandsOnExactlyOnePage() {
        for (size in listOf(PageSize(420, 760), PageSize(700, 500), PageSize(260, 900))) {
            val layout = paginate(chapter, size, typography(), 1f) { null }
            assertTrue("expected several pages at $size", layout.pages.size > 3)
            assertLinesCoveredOnce(layout)
            for (page in layout.pages) {
                for (item in page.items.filterIsInstance<PageItem.Text>()) {
                    val bottom = item.y + item.layout.getLineTop(item.endLine) - item.layout.getLineTop(item.firstLine)
                    assertTrue("text runs off the page at $size: $bottom", item.y >= 0f && bottom <= size.height + 0.5f)
                    assertTrue(item.x >= 0f && item.x + item.layout.width <= size.width + 0.5f)
                }
            }
        }
    }

    @Test fun pagesStartAtIncreasingPositionsAndCanBeFoundAgain() {
        val layout = paginate(chapter, PageSize(420, 760), typography(), 1f) { null }
        var last = -1L
        for (page in layout.pages) {
            val position = page.block * 1_000_000L + page.char
            assertTrue(position > last)
            last = position
            assertEquals(page.index, layout.pageOf(page.block, page.char))
            assertEquals(page.index, layout.pageOf(page.block, page.char + 1))
        }
        assertEquals(0, layout.pageOf(0, 0))
        assertEquals(layout.pages.lastIndex, layout.pageOf(Int.MAX_VALUE, 0))
    }

    @Test fun aPositionSurvivesRepagination() {
        // Unfolding the phone re-paginates at another size; the first word of the page being read
        // must still be on the page that is shown afterwards.
        val narrow = paginate(chapter, PageSize(300, 640), typography(), 1f) { null }
        val wide = paginate(chapter, PageSize(520, 700), typography(), 1f) { null }
        for (page in narrow.pages) {
            val shown = wide.pages[wide.pageOf(page.block, page.char)]
            val holder = shown.items.filterIsInstance<PageItem.Text>().firstOrNull {
                it.block == page.block &&
                    page.char >= it.layout.getLineStart(it.firstLine) &&
                    page.char < it.layout.getLineStart(it.endLine)
            }
            assertNotNull("character ${page.char} of block ${page.block} is not on page ${shown.index}", holder)
        }
    }

    @Test fun biggerTextMeansMorePages() {
        val small = paginate(chapter, PageSize(420, 760), typography(16f), 1f) { null }
        val large = paginate(chapter, PageSize(420, 760), typography(24f), 1f) { null }
        assertTrue(large.pages.size > small.pages.size)
    }

    // Expected pointers are written out by hand from the chapter's markup in TestEpub, following
    // crengine's rules: 1-based DocFragment, an index only among same-named siblings, and
    // whitespace-only text nodes not counted.
    @Test fun formatsCrenginePointers() {
        assertEquals("/body/DocFragment[2]/body/div/h1/text().0", XPointer.format(chapter, Locator(1, 0, 0)))
        assertEquals("/body/DocFragment[2]/body/div/p[1]/text().6", XPointer.format(chapter, Locator(1, 1, 6)))
        // "A second paragraph with " is 24 characters; "emphasis" sits in its own <em>.
        assertEquals("/body/DocFragment[2]/body/div/p[2]/text()[1].9", XPointer.format(chapter, Locator(1, 2, 9)))
        assertEquals("/body/DocFragment[2]/body/div/p[2]/em/text().2", XPointer.format(chapter, Locator(1, 2, 26)))
        assertEquals("/body/DocFragment[2]/body/div/p[2]/text()[2].1", XPointer.format(chapter, Locator(1, 2, 33)))
        assertEquals("/body/DocFragment[2]/body/div/p[2]/a/text().0", XPointer.format(chapter, Locator(1, 2, 46)))
        // The hidden paragraph is p[3]: it is not shown but still counts in the document.
        assertEquals("/body/DocFragment[2]/body/div/p[4]/text().0", XPointer.format(chapter, Locator(1, 3, 0)))
    }

    @Test fun resolvesPointersWrittenByKoreader() {
        assertEquals(1, XPointer.spineIndex("/body/DocFragment[2]/body/div/p[4]/text().0"))
        assertEquals(0, XPointer.spineIndex("/body/DocFragment/body/div"))
        assertEquals(null, XPointer.spineIndex("epubcfi(/6/4!/4/2)"))

        assertEquals(Locator(1, 2, 33), XPointer.resolve(chapter, "/body/DocFragment[2]/body/div/p[2]/text()[2].1"))
        assertEquals(Locator(1, 2, 26), XPointer.resolve(chapter, "/body/DocFragment[2]/body/div/p[2]/em/text().2"))
        // KOReader often reports an element with no text node or offset.
        assertEquals(Locator(1, 3, 0), XPointer.resolve(chapter, "/body/DocFragment[2]/body/div/p[4]"))
        assertEquals(Locator(1, 2, 24), XPointer.resolve(chapter, "/body/DocFragment[2]/body/div/p[2]/em"))
        // A step that does not exist here falls back to the nearest ancestor that does.
        assertEquals(Locator(1, 3, 0), XPointer.resolve(chapter, "/body/DocFragment[2]/body/div/p[4]/span[3]/text().7"))
        assertEquals(Locator(1, 0, 0), XPointer.resolve(chapter, "/body/DocFragment[2]/body/section[9]/p"))
    }

    @Test fun pointersRoundTripAtEveryPageStart() {
        val layout = paginate(chapter, PageSize(300, 500), typography(), 1f) { null }
        val positions = layout.pages.map { Locator(1, it.block, it.char) } +
            chapter.blocks.indices.flatMap { i ->
                val length = chapter.blocks[i].charLength
                listOf(0, length / 3, length - 1).distinct().map { Locator(1, i, it) }
            }
        assertTrue(positions.size > 100)
        for (position in positions) {
            val pointer = XPointer.format(chapter, position)
            assertEquals(pointer, position, XPointer.resolve(chapter, pointer))
        }
    }

    private fun typography(size: Float = 18f) = Typography(Typeface.SERIF, size)

    private fun assertLinesCoveredOnce(layout: ChapterLayout) {
        val items = layout.pages.flatMap { it.items }.filterIsInstance<PageItem.Text>().groupBy { it.block }
        layout.chapter.blocks.forEachIndexed { index, block ->
            if (block !is TextBlock) return@forEachIndexed
            val pieces = items[index] ?: error("block $index was never placed")
            assertEquals(0, pieces.first().firstLine)
            for ((a, b) in pieces.zipWithNext()) assertEquals("block $index", a.endLine, b.firstLine)
            assertEquals(pieces.last().layout.lineCount, pieces.last().endLine)
            assertFalse(pieces.any { it.endLine <= it.firstLine })
            assertEquals(block.text.toString(), pieces.first().layout.text.toString())
        }
    }
}
