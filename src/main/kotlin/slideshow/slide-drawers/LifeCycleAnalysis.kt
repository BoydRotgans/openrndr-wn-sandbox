package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import slideshow.Frame
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What a source's mark is drawn as. */
enum class LcaMark {
    /** A precast element in elevation: a block with two slots cut in its top. */
    ELEMENT,

    /** A concrete mix: a cluster of aggregate, in the house pair and grey. */
    MIX
}

/** One database feeding the analysis: what it is called, what it knows, and its mark. */
class LcaSource(val caption: String, val attributes: List<String>, val mark: LcaMark)

/**
 * The life-cycle analysis: two databases gathered into one calculation.
 *
 * Each source states the same five things about what it holds — material, supplier, transport,
 * origin, CO₂ — and those five converge into the thing itself: a precast element for what was
 * bought, a mix of aggregate for what it is made of. The two marks are then gathered by a
 * brace into **Levenscyclus analyse**, and what the analysis is *for* follows straight on from its
 * name, its first line on the name's own baseline, so the two read as one sentence: the analysis
 * makes the calculation possible (feedback of 20 October 2026, which also took out the definition
 * that stood under it).
 *
 * **Everything is a fan of the same curve.** The five attributes converging on a mark and the
 * two marks converging on the analysis are the same primitive at two scales — a cubic with both
 * control points on the midline between its ends, which is d3's `linkHorizontal` and the same
 * one `TreeSlide` fans its labels with. That is why the drawing reads as one gesture repeated
 * rather than as a diagram with two kinds of line in it.
 *
 * **A line arrives by being drawn, not by fading.** `contour.sub(0, t)` is a shorter piece of
 * the same curve, so the leaders travel from the words into the mark — which is the direction
 * the argument runs, and a fade would say nothing about direction at all.
 *
 * The five attributes are one list handed in twice, because they are the same five: reword them
 * once and both fans follow.
 *
 * **The layout is lined up rather than placed** (feedback of 20 October 2026, "make the layout more
 * neat"): the two sources stand the same distance above and below the analysis line, each caption
 * starts on the left edge of the attribute column above it, every line of the diagram meets its mark
 * at the mark's own edge, the analysis's name is centred on the point the brace closes on, and the
 * conclusion's middle line stands on the name's baseline, set at the attributes' size and leading.
 * The right margin is the left one.
 */
