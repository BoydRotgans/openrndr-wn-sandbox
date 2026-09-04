package slideshow

import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import kotlin.math.abs

/**
 * Where a slide stands, this frame. A slide is handed one of these and draws from it —
 * it reads no clock, keeps no state and is never told that something "just happened".
 *
 * The whole of a slide's own animation hangs off one number, [position]: a continuous,
 * eased step index. At rest it sits on a whole number; a click moves it to the next one
 * over the slide's `stepFrames`. Everything a slide reveals is then a clamped window on
 * that single value, which is what [on] does:
 *
 *     position   0.0 ....... 0.5 ....... 1.0 ....... 1.6 ....... 2.0
 *     on(1)      0.0         0.5         1.0         1.0         1.0
 *     on(2)      0.0         0.0         0.0         0.6         1.0
 *
 * So `circle(centre, 150.0 * stage.on(1))` is a circle that grows in on the first click,
 * shrinks away if you click back, and needs no state to do either. This is the same idea
 * as `packBoxes` in demo01 and `stateAt` in Decision — one number in, a whole composition
 * out — and it has the same payoff: change the layout and it keeps animating for free.
 */
class Stage(
    /** The canvas, in canvas pixels. A slide lays out against this, not against a window. */
    val bounds: Rectangle,
    /** Frames since this slide came up. Use it for anything that runs on its own. */
    val frame: Int,
    /** How many clicks this slide holds. */
    val steps: Int,
    /** The click it is on, a whole number: where [position] is heading. */
    val step: Int,
    /** The eased, continuous step index. Everything else is derived from it. */
    val position: Double,
    /** 0..1 as the slide arrives, 1 once it is alone on screen. */
    val enter: Double,
    /** 0..1 as the slide leaves. 0 while it is the one being shown. */
    val exit: Double,
    /** Phase within the slide's fixed loop, 0..1. Always 0 when the slide does not loop. */
    val loop: Double,
    /** How many whole loops have run since the slide came up. */
    val cycle: Int
) {
    val width: Double get() = bounds.width
    val height: Double get() = bounds.height
    val center: Vector2 get() = bounds.center

    /**
     * How far the thing that arrives on click [step] has arrived: 0 before it, 1 after it,
     * eased across the click itself. `on(0)` is always 1 — that is the slide's opening
     * state, which is on screen before anything is clicked.
     */
    fun on(step: Int): Double = (position - step + 1.0).coerceIn(0.0, 1.0)

    /** The complement: 1 until click [step], then away. */
    fun off(step: Int): Double = 1.0 - on(step)

    /**
     * A thing that arrives on click [from] and leaves again on click [until] — a build-up
     * and build-down in one expression, and the reason those need no state either.
     */
    fun between(from: Int, until: Int): Double = on(from) * off(until)

    /** 0..1 over [length] frames starting at frame [from], for animation off the clock. */
    fun since(from: Int, length: Int): Double = ramp(frame - from, length)

    /** True when the step move has finished and the slide is holding still. */
    val settled: Boolean get() = abs(position - step) < 1e-6
}
