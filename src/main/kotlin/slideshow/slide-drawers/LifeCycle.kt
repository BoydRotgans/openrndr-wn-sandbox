package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import kotlin.math.min

/** One box in a phase: its code set bold, and what it is. */
class LifeStep(val code: String, val name: String)

/** A column of the life cycle: its heading, and the boxes under it. */
class LifePhase(val code: String, val name: String, val steps: List<LifeStep>)

/**
 * The life cycle of a concrete product, built a phase at a time — and then broken.
 *
 * Production, construction, use, end of life: four columns arriving one a click, each one
 * narrowing the ones already up so the whole cycle stays on the frame. Then the turn the
 * slide exists for — **the end-of-life column is taken away and The Circle put in its place**,
 * white where the rest is red, and the four steps that replace disposal arrive in it. The
 * linear cycle is drawn in full before it is contradicted, which is the only order in which
 * the contradiction reads.
 *
 * **The layout is a pure function of one number**: how many columns are showing. That number
 * is continuous — `1 + stage.position`, clamped — so a column arriving is not a new layout but
 * the same one at 2.4 columns instead of 2, and the ones already up slide and narrow to make
 * room by themselves. `packBoxes` in demo01 and `stackRows` in the stack again, and the same
 * payoff: change the proportions and it keeps animating for free.
 *
 * **A box's height is read off its phase**, not stated: a column's boxes share what the header
 * leaves, so the five of the use phase are shorter than the three of production without either
 * being told so. Reword a phase or add a step and the drawing re-proportions.
 *
 * The two weights are set as one run — the code bold, the name regular — and wrapped together,
 * so "A2 Transport naar fabricage plek" breaks where it will fit rather than at a place stated
 * for one column width. That matters here because the same label is set at four widths as the
 * columns close in.
 */
