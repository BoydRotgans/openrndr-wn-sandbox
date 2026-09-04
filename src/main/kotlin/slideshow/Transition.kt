package slideshow

import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2

/**
 * How one slide hands over to the next.
 *
 * Each slide is drawn into its own buffer, background and all, and the transition is
 * handed both images and a 0..1 across the handover. That is what lets two slides with
 * different grounds cross over cleanly: there is no moment where one background is
 * painted over the other, only two finished pictures being mixed.
 *
 * A transition belongs to the slide that is *arriving* — a slide says how it comes in,
 * not how the one before it goes out. Stepping backwards replays the same transition in
 * reverse, so going back through a deck undoes exactly what going forward did.
 */
sealed class Transition(val length: Int) {
    abstract fun compose(drawer: Drawer, from: ColorBuffer, to: ColorBuffer, t: Double, reverse: Boolean)
}

/** No handover at all: the new slide is simply there. */
data object Cut : Transition(0) {
    override fun compose(drawer: Drawer, from: ColorBuffer, to: ColorBuffer, t: Double, reverse: Boolean) =
        drawer.paint(to)
}

/** The two slides cross over in place. */
class Fade(length: Int = frames(0.5)) : Transition(length) {
    override fun compose(drawer: Drawer, from: ColorBuffer, to: ColorBuffer, t: Double, reverse: Boolean) {
        drawer.paint(from)
        drawer.paint(to, alpha = smoothstep(t))
    }
}

/**
 * The old slide is pushed off and the new one takes its place, both moving together, so
 * the deck reads as one strip travelling past the frame.
 *
 * [direction] is the way the outgoing slide travels; the default sends it off to the
 * left and brings the new one in from the right.
 */
class Push(
    length: Int = frames(0.6),
    private val direction: Vector2 = Vector2(-1.0, 0.0)
) : Transition(length) {
    override fun compose(drawer: Drawer, from: ColorBuffer, to: ColorBuffer, t: Double, reverse: Boolean) {
        val way = if (reverse) -direction else direction
        val eased = easeInOutCubic(t)
        val span = Vector2(from.width.toDouble(), from.height.toDouble())
        val travel = Vector2(way.x * span.x, way.y * span.y)
        drawer.paint(from, travel * eased)
        // one frame behind the outgoing slide, edge to edge with it
        drawer.paint(to, travel * (eased - 1.0))
    }
}

// ------------------------------------------------------------------------------ //

/**
 * Draws a slide's buffer at [offset], optionally see-through.
 *
 * The alpha goes through a shade style rather than a tint, because `drawer.image` takes
 * its colour from the texture and there is nothing else to fade.
 */
private fun Drawer.paint(buffer: ColorBuffer, offset: Vector2 = Vector2.ZERO, alpha: Double = 1.0) {
    shadeStyle = if (alpha >= 1.0) null else shadeStyle {
        fragmentTransform = "x_fill.a *= p_alpha;"
        parameter("alpha", alpha)
    }
    image(buffer, offset.x, offset.y, buffer.width.toDouble(), buffer.height.toDouble())
    shadeStyle = null
}
