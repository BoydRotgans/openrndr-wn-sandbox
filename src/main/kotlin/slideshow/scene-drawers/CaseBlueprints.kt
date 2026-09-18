package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolated
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
    val realisation: String = "",
    /** The composed close-ups that follow the overview, a click each, in order. */
    val details: List<BlueprintDetail> = emptyList()
)

/**
 * One composed view into a case: a region of drawing [side] (`'a'` the left projector, `'b'` the
 * right), stated as shares of the drawing's own width and height, that the click frames to fill
 * the pane, with a short Dutch [caption] under it. The other projector keeps its drawing whole.
 */
class BlueprintDetail(
    val side: Char,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val caption: String
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
 *
 * **A case is a sequence of composed views, not one camera journey.** The overview first, then
 * each of its [BlueprintDetail]s a click: the named region of one drawing framed to fill its pane
 * — a scale about the region's centre, in log space so it reads as even — while the other pane
 * keeps its drawing whole, the facts on the framed pane giving way to the detail's caption. Then
 * the next case, through black. That is the confirmed decision of the brief of 16 September: a
 * project file paged through with deliberate framings, each view with one subject, rather than a
 * continuous move. The regions are stated per case; until WN names the facade details worth
 * showing, the show's are placeholders that prove the mechanism.
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

    /** One view: which case, and which of its details, or -1 for the overview. */
    private class View(val case: Int, val detail: Int)
    private val views: List<View> = cases.flatMapIndexed { ci, c -> listOf(View(ci, -1)) + c.details.indices.map { View(ci, it) } }

    override val steps get() = views.size.coerceAtLeast(1)
    override val settle get() = frames(OPEN)
    override fun stepName(step: Int) = views.getOrNull(step)?.let { v ->
        val c = cases[v.case]
        if (v.detail < 0) c.name else "${c.name}: ${c.details[v.detail].caption}"
    }

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
        val vi = p.toInt().coerceIn(0, views.size - 1)
        val vj = (vi + 1).coerceAtMost(views.size - 1)
        val t = if (vj == vi) 0.0 else p - vi
        val va = views[vi]
        val vb = views[vj]
        val from = va.case
        val to = vb.case

        // The opening: the first drawings up over OPEN, the facts from half way through it.
        val opening = stage.step == 0 && p < 1e-6
        val drawn = if (opening) smoothstep(stage.since(0, frames(OPEN))) else 1.0
        val lettered = if (opening) smoothstep((stage.since(0, frames(OPEN)) - 0.5) / 0.5) else 1.0

        if (from != to) {
            // Between two cases, through black: out by the middle of the click, in from the middle;
            // the house text gone by a third and in from two thirds. Both at their overview framing,
            // since a case always ends on its last view and the next always opens on its overview.
            val drawingOut = (1.0 - 2.0 * t).coerceIn(0.0, 1.0) * drawn
            val drawingIn = (2.0 * t - 1.0).coerceIn(0.0, 1.0)
            val textOut = (1.0 - 3.0 * t).coerceIn(0.0, 1.0) * lettered
            val textIn = (3.0 * t - 2.0).coerceIn(0.0, 1.0)
            for (side in 0..1) {
                val bounds = Rectangle(stage.bounds.x + side * pane, stage.bounds.y, pane, h)
                val leaving = cases[from].details.getOrNull(va.detail)?.takeIf { it.side == sideOf(side) }
                drawing(drawer, pictureOf(from, side), bounds, drawingOut, leaving, 1.0)
                drawing(drawer, pictureOf(to, side), bounds, drawingIn, null, 0.0)
                if (leaving == null) facts(drawer, cases[from], bounds, textOut) else caption(drawer, leaving, bounds, textOut)
                facts(drawer, cases[to], bounds, textIn)
            }
            return
        }

        // Within a case: each pane eases between its framing in the view being left and the view
        // being landed on. A pane the detail is not on stays whole.
        val ease = smoothstep(t)
        for (side in 0..1) {
            val bounds = Rectangle(stage.bounds.x + side * pane, stage.bounds.y, pane, h)
            val c = cases[from]
            val da = c.details.getOrNull(va.detail)?.takeIf { it.side == sideOf(side) }
            val db = c.details.getOrNull(vb.detail)?.takeIf { it.side == sideOf(side) }
            // How far into a detail this pane is: 0 whole, 1 framed on it.
            val zoomA = if (da != null) 1.0 else 0.0
            val zoomB = if (db != null) 1.0 else 0.0
            val zoom = zoomA + (zoomB - zoomA) * ease
            val region = db ?: da
            drawing(drawer, pictureOf(from, side), bounds, drawn, region, zoom)
            // The facts give way to the caption as the pane closes in, and come back as it opens.
            facts(drawer, c, bounds, lettered * (1.0 - zoom))
            if (da != null && db != null && da !== db) {
                caption(drawer, da, bounds, (1.0 - 3.0 * t).coerceIn(0.0, 1.0))
                caption(drawer, db, bounds, (3.0 * t - 2.0).coerceIn(0.0, 1.0))
            } else region?.let { caption(drawer, it, bounds, zoom) }
        }
    }

    private fun sideOf(side: Int) = if (side == 0) 'a' else 'b'
    private fun pictureOf(case: Int, side: Int): ColorBuffer? = drawings.getOrNull(case)?.let { if (side == 0) it.first else it.second }

    /**
     * A drawing in its pane: fitted whole and centred, or — [zoom] of the way — framed on
     * [region], the scale about the region's centre in log space so the move reads as even.
     * Faded by laying the ground back over it.
     */
    private fun drawing(drawer: Drawer, picture: ColorBuffer?, pane: Rectangle, alpha: Double, region: BlueprintDetail?, zoom: Double) {
        if (picture == null || alpha <= 0.0) return
        val fit = min(pane.width * FIT / picture.width, pane.height * FIT / picture.height)
        val whole = Rectangle.fromCenter(pane.center, picture.width * fit, picture.height * fit)
        var k = 1.0
        var focus = whole.center
        if (region != null && zoom > 0.0) {
            val rw = region.w * whole.width
            val rh = region.h * whole.height
            val into = min(pane.width * FIT / rw, pane.height * FIT / rh).coerceAtLeast(1.0)
            k = Math.pow(into, zoom)
            val centre = Vector2(whole.x + (region.x + region.w / 2.0) * whole.width, whole.y + (region.y + region.h / 2.0) * whole.height)
            focus = whole.center + (centre - whole.center) * zoom
        }
        drawer.isolated {
            drawer.drawStyle.clip = pane
            drawer.translate(pane.center)
            drawer.scale(k)
            drawer.translate(-focus)
            drawer.image(picture, whole.corner, whole.width, whole.height)
        }
        if (alpha < 1.0) {
            drawer.stroke = null
            drawer.fill = background.opacify(1.0 - alpha)
            drawer.rectangle(pane)
        }
    }

    /** A detail's caption, centred in the pane's foot band. */
    private fun caption(drawer: Drawer, detail: BlueprintDetail, pane: Rectangle, alpha: Double) {
        if (alpha <= 0.0) return
        val h = pane.height
        drawer.stroke = null
        drawer.fill = ink.opacify(alpha)
        drawer.setLine(detail.caption, bold, Vector2(pane.center.x, pane.y + h - h * BAND_FOOT), h * TYPE, SIZE, align = 0.5)
    }

    /** The four facts in the pane's corners: name and what it is along the top, where and how long along the foot. */
    private fun facts(drawer: Drawer, case: BlueprintCase, pane: Rectangle, alpha: Double) {
        if (alpha <= 0.0) return
        val h = pane.height
        val size = h * TYPE
        val nameSize = h * NAME
        val left = pane.x + h * MARGIN
        val right = pane.x + pane.width * FACTS_AT
        // The facts stand in the bands the drawing leaves clear above and below it — never on
        // the drawing or its dimension lines, which is where they lay when the drawing took 96%.
        val top = pane.y + h * BAND_TOP + nameSize
        val foot = pane.y + h - h * BAND_FOOT
        drawer.stroke = null
        drawer.fill = ink.opacify(alpha)
        drawer.setLine(case.name, bold, Vector2(left, top), nameSize, SIZE)
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
        /** The project's name: the one thing to read on the wall, so larger than the facts. */
        const val NAME = 0.055
        const val MARGIN = 0.05
        const val FACTS_AT = 0.52
        const val LEAD = 1.15
        /** The bands above and below the drawing that the facts stand in. */
        const val BAND_TOP = 0.03
        const val BAND_FOOT = 0.04
        /** How much of the pane a drawing may take: the rest is the two bands. */
        const val FIT = 0.80
        /** Seconds the first case takes to stand up. */
        const val OPEN = 1.2
    }
}
