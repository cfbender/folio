package dev.cfbender.folio.reader

import android.graphics.RectF
import dev.cfbender.folio.render.PageSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Space the system keeps for itself at each window edge, in pixels. */
data class EdgeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/**
 * Where the pages sit in the reader window, in pixels. A window shows either one leaf filling it,
 * or two leaves meeting at a spine like an open book.
 */
data class ReaderGeometry(
    val width: Int,
    val height: Int,
    /** x of the spine when two leaves are shown side by side; null for a single leaf. */
    val spineX: Float?,
    /** Text inset from a leaf's outer edge. */
    val outer: Float,
    /** Text inset from the spine; equal to [outer] for a single leaf. */
    val gutter: Float,
    val top: Float,
    val bottom: Float,
) {
    val spread: Boolean get() = spineX != null
    val leafCount: Int get() = if (spread) 2 else 1

    /** Size of the text area, the same on every leaf so that one pagination serves both. */
    val pageSize: PageSize
        get() {
            val leafWidth = spineX?.let { min(it, width - it) } ?: width.toFloat()
            return PageSize(
                max(1, (leafWidth - outer - gutter).toInt()),
                max(1, (height - top - bottom).toInt()),
            )
        }

    /** The rectangle of leaf [index]: 0 is the only or left leaf, 1 the right. */
    fun leaf(index: Int): RectF = when {
        spineX == null -> RectF(0f, 0f, width.toFloat(), height.toFloat())
        index == 0 -> RectF(0f, 0f, spineX, height.toFloat())
        else -> RectF(spineX, 0f, width.toFloat(), height.toFloat())
    }

    /** Left edge of the text area of leaf [index]. */
    fun textLeft(index: Int): Float = when {
        spineX == null -> outer
        index == 0 -> spineX - gutter - pageSize.width
        else -> spineX + gutter
    }

    companion object {
        /**
         * Lays out the reader for a window. A vertical fold makes the window a spread with its
         * spine on the fold; so does a window wide enough for two comfortable columns.
         *
         * @param foldLeft left and right edges of a vertical fold or hinge, if the window has one.
         */
        fun of(
            width: Int,
            height: Int,
            density: Float,
            insets: EdgeInsets = EdgeInsets(),
            foldLeft: Int? = null,
            foldRight: Int? = null,
        ): ReaderGeometry {
            fun dp(value: Float) = value * density
            val hasFold = foldLeft != null && foldRight != null && foldLeft > 0 && foldRight < width
            val wide = width >= dp(600f) && width >= height * 0.9f
            val top = insets.top + dp(44f)
            val bottom = insets.bottom + dp(44f)

            if (!hasFold && !wide) {
                val side = max(max(insets.left, insets.right).toFloat(), 0f) + dp(26f)
                return ReaderGeometry(width, height, null, side, side, top, bottom)
            }
            val spine = if (hasFold) (foldLeft!! + foldRight!!) / 2f else width / 2f
            // A hinge with real width hides whatever is drawn behind it, so text clears it.
            val hinge = if (hasFold) (foldRight!! - foldLeft!!) / 2f else 0f
            val leafWidth = min(spine, width - spine)
            // Margins grow a little with the leaf so that wide leaves keep a readable measure.
            val outer = max(insets.left, insets.right) + (leafWidth * 0.08f).coerceIn(dp(24f), dp(56f))
            val gutter = hinge + (leafWidth * 0.07f).coerceIn(dp(22f), dp(48f))
            return ReaderGeometry(width, height, spine.roundToInt().toFloat(), outer, gutter, top, bottom)
        }
    }
}
