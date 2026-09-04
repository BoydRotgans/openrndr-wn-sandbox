package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import slideshow.Slide
import slideshow.Stage
import slideshow.seconds
import slideshow.smoothstep
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A dot that swells into a turning globe, gaining a word at a time: each label arrives at
 * the right, and the ones already round it close up to let it in.
 *
 * **The ring is rigid and turns as one body.** Every word sits at a fixed angle of its own —
 * `i` places back from the first — and the whole ring is rotated by a spin that is linear in
 * time, so everything on it moves at exactly one rate for the whole slide. Words are added at
 * the trailing end of the arc, which grows a word at a time until it closes.
 *
 * That is worth stating because the obvious arrangement is not rigid, and looks it. Anchoring
 * the *newest* word at a fixed point on screen and counting the others back from it makes
 * every angle a function of how many have arrived — so the arrival rate leaks into the
 * rotation, and with arrivals that quicken the ring visibly unwinds early and settles late.
 * The spin was constant the whole time; what was moving was the ring deforming under it.
 *
 * **The type is one size and never changes**, and the pitch between two words is the angle
 * one line of that size takes at the full radius. Those two being constants is what leaves
 * the rotation as the only thing moving.
 *
 * **The globe swells from a dot into that ring** over [swell] — an entrance rather than a
 * growth that runs through the piece, and it has to be. A globe that goes on growing has to
 * go on packing its words tighter to keep them shoulder to shoulder, and packing them tighter
 * is a deformation: the ring stops being rigid and the rotation stops being one motion. The
 * swell is over before the second word lands, so there is nothing on the ring to deform.
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
 * **The one size is worked out from the finished globe**, off two limits that meet there:
 * the ring's own spacing at full radius, and the room the longest word in the list needs
 * between that radius and the frame. `size` is at the value where the two are equal, which
 * is where the type is as large as it can be — smaller and the ring binds, larger and the
 * frame does.
 */
class GlobeSlide(
    /**
     * The words. The ring carries [fill] of them and takes them from here in turn, so a list
     * shorter than that comes round again — which is what the reference for this slide does,
     * and what keeps the ring dense without inventing copy for it.
     */
    private val labels: List<String> = emptyList(),
    /** How many are already on the ring on the first frame. */
    private val opening: Int = 30,
    /** How many it ends with. */
    private val fill: Int = 100,
    private val fontPath: String = "data/fonts/default.otf",
    private val disc: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /** Seconds a word, averaged — `pace * (fill - opening)` is the whole build. */
    private val pace: Double = 0.3,
    /**
     * How the arrivals are paced across that time. Below 1 they start slow and quicken,
     * which is what gives the early words room to be read before the ring closes on them;
     * 1 is an even rate throughout.
     */
    private val ramp: Double = 0.7,

    /**
     * The disc's radius as a fraction of the pane's height. Left null it is *worked out*:
     * the radius at which the ring's own spacing and the room the longest word needs out to
     * the frame give the same size of type, which is the largest the type can be. Bigger and
     * the frame binds, smaller and the ring does. Stating one overrides that.
     */
    private val size: Double? = null,
    /**
     * The dot it opens on, as a fraction of the full disc. 1.0 opens at full size, which is
     * what a ring that already carries [opening] words has to do — they are at the finished
     * pitch from the first frame, and a smaller disc cannot hold them apart.
     */
    private val from: Double = 1.0,
    /**
     * Seconds the dot takes to swell into the globe. Kept shorter than the wait for the
     * second word: while it is growing the pitch is still tightening, and that is only
     * harmless while there is nothing on the ring to tighten.
     */
    private val swell: Double = 2.2
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

        // The largest the type can be is a radius, not a number: the ring's spacing gives a
        // size that grows with the disc, the room out to the frame gives one that shrinks
        // with it, and where the two cross is the answer. Worked out rather than stated, so
        // a hundred words compose as readily as forty — the disc simply comes out bigger.
        val reach = min(stage.width, stage.height) / 2.0 - INSET - GAP
        val measure = (words.maxOfOrNull { face.advanceOf(it) } ?: 1.0).coerceAtLeast(1.0) / ATLAS
        val fitted = if (count == 0) reach / 2.0
                     else reach / (1.0 + 2.0 * PI * TIGHT * measure / count)
        val full = size?.let { stage.height * it } ?: fitted

        // Swells only if it opens on fewer words than the ring can hold apart; a ring that
        // starts loaded is at full size from the first frame.
        val radius = full * (from + (1.0 - from) * smoothstep(elapsed / swell))

        drawer.stroke = null
        drawer.fill = disc
        drawer.circle(stage.center, radius)
        if (count == 0) return

        // How many are on the ring: the ones it opened with, and the rest arriving slowly at
        // first and quickening.
        val added = (count - opening).coerceAtLeast(0)
        val build = pace * added.coerceAtLeast(1)
        val arrived = (opening + added * (elapsed / build).coerceAtLeast(0.0).pow(1.0 / ramp))
            .coerceIn(0.0, count.toDouble())
        val shown = floor(arrived).toInt().coerceIn(0, count)
        if (shown < 1) return

        // One size for the whole slide, off the finished globe and the whole ring, so it is a
        // constant: nothing here depends on the frame it is read on, which is what makes the
        // type never move.
        val room = reach - full
        val scale = min(2.0 * PI * full * TIGHT / count / ATLAS, room / (measure * ATLAS))

        // The pitch is the type, not a division of the circle: exactly the angle one line of
        // this size takes at this radius, so a word always sits against its neighbour. It is
        // floored at the finished ring's own spacing, which is what closes the circle exactly
        // on the last word rather than a degree or two either side of it.
        val pitch = max(scale * ATLAS / (radius * TIGHT), 2.0 * PI / count) * 180.0 / PI

        // The only thing that moves: one turn over the build, linear in time, and it goes on
        // at that rate once the ring is full.
        val spin = 360.0 * elapsed / build

        drawer.fill = ink
        drawer.fontMap = face

        for (i in 0 until shown) {
            drawer.isolated {
                drawer.translate(stage.center)
                // `i` places back from the first word, not forward from the newest: a fixed
                // angle on a ring that the spin turns as one body.
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
