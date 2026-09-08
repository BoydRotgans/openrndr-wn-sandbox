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

    /**
     * What this slide sounds like when it arrives, or null for a silent one.
     *
     * It sits here beside [transition] and [background] because it is the same kind of
     * thing — what the slide *is*, declared once and read by the driver — rather than
     * anything [draw] does. A slide never plays its own sound, for the reason it never
     * reads a clock: the deck can be clicked backwards, jumped into and filmed, and only
     * the driver knows which of those is happening.
     *
     * Stated in `Slideshow.kt` at the slide it belongs to. See [Sound].
     */
    open val sound: Sound? get() = null

    /**
     * The cue click [step] makes as it lands, or null for a silent click.
     *
     * The counterpart to [sound] for a slide that is *built* rather than simply arrived at:
     * [sound] is what the slide says on coming up, this is what each click says after that.
     * The stack is the case it exists for — a band a click, each one landing.
     *
     * The tree is the other case, and a different one: it opens on the city's own last frame
     * and holds there, so its cue belongs to the click that fans the labels out rather than
     * to an arrival nobody can see.
     *
     * Only ever fired going **forward**, the same rule the chapter cards follow. Clicking
     * back through a build is a correction, and re-firing the marks on the way would say
     * something is being built when it is being taken apart.
     *
     * Defaults to reading [stepCues], which is what a slide usually wants; override the
     * function itself where the cue has to be worked out rather than listed.
     */
    open fun stepSound(step: Int): Sound? = stepCues.getOrNull(step - 1)

    /**
     * A cue a click, **from click 1 on** — click 0 is the slide arriving, and that is [sound].
     *
     * Short of the step count it simply runs out, so a slide can gain a click without a cue
     * having to be found for it. It is the list behind [stepSound]; a slide whose cue depends
     * on more than the step number overrides that instead.
     */
    open val stepCues: List<Sound> get() = emptyList()

    /**
     * How this slide arrives, and by default it does not — it is simply there.
     *
     * The default was a [Fade] once and every slide in the deck has since been asked to stop
     * doing it, one at a time, which is the deck saying what it wants: a talk moves between
     * subjects and a dissolve reads as one picture becoming another. A slide that *should*
     * hand over — a [Push], or a fade between two states of the same thing — says so.
     */
    open val transition: Transition get() = Cut

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
