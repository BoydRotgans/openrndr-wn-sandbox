package slideshow

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer

/**
 * One slide. Subclass it, override what differs from the defaults, and draw.
 *
 * The defaults are the minimal case on purpose — a slide that is one click, on white,
 * fading in, is nothing but a `draw`:
 *
 *     class Cover : Slide() {
 *         override val background = ColorRGBa.fromHex("F1EDE4")
 *         override fun draw(drawer: Drawer, stage: Stage) {
 *             drawer.fill = ColorRGBa.BLACK
 *             drawer.circle(stage.center, 200.0)
 *         }
 *     }
 *
 * Anything a sketch already does fits here: whatever `draw` would have been in an
 * `extend { }` goes in [draw], and whatever it loaded at startup — an svg, a mesh, a
 * font, a render target — goes in [load], which runs once for every slide in the deck
 * before the first frame. A show must not hitch on a click, so nothing loads late.
 */
abstract class Slide {

    /** Shown in the debug overlay and matched by `SLIDES_START`. */
    open val name: String get() = this::class.simpleName ?: "slide"

    /** How many clicks this slide holds. 1 is a slide you click straight past. */
    open val steps: Int get() = 1

    /** Frames one click takes to play. */
    open val stepFrames: Int get() = frames(0.45)

    /** Frames in one turn of the slide's loop, or 0 for a slide that holds still. */
    open val loop: Int get() = 0

    /** Cleared to this before [draw]. */
    open val background: ColorRGBa get() = ColorRGBa.WHITE

    /** How this slide arrives. [Cut] is the hard cut; the default eases the two together. */
    open val transition: Transition get() = Fade()

    /**
     * What click [step] does, for the debug overlay — "build down", "reveal the plan",
     * whatever it is for. The overlay times the click but cannot tell one that reveals
     * from one that takes away, because that is inside [draw]; this is how a slide says.
     * Left null it reads "reveal" going forward and "build down" going back.
     */
    open fun stepName(step: Int): String? = null

    /** Runs once at startup, for everything that would otherwise load on the click. */
    open fun load(program: Program) {}

    /** One frame of the slide. Read [stage], draw, keep nothing. */
    abstract fun draw(drawer: Drawer, stage: Stage)
}
