package dev.cfbender.folio.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.cfbender.folio.render.ChapterLayout
import dev.cfbender.folio.render.Page

/** One side of a leaf as shown: a laid-out page with its running header and footer. */
class PageFace(val layout: ChapterLayout, val page: Page, val header: String, val footer: String)

/**
 * What the window shows at rest: one page, or the two facing pages of a spread. [second] is used
 * only in a spread and is null when a chapter ends on a left-hand page.
 */
class Sheet(val spine: Int, val index: Int, val first: PageFace, val second: PageFace?) {
    fun face(leaf: Int): PageFace? = if (leaf == 0) first else second

    /** The words on the sheet in reading order. */
    fun text(): String = listOfNotNull(first, second).joinToString("\n") { it.layout.text(it.page) }
}

data class PageColors(val paper: Int, val ink: Int, val muted: Int, val dark: Boolean)

/** One frame of a leaf being turned. */
class TurnFrame(
    /** Which leaf of the window is turning. */
    val leafIndex: Int,
    val turn: LeafTurn,
    /** Where the held point of the leaf is now. */
    val x: Float,
    val y: Float,
    /** The turning leaf's upper side. */
    val front: PageFace?,
    /** What lies beneath the turning leaf. */
    val under: PageFace?,
    /** The turning leaf's reverse, which lands on the other leaf of a spread; unused for a single leaf. */
    val back: PageFace?,
    /** The leaf of a spread that stays put. */
    val other: PageFace?,
)

/**
 * Draws leaves and page turns onto a platform canvas. Each page's text is recorded once into a
 * [Picture], so a frame of a turn replays display lists under clips and transforms instead of
 * laying text out again.
 *
 * Confined to the main thread.
 */
