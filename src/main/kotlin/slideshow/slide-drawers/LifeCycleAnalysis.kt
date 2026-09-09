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
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
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
 * brace into **Levenscyclus analyse**, and from there into what the analysis is *for*.
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
 */
class LifeCycleAnalysis(
    private val title: String = "Levenscyclus van betonproducten",
    /** The databases, top first. Two, on the reference. */
    private val sources: List<LcaSource>,
    private val analysis: String = "Levenscyclus analyse",
    /** What the analysis makes possible, set large to the right of it. */
    private val conclusion: String = "Maakt berekening materiaalgebonden CO₂-uitstoot mogelijk",
    /** And what that means, under an equals sign. */
    private val definition: String =
        "De hoeveelheid CO₂ die een product veroorzaakt bij productie/over volledige levensduur.",
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val accent: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "LCA"

    /** A click a source, one to gather them, one for the conclusion, one for what it means. */
    override val steps = sources.size + 3

    private val gathered get() = sources.size
    private val concluded get() = sources.size + 1
    private val defined get() = sources.size + 2

    override fun stepName(step: Int) = when (step) {
        gathered -> "gather"
        concluded -> "what it makes possible"
        defined -> "what that means"
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
        drawer.setLine(title, bold, Vector2(stage.center.x, stage.height * 0.058), stage.height * TITLE, SIZE, CENTRE)

        val markX = stage.width * MARK_X
        val braceX = stage.width * BRACE_X
        val analysisY = stage.center.y

        sources.forEachIndexed { i, source ->
            val shown = stage.on(i)
            if (shown <= 0.0) return@forEachIndexed

            // The two bands the sources stand in, split evenly about the middle.
            val band = stage.height * (if (i == 0) TOP_BAND else BOTTOM_BAND)
            fan(drawer, stage, source, band, markX, shown)
        }

        // The brace: the two marks gathered into one point, then the name of what they make.
        val gather = stage.on(gathered)
        if (gather > 0.0) {
            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = stage.height * RULE
            drawer.fill = null
            sources.forEachIndexed { i, _ ->
                val from = Vector2(markX + stage.width * MARK_W, stage.height * (if (i == 0) TOP_BAND else BOTTOM_BAND))
                drawer.contour(link(from, Vector2(braceX, analysisY)).sub(0.0, gather))
            }
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE.opacify(gather)
            drawer.setLine(analysis, bold, Vector2(braceX + stage.width * 0.018, analysisY), stage.height * ANALYSIS, SIZE, LEFT)
        }

        // What the analysis is for, on its own click, and what it means on the next.
        val says = stage.on(concluded)
        if (says > 0.0) {
            val from = Vector2(braceX + stage.width * 0.018 + bold.advanceWithSubscripts(analysis) * (stage.height * ANALYSIS / SIZE) + stage.width * 0.012, analysisY)
            val to = Vector2(stage.width * SAYS_X - stage.width * 0.010, analysisY)
            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = stage.height * RULE
            drawer.fill = null
            drawer.contour(ShapeContour.fromPoints(listOf(from, from + (to - from) * says), false))

            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE.opacify(says)
            val box = Rectangle(stage.width * SAYS_X, 0.0, stage.width * (0.985 - SAYS_X), stage.height)
            block(drawer, conclusion, text, box, stage.height * SAYS, analysisY - stage.height * 0.075, up = true)
        }

        val means = stage.on(defined)
        if (means > 0.0) {
            drawer.fill = ColorRGBa.WHITE.opacify(means)
            val box = Rectangle(stage.width * SAYS_X, 0.0, stage.width * (0.985 - SAYS_X), stage.height)
            drawer.setLine("=", text, Vector2(box.corner.x, analysisY + stage.height * 0.100), stage.height * MEANS, SIZE, LEFT)
            block(drawer, definition, text, box, stage.height * MEANS, analysisY + stage.height * 0.145, up = false)
        }
    }

    /** One source: its attributes ranged right, the leaders into its mark, and the caption. */
    private fun fan(drawer: Drawer, stage: Stage, source: LcaSource, middle: Double, markX: Double, shown: Double) {
        val size = stage.height * ATTRIBUTE
        val leading = stage.height * ATTR_LEADING
        val right = stage.width * ATTR_RIGHT
        val n = source.attributes.size
        val top = middle - (n - 1) * leading / 2.0

        // The words first, then the lines out of them, so a leader always leaves from type
        // that is already there.
        drawer.fill = ColorRGBa.WHITE.opacify(shown)
        source.attributes.forEachIndexed { i, attribute ->
            drawer.setLine(attribute, text, Vector2(right, top + i * leading + size * 0.34), size, SIZE, RIGHT)
        }

        val meet = Vector2(markX - stage.width * 0.012, middle)
        drawer.stroke = ColorRGBa.WHITE.opacify(shown)
        drawer.strokeWeight = stage.height * RULE
        drawer.fill = null
        source.attributes.forEachIndexed { i, _ ->
            val from = Vector2(right + stage.width * 0.010, top + i * leading)
            drawer.contour(link(from, meet).sub(0.0, shown))
        }
        drawer.stroke = null

        mark(drawer, stage, source.mark, markX, middle, shown)

        drawer.fill = ColorRGBa.WHITE.opacify(shown)
        drawer.setLine(
            source.caption, bold,
            Vector2(stage.width * CAPTION_X, middle + stage.height * CAPTION_DROP),
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
                // Aggregate: a cluster in the house pair and grey, from a fixed seed so the
                // same frame always draws the same mix.
                val random = Random(MIX_SEED)
                val radius = stage.height * 0.016
                val centre = Vector2(x + w / 2.0, y)

                // Placed by rejection rather than taken as they fall: aggregate that touches
                // reads as a blob, and what makes this a *mix* is being able to count it. A
                // fixed seed, so the same frame always draws the same grains.
                val grains = mutableListOf<Vector2>()
                var tries = 0
                while (grains.size < MIX_GRAINS && tries++ < 4000) {
                    val angle = random.nextDouble() * 2.0 * Math.PI
                    val reach = Math.sqrt(random.nextDouble())
                    val at = centre + Vector2(cos(angle) * reach * w * 0.62, sin(angle) * reach * h * 1.15)
                    if (grains.none { it.distanceTo(at) < radius * 2.35 }) grains += at
                }

                grains.forEach { at ->
                    val across = (at.x - centre.x) / (w * 0.62)
                    drawer.fill = when {
                        across < -0.05 -> accent
                        at.y < y -> ink
                        else -> ColorRGBa.fromHex("D9D9D9")
                    }.opacify(shown)
                    drawer.circle(at, radius)
                }
            }
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

    /** A passage broken to [box]'s measure, set from [at] downwards or upwards. */
    private fun block(
        drawer: Drawer, passage: String, face: FontImageMap,
        box: Rectangle, size: Double, at: Double, up: Boolean
    ) {
        val scale = size / SIZE
        val lines = wrap(face, passage, box.width / scale)
        val leading = size * 1.3
        var y = if (up) at - (lines.size - 1) * leading else at
        lines.forEach {
            drawer.setLine(it, face, Vector2(box.corner.x, y), size, SIZE, LEFT)
            y += leading
        }
    }

    private companion object {
        const val SIZE = 150.0
        const val LEFT = 0.0
        const val CENTRE = 0.5
        const val RIGHT = 1.0

        // Measured off the pdf, as shares of the pane.
        const val ATTR_RIGHT = 0.193
        const val ATTR_LEADING = 0.0365
        const val MARK_X = 0.320
        const val MARK_W = 0.077
        const val MARK_H = 0.075
        const val BRACE_X = 0.470
        const val SAYS_X = 0.770
        const val TOP_BAND = 0.307
        const val BOTTOM_BAND = 0.675
        const val CAPTION_X = 0.052
        const val CAPTION_DROP = 0.145

        const val TITLE = 0.038
        const val ATTRIBUTE = 0.030
        const val CAPTION = 0.028
        const val ANALYSIS = 0.038
        const val SAYS = 0.032
        const val MEANS = 0.022
        const val RULE = 0.0022


        const val MIX_SEED = 7
        const val MIX_GRAINS = 17
    }
}
