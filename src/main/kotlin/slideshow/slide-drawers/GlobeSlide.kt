// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet, in the default
//  package, which a named package cannot import from. The folder is
//  slide-drawers because that is where a slide's drawing lives.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Arrival
import slideshow.Slide
import slideshow.pitchStep
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.frames
import slideshow.seconds
import slideshow.mix
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
 *
 * **The disc is a globe drawn in catalogue pieces.** Given a [sheet] it is not a flat circle
 * but the [Crowd] slide's globe with its people replaced by components: the disc is read as a
 * sphere tilted the way a school globe is, a field of pieces stands on it, and a piece with a
 * line of the grid running through it takes the [grid] colour while the rest stay [piece] —
 * the familiar meridians and parallels, drawn in things rather than in lines. The parallels
 * never move and the meridians travel east as the globe turns, so a wave of colour goes round
 * at the rate the ring spins. Nothing moves but the marking: the pieces stand where they are,
 * which is what makes it read as a globe turning under them rather than as a field sliding.
 *
 * Each cell samples its own centre and four points around it; a cell whose samples fall in two
 * longitude sectors has a meridian through it, and one whose samples fall in two latitude bands
 * has a parallel. The rim counts as a line, as it does on any drawing of a globe. All of that is
 * [Crowd]'s, kept in step with it by hand rather than shared, because the two want different
 * things on the cell — a figure there, a fitted silhouette here.
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
    /**
     * The catalogue sheet the globe is made of. Null leaves the plain disc, which is what this
     * slide drew before the globe was built out of pieces.
     */
    private val sheet: File? = null,
    /** The flat disc, where there is no sheet. */
    private val disc: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    /** A piece standing on the globe, and one with a line of the grid running through it. */
    private val piece: ColorRGBa = ColorRGBa.WHITE,
    private val grid: ColorRGBa = ColorRGBa.fromHex("2E5BFF"),
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

    /** The whole build, so a written run holds until the ring is full. */
    override val settle: Int get() = frames(pace * (fill - opening).coerceAtLeast(1))

    /**
     * A note a word: the ring opening, and then every word it takes in after that.
     *
     * **It is the draw's own expression read backwards.** `draw` runs the *pitch* linearly in
     * time and reads the word count off it — `arrived = 360 / pitch` — so the frame the ring
     * takes its n-th word in is the frame the pitch has narrowed to `360/n`. Inverting that is
     * exact, and it is the only way to get this right: the words do **not** arrive evenly. A
     * pitch linear in time delivers them slowly at first and quickening, which is the whole
     * pacing of the slide, and a note every `pace` seconds would say the opposite.
     *
     * A word is held until the next one lands, so the run tiles the build rather than ticking
     * through it — what a lane says is which word is the newest.
     */
    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val count = words.size
        val opened = opening.coerceIn(1, count)
        if (count <= opened) return super.arrivals(clicks)
        val build = pace * (count - opened)
        val wide = 360.0 / opened      // the pitch the ring opens at
        val tight = 360.0 / count      // and the one it closes at
        val starts = listOf(0) +
                (opened + 1..count).map { n -> frames(build * ((360.0 / n - wide) / (tight - wide))) }
        return starts.mapIndexed { i, at ->
            val next = starts.getOrNull(i + 1) ?: (at + frames(pace))
            Arrival(lane = 0, index = pitchStep(i, starts.size), start = at, length = (next - at).coerceAtLeast(1))
        }
    }

    private lateinit var face: FontImageMap

    /** The catalogue the globe is made of; empty leaves the plain disc. */
    private var pieces: List<SheetObject> = emptyList()

    /** The ring's own copy: [fill] words, taken from [labels] in turn and repeating. */
    private val words: List<String> =
        if (labels.isEmpty()) emptyList() else List(fill) { labels[it % labels.size] }

    override fun load(program: Program) {
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
        pieces = sheet?.takeIf { it.isFile }?.let { runCatching { loadObjectSheet(it) }.getOrNull() }.orEmpty()
        if (sheet != null && pieces.isEmpty()) println("globe: no pieces off ${sheet.path} — drawing the plain disc")
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

        val shown = floor(arrived).toInt().coerceIn(1, count)
        val spin = 360.0 * elapsed / turn

        drawer.stroke = null
        if (pieces.isEmpty()) {
            drawer.fill = disc
            drawer.circle(stage.center, radius)
        } else {
            globe(drawer, stage.center, radius, 2.0 * PI * elapsed / turn)
        }

        drawer.fill = ink
        drawer.fontMap = face

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

    /**
     * The globe: its meridians and parallels drawn as strings of catalogue pieces, a piece a
     * step along each line. [turned] is how far it has come round, in radians — the meridians
     * travel with it and the parallels do not, which is what makes it read as turning.
     *
     * **The lines are the drawing, not a marking on a field.** The first version was Crowd's
     * exactly — every cell of the disc carrying a piece, the ones a line passed through in the
     * grid colour — and it does not survive the change of subject: Crowd's figures are one
     * narrow silhouette repeated, so a coloured run of them reads as a line, where a hundred
     * different components at a hundred different widths read as speckle whatever they are
     * coloured. Drawing only the lines says the same thing about the same shape, and leaves the
     * disc small enough that the ring of words around it is still set at label size.
     *
     * Only the near half is drawn, which is what makes it a globe rather than a wireframe, and
     * a piece shrinks a little toward the rim so the sphere turns away from the viewer.
     */
    private fun globe(drawer: Drawer, middle: Vector2, radius: Double, turned: Double) {
        val tilt = TILT * PI / 180.0
        val unit = radius * PIECE

        /** A latitude and longitude on the tilted globe as a point on the pane, with its depth. */
        fun project(lat: Double, lon: Double): Triple<Vector2, Double, Double> {
            val x = cos(lat) * sin(lon)
            val by = sin(lat)
            val bz = cos(lat) * cos(lon)
            // undo Crowd's tilt, which leans the north pole toward the viewer
            val y = by * cos(tilt) - bz * sin(tilt)
            val z = by * sin(tilt) + bz * cos(tilt)
            return Triple(Vector2(middle.x + x * radius, middle.y - y * radius), z, 0.0)
        }

        /** One line of the grid, laid with a piece every [unit] or so of pane. */
        fun line(points: List<Pair<Vector2, Double>>, tint: ColorRGBa, seed: Int) {
            drawer.fill = tint
            var last: Vector2? = null
            var n = 0
            for ((at, depth) in points) {
                if (depth <= 0.0) { last = null; continue }          // the far side of the globe
                if (last != null && (at - last!!).length < unit * SPACING) continue
                last = at
                val shape = pieces[((seed * 7 + n * 13) % pieces.size + pieces.size) % pieces.size]
                n++
                // Smaller toward the rim, so the sphere turns away rather than reading as a disc.
                val k = unit * (1.0 - RELIEF + RELIEF * depth)
                val fit = min(k / shape.bounds.height, k * WIDEST / shape.bounds.width)
                drawer.isolated {
                    drawer.translate(at)
                    drawer.scale(fit, fit)
                    drawer.translate(-shape.bounds.center)
                    drawer.shapes(shape.shapes)
                }
            }
        }

        val step = STEP * PI / 180.0
        // The meridians, travelling east as the globe turns.
        val meridians = (360.0 / LON_STEP).toInt()
        for (m in 0 until meridians) {
            val lon = m * LON_STEP * PI / 180.0 + turned
            val points = (0..(180.0 / STEP).toInt()).map { i ->
                val lat = -PI / 2.0 + i * step
                project(lat, lon).let { it.first to it.second }
            }
            line(points, piece, m)
        }
        // The parallels, which stand still.
        val bands = (90.0 / LAT_STEP).toInt()
        for (b in -bands..bands) {
            val lat = b * LAT_STEP * PI / 180.0
            if (kotlin.math.abs(lat) > PI / 2.0 - 1e-6) continue
            val points = (0..(360.0 / STEP).toInt()).map { i ->
                val lon = i * step + turned
                project(lat, lon).let { it.first to it.second }
            }
            line(points, grid, 100 + b)
        }
    }

    private companion object {
        /** The atlas the labels are baked at; everything is scaled down from it, never up. */
        const val ATLAS = 64.0

        /** The globe grid, as Crowd draws it: degrees between meridians and parallels, and the tilt. */
        const val LON_STEP = 30.0
        const val LAT_STEP = 30.0
        const val TILT = 23.0

        /** Degrees between the points a line is sampled at, before they are thinned by [SPACING]. */
        const val STEP = 2.0

        /** How tall a piece stands, as a share of the disc's radius, and the most it may be wide. */
        const val PIECE = 0.13
        const val WIDEST = 1.4

        /** The least a piece stands from the last one on its line, in piece heights. */
        const val SPACING = 0.95

        /** How much smaller a piece is at the rim than at the middle: the sphere's relief. */
        const val RELIEF = 0.35

        /** Kept clear at the frame's edge, so the longest label is not against the side. */
        const val INSET = 40.0

        /** Between the disc's edge and the start of a label. */
        const val GAP = 16.0

        /** Of the arc between two labels, how much a line of type may take. */
        const val TIGHT = 0.80

        const val BASELINE = 0.31
    }
}
