package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Stage

/**
 * The multi-click slide: a black circle on the first click, a black square on the second,
 * and the third moves on.
 *
 * Both shapes are `stage.on(n)` and nothing else. Clicking back plays them out again,
 * because the shapes are not events that happened — they are a function of where the slide
 * stands.
 */
class RevealSlide : Slide() {
    override val name = "Reveal"
    override val steps = 3
    override val background = ColorRGBa.fromHex("E4572E")

    private val ink = ColorRGBa.fromHex("101010")

    override fun load(program: Program) = Type.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        val circle = stage.on(1)
        val square = stage.on(2)

        // Sizes come off the height and the spread off the width: the height is the stable
        // dimension across the canvases a deck is shown at, so the shapes stay one size
        // while the pair opens out to suit a wider frame.
        val apart = stage.width * 0.11 * square

        drawer.fill = ink
        drawer.stroke = null
        drawer.circle(stage.center - Vector2(apart, 0.0), 160.0 * circle)
        drawer.rectangle(Rectangle.fromCenter(stage.center + Vector2(apart, 0.0), 320.0 * square))

        drawer.caption(stage, ink, 2)
    }
}
