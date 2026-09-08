package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Cut
import slideshow.Slide
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames

/**
 * One click, and an opener, so it has nothing to hand over from and arrives on a cut.
 *
 * A slide with one click can still move: the rule draws itself in off the slide's own
 * frame count rather than off a click, and settles by itself.
 */
class TitleSlide : Slide() {
    override val name = "Title"
    override val background = ColorRGBa.fromHex("F0EBE1")

    private val ink = ColorRGBa.fromHex("161616")

    override fun load(program: Program) = Type.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        val opening = easeInOutCubic(stage.since(0, frames(0.9)))

        drawer.fill = ink
        drawer.fontMap = Type.display
        drawer.text("SLIDESHOW", MARGIN, 520.0)

        drawer.stroke = null
        drawer.rectangle(MARGIN, 560.0, (stage.width - 2 * MARGIN) * opening, 10.0)

        drawer.caption(stage, ink, 1)
    }
}
