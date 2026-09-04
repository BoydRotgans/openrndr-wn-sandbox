package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Slide
import slideshow.Stage
import slideshow.frames
import slideshow.mix

/**
 * Build up and build down. Three bars come in one at a time and two of them go out again
 * on the last click, leaving the one the slide was about.
 *
 * That is `stage.between(from, until)`: a thing that is here from one click and gone by
 * another. There is no "remove" anywhere — the bars that leave are the same expression as
 * the bars that arrive, read the other way round.
 */
class BuildSlide : Slide() {
    override val name = "Build"
    override val steps = 5

    // Slower than the deck's default: the last click takes two bars away and widens the
    // third, and a build down that leaves at reveal speed reads as things going missing.
    override val stepFrames = frames(0.7)
    override val background = ColorRGBa.fromHex("2D3142")

    // The last click is the one that takes two bars away. The overlay times every click,
    // but it cannot tell a click that reveals from one that removes — that is inside
    // draw — so the slide says which is which.
    override fun stepName(step: Int) = if (step == 4) "build down" else "reveal"

    private val ink = ColorRGBa.fromHex("F5F3EE")

    override fun load(program: Program) = Type.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        val kept = stage.on(3)
        val conclusion = stage.on(4)

        val full = stage.width - 2 * MARGIN
        val short = full * 0.44

        drawer.stroke = null
        drawer.fill = ink.opacify(0.55)
        bar(drawer, 360.0, short * stage.between(1, 4))
        bar(drawer, 520.0, short * stage.between(2, 4))

        // the one that stays, and then takes the width the others left
        drawer.fill = ink
        bar(drawer, 680.0, mix(short, full, conclusion) * kept)

        drawer.caption(stage, ink, 4)
    }

    private fun bar(drawer: Drawer, y: Double, width: Double) {
        if (width > 1.0) drawer.rectangle(MARGIN, y, width, 60.0)
    }
}