class LifeCycleAnalysis(
    private val title: String = "Levenscyclus van betonproducten",
    /** The databases, top first. Two, on the reference. */
    private val sources: List<LcaSource>,
    private val analysis: String = "Levenscyclus analyse",
    /** What the analysis makes possible, set straight after its name. */
    private val conclusion: String = "Maakt berekening materiaalgebonden CO₂-uitstoot mogelijk",
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = slideshow.Palette.RED,
    private val accent: ColorRGBa = slideshow.Palette.BLUE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "LCA"

    /** A click a source, one to gather them, and one for the conclusion. */
    override val steps = sources.size + 2

    private val gathered get() = sources.size
    private val concluded get() = sources.size + 1

    override fun stepName(step: Int) = when (step) {
        gathered -> "gather"
        concluded -> "what it makes possible"
        else -> sources.getOrNull(step)?.caption
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        drawer.stroke = null
        drawer.fill = ColorRGBa.WHITE
        drawer.setLine(title, bold, Vector2(stage.center.x, Frame(stage.bounds).titleBaseline), stage.height * TITLE, SIZE, CENTRE)

        val markX = stage.width * MARK_X
        val braceX = stage.width * BRACE_X
        val analysisY = stage.center.y
        val bands = sources.indices.map { analysisY + stage.height * BAND * (if (it == 0) -1.0 else 1.0) }

        // The attribute column is as wide as its widest word, and stands on the left margin.
        val attributeScale = stage.height * ATTRIBUTE / SIZE
        val column = stage.width * MARGIN +
                sources.flatMap { it.attributes }.maxOf { text.advanceWithSubscripts(it) } * attributeScale

        // The first source has no click to arrive on — the slide opens on it — so it builds on
        // the slide's own clock instead, words then threads, the way the second builds on its
        // click (review of 22 September: it stood there whole from the first frame). Only while
        // the slide is still on its opening state, so stepping back to it finds it built.
        val opening = stage.step == 0 && stage.position < 1e-6
        val first = if (opening) smoothstep(stage.since(frames(OPEN_DELAY), stepFrames)) else 1.0

        sources.forEachIndexed { i, source ->
            val shown = if (i == 0) first else stage.on(i)
            if (shown <= 0.0) return@forEachIndexed

            fan(drawer, stage, source, bands[i], column, markX, shown)
        }

        // The brace: the two marks gathered into one point, then the name of what they make.
        val gather = stage.on(gathered)
        if (gather > 0.0) {
            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = stage.height * RULE
            drawer.fill = null
            sources.forEachIndexed { i, source ->
                val from = Vector2(markEdges(stage, source.mark, markX, bands[i]).y + stage.width * GAP, bands[i])
                drawer.contour(link(from, Vector2(braceX, analysisY)).sub(0.0, gather))
            }
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE.opacify(gather)
            drawer.setLine(analysis, bold, Vector2(braceX + stage.width * 0.018, analysisBaseline(stage)), stage.height * ANALYSIS, SIZE, LEFT)
        }

        // What the analysis is for, on its own click, straight after its name, its middle line on the name's baseline.
        val says = stage.on(concluded)
        if (says > 0.0) {
            val left = braceX + stage.width * 0.018 + bold.advanceWithSubscripts(analysis) * (stage.height * ANALYSIS / SIZE) +
                    stage.width * SAYS_GAP
            drawer.fill = ColorRGBa.WHITE.opacify(says)
            val scale = stage.height * ATTRIBUTE / SIZE
            val lines = wrap(text, conclusion, (stage.width * (1.0 - MARGIN) - left) / scale)
            val leading = stage.height * ATTR_LEADING
            val first = analysisBaseline(stage) - (lines.size - 1) * leading / 2.0
            lines.forEachIndexed { i, line ->
                drawer.setLine(line, text, Vector2(left, first + i * leading), stage.height * ATTRIBUTE, SIZE, LEFT)
            }
        }
    }

    /** The analysis's baseline, set so its capitals are centred on the point the brace closes on. */
    private fun analysisBaseline(stage: Stage) = stage.center.y + stage.height * ANALYSIS * CAP_MIDDLE

    /** One source: its attributes ranged right, the leaders into its mark, and the caption. */
    private fun fan(drawer: Drawer, stage: Stage, source: LcaSource, middle: Double, right: Double, markX: Double, shown: Double) {
        val size = stage.height * ATTRIBUTE
        val leading = stage.height * ATTR_LEADING
        val n = source.attributes.size
        val top = middle - (n - 1) * leading / 2.0

        // The words first, then the lines out of them, so a leader always leaves from type
        // that is already there.
        drawer.fill = ColorRGBa.WHITE.opacify(shown)
        source.attributes.forEachIndexed { i, attribute ->
            drawer.setLine(attribute, text, Vector2(right, top + i * leading + size * CAP_MIDDLE), size, SIZE, RIGHT)
        }

        val meet = Vector2(markEdges(stage, source.mark, markX, middle).x - stage.width * GAP, middle)
        drawer.stroke = ColorRGBa.WHITE.opacify(shown)
        drawer.strokeWeight = stage.height * RULE
        drawer.fill = null
        source.attributes.forEachIndexed { i, _ ->
            val from = Vector2(right + stage.width * GAP, top + i * leading)
            drawer.contour(link(from, meet).sub(0.0, shown))
        }
        drawer.stroke = null

        mark(drawer, stage, source.mark, markX, middle, shown)

        drawer.fill = ColorRGBa.WHITE.opacify(shown)
        // Under the column, on its left edge: one and a half leadings below its last line.
        drawer.setLine(
            source.caption, bold,
            Vector2(stage.width * MARGIN, top + (n - 1) * leading + size * CAP_MIDDLE + leading * CAPTION_GAP),
            stage.height * CAPTION, SIZE, LEFT
        )
    }

    /** The thing the attributes describe: a bought element, or the mix it is made of. */
    private fun mark(drawer: Drawer, stage: Stage, kind: LcaMark, x: Double, y: Double, shown: Double) {
        val w = stage.width * MARK_W
        val h = stage.height * MARK_H
        when (kind) {
            LcaMark.ELEMENT -> {
                // A block with two slots cut in its top — a precast element seen square on.
                val box = Rectangle(x, y - h / 2.0, w, h)
                drawer.fill = ink.opacify(shown)
                drawer.rectangle(box)
                drawer.fill = background.opacify(shown)
                val slot = w * 0.085
                listOf(0.24, 0.56).forEach { at ->
                    drawer.rectangle(box.corner.x + w * at, box.corner.y, slot, h * 0.22)
                }
            }

            LcaMark.MIX -> {
                // Aggregate: a cluster in the house pair and grey.
                val centre = Vector2(x + w / 2.0, y)
                grains(stage, centre).forEach { at ->
                    val across = (at.x - centre.x) / (w * 0.62)
                    drawer.fill = when {
                        across < -0.05 -> accent
                        at.y < y -> ink
                        else -> ColorRGBa.fromHex("D9D9D9")
                    }.opacify(shown)
                    drawer.circle(at, grainRadius(stage))
                }
            }
        }
    }

    /**
     * The mix's grains about [centre]. Placed by rejection rather than taken as they fall: aggregate
     * that touches reads as a blob, and what makes this a *mix* is being able to count it. A fixed
     * seed, so the same frame always draws the same grains.
     */
    private fun grains(stage: Stage, centre: Vector2): List<Vector2> {
        val w = stage.width * MARK_W
        val h = stage.height * MARK_H
        val radius = grainRadius(stage)
        val random = Random(MIX_SEED)
        val grains = mutableListOf<Vector2>()
        var tries = 0
        while (grains.size < MIX_GRAINS && tries++ < 4000) {
            val angle = random.nextDouble() * 2.0 * Math.PI
            val reach = Math.sqrt(random.nextDouble())
            val at = centre + Vector2(cos(angle) * reach * w * 0.62, sin(angle) * reach * h * 1.15)
            if (grains.none { it.distanceTo(at) < radius * 2.35 }) grains += at
        }
        return grains
    }

    private fun grainRadius(stage: Stage) = stage.height * 0.016

    /**
     * Where a mark starts and ends across the pane, as (left, right): the lines meet it there rather
     * than at a box it does not fill, so a leader never runs under a grain of the mix.
     */
    private fun markEdges(stage: Stage, kind: LcaMark, x: Double, y: Double): Vector2 = when (kind) {
        LcaMark.ELEMENT -> Vector2(x, x + stage.width * MARK_W)
        LcaMark.MIX -> {
            val grains = grains(stage, Vector2(x + stage.width * MARK_W / 2.0, y))
            Vector2(grains.minOf { it.x } - grainRadius(stage), grains.maxOf { it.x } + grainRadius(stage))
        }
    }

    /**
     * A link from [a] to [b]: a cubic with both control points on the midline between them.
     *
     * d3's `linkHorizontal`, and the same curve `TreeSlide` fans with — which is what gives a
     * tight bundle where the lines meet and an even spread where they leave the words.
     */
    private fun link(a: Vector2, b: Vector2): ShapeContour {
        val mid = (a.x + b.x) / 2.0
        return contour {
            moveTo(a)
            curveTo(Vector2(mid, a.y), Vector2(mid, b.y), b)
        }
    }

    /** Greedy wrap, measured with [width] so a subscript is not counted at full size. */
    private fun wrap(face: FontImageMap, passage: String, measure: Double): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in passage.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && face.advanceWithSubscripts(candidate) > measure) {
                lines += line; line = word
            } else line = candidate
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private companion object {
        const val SIZE = 150.0
        const val LEFT = 0.0
        const val CENTRE = 0.5
        const val RIGHT = 1.0

        // Measured off the pdf, as shares of the pane, and then lined up.
        /** Left and right, as a share of the width: the attribute column and the captions start on it. */
        const val MARGIN = 0.06
        const val ATTR_LEADING = 0.0365
        const val MARK_X = 0.320
        const val MARK_W = 0.077
        const val MARK_H = 0.075
        const val BRACE_X = 0.470
        /** Between the analysis's name and what it makes possible, as a share of the pane's width. */
        const val SAYS_GAP = 0.016
        /** Each source's middle, above and below the analysis line, as a share of the height. */
        const val BAND = 0.185
        /** Between a line and the type or the mark it leaves or meets, as a share of the width. */
        const val GAP = 0.010
        /** A caption's baseline under its column's last, in leadings. */
        const val CAPTION_GAP = 1.5
        /** How far below a line's middle its baseline goes, as a share of the size: half a capital. */
        const val CAP_MIDDLE = 0.34

        const val TITLE = 0.038
        const val ATTRIBUTE = 0.030
        const val CAPTION = 0.028
        const val ANALYSIS = 0.038
        const val RULE = 0.0022


        /** Seconds the first source waits after the slide comes up before it builds. */
        const val OPEN_DELAY = 0.3

        const val MIX_SEED = 7
        const val MIX_GRAINS = 17
    }
}
