package dev.cfbender.folio.reader

import kotlin.math.abs
import kotlin.math.hypot

/*
 * Geometry of a page curl, modelled as paper folded along a straight line. A leaf is bound at the
 * spine and held by a point on its outer edge (the origin). Dragging that point to a target folds
 * the leaf along the perpendicular bisector of origin and target: paper on the origin's side of
 * the line lifts off and lands mirrored on the other side, showing its back.
 *
 * Nothing here touches Android, so the maths is tested on the JVM.
 */

/**
 * A leaf at rest in [left, right] × [top, bottom], bound along one vertical edge at [spineX] and
 * picked up at height [originY] on the opposite edge.
 */
class LeafTurn(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val spineX: Float,
    val originY: Float,
) {
    /** The held point at rest: on the edge away from the spine. */
    val originX: Float = if (abs(left - spineX) < abs(right - spineX)) right else left

    /** Where the held point ends up once the leaf lies fully turned on the other side of the spine. */
    val endX: Float = 2 * spineX - originX

    /** How far the turn has gone for a target at [x]: 0 at rest, 1 when fully turned. */
    fun progress(x: Float): Float = (x - originX) / (endX - originX)

    /**
     * Pulls a target back to where the paper can reach. The held point cannot get further from
     * either end of the spine than it is at rest without tearing the leaf from its binding.
     */
    fun clamp(x: Float, y: Float): Pair<Float, Float> {
        var tx = x
        var ty = y
        repeat(3) {
            for (anchorY in floatArrayOf(top, bottom)) {
                val reach = hypot(originX - spineX, originY - anchorY)
                val distance = hypot(tx - spineX, ty - anchorY)
                if (distance > reach && distance > 0f) {
                    tx = spineX + (tx - spineX) * reach / distance
                    ty = anchorY + (ty - anchorY) * reach / distance
                }
            }
        }
        return tx to ty
    }

    /** The fold for a (clamped) target, or null while the leaf has not left its resting place. */
    fun fold(x: Float, y: Float): Fold? {
        val dx = x - originX
        val dy = y - originY
        val length = hypot(dx, dy)
        if (length < 0.5f) return null
        return Fold((originX + x) / 2, (originY + y) / 2, dx / length, dy / length, length / 2)
    }

    /** The part of the leaf that has lifted off, as a polygon of x,y pairs; empty if none has. */
    fun lifted(fold: Fold): FloatArray = fold.clipBehind(floatArrayOf(left, top, right, top, right, bottom, left, bottom))
}

/**
 * A fold line through ([px], [py]) with unit normal ([nx], [ny]) pointing from the lifted side to
 * the side the paper lands on. [reach] is how far the lifted paper extends past the line.
 */
class Fold(val px: Float, val py: Float, val nx: Float, val ny: Float, val reach: Float) {

    /** Signed distance of a point from the line; negative on the lifted side. */
    fun side(x: Float, y: Float): Float = (x - px) * nx + (y - py) * ny

    fun reflectX(x: Float, y: Float): Float = x - 2 * side(x, y) * nx

    fun reflectY(x: Float, y: Float): Float = y - 2 * side(x, y) * ny

    /** The reflection across the line as a row-major 3×3 matrix, as android.graphics.Matrix takes. */
    fun matrix(): FloatArray {
        val c = 2 * (px * nx + py * ny)
        return floatArrayOf(
            1 - 2 * nx * nx, -2 * nx * ny, c * nx,
            -2 * nx * ny, 1 - 2 * ny * ny, c * ny,
            0f, 0f, 1f,
        )
    }

    /** Reflects a polygon of x,y pairs across the line. */
    fun reflect(polygon: FloatArray): FloatArray {
        val out = FloatArray(polygon.size)
        for (i in polygon.indices step 2) {
            out[i] = reflectX(polygon[i], polygon[i + 1])
            out[i + 1] = reflectY(polygon[i], polygon[i + 1])
        }
        return out
    }

    /** Clips a convex polygon to the lifted side of the line (Sutherland–Hodgman, one edge). */
    fun clipBehind(polygon: FloatArray): FloatArray {
        val out = ArrayList<Float>(polygon.size + 4)
        val count = polygon.size / 2
        for (i in 0 until count) {
            val ax = polygon[2 * i]
            val ay = polygon[2 * i + 1]
            val j = (i + 1) % count
            val bx = polygon[2 * j]
            val by = polygon[2 * j + 1]
            val sa = side(ax, ay)
            val sb = side(bx, by)
            if (sa <= 0f) {
                out += ax
                out += ay
            }
            if ((sa < 0f && sb > 0f) || (sa > 0f && sb < 0f)) {
                val t = sa / (sa - sb)
                out += ax + (bx - ax) * t
                out += ay + (by - ay) * t
            }
        }
        return if (out.size < 6) FloatArray(0) else out.toFloatArray()
    }
}
