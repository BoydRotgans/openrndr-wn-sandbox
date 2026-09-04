package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Push
import slideshow.Slide
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames

/**
 * The looping slide: a mark going round in a fixed five seconds, for ever, whether or not
 * anyone clicks.
 *
 * `loop` is the only thing that makes it move; the phase arrives as `stage.loop` and the
 * slide draws it. The second click adds a mark half a turn behind, so a loop and clicks
 * are not exclusive.
 */
class LoopSlide : Slide() {
    override val name = "Loop"
    override val steps = 2
    override val loop = frames(5.0)
    override val background = ColorRGBa.fromHex("17255A")
    override val transition = Push()

    private val ink = ColorRGBa.fromHex("F5F3EE")

    override fun load(program: Program) = Type.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        val track = Rectangle.fromCenter(
            stage.center - Vector2(0.0, 20.0), (stage.width - 2 * MARGIN) * 0.62, 460.0
        )

        drawer.stroke = ink.opacify(0.28)
        drawer.strokeWeight = 2.0
        drawer.fill = null
        drawer.rectangle(track)

        drawer.stroke = null
        drawer.fill = ink
        drawer.rectangle(Rectangle.fromCenter(corner(track, stage.loop), 84.0))

        // a second mark, half a turn behind, on the second click
        val trailing = stage.on(1)
        if (trailing > 0.0) {
            drawer.fill = null
            drawer.stroke = ink
            drawer.strokeWeight = 8.0
            drawer.circle(corner(track, (stage.loop + 0.5) % 1.0), 46.0 * trailing)
        }

        // the phase itself, so the five seconds are visible rather than felt
        drawer.stroke = null
        drawer.fill = ink.opacify(0.3)
        drawer.rectangle(MARGIN, 880.0, stage.width - 2 * MARGIN, 4.0)
        drawer.fill = ink
        drawer.rectangle(MARGIN, 880.0, (stage.width - 2 * MARGIN) * stage.loop, 4.0)

        drawer.caption(stage, ink, 3)
    }

    /**
     * Where [t] falls on the perimeter of [rect], a quarter per corner and eased between
     * them, so the mark rests at each corner rather than sliding round evenly. Four rests
     * in five seconds is what makes the loop legible as a loop.
     */
    private fun corner(rect: Rectangle, t: Double): Vector2 {
        val corners = listOf(
            rect.corner,
            rect.corner + Vector2(rect.width, 0.0),
            rect.corner + Vector2(rect.width, rect.height),
            rect.corner + Vector2(0.0, rect.height)
        )
        val side = (t * 4.0).toInt().coerceIn(0, 3)
        val along = easeInOutCubic((t * 4.0) % 1.0)
        val from = corners[side]
        val to = corners[(side + 1) % 4]
        return from + (to - from) * along
    }
}
