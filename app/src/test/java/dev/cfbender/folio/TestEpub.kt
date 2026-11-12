package dev.cfbender.folio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a small EPUB 3 on the fly, so tests need no binary fixtures. It has a cover page with an
 * image, three chapters of prose with inline markup and anchors, and a nested table of contents.
 */
object TestEpub {
    const val TITLE = "Pride and Prejudice"
    const val AUTHOR = "Jane Austen"

    /** Spine: 0 cover, 1–3 chapters. */
    const val CHAPTERS = 3

    private val OPENING = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well " +
            "fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.",
        "“My dear Mr. Bennet,” said his lady to him one day, “have you heard that <em>Netherfield Park</em> is let at last?”",
        "Mr. Bennet replied that he had not.",
        "“But it is,” returned she; “for Mrs. Long has just been here, and she told me all about it.”",
        "Mr. Bennet made no answer.",
        "“Do not you want to know who has taken it?” cried his wife impatiently.",
        "“<em>You</em> want to tell me, and I have no objection to hearing it.”",
        "This was invitation enough.",
        "“Why, my dear, you must know, Mrs. Long says that Netherfield is taken by a young man of large fortune from the north of England; " +
            "that he came down on Monday in a chaise and four to see the place, and was so much delighted with it that he agreed with " +
            "Mr. Morris immediately; that he is to take possession before Michaelmas, and some of his servants are to be in the house by the end of next week.”",
        "“What is his name?”",
        "“Bingley.”",
        "“Is he married or single?”",
        "“Oh! single, my dear, to be sure! A single man of large fortune; four or five thousand a year. What a fine thing for our girls!”",
        "“How so? how can it affect them?”",
        "“My dear Mr. Bennet,” replied his wife, “how can you be so tiresome! You must know that I am thinking of his marrying one of them.”",
        "“Is that his design in settling here?”",
        "“Design! nonsense, how can you talk so! But it is very likely that he <em>may</em> fall in love with one of them, and therefore " +
            "you must visit him as soon as he comes.”",
    )

    /** The second paragraph of chapter one, which tests address by position. */
    const val MARKED_PARAGRAPH = "A second paragraph with <em>emphasis</em> inside and a <a id=\"note\" href=\"#c1\">link</a>."

    /** The words of a chapter as a reader sees them, paragraphs separated by single spaces. */
    fun chapterText(chapter: Int, paragraphRepeats: Int = 6): String {
        val paragraphs = buildList {
            add("Chapter $chapter")
            add(OPENING[0])
            if (chapter == 1) add(MARKED_PARAGRAPH)
            repeat(paragraphRepeats) { addAll(OPENING.drop(1)) }
        }
        return paragraphs.joinToString(" ") { it.replace(Regex("<[^>]+>"), "") }
    }

    /**
     * The crengine XPointer of the character at [offset] in [chapterText] of chapter 2 or 3, worked
     * out from the markup [write] produces rather than by the app's own XPointer code.
     */
    fun xpointerAt(chapter: Int, offset: Int): String {
        require(chapter in 2..CHAPTERS) { "chapter 1 has an extra paragraph" }
        val root = "/body/DocFragment[${chapter + 1}]/body/div"
        var left = offset
        val heading = "Chapter $chapter"
        if (left < heading.length) return "$root/h1/text().$left"
        left -= heading.length + 1
        // p[1] opens the chapter, p[2] is the hidden one, and the repeated paragraphs follow.
        val paragraphs = listOf(1 to OPENING[0]) + generateSequence(0) { it + 1 }.take(200).map { it + 3 to OPENING[1 + it % (OPENING.size - 1)] }
        for ((number, markup) in paragraphs) {
            val nodes = Regex("<em>(.*?)</em>|([^<]+)").findAll(markup).map { it.groupValues[1] to it.groupValues[2] }.toList()
            val textNodes = nodes.count { it.second.isNotEmpty() }
            var textNode = 0
            for ((emphasised, plain) in nodes) {
                if (plain.isNotEmpty()) textNode++
                val length = emphasised.length + plain.length
                if (left < length) {
                    return when {
                        emphasised.isNotEmpty() -> "$root/p[$number]/em/text().$left"
                        textNodes > 1 -> "$root/p[$number]/text()[$textNode].$left"
                        else -> "$root/p[$number]/text().$left"
                    }
                }
                left -= length
            }
            left -= 1 // the space that joins paragraphs in chapterText
        }
        error("offset $offset is past the end of the chapter")
    }

    /** [title], [author], and [cover] exist so that a library can hold books that look different. */
    fun write(file: File, paragraphRepeats: Int = 6, title: String = TITLE, author: String = AUTHOR, cover: Boolean = true): File {
        // A book without a cover still has the picture, under a name that does not suggest one.
        val art = if (cover) "cover.png" else "plate.png"
        ZipOutputStream(file.outputStream()).use { zip ->
            // The mimetype entry must come first and be stored uncompressed.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(path: String, text: String) = put(path, text.trimIndent().toByteArray())

            put(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """,
            )
            val items = (1..CHAPTERS).joinToString("\n") { """<item id="c$it" href="text/chapter$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val refs = (1..CHAPTERS).joinToString("\n") { """<itemref idref="c$it"/>""" }
            put(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:test:pride</dc:identifier>
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="css" href="style/book.css" media-type="text/css"/>
                    <item id="art" href="images/$art" media-type="image/png"${if (cover) " properties=\"cover-image\"" else ""}/>
                    <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                    $items
                  </manifest>
                  <spine>
                    <itemref idref="cover"/>
                    $refs
                  </spine>
                </package>
                """,
            )
            put(
                "OEBPS/nav.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body><nav epub:type="toc"><ol>
                  <li><a href="text/chapter1.xhtml#c1">Chapter 1</a>
                    <ol><li><a href="text/chapter1.xhtml#note">A note</a></li></ol>
                  </li>
                  <li><a href="text/chapter2.xhtml">Chapter 2</a></li>
                  <li><a href="text/chapter3.xhtml">Chapter 3</a></li>
                </ol></nav></body></html>
                """,
            )
            put(
                "OEBPS/style/book.css",
                """
                h1 { text-align: center; margin: 2em 0 1.5em; font-size: 1.5em; page-break-before: always; }
                p { margin: 0; text-indent: 1.2em; text-align: justify; }
                p.first { text-indent: 0; }
                .hidden { display: none; }
                """,
            )
            put(
                "OEBPS/text/cover.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cover</title></head>
                <body><div><img style="display: block; margin: 0 auto" src="../images/$art" alt="Cover"/></div></body></html>
                """,
            )
            put("OEBPS/images/$art", coverPng())
            for (chapter in 1..CHAPTERS) {
                val body = buildString {
                    append("<p class=\"first\">").append(OPENING[0]).append("</p>\n")
                    if (chapter == 1) append("<p>").append(MARKED_PARAGRAPH).append("</p>\n")
                    append("<p class=\"hidden\">This must never be shown.</p>\n")
                    repeat(paragraphRepeats) {
                        for (paragraph in OPENING.drop(1)) append("<p>").append(paragraph).append("</p>\n")
                    }
                }
                put(
                    "OEBPS/text/chapter$chapter.xhtml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $chapter</title>
                    <link rel="stylesheet" type="text/css" href="../style/book.css"/></head>
                    <body>
                    <div class="chapter">
                    <h1 id="c$chapter">Chapter $chapter</h1>
                    """.trimIndent() + "\n" + body + "</div>\n</body></html>",
                )
            }
        }
        return file
    }

    fun coverPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x2F, 0x4B, 0x5C))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF2, 0xE3, 0xC6) }
        canvas.drawRect(60f, 60f, 540f, 840f, Paint().apply { color = Color.rgb(0x26, 0x3D, 0x4B) })
        paint.textSize = 64f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("PRIDE", 300f, 380f, paint)
        paint.textSize = 36f
        canvas.drawText("and", 300f, 440f, paint)
        paint.textSize = 64f
        canvas.drawText("PREJUDICE", 300f, 520f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
