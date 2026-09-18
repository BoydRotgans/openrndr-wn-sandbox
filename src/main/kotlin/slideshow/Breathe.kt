package slideshow

import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Principle 11 — breathe: the move for things that stand for minutes.
 *
 * A chapter card is up for seven minutes and a course wall for half an hour; neither can loop
 * visibly and neither can hold still. So they breathe: a very slow, bounded drift made of two
 * waves whose rates are a golden ratio apart, so it never lands back where it started and never
 * drifts away either. A pure function of the frame, like everything else here.
 *
 * [at] is 0 at frame 0 and rises first, so a thing that starts breathing at the end of a reveal
 * joins it without a step. [wander] is a point crossing a box on the same two waves, for a lamp
 * or a light that should move at the scale of the wall.
 *
 * It is deliberately at the edge of noticing at its default depth: someone watching sees nothing
 * move; someone glancing back after a minute finds the wall different. Where stillness serves the
 * speaker better, the depth is 0.
 */
class Breathe(
    /** Seconds of the slower wave; the faster is that over the golden ratio. */
    private val period: Double = 45.0
) {
    private val frames = frames(period).coerceAtLeast(1)

    /** 0..1, starting at 0 and never repeating: the slow wave shaped by the faster one. */
    fun at(frame: Int): Double {
        val slow = 0.5 - 0.5 * cos(2.0 * PI * frame / frames)
        val fast = 0.75 + 0.25 * sin(2.0 * PI * frame / (frames * GOLDEN))
        return (slow * fast).coerceIn(0.0, 1.0)
    }

    /** A point wandering [box]'s middle, [reach] of its size either way, never retracing. */
    fun wander(frame: Int, width: Double, height: Double, reach: Vector2 = Vector2(0.38, 0.28)): Vector2 {
        val turn = frame.toDouble() / frames
        return Vector2(
            width * (0.5 + reach.x * sin(2.0 * PI * turn)),
            height * (0.5 + reach.y * sin(2.0 * PI * turn / GOLDEN + 1.0))
        )
    }

    companion object {
        const val GOLDEN = 1.6180339887
    }
}
