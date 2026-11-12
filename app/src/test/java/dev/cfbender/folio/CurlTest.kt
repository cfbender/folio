package dev.cfbender.folio

import dev.cfbender.folio.reader.LeafTurn
import dev.cfbender.folio.reader.ReaderGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class CurlTest {
    // The right-hand leaf of a 2000×1000 spread, held two thirds of the way down its outer edge.
    private val leaf = LeafTurn(left = 1000f, top = 0f, right = 2000f, bottom = 1000f, spineX = 1000f, originY = 650f)

    @Test fun heldPointSitsOnTheEdgeAwayFromTheSpine() {
        assertEquals(2000f, leaf.originX)
        assertEquals(0f, leaf.endX)
        assertEquals(0.25f, leaf.progress(1500f), 1e-6f)

        val leftLeaf = LeafTurn(0f, 0f, 1000f, 1000f, spineX = 1000f, originY = 650f)
        assertEquals(0f, leftLeaf.originX)
        assertEquals(2000f, leftLeaf.endX)
    }

    @Test fun foldCarriesTheHeldPointToTheFinger() {
        val fold = leaf.fold(1300f, 420f)!!
        assertEquals(1300f, fold.reflectX(leaf.originX, leaf.originY), 1e-2f)
        assertEquals(420f, fold.reflectY(leaf.originX, leaf.originY), 1e-2f)
        // The matrix handed to the canvas is the same reflection.
        val m = fold.matrix()
        assertEquals(1300f, m[0] * leaf.originX + m[1] * leaf.originY + m[2], 1e-1f)
        assertEquals(420f, m[3] * leaf.originX + m[4] * leaf.originY + m[5], 1e-1f)
    }

    @Test fun fullyTurnedLeafLandsExactlyOnTheFacingPage() {
        // The back of a turning leaf is drawn as the facing page mirrored over the spine and then
        // over the fold. Once the turn completes those two mirrors must cancel, or the page would
        // jump when the turn ends.
        val m = leaf.fold(leaf.endX, leaf.originY)!!.matrix()
        for ((x, y) in listOf(120f to 80f, 990f to 940f, 400f to 500f)) {
            val spineX = 2 * leaf.spineX - x
            assertEquals(x, m[0] * spineX + m[1] * y + m[2], 1e-2f)
            assertEquals(y, m[3] * spineX + m[4] * y + m[5], 1e-2f)
        }
    }

    @Test fun leafAtRestHasNoFold() {
        assertNull(leaf.fold(leaf.originX, leaf.originY))
    }

    @Test fun clampKeepsThePaperAttachedToTheSpine() {
        // Dragging the corner up and away would tear the leaf from the bottom of the binding.
        val (x, y) = leaf.clamp(1900f, -600f)
        for (anchorY in listOf(0f, 1000f)) {
            val reach = hypot(leaf.originX - leaf.spineX, leaf.originY - anchorY)
            assertTrue(hypot(x - leaf.spineX, y - anchorY) <= reach + 0.5f)
        }
        assertFalse(abs(x - 1900f) < 1f && abs(y + 600f) < 1f)

        // A target the paper can reach is left alone.
        assertEquals(1400f to 600f, leaf.clamp(1400f, 600f))
    }

    @Test fun moreOfTheLeafLiftsAsTheTurnProceeds() {
        var last = 0f
        for (x in listOf(1900f, 1600f, 1200f, 700f, 200f, 0f)) {
            val area = area(leaf.lifted(leaf.fold(x, leaf.originY)!!))
            assertTrue("lifted area should grow at x=$x: $area after $last", area > last)
            last = area
        }
        assertEquals(1000f * 1000f, last, 1f) // fully turned: the whole leaf has lifted
    }

    @Test fun liftedPaperStaysOnItsOwnLeaf() {
        val lifted = leaf.lifted(leaf.fold(1300f, 420f)!!)
        for (i in lifted.indices step 2) {
            assertTrue(lifted[i] in 999.9f..2000.1f)
            assertTrue(lifted[i + 1] in -0.1f..1000.1f)
        }
    }

    @Test fun phoneShowsOnePage() {
        val g = ReaderGeometry.of(width = 1080, height = 2400, density = 2.75f)
        assertFalse(g.spread)
        assertEquals(1, g.leafCount)
    }

    @Test fun unfoldedPhoneOpensAtTheHinge() {
        // A book-style foldable, unfolded: a zero-width fold down the middle.
        val g = ReaderGeometry.of(width = 2208, height = 1840, density = 2.6f, foldLeft = 1104, foldRight = 1104)
        assertTrue(g.spread)
        assertEquals(1104f, g.spineX)
        // Both text areas fit on their own side of the fold.
        assertTrue(g.textLeft(0) + g.pageSize.width <= 1104f)
        assertTrue(g.textLeft(1) >= 1104f)
        assertTrue(g.textLeft(1) + g.pageSize.width <= 2208f)
    }

    @Test fun textClearsAHingeWithWidth() {
        // A dual-screen device whose hinge covers 84 px of the window.
        val g = ReaderGeometry.of(width = 2784, height = 1800, density = 2.5f, foldLeft = 1350, foldRight = 1434)
        assertEquals(1392f, g.spineX)
        assertTrue(g.textLeft(0) + g.pageSize.width <= 1350f)
        assertTrue(g.textLeft(1) >= 1434f)
    }

    @Test fun tabletInLandscapeIsASpreadButNotInPortrait() {
        assertTrue(ReaderGeometry.of(width = 2560, height = 1600, density = 2f).spread)
        assertFalse(ReaderGeometry.of(width = 1600, height = 2560, density = 2f).spread)
    }

    private fun area(polygon: FloatArray): Float {
        var sum = 0f
        val n = polygon.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += polygon[2 * i] * polygon[2 * j + 1] - polygon[2 * j] * polygon[2 * i + 1]
        }
        return abs(sum) / 2
    }
}
