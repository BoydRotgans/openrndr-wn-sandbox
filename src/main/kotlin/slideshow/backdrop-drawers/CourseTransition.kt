package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont

import org.openrndr.draw.renderTarget
import org.openrndr.extra.color.colormatrix.tint
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.FPS
import slideshow.Layer
import slideshow.MosaicCells
import slideshow.Scale
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.ChapterOpening
import slideshow.drawers.advanceOf
import slideshow.frames
import slideshow.level
import slideshow.seconds
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The dinner handing the room back to the talk: the wall that closes a course and builds, over the
 * length of one sample, into the first frame of the chapter that follows.
 *
 * It stands last in a moment of the evening, and the click onto it is the operator saying the course is
 * over. From that frame [sample] plays and **the course goes out softly, picture and music together**:
 * the moment's playlist is let go over [fade] seconds and the course wall fades to dark over the same
 * seconds. **Only once the wall is almost dark** — under [dark] of its brightness — does the chapter
 * opening's field of blocks begin to rise, a cell at a time, on the dark ground. The last block lands
 * [rest] seconds before the sample ends, and the finished wall stands through the rest of it exactly as
 * [opening] draws its first frame — the click into the chapter is a cut nobody can see, and the title
 * reveal starts from there.
 *
 * **It is timed to the sample, not to a stated length.** The whole transition is the file's own
 * duration, read off its header, and the build follows the sound: [follow] of it is the file's
 * cumulative loudness from the moment the build begins, measured at load, so the blocks come fastest as
 * the sample swells and settle as it dies away. The rest is an even pace to the end, so something is
 * always moving. Another sample changes the timing with nothing restated.
 *
 * **The build closes in on the title.** Cells rise farthest from the title's middle first, with
 * [scatter] of a seeded shuffle, so the wave comes in from the far edge of the quote's projector and
 * the edges of the title's and ends where the title is about to come up. The opening then sinks those
 * last blocks first: the motion reverses into the reveal rather than stopping.
 *
 * **The course wall goes on moving as it fades.** The deck says which wall it left and how long that
 * stood ([cameFrom]); the wall is drawn at its own frame count carried on, so it does not jump, and
 * once the build begins only in the cells no block has taken yet — by then too dark to see, so the
 * last trace of it goes with no edge. The fade is eased and in light, so it reads as even to the eye
 * rather than holding bright and dropping at the end. With no wall before it — opened on, or
 * previewed — it opens dark. Stepped back into from the chapter, it lands built, as every slide does
 * going back, and says nothing.
 *
 * **A countdown in the bottom left** says how much of the transition is left, for the speaker to be
 * ready on the click. It is drawn into the wall, so a film carries it; [countdown] false leaves it off.
 * **It stands on an opaque plate of its own, full strength from the click**: drawn straight over the
 * course wall, white type only became readable as the wall went dark, which on a light wall is most
 * of the fade and read as the countdown fading in.
 */