class LifeCycle(
    private val title: String = "Levenscyclus van betonproducten",
    /** The columns, in the order they arrive. The last is the one The Circle replaces. */
    private val phases: List<LifePhase>,
    /** What stands in the last column's place once the linear cycle is broken. */
    private val closing: LifePhase,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    /** The column frames. The Figma export's blue, not the draaiboek's navy. */
    private val frame: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Life cycle"

    /** A click a column, then one that empties the last, then one that fills it again. */
    override val steps = phases.size + 2

    private val emptied get() = phases.size          // the click that puts The Circle up
    private val filled get() = phases.size + 1       // the click that fills it

    override fun stepName(step: Int) = when (step) {
        emptied -> "break the cycle"
        filled -> "the circle"
        else -> phases.getOrNull(step)?.code
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
        run(drawer, listOf(title to bold), Vector2(stage.center.x, stage.height * TITLE_Y), stage.height * TITLE / SIZE)

        // How many columns are up, continuously: 1 at rest on click 0, 2.4 part way through
        // the second click, and never more than there are phases.
        val showing = (1.0 + stage.position).coerceIn(1.0, phases.size.toDouble())

        val span = stage.width * (1.0 - 2.0 * MARGIN)
        val width = (span - (showing - 1.0) * stage.width * GAP) / showing
        val top = stage.height * TOP
        val height = stage.height * (BOTTOM - TOP)

        // The last column is taken away and given back, so the two clicks after the phases
        // are a crossfade in place rather than anything moving.
        val gone = stage.on(emptied)
        val back = stage.on(filled)

        phases.forEachIndexed { i, phase ->
            // A column is only drawn once it has started arriving, and fades up as it does.
            val arriving = (showing - i).coerceIn(0.0, 1.0)
            if (arriving <= 0.0) return@forEachIndexed

            val box = Rectangle(
                stage.width * MARGIN + i * (width + stage.width * GAP), top, width, height
            )
            val last = i == phases.size - 1
            column(drawer, stage, box, phase, arriving * if (last) 1.0 - gone else 1.0, ink, ColorRGBa.WHITE)

            // The Circle stands in the last column's place: the same frame, filled the other
            // way round — white boxes and red type, so the break reads as a change of kind.
            if (last && gone > 0.0) {
                column(drawer, stage, box, closing, gone, paper, ink, fill = back)
            }
        }
    }

    /** One column: its frame, its heading, and the boxes the heading leaves room for. */
    private fun column(
        drawer: Drawer, stage: Stage, box: Rectangle, phase: LifePhase,
        shown: Double, boxInk: ColorRGBa, boxText: ColorRGBa, fill: Double = 1.0
    ) {
        if (shown <= 0.0) return

        drawer.fill = frame.opacify(shown)
        drawer.rectangle(box)

        val head = stage.height * HEADER
        drawer.fill = ColorRGBa.WHITE.opacify(shown)
        val heading = if (phase.code.isEmpty()) listOf(phase.name to bold)
        else listOf(phase.code to bold, " ${phase.name}" to text)
        run(drawer, heading, Vector2(box.center.x, box.corner.y + head * 0.68), stage.height * HEAD / SIZE)

        if (phase.steps.isEmpty()) return

        // The boxes share what the heading leaves, so five of them are shorter than three
        // without either being stated.
        val pad = stage.width * PAD
        val inner = Rectangle(
            box.corner.x + pad, box.corner.y + head,
            box.width - 2.0 * pad, box.height - head - pad
        )

        // [fill] opens the gaps rather than fading the boxes in, which is what makes The
        // Circle arrive as a plate that *divides* into four: closed up they are one white
        // panel, and the click cuts them apart. A fade would put two pictures on top of each
        // other for half a second; this has one picture throughout.
        val gap = stage.height * BOX_GAP * fill
        val each = (inner.height - (phase.steps.size - 1) * gap) / phase.steps.size

        phase.steps.forEachIndexed { i, step ->
            val at = Rectangle(inner.corner.x, inner.corner.y + i * (each + gap), inner.width, each)
            drawer.fill = boxInk.opacify(shown)
            drawer.rectangle(at)

            if (fill <= 0.0) return@forEachIndexed
            drawer.fill = boxText.opacify(shown * fill)
            val label = if (step.code.isEmpty()) listOf(step.name to text)
            else listOf(step.code to bold, " ${step.name}" to text)
            wrapped(drawer, label, at, stage.height * LABEL / SIZE)
        }
    }

    /**
     * A mixed-weight label, broken to fit [box] and centred in it.
     *
     * Wrapped as one run across both faces rather than per face: the code is bold and the name
     * is not, and a break has to be able to fall anywhere in the pair. It matters here because
     * the same label is set at four widths as the columns close in — a break stated for one of
     * them is wrong for the other three.
     */
    private fun wrapped(drawer: Drawer, parts: List<Pair<String, FontImageMap>>, box: Rectangle, scale: Double) {
        val words = parts.flatMap { (piece, face) ->
            piece.trim().split(" ").filter { it.isNotEmpty() }.map { it to face }
        }
        if (words.isEmpty()) return

        val measure = (box.width - 2.0 * box.width * 0.06) / scale
        val space = text.advanceOf(" ")

        val lines = mutableListOf<MutableList<Pair<String, FontImageMap>>>()
        var line = mutableListOf<Pair<String, FontImageMap>>()
        var used = 0.0
        for ((word, face) in words) {
            val wide = face.advanceOf(word)
            if (line.isNotEmpty() && used + space + wide > measure) {
                lines += line; line = mutableListOf(); used = 0.0
            }
            if (line.isNotEmpty()) used += space
            line += word to face
            used += wide
        }
        if (line.isNotEmpty()) lines += line

        val leading = SIZE * LEADING * scale
        var y = box.center.y - (lines.size - 1) * leading / 2.0 + SIZE * 0.34 * scale
        for (row in lines) {
            val spaced = row.flatMapIndexed { i, part -> if (i == 0) listOf(part) else listOf(" " to text, part) }
            run(drawer, spaced, Vector2(box.center.x, y), scale)
            y += leading
        }
    }

    /** One line of mixed weights, centred on [at]. */
    private fun run(drawer: Drawer, parts: List<Pair<String, FontImageMap>>, at: Vector2, scale: Double) {
        val wide = parts.sumOf { (piece, face) -> face.advanceOf(piece) }
        drawer.isolated {
            drawer.translate(at)
            drawer.scale(scale)
            var x = -wide / 2.0
            for ((piece, face) in parts) {
                drawer.fontMap = face
                drawer.text(piece, x, 0.0)
                x += face.advanceOf(piece)
            }
        }
    }

    private companion object {
        const val SIZE = 150.0

        // Measured off the pdf: four columns from 0.013 to 0.988 of the width with a 0.011
        // gutter, standing from 0.10 to 0.978 of the height under a 0.063 heading band.
        const val MARGIN = 0.0131
        const val GAP = 0.0113
        const val TOP = 0.100
        const val BOTTOM = 0.978
        const val HEADER = 0.063
        const val PAD = 0.008
        const val BOX_GAP = 0.016

        const val TITLE = 0.038
        const val TITLE_Y = 0.060
        const val HEAD = 0.030
        const val LABEL = 0.030
        const val LEADING = 1.25
    }
}
