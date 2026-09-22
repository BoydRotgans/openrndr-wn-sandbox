package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Sound
import slideshow.Stage
import slideshow.linear
import slideshow.smoothstep

/**
 * The intro wall: the speaker's name standing in the left projector, the evening's programme
 * in the right, and at the end the guest the speaker hands over to, named in the right projector
 * level with him.
 *
 * **Three drawers, one wall.** The host's tag is [NameTag] drawn settled, so the cut in from the
 * name tag on its own is invisible — the same block in the same place. The programme is
 * [Programme] with its list alone in the right pane, handed the stage with its position held at
 * its own last state, so its clicks, dims and build are its own. The last click is this wall's:
 * the programme gives way over the first half of it, and the guest's [NameTag] builds over the
 * second on the click's own linear time — a count of the tag's seconds rather than an ease, so
 * the name, the rule and the title arrive in the order and the rhythm the host's did.
 *
 * The programme is hidden by a sheet of the ground laid over the right pane, which is why the
 * ground has to be the wall's plain black: the wall's concrete is laid over the finished frame
 * afterwards, so black here is the same stone as everywhere else.
 */
class IntroWall(
    private val host: NameTag,
    private val programme: Programme,
    private val guest: NameTag?,
    override val sound: Sound? = null
) : Backdrop() {

    override val name = "Intro"
    override val background: ColorRGBa = ColorRGBa.BLACK
    override val stepFrames get() = programme.stepFrames
    override val steps get() = programme.steps + if (guest != null) 1 else 0
    override val settle get() = programme.settle

    /** The click the guest arrives on. */
    private val guestStep get() = programme.steps

    override fun stepName(step: Int): String? =
        if (guest != null && step >= guestStep) "the guest" else programme.stepName(step)

    override fun load(program: Program) {
        host.load(program)
        programme.load(program)
        guest?.load(program)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        host.tag(drawer, stage, NameTag.BUILT)

        val last = programme.steps - 1
        val held = Stage(
            stage.bounds, stage.frame, programme.steps, minOf(stage.step, last),
            stage.position.coerceAtMost(last.toDouble()), stage.enter, stage.exit, stage.loop, stage.cycle
        )
        val handing = if (guest == null) 0.0 else linear(stage.on(guestStep))
        if (handing < 0.5) {
            programme.draw(drawer, held)
            val gone = smoothstep(handing * 2.0)
            if (gone > 0.0) {
                drawer.stroke = null
                drawer.fill = background.opacify(gone)
                drawer.rectangle(Rectangle(stage.bounds.x + stage.width / 2.0, stage.bounds.y, stage.width / 2.0, stage.height))
            }
        }
        if (guest != null && handing > 0.5) guest.tag(drawer, stage, (handing - 0.5) * 2.0 * NameTag.BUILT)
    }
}
