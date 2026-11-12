package dev.cfbender.folio.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/** A turn in progress: which way, and where the held point of the leaf is. */
class ActiveTurn(val direction: TurnDirection, val originY: Float, val point: Offset)

/**
 * Drives page turns from drags and taps. It owns only the motion of the leaf; when a turn
 * completes it calls [onTurned], and the caller swaps in the next sheet in the same frame.
 */
@Stable
class TurnController(private val scope: CoroutineScope) {
    var active: ActiveTurn? by mutableStateOf(null)
        private set

    var geometry: ReaderGeometry? = null
        set(value) {
            if (field != value) cancel()
            field = value
        }
    var canTurn: (TurnDirection) -> Boolean = { false }
    var onTurned: (TurnDirection) -> Unit = {}

    private var job: Job? = null
    private var committing = false
    private var start = Offset.Zero
    private var finish = Offset.Zero
    private var down = Offset.Zero
    private var gain = 1f
    private var wanted = 1f

    /** Abandons any turn in progress without changing the page. */
    fun cancel() {
        job?.cancel()
        job = null
        committing = false
        active = null
    }

    /** Ends a settling animation at once, so the next gesture starts from a page at rest. */
    private fun finishNow() {
        job?.cancel()
        job = null
        val turn = active ?: return
        active = null
        if (committing) {
            committing = false
            onTurned(turn.direction)
        }
    }

    /** Puts a turn at a fixed point without animating, for previews and tests. */
    fun hold(direction: TurnDirection, originY: Float, point: Offset) {
        cancel()
        active = ActiveTurn(direction, originY, point)
    }

    /** Turns the page with an animated curl, as for a tap or a key press. */
    fun turn(direction: TurnDirection) {
        finishNow()
        val g = geometry ?: return
        if (!canTurn(direction)) return
        val originY = g.height * 0.78f
        prepare(g, direction, originY)
        committing = true
        val from = start
        val to = finish
        active = ActiveTurn(direction, originY, from)
        job = scope.launch {
            Animatable(0f).animateTo(1f, tween(TAP_TURN_MS, easing = TAP_EASING)) {
                // The leaf rises on its way over, which tilts the fold like a hand lifting a corner.
                val lift = sin(Math.PI * value).toFloat() * g.height * 0.14f
                active = ActiveTurn(direction, originY, Offset(from.x + (to.x - from.x) * value, originY - lift))
            }
            commit()
        }
    }

    /** Starts following a finger that went down at [at]. False if there is no page that way. */
    fun beginDrag(direction: TurnDirection, at: Offset): Boolean {
        finishNow()
        val g = geometry ?: return false
        if (!canTurn(direction)) return false
        val originY = at.y.coerceIn(g.height * 0.04f, g.height * 0.96f)
        prepare(g, direction, originY)
        down = at
        // Fingers move left to go forward. A finger that starts mid-page has less room to travel,
        // so its motion is scaled up to still carry the leaf the usual distance.
        val room = if (direction == TurnDirection.FORWARD) at.x else g.width - at.x
        gain = max(1f, wanted / max(room, wanted / 3f))
        active = ActiveTurn(direction, originY, start)
        return true
    }

    fun dragTo(touch: Offset) {
        val turn = active ?: return
        val x = start.x + (touch.x - down.x) * gain
        val low = minOf(start.x, finish.x)
        val high = maxOf(start.x, finish.x)
        active = ActiveTurn(turn.direction, turn.originY, Offset(x.coerceIn(low, high), start.y + (touch.y - down.y)))
    }

    /** Lets go of a dragged leaf: it completes the turn or falls back, depending on how far and fast. */
    fun release(velocityX: Float, flingThreshold: Float) {
        val turn = active ?: return
        val towards = sign(finish.x - start.x)
        val travelled = abs(turn.point.x - start.x) / wanted
        val speed = velocityX * towards
        committing = speed > flingThreshold || (travelled > 0.3f && speed > -flingThreshold)
        val target = if (committing) finish else start
        job?.cancel()
        job = scope.launch {
            Animatable(turn.point, Offset.VectorConverter).animateTo(
                target,
                spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = Offset(1f, 1f)),
                initialVelocity = Offset(velocityX * gain, 0f),
            ) {
                active = ActiveTurn(turn.direction, turn.originY, value)
            }
            commit()
        }
    }

    private fun commit() {
        val turn = active ?: return
        job = null
        active = null
        if (committing) {
            committing = false
            onTurned(turn.direction)
        }
    }

    private fun prepare(g: ReaderGeometry, direction: TurnDirection, originY: Float) {
        val path = turnPath(g, direction, originY)
        start = path.first
        finish = path.second
        // How far the held point should travel for a drag across the available room: all the way
        // in a spread, and in single-page mode far enough that the fold follows the finger.
        wanted = when {
            g.spread -> abs(finish.x - start.x)
            direction == TurnDirection.FORWARD -> g.width.toFloat()
            else -> g.width * 2f
        }
    }

    private companion object {
        const val TAP_TURN_MS = 520
        val TAP_EASING = CubicBezierEasing(0.35f, 0f, 0.2f, 1f)
    }
}