class CourseTransition(
    /** The chapter opening this builds up to: its first frame is this wall's last. */
    private val opening: ChapterOpening,
    /** The sample the transition is timed to and plays. */
    private val sample: File,
    /** The face the countdown is set in. */
    private val fontPath: String,
    /** Seconds over which the moment's playlist is let go and the course wall fades to dark, from the click. */
    private val fade: Double = 10.0,
    /** How bright the course wall may still be, as seen, 0 to 1, when the blocks begin to rise. */
    private val dark: Double = 0.12,
    /** Seconds each block takes to rise. */
    private val rise: Double = 1.4,
    /** Seconds the finished wall stands before the sample ends: the build is done this long before 0:00. */
    private val rest: Double = 10.0,
    /** How much of the build follows the sample's loudness rather than an even pace, 0 to 1. */
    private val follow: Double = 0.6,
    /** How much of a seeded shuffle is blended into the outside-in order, 0 to 1. */
    private val scatter: Double = 0.3,
    /** Whether to show the countdown. */
    private val countdown: Boolean = true,
    /** The gain the sample plays at, over the file's own level. */
    private val gain: Double = 1.0,
    private val seed: Int = 7
) : Backdrop() {

    override val name get() = "Course transition"
    override val background: ColorRGBa get() = ColorRGBa.BLACK

    /** The whole transition, in seconds: the sample's own length. */
    val duration: Double = runCatching {
        AudioSystem.getAudioFileFormat(sample).let { it.frameLength.toDouble() / it.format.frameRate }
    }.getOrElse {
        println("course transition: ${sample.path} will not open — timed to 33 s and silent")
        33.0
    }

    override val settle: Int get() = frames(duration)
    /** A beat after the countdown reaches nothing, and a hands-off run clicks into the chapter. */
    override val holdAfterSettle: Double get() = 0.5
    override val leadIn = Sound(sample, gain = gain, fadeOut = frames(2.0), layer = Layer.MUSIC)
    override val momentMusic get() = false
    override val outgoingFade get() = frames(fade)
    override val carriesOn get() = true
    /** It goes on from the course wall as that stood, and builds into the chapter itself. */
    override val buildsIn get() = false

    // --- what the deck said last: the wall before, and whether this was stepped back into ---------- //

    private var under: Slide? = null
    private var underFrom = 0
    private var underStep = 0
    private var built = false

    override fun cameFrom(previous: Slide, stood: Int, leftOn: Int, opensOn: Int) {
        // Back from the chapter it leads into: it lands built. From anything else it builds, over the
        // wall it came from when that is a wall.
        built = previous === opening
        if (built) return
        under = previous.takeIf { it.wide && !it.carriesCard && it !is CourseTransition }
        underFrom = stood
        underStep = leftOn
    }

    // --- the schedule: when each block starts to rise ------------------------------------------------ //

    /** The sample's cumulative loudness a frame at a time, 0 to 1, and the first frame that is heard. */
    private var loudness = DoubleArray(0)
    private var heard = 0.0

    private var cells: List<Rectangle> = emptyList()
    private var starts = DoubleArray(0)
    private var wall = Rectangle(0.0, 0.0, 1.0, 1.0)

    private var font: FontImageMap? = null
    private var target: RenderTarget? = null

    override fun load(program: Program) {
        if (countdown) font = runCatching { program.loadFont(fontPath, Scale.title(1080.0), contentScale = 1.0) }.getOrNull()
        runCatching { measure() }.onFailure { println("course transition: could not read ${sample.name} (${it.message}) — an even build") }
    }

    /** The sample's loudness, a frame at a time: RMS over each 1/FPS second, summed and normalised. */
    private fun measure() {
        val lv = level(sample, levelled = false)
        val window = (lv.rate / FPS) * lv.channels
        val rms = mutableListOf<Double>()
        var square = 0.0
        var n = 0
        lv.each { v ->
            square += v.toDouble() * v
            if (++n == window) { rms += sqrt(square / n); square = 0.0; n = 0 }
        }
        val threshold = 10.0.pow(-60.0 / 20.0)
        heard = seconds(rms.indexOfFirst { it > threshold }.coerceAtLeast(0))
        var sum = 0.0
        val running = DoubleArray(rms.size) { sum += rms[it]; sum }
        loudness = if (sum > 0.0) DoubleArray(running.size) { running[it] / sum } else DoubleArray(0)
    }

    /** How bright the course wall looks at [t], 1 to 0: eased out over [fade]. */
    private fun seen(t: Double): Double {
        val x = (t / fade).coerceIn(0.0, 1.0)
        return 1.0 - x * x * x * (x * (6.0 * x - 15.0) + 10.0)
    }

    /** When the wall has gone dark enough for the blocks to begin: the first frame it looks under [dark]. */
    private val buildFrom: Double by lazy {
        (0..frames(fade)).map { seconds(it) }.firstOrNull { seen(it) <= dark } ?: fade
    }

    /** How far the build has got at [t] seconds, 0 to 1: the sample's loudness from [buildFrom] blended with an even pace. */
    private fun progress(t: Double): Double {
        val end = duration - rise - rest
        if (t <= buildFrom) return 0.0
        if (t >= end) return 1.0
        val even = ((t - buildFrom) / (end - buildFrom).coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
        if (loudness.isEmpty()) return even
        fun at(s: Double) = loudness[frames(s).coerceIn(0, loudness.lastIndex)]
        // Measured against the loudness the build's own window holds, not the whole file's: with the
        // build done [rest] seconds early, the part of the swell still to come after it would otherwise
        // be owed at [end] and the last blocks would all start on that one frame.
        val from = at(buildFrom)
        val to = at(end)
        val loud = if (to <= from) even else ((at(t) - from) / (to - from)).coerceIn(0.0, 1.0)
        return (follow * loud + (1.0 - follow) * even).coerceIn(0.0, 1.0)
    }

    /**
     * Each element's turn, once the opening can say what its elements are: ranked farthest from the
     * title's middle first, with [scatter] of a shuffle, and started when the build reaches its rank.
     */
    private fun schedule(): Boolean {
        if (cells.isNotEmpty()) return true
        val elements = opening.elements() ?: return false
        val far = elements.boxes.maxOf { it.center.distanceTo(elements.middle) }.coerceAtLeast(1.0)
        val random = Random(seed)
        val order = elements.boxes.indices.sortedBy {
            (1.0 - scatter) * (1.0 - elements.boxes[it].center.distanceTo(elements.middle) / far) + scatter * random.nextDouble()
        }
        val rank = DoubleArray(order.size).also { r -> order.forEachIndexed { k, i -> r[i] = (k + 1.0) / order.size } }
        val track = (0..frames(duration)).map { progress(seconds(it)) }
        starts = DoubleArray(order.size) { i -> seconds(track.indexOfFirst { it >= rank[i] }.let { if (it < 0) track.lastIndex else it }) }
        cells = elements.boxes
        wall = Rectangle(0.0, 0.0, elements.width, elements.height)
        return true
    }

    /** How far block [i] has risen at [t]: out of the floor and easing to a stop. */
    private fun risen(i: Int, t: Double): Double {
        val r = ((t - starts[i]) / rise).coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - r) * (1.0 - r) * (1.0 - r)
    }

    // --- the picture ---------------------------------------------------------------------------------- //

    override fun draw(drawer: Drawer, stage: Stage) {
        val t = if (built) duration else seconds(stage.frame).coerceAtMost(duration)
        if (!schedule()) {
            // No field to build — the face cannot be read here. Show the wall it came from, if any.
            under?.let { drawUnder(drawer, stage, 1.0, listOf(stage.bounds)) }
            if (countdown && !built) drawCountdown(drawer, stage, duration - t)
            return
        }
        // The course wall fading, the whole of it, until the build begins; the canvas is linear light,
        // so what is seen as [seen] is that to the power 2.2.
        val light = seen(t).pow(2.2)
        if (t < buildFrom) {
            if (under != null) drawUnder(drawer, stage, light, listOf(stage.bounds))
        } else {
            // The chapter's field, as far as it has come, with the ground bare where nothing has risen yet.
            opening.drawBuilding(drawer, stage.bounds) { risen(it, t) }

            // What is left of the course wall, in the cells whose block has not started: each is its
            // leaf of the grid, so the cells taken and not taken tile the wall exactly.
            val sx = stage.bounds.width / wall.width
            val sy = stage.bounds.height / wall.height
            val left = cells.indices.filter { starts[it] > t }.map { i ->
                val leaf = cells[i].offsetEdges(MosaicCells.GAP / 2.0)
                Rectangle(stage.bounds.x + leaf.x * sx, stage.bounds.y + leaf.y * sy, leaf.width * sx, leaf.height * sy)
            }
            if (left.isNotEmpty() && under != null && light > 0.0) drawUnder(drawer, stage, light, left)
        }

        if (countdown && !built) drawCountdown(drawer, stage, duration - t)
    }

    /** The wall before, at its own frame count carried on, into [cells] of the picture, at [brightness]. */
    private fun drawUnder(drawer: Drawer, stage: Stage, brightness: Double, cells: List<Rectangle>) {
        val wall = under ?: return
        val w = stage.bounds.width.toInt()
        val h = stage.bounds.height.toInt()
        val rt = target?.takeIf { it.width == w && it.height == h }
            ?: renderTarget(w, h) { colorBuffer() }.also { target?.destroy(); target = it }
        val frame = underFrom + stage.frame
        val length = wall.loop
        val carried = Stage(
            bounds = Rectangle(0.0, 0.0, stage.bounds.width, stage.bounds.height), frame = frame,
            steps = wall.steps, step = underStep, position = underStep.toDouble(), enter = 1.0, exit = 0.0,
            loop = if (length > 0) (frame % length).toDouble() / length else 0.0,
            cycle = if (length > 0) frame / length else 0
        )
        drawer.isolatedWithTarget(rt) {
            ortho(rt)
            clear(wall.background)
            wall.draw(this, carried)
        }
        val picture: ColorBuffer = rt.colorBuffer(0)
        drawer.isolated {
            drawer.drawStyle.colorMatrix = tint(ColorRGBa(brightness, brightness, brightness, 1.0))
            drawer.image(picture, cells.map { it.movedBy(-stage.bounds.corner) to it })
        }
    }

    /**
     * The seconds left, m:ss, with a line that shortens under them — bottom left of the wall, on an
     * opaque black plate so it reads from the first frame whatever the course wall behind it is doing.
     */
    private fun drawCountdown(drawer: Drawer, stage: Stage, left: Double) {
        val face = font ?: return
        val whole = kotlin.math.round(left).toInt().coerceAtLeast(0)
        val text = "%d:%02d".format(whole / 60, whole % 60)
        val x = stage.bounds.x + stage.bounds.height * 16.0 / 9.0 * MARGIN
        val baseline = stage.bounds.y + stage.bounds.height * FOOT
        val length = stage.bounds.height * 0.16
        val size = Scale.title(1080.0)
        // Sized to the widest the figures get rather than to the text, so the plate never changes size.
        val width = maxOf(face.advanceOf("0:00"), length)
        val top = baseline - size * CAP - PAD
        val plate = Rectangle(x - PAD, top, width + 2 * PAD, baseline + BAR + 3.0 + PAD - top)
        drawer.isolated {
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK
            drawer.rectangle(plate)
            drawer.fontMap = face
            drawer.fill = ColorRGBa.WHITE
            drawer.text(text, x, baseline)
            drawer.fill = ColorRGBa.WHITE.opacify(0.3)
            drawer.rectangle(x, baseline + BAR, length, 3.0)
            drawer.fill = ColorRGBa.WHITE
            drawer.rectangle(x, baseline + BAR, length * (left / duration).coerceIn(0.0, 1.0), 3.0)
        }
    }

    private companion object {
        /** The house margin and foot, as `Frame` states them for a pane. */
        const val MARGIN = 0.02
        const val FOOT = 0.92
        /** The plate's padding round the figures and the line, the line's drop under the baseline, and the figures' height over it as a share of the size. */
        const val PAD = 18.0
        const val BAR = 12.0
        const val CAP = 0.75
    }
}
