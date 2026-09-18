package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * The five moves of `style-guide/principles.md`, as functions of one progress number. None of
 * them keeps state: hand them `stage.on(n)` for a thing that travels, `linear(stage.on(n))` for
 * a thing that is counted, and read a place, a size or an opacity back.
 */

/**
 * Principle 6 — arrive: take its step. Something new comes in from one unit back, travelling to
 * its place while it fades up, on one number. Never appears, never scales up out of the floor.
 */
object Arrive {
    /** Where the thing is at [t], having started [unit] away from [at] in direction [from]. */
    fun at(at: Vector2, from: Vector2, unit: Double, t: Double): Vector2 = at + from * (unit * (1.0 - t))

    /** How present it is: the fade rides the same number as the travel. */
    fun alpha(t: Double): Double = t.coerceIn(0.0, 1.0)

    /**
     * The plural form: the [i]-th of [n] things along one progress [t], [lag] of the run apart,
     * each keeping at least a fifth of the run to itself. Nine bullets at 0.15 apart made each
     * item's share negative, and every bullet then read as already there at t = 0.
     */
    fun stagger(t: Double, i: Int, n: Int, lag: Double = 0.2): Double {
        if (n <= 1) return t.coerceIn(0.0, 1.0)
        val step = lag.coerceAtMost(0.8 / (n - 1))
        val share = 1.0 - step * (n - 1)
        return ((t - i * step) / share).coerceIn(0.0, 1.0)
    }

    /** From the middle of a run outwards: how far off the middle the [i]-th of [n] is, 0 to 1. */
    fun offMiddle(i: Int, n: Int): Double = if (n <= 1) 0.0 else kotlin.math.abs(i - (n - 1) / 2.0) / ((n - 1) / 2.0)
}

/**
 * Principle 7 — grow: from the edge it belongs to. A bar grows out of its axis, a box down from
 * its top or out from its left. Nothing that means a quantity grows from its centre.
 */
object Grow {
    /** [rect] grown up from its own foot by [t]. */
    fun fromBaseline(rect: Rectangle, t: Double): Rectangle =
        Rectangle(rect.x, rect.y + rect.height * (1.0 - t), rect.width, rect.height * t)

    /** [rect] grown down from its own top by [t]. */
    fun fromTop(rect: Rectangle, t: Double): Rectangle = Rectangle(rect.x, rect.y, rect.width, rect.height * t)

    /** [rect] grown out from its own left edge by [t]. */
    fun fromLeft(rect: Rectangle, t: Double): Rectangle = Rectangle(rect.x, rect.y, rect.width * t, rect.height)
}

/**
 * Principle 9 — swap: text changes by crossing over. What leaves is gone by a third of the click,
 * what arrives is in from two thirds, so the two are never both there; a title that keeps its
 * opening words crossfades straight instead. Every value is 0..1 on the click's fraction [t].
 */
object Swap {
    /** The leaving text: 1 at the start, gone by a third. */
    fun out(t: Double): Double = (1.0 - t / THIRD).coerceIn(0.0, 1.0)

    /** The arriving text: nothing until two thirds, whole at the end. */
    fun `in`(t: Double): Double = ((t - (1.0 - THIRD)) / THIRD).coerceIn(0.0, 1.0)

    /** Straight, for two texts that share their opening words. */
    fun straight(t: Double): Double = t.coerceIn(0.0, 1.0)

    /**
     * The two states a continuous position is between, and how far: [a] and [b] a step apart
     * (or the same at rest) and [t] the fraction between them.
     */
    fun between(position: Double, steps: Int): Triple<Int, Int, Double> {
        val p = position.coerceIn(0.0, (steps - 1).coerceAtLeast(0).toDouble())
        val a = kotlin.math.floor(p).toInt()
        val b = minOf(a + 1, steps - 1)
        return Triple(a, b, p - a)
    }

    const val THIRD = 1.0 / 3.0
}

/**
 * Principle 10 — count: one at a time, evenly. Anything counted undoes the deck's ease so the
 * items come at an even rate rather than crowding into the middle of the click; instant per
 * item, never a fade.
 */
object Count {
    /** How many of [n] are in by [eased] — the deck's own number, undone. */
    fun upTo(n: Int, eased: Double): Int = (linear(eased) * n).toInt().coerceIn(0, n)

    /** Whether the [i]-th of [n] is in yet. */
    fun has(i: Int, n: Int, eased: Double): Boolean = i < upTo(n, eased)
}

/**
 * Principle 4 — a label never sits on what it names: a hairline grows from the words toward the
 * thing, on the number the words fade up on.
 */
object Leader {
    /** The line from [from] toward [to], drawn [t] of the way, in [colour]. */
    fun draw(drawer: Drawer, from: Vector2, to: Vector2, t: Double, colour: ColorRGBa, weight: Double = WEIGHT) {
        if (t <= 0.0) return
        drawer.stroke = colour
        drawer.strokeWeight = weight
        drawer.lineSegment(from, from + (to - from) * t.coerceIn(0.0, 1.0))
        drawer.stroke = null
    }

    /** Lines are 2 px and only lead or connect — visual.md. */
    const val WEIGHT = 2.0
}
