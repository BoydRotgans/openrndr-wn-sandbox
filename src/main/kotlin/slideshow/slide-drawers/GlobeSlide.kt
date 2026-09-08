package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.seconds
import slideshow.mix
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A dot that swells into a turning globe, gaining a word at a time: each label arrives at
 * the right, and the ones already round it close up to let it in.
 *
 * **The ring is closed from the first frame and stays closed.** It opens carrying
 * [opening] words all the way round, and every word added after that is taken in by the ring
 * drawing tighter: the pitch narrows, the type comes down with it, and the disc grows to
 * carry the extra. At no point is it an arc filling in — it is always a complete ring, only
 * finer.
 *
 * **The size and the radius are not settings; they are read off the count.** At any number of
 * words there is one radius where the ring's own spacing and the room the longest word needs
 * out to the frame ask for the same size of type, and that is the largest the type can be:
 * bigger and the frame binds, smaller and the ring does. Thirty words want a 171px disc and
 * 29px type; a hundred want 312px and 16px. Everything between follows from the same
 * expression, so the composition is never tuned — it is solved, every frame.
 *
 * **Nothing accelerates.** The pitch is what runs linearly in time, not the word count: each
 * word's angle is `spin - i * pitch` with both terms linear, so every word on the ring has a
 * constant angular velocity for the whole slide. Running the *count* linearly instead makes
 * the pitch go as 1/t, which winds the ring hard at the start and barely at the end — the
 * spin is constant either way, and what reads as it changing is the ring deforming under it.
 * A pitch linear in time also means words arrive slowly at first and quicken, which is the
 * pacing wanted, without anything being asked for it twice.
 *
 * **It builds on its own frame count, not on clicks.** Twenty-odd clicks to set out
 * twenty-odd words would be a slide nobody could talk over; this one arrives and fills while
 * you speak. `stage.frame` is what makes that repeatable — it is frames since *this slide*
 * came up, so replaying it or jumping into it starts the build from the beginning, and a
 * recorded run is identical to a watched one. (A wall clock would do none of that; see the
 * `ScreenRecorder` note in CLAUDE.md.)
 *
 * **The labels turn with the globe**, so a word is upside down for half of every turn. That
 * is the piece rather than an oversight: they are fixed to the thing that is spinning, and
 * flipping them to stay upright would mean each one snapping over as it crossed the bottom.
 *
 * The one thing a closed ring cannot avoid is that its words shift as it tightens: a closed
 * loop of thirty cannot become a closed loop of a hundred with everything standing still.
 * They flow gently towards the first word and new ones come in behind it.
 */
class GlobeSlide(
    /**
     * The words. The ring carries [fill] of them and takes them from here in turn, so a list
     * shorter than that comes round again — which is what the reference for this slide does,
     * and what keeps the ring dense without inventing copy for it.
     */
    private val labels: List<String> = emptyList(),
    /** How many are already on the ring on the first frame, closed all the way round. */
    private val opening: Int = 15,
    /** How many it ends with. */
    private val fill: Int = 50,
    private val fontPath: String = "data/fonts/default.otf",
    private val disc: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /** Seconds a word, averaged — `pace * (fill - opening)` is the whole build. */
    private val pace: Double = 0.3,
    /**
     * Seconds the globe takes to come round once. Its own number rather than the length of
     * the build: the ring goes on turning long after the last word has landed, and tying the
     * two together makes a slide that fills quickly also spin quickly, which is backwards.
     */
    private val turn: Double = 40.0,

    /**
     * The disc's radius as a fraction of the pane's height. Left null it is solved for, every
     * frame, from the words on the ring — see the note above. Stating one fixes it, and the
     * type is then whatever that radius will carry.
     */
    private val size: Double? = null,
    /** The cue as the ring comes up. Stated in `Slideshow.kt`. */
    override val sound: Sound? = null
) : Slide() {
    override val name = "Globe"

    private lateinit var face: FontImageMap

    /** The ring's own copy: [fill] words, taken from [labels] in turn and repeating. */
    private val words: List<String> =
        if (labels.isEmpty()) emptyList() else List(fill) { labels[it % labels.size] }

    override fun load(program: Program) {
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val elapsed = seconds(stage.frame)
        val count = words.size
        if (count == 0) return

        val opened = opening.coerceIn(1, count)

        // **The pitch is what runs linearly in time**, not the count. Both terms of a word's
        // angle are then linear, so every word turns at a constant rate and nothing on the
        // ring accelerates. The count follows from it — a closed ring holds exactly as many
        // words as its pitch divides the circle into — and arrives quickening, which is the
        // pacing wanted and comes free rather than being asked for twice.
        val build = pace * (count - opened).coerceAtLeast(1)
        val phase = (elapsed / build).coerceIn(0.0, 1.0)
        val pitch = mix(360.0 / opened, 360.0 / count, phase)
        val arrived = (360.0 / pitch).coerceIn(1.0, count.toDouble())

        // The radius at which the ring's own spacing and the room the longest word needs out
        // to the frame ask for the same size of type — the largest it can be. Solved for the
        // words on the ring *now*, so the disc grows as the ring takes more in.
        val reach = min(stage.width, stage.height) / 2.0 - INSET - GAP
        val measure = (words.maxOf { face.advanceOf(it) } / ATLAS).coerceAtLeast(0.01)
        val radius = size?.let { stage.height * it }
            ?: (reach / (1.0 + 2.0 * PI * TIGHT * measure / arrived))

        // ...and the type that radius carries. The two limits are equal at the solved radius,
        // so the min only bites when `size` has been stated by hand.
        val scale = min(2.0 * PI * radius * TIGHT / arrived, (reach - radius) / measure) / ATLAS

        drawer.stroke = null
        drawer.fill = disc
        drawer.circle(stage.center, radius)

        drawer.fill = ink
        drawer.fontMap = face

        val shown = floor(arrived).toInt().coerceIn(1, count)
        val spin = 360.0 * elapsed / turn

        for (i in 0 until shown) {
            drawer.isolated {
                drawer.translate(stage.center)
                // `i` places back from the first word, so the ring closes on itself: the last
                // one sits one pitch behind the first, wherever the spin has taken them.
                drawer.rotate(spin - i * pitch)
                drawer.translate(radius + GAP, 0.0)
                drawer.scale(scale)
                // the baseline sits a little below the radius so the line reads as centred
                // on it rather than hanging from it
                drawer.text(words[i], 0.0, ATLAS * BASELINE)
            }
        }
    }

    private companion object {
        /** The atlas the labels are baked at; everything is scaled down from it, never up. */
        const val ATLAS = 64.0

        /** Kept clear at the frame's edge, so the longest label is not against the side. */
        const val INSET = 40.0

        /** Between the disc's edge and the start of a label. */
        const val GAP = 16.0

        /** Of the arc between two labels, how much a line of type may take. */
        const val TIGHT = 0.80

        const val BASELINE = 0.31
    }
}