class PagePainter(
    private val density: Float,
    typeface: Typeface,
    private val bitmap: (String) -> Bitmap?,
) {
    private class Recorded(val picture: Picture, val ink: Int)

    private val pictures = object : LinkedHashMap<Page, Recorded>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Page, Recorded>) = size > 12
    }

    private val fill = Paint()
    private val gradient = Paint()
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        // TextPaint has a density field of its own, which is not the screen's.
        textSize = 12f * this@PagePainter.density
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val flapShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val liftedPath = Path()
    private val flapPath = Path()
    private val matrix = Matrix()

    private var recordedImages = 0

    /**
     * Draws the window: [sheet] at rest, or [frame] if a leaf is turning. [imageVersion] changes
     * when an image of the book finishes loading; pages recorded before that are recorded again.
     */
    fun draw(canvas: Canvas, g: ReaderGeometry, colors: PageColors, sheet: Sheet?, frame: TurnFrame?, imageVersion: Int) {
        if (imageVersion != recordedImages) {
            pictures.clear()
            recordedImages = imageVersion
        }
        if (frame == null) {
            for (leaf in 0 until g.leafCount) drawLeaf(canvas, g, colors, leaf, sheet?.face(leaf))
            return
        }
        if (g.spread) drawLeaf(canvas, g, colors, 1 - frame.leafIndex, frame.other)
        drawTurn(canvas, g, colors, frame)
    }

    private fun drawLeaf(canvas: Canvas, g: ReaderGeometry, colors: PageColors, leaf: Int, face: PageFace?) {
        val rect = g.leaf(leaf)
        fill.color = colors.paper
        canvas.drawRect(rect, fill)

        g.spineX?.let { spine ->
            // Pages of an open book dip into the binding, which reads as a soft shadow at the spine.
            val depth = 30f * density * if (leaf == 0) -1f else 1f
            gradient.shader = LinearGradient(
                spine, 0f, spine + depth, 0f,
                intArrayOf(shade(if (colors.dark) 0.55f else 0.16f), shade(if (colors.dark) 0.18f else 0.05f), 0),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, gradient)
        }

        if (face == null) return
        val left = g.textLeft(leaf)
        val size = g.pageSize
        canvas.save()
        canvas.translate(left - BLEED, g.top - BLEED)
        canvas.drawPicture(picture(face, colors))
        canvas.restore()

        label.color = colors.muted
        val centre = left + size.width / 2f
        if (face.header.isNotEmpty()) {
            val text = TextUtils.ellipsize(face.header, label, size.width * 0.9f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, centre, g.top - 16f * density, label)
        }
        if (face.footer.isNotEmpty()) canvas.drawText(face.footer, centre, g.height - g.bottom + 27f * density, label)
    }

    private fun picture(face: PageFace, colors: PageColors): Picture {
        pictures[face.page]?.takeIf { it.ink == colors.ink }?.let { return it.picture }
        val size = face.layout.size
        val picture = Picture()
        // Recorded with a margin so that italic overhang and hanging hyphens are not culled.
        val canvas = picture.beginRecording(size.width + 2 * BLEED, size.height + 2 * BLEED)
        canvas.translate(BLEED.toFloat(), BLEED.toFloat())
        face.layout.draw(canvas, face.page, colors.ink, bitmap)
        picture.endRecording()
        pictures[face.page] = Recorded(picture, colors.ink)
        return picture
    }

    private fun drawTurn(canvas: Canvas, g: ReaderGeometry, colors: PageColors, frame: TurnFrame) {
        val turn = frame.turn
        val (x, y) = turn.clamp(frame.x, frame.y)
        drawLeaf(canvas, g, colors, frame.leafIndex, frame.front)
        val fold = turn.fold(x, y) ?: return
        val lifted = turn.lifted(fold)
        if (lifted.isEmpty()) return
        liftedPath.set(lifted)
        val flap = fold.reflect(lifted)
        flapPath.set(flap)
        val leafWidth = turn.right - turn.left

        // Where the leaf has lifted, the page beneath shows, shaded by the paper curving above it.
        canvas.save()
        canvas.clipPath(liftedPath)
        drawLeaf(canvas, g, colors, frame.leafIndex, frame.under)
        val cast = (fold.reach * 0.7f).coerceIn(10f * density, leafWidth * 0.3f)
        gradient.shader = LinearGradient(
            fold.px, fold.py, fold.px - fold.nx * cast, fold.py - fold.ny * cast,
            intArrayOf(shade(if (colors.dark) 0.6f else 0.34f), shade(if (colors.dark) 0.25f else 0.1f), 0),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(gradient)
        canvas.restore()

        // The lifted paper also throws a soft shadow on the pages it hangs over.
        canvas.save()
        canvas.clipOutPath(flapPath)
        canvas.clipOutPath(liftedPath)
        flapShadow.color = colors.paper
        flapShadow.setShadowLayer(
            (fold.reach * 0.35f).coerceIn(4f * density, 26f * density), 0f, 0f,
            shade(if (colors.dark) 0.75f else 0.4f),
        )
        canvas.drawPath(flapPath, flapShadow)
        canvas.restore()

        // The lifted paper itself, mirrored across the fold so that its back faces up.
        canvas.save()
        canvas.clipPath(flapPath)
        canvas.save()
        matrix.setValues(fold.matrix())
        canvas.concat(matrix)
        if (g.spread) {
            // The back is printed with the page that will lie on the other leaf: draw that page
            // where it will rest, flipped over the spine onto the leaf it is printed behind.
            canvas.scale(-1f, 1f, turn.spineX, 0f)
            drawLeaf(canvas, g, colors, 1 - frame.leafIndex, frame.back)
        } else {
            // A single leaf has no facing page, so its back is blank paper with the print of the
            // front showing through faintly.
            drawLeaf(canvas, g, colors, frame.leafIndex, frame.front)
            fill.color = colors.paper and 0x00FFFFFF or (0xE0 shl 24)
            canvas.drawRect(turn.left, turn.top, turn.right, turn.bottom, fill)
        }
        canvas.restore()
        // Shading that makes the flat mirror image read as a curve: dark in the crease, a
        // highlight where the paper rounds over, and falling away again toward the free edge.
        val across = fold.reach.coerceAtLeast(1f)
        gradient.shader = LinearGradient(
            fold.px, fold.py, fold.px + fold.nx * across, fold.py + fold.ny * across,
            intArrayOf(
                shade(if (colors.dark) 0.5f else 0.26f),
                shade(0.04f),
                Color.argb(if (colors.dark) 14 else 46, 255, 255, 255),
                0,
                shade(if (colors.dark) 0.3f else 0.12f),
            ),
            floatArrayOf(0f, 0.14f, 0.34f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(gradient)
        canvas.restore()
    }

    private fun Path.set(polygon: FloatArray) {
        rewind()
        moveTo(polygon[0], polygon[1])
        for (i in 2 until polygon.size step 2) lineTo(polygon[i], polygon[i + 1])
        close()
    }

    private fun shade(alpha: Float): Int = Color.argb((alpha * 255).toInt(), 0, 0, 0)

    private companion object {
        const val BLEED = 12
    }
}