@Composable
fun rememberTurnController(): TurnController {
    val scope = rememberCoroutineScope()
    return remember { TurnController(scope) }
}

/** The leaf that turns for [direction], held at [originY]. */
fun turningLeaf(g: ReaderGeometry, direction: TurnDirection, originY: Float): LeafTurn {
    val spine = g.spineX
    val w = g.width.toFloat()
    val h = g.height.toFloat()
    return when {
        // A single leaf is bound at the window's left edge and always turns from the right.
        spine == null -> LeafTurn(0f, 0f, w, h, 0f, originY)
        direction == TurnDirection.FORWARD -> LeafTurn(spine, 0f, w, h, spine, originY)
        else -> LeafTurn(0f, 0f, spine, h, spine, originY)
    }
}

/**
 * Where the held point starts and finishes for a turn. Going back in single-page mode is the
 * forward turn of the previous page played in reverse: that page returns from beyond the binding.
 */
fun turnPath(g: ReaderGeometry, direction: TurnDirection, originY: Float): Pair<Offset, Offset> {
    val leaf = turningLeaf(g, direction, originY)
    val rest = Offset(leaf.originX, originY)
    val turned = Offset(leaf.endX, originY)
    return if (!g.spread && direction == TurnDirection.BACKWARD) turned to rest else rest to turned
}

/** Assembles what to draw for a turn from the sheets either side of the current one. */
fun turnFrame(g: ReaderGeometry, turn: ActiveTurn, previous: Sheet?, current: Sheet?, next: Sheet?): TurnFrame {
    val leaf = turningLeaf(g, turn.direction, turn.originY)
    val forward = turn.direction == TurnDirection.FORWARD
    return when {
        !g.spread && forward -> TurnFrame(0, leaf, turn.point.x, turn.point.y, current?.first, next?.first, null, null)
        !g.spread -> TurnFrame(0, leaf, turn.point.x, turn.point.y, previous?.first, current?.first, null, null)
        // The right leaf turns over the spine; its back carries the next spread's left page.
        forward -> TurnFrame(1, leaf, turn.point.x, turn.point.y, current?.second, next?.second, next?.first, current?.first)
        // The left leaf turns back; its back carries the previous spread's right page.
        else -> TurnFrame(0, leaf, turn.point.x, turn.point.y, current?.first, previous?.first, previous?.second, current?.second)
    }
}

/**
 * Reads page gestures: a horizontal drag turns the leaf under the finger, and a tap is reported
 * to [onTap] with its position.
 */
suspend fun PointerInputScope.pageGestures(controller: TurnController, onTap: (Offset) -> Unit) {
    val slop = viewConfiguration.touchSlop
    val flingThreshold = 500f * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var rejected = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed && !dragging) break
                if (!change.pressed) {
                    released = true
                    if (dragging) {
                        controller.release(velocity.calculateVelocity().x, flingThreshold)
                    } else if (!rejected && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        onTap(change.position)
                    }
                    break
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - down.position
                if (!dragging && !rejected) {
                    if (abs(delta.x) > slop && abs(delta.x) > abs(delta.y)) {
                        val direction = if (delta.x < 0) TurnDirection.FORWARD else TurnDirection.BACKWARD
                        dragging = controller.beginDrag(direction, down.position)
                        rejected = !dragging
                    } else if (abs(delta.y) > slop * 2) {
                        rejected = true
                    }
                }
                if (dragging) {
                    change.consume()
                    controller.dragTo(change.position)
                }
            }
        } finally {
            // A gesture taken away mid-drag must not leave the leaf hanging in the air.
            if (dragging && !released) controller.release(0f, flingThreshold)
        }
    }
}
