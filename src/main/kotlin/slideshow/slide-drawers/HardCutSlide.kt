package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.shape.Rectangle
import slideshow.Cut
import slideshow.Slide
import slideshow.Stage

/** The hard cut: no handover at all, the slide is simply there. */
class HardCutSlide : Slide() {
    override val name = "Cut"
    override val background = ColorRGBa.fromHex("F2C14E")

    private val ink = ColorRGBa.fromHex("101010")

    override fun load(program: Program) = Type.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        drawer.fill = ink
        drawer.stroke = null
        drawer.rectangle(Rectangle.fromCenter(stage.center, 420.0))

        drawer.caption(stage, ink, 5)
    }
}
