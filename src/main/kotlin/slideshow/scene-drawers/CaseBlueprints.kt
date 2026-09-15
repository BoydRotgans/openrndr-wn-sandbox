package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Scene
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import slideshow.Cut
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.min

/**
 * One case: the two drawings — [a] on the left projector, [b] on the right — and the four facts
 * set in the corners. An empty fact is simply not set, so a case still to be filled in reads as
 * a drawing with its name on it rather than as a form with blanks.
 */
class BlueprintCase(
    val name: String,
    val a: String,
    val b: String,
    /** What the building is; one line a list entry. */
    val function: List<String> = emptyList(),
    val location: String = "",
    val realisation: String = ""
)

/**
 * The case studies as blueprints: a project a click, its plan filling the left projector and a
 * second drawing the right, with its name, what it is, where and how long it took in the four
 * corners of both — the sketch in `data/blue-prints`.
 *
 * **The wall is two projectors, and each carries a whole drawing with its own facts.** Anything
 * to be read stays inside one 1920 pane (the rule under the programme wall), so the facts are set
 * on both rather than once across the seam.
 *
 * **One number drives it.** Between two cases the drawings hand over through black — the leaving
 * pair gone by the middle of the click, the next pair up from the middle on — and the facts on the
 * house crossfade, gone by a third and in from two thirds, so nothing is ever two cases at once.
 * The first case builds on the scene's own clock while it is still on its first state, so stepping
 * back into the scene finds it standing.
 */
class CaseBlueprints(
    private val cases: List<BlueprintCase>,
    private val folder: File = File("data/case-blueprints"),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.2),
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Scene() {

    override val name = "Case blueprints"
    override val steps get() = cases.size.coerceAtLeast(1)
    override val settle get() = frames(OPEN)
    override fun stepName(step: Int) = cases.getOrNull(step)?.name

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var drawings: List<Pair<ColorBuffer?, ColorBuffer?>> = emptyList()

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        fun picture(file: String): ColorBuffer? {
            val f = File(folder, file)
            if (!f.isFile) { println("case blueprints: no drawing at ${f.path}"); return null }
            return loadImage(f).apply { generateMipmaps(); filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR }
        }
        drawings = cases.map { picture(it.a) to picture(it.b) }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (cases.isEmpty()) return
        val w = stage.width
        val h = stage.height
        val pane = w / 2.0

        val p = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val from = p.toInt().coerceIn(0, cases.size - 1)
        val to = (from + 1).coerceAtMost(cases.size - 1)
        val t = if (to == from) 0.0 else p - from

        // The opening: the first drawings up over OPEN, the facts from half way through it.
        val opening = stage.step == 0 && p < 1e-6
        val drawn = if (opening) smoothstep(stage.since(0, frames(OPEN))) else 1.0
        val lettered = if (opening) smoothstep((stage.since(0, frames(OPEN)) - 0.5) / 0.5) else 1.0

        // Through black: out by the middle of the click, in from the middle.
        val drawingOut = (1.0 - 2.0 * t).coerceIn(0.0, 1.0) * drawn
        val drawingIn = (2.0 * t - 1.0).coerceIn(0.0, 1.0)
        // The house text crossfade: gone by a third, in from two thirds.
        val textOut = (1.0 - 3.0 * t).coerceIn(0.0, 1.0) * lettered
        val textIn = (3.0 * t - 2.0).coerceIn(0.0, 1.0)

        for (side in 0..1) {
            val bounds = Rectangle(stage.bounds.x + side * pane, stage.bounds.y, pane, h)
            drawing(drawer, drawings.getOrNull(from)?.let { if (side == 0) it.first else it.second }, bounds, drawingOut)
            if (to != from) drawing(drawer, drawings[to].let { if (side == 0) it.first else it.second }, bounds, drawingIn)
            facts(drawer, cases[from], bounds, textOut)
            if (to != from) facts(drawer, cases[to], bounds, textIn)
        }
    }

    /** A drawing fitted whole into its pane and centred, faded by laying the ground back over it. */
    private fun drawing(drawer: Drawer, picture: ColorBuffer?, pane: Rectangle, alpha: Double) {
        if (picture == null || alpha <= 0.0) return
        val fit = min(pane.width * FIT / picture.width, pane.height * FIT / picture.height)
        val r = Rectangle.fromCenter(pane.center, picture.width * fit, picture.height * fit)
        drawer.image(picture, r.corner, r.width, r.height)
        if (alpha < 1.0) {
            drawer.stroke = null
            drawer.fill = background.opacify(1.0 - alpha)
            drawer.rectangle(r)
        }
    }

    /** The four facts in the pane's corners: name and what it is along the top, where and how long along the foot. */
    private fun facts(drawer: Drawer, case: BlueprintCase, pane: Rectangle, alpha: Double) {
        if (alpha <= 0.0) return
        val h = pane.height
        val size = h * TYPE
        val left = pane.x + h * MARGIN
        val right = pane.x + pane.width * FACTS_AT
        val top = pane.y + h * MARGIN + size
        val foot = pane.y + h - h * MARGIN
        drawer.stroke = null
        drawer.fill = ink.opacify(alpha)
        drawer.setLine(case.name, bold, Vector2(left, top), size, SIZE)
        case.function.forEachIndexed { i, line ->
            drawer.setLine(line, text, Vector2(right, top + i * size * LEAD), size, SIZE)
        }
        drawer.setLine(case.location, text, Vector2(left, foot), size, SIZE)
        if (case.realisation.isNotEmpty()) {
            drawer.setLine("Realisatie: ${case.realisation}", text, Vector2(right, foot), size, SIZE)
        }
    }

    private companion object {
        const val SIZE = 200.0
        /** Type size and margin as shares of the pane's height, and where the right-hand facts start across it. */
        const val TYPE = 0.04
        const val MARGIN = 0.05
        const val FACTS_AT = 0.52
        const val LEAD = 1.15
        /** How much of the pane a drawing may take. */
        const val FIT = 0.96
        /** Seconds the first case takes to stand up. */
        const val OPEN = 1.2
    }
}
