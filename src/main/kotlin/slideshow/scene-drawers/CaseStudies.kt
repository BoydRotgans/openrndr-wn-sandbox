// No `package` declaration: it stands on `PhotoMosaic` and the catalogue's marks, which are in the
// default package — `CityMapSlide`'s reason.

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Breathe
import slideshow.Frame
import slideshow.Palette
import slideshow.Scene
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.floor

/**
 * One project of the case studies: its photographs, a folder under the case studies, and its
 * blueprints, drawings cut into the blueprints folder. Either may be missing.
 */
class CaseProject(
    val name: String,
    /** One line under the name: where it stands and what it is. */
    val line: String = "",
    /** The project's folder of photographs, inside the case studies folder; null for none. */
    val photos: String? = null,
    /** The project's drawings, by file name inside the blueprints folder. */
    val blueprints: List<String> = emptyList(),
    /**
     * The facts set under the name, each a key and a value — "Functie", "Opdrachtgever",
     * "Locatie", "Realisatietijd" — in the order given. When there are any, [line] is not set.
     */
    val facts: List<Pair<String, String>> = emptyList()
)

/**
 * The case studies across the whole wall, through the project highlight's effect: each project's
 * photographs on the left projector and its blueprints on the right, a click a view, each view
 * built up out of the catalogue's marks and then dissolved into the whole picture, and handed over
 * to the next through them.
 *
 * **Two panes, one wall.** Each projector is its own [PhotoMosaic] on the same grid, so the joints
 * line up across the seam, and their fronts are the two halves of one sweep — the build and every
 * handover cross the wall from the left edge to the right as a single front. Nothing to read
 * crosses the seam: each projector carries its own copy of the lettering.
 *
 * **A project is its photos beside its drawings.** Its views are as many as the longer of the two
 * lists, and the shorter holds its last picture while the other goes on, so a side that has nothing
 * new stands still rather than repeating itself. A project with no drawings shows its photos on
 * both sides, a photo apart; one with no photos shows its first drawing on the left and the rest on
 * the right. **A drawing arrives in concrete**: it is white lines on black, so seen through the
 * marks it would be a field of black, and its marks are cast instead in the concrete textures —
 * one picked a mark, cut from its own place and at its own tone — and give way to the black
 * drawing as they dissolve. Drawings are **fitted whole** rather than cropped — a plan cut to fill the pane loses
 * its edges — and the black past a drawing's edge is its own ground. Photos fill the pane.
 *
 * **It can run on its own clock** ([cycle]), every view holding and handing over to the next and
 * the last back round to the first, the way a backdrop does — the show runs it so since 22
 * September, when a click a view was asked to become a wall that goes round by itself. And the
 * photographs can **stay in the grid** ([dissolves] false): seen through the marks while they stand
 * rather than dissolving into the whole photograph, which was asked for the same day. A drawing
 * still dissolves out of its concrete, since that is the only way it is seen.
 *
 * **Each project carries its facts** — function, client, location, the time it took — set in the
 * four corners of both projectors, the same on each, after the blueprint sketch. A project without
 * them sets its one line where the function goes.
 *
 * **The label changes with the project, not with the view**: the name and its line fade out through
 * the first half of the handover into a new project and in through the second, and within a project
 * they stand.
 *
 * Every picture is loaded and packed in `load` and brought down to a sensible size on the way in, so
 * no click waits on a 6811-pixel photograph.
 */
class CaseStudies(
    private val projects: List<CaseProject>,
    private val photoFolder: File,
    private val blueprintFolder: File,
    private val boldPath: String,
    private val textPath: String,
    /** The elements the pictures are seen through. */
    private val marks: File = File("data/svg/subset_svg"),
    /** The concrete a blueprint's marks are cast in: a folder of textures, one picked a mark. */
    private val concrete: File = File("data/concrete"),
    /** Whether a mark gives way to its whole cell in one frame (true) or crossfades in. */
    private val instant: Boolean = true,
    /** Whether a picture dissolves out of its marks into the whole image, or stays in the grid. */
    private val dissolves: Boolean = true,
    /**
     * Seconds each view holds before the next, running on the wall's own clock round and round like
     * a backdrop; null steps a view a click.
     */
    private val cycle: Double? = null,
    /** The wall everything is laid out for; `draw` fits it into whatever it gets. */
    private val wall: Vector2 = Vector2(3840.0, 1080.0)
) : Scene() {
    override val name = "Case studies"
    override val background: ColorRGBa = Palette.onBlack.paper
    override val settle get() = frames(PhotoMosaic.OPENING)
    override val stepFrames get() = frames(PhotoMosaic.CLICK)

    /** Seconds a handover takes: the whole click, since a drawing still dissolves at the end of it. */
    private val handoverLength get() = PhotoMosaic.CLICK

    /** Every picture, and whether it is a drawing, in the order they are keyed. */
    private class Source(val file: File, val drawing: Boolean)
    private val sources = ArrayList<Source>()

    /** One view: which project, and the keys of the picture on each side. */
    private class View(val project: Int, val left: Int, val right: Int)
    private val views: List<View>

    init {
        fun key(file: File, drawing: Boolean): Int { sources += Source(file, drawing); return sources.size - 1 }
        views = projects.flatMapIndexed { p, project ->
            val photos = project.photos?.let { File(photoFolder, it).listFiles() }.orEmpty()
                .filter { it.isFile && it.extension.lowercase() in IMAGES }
                .sortedBy { it.name.lowercase() }
                .map { key(it, false) }
            val drawings = project.blueprints.map { File(blueprintFolder, it) }
                .filter { it.isFile.also { ok -> if (!ok) println("case studies: no drawing at ${it.path}") } }
                .map { key(it, true) }
            val (left, right) = when {
                photos.isNotEmpty() && drawings.isNotEmpty() -> photos to drawings
                photos.isNotEmpty() -> photos to (photos.drop(1) + photos.take(1))
                drawings.isNotEmpty() -> drawings.take(1) to drawings.drop(1).ifEmpty { drawings }
                else -> emptyList<Int>() to emptyList()
            }
            if (left.isEmpty()) emptyList()
            else (0 until maxOf(left.size, right.size)).map {
                View(p, left[minOf(it, left.size - 1)], right[minOf(it, right.size - 1)])
            }
        }
    }

    override val steps get() = if (cycle != null) 1 else views.size.coerceAtLeast(1)
    override fun stepName(step: Int) = views.getOrNull(step)?.let { projects[it.project].name }

    private val palette = Palette.onBlack
    private val breathe = Breathe(DRIFT_PERIOD)
    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var pictures: List<MosaicPicture> = emptyList()
    private lateinit var left: PhotoMosaic
    private lateinit var right: PhotoMosaic

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, NAME_EM, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, TEXT_EM, TYPE_CHARACTERS, contentScale = 1.0)
        pictures = sources.map {
            MosaicPicture(loadPhoto(program.drawer, it.file), whole = it.drawing, concrete = it.drawing)
        }
        val stones = loadConcretes(concrete)
        if (stones.isEmpty()) println("case studies: no concrete at ${concrete.path}, drawings seen through their marks")
        val templates = loadMarkTemplates(marks)
        if (templates.isEmpty()) println("case studies: no marks at ${marks.path}")
        val pane = wall.x / 2.0
        left = PhotoMosaic(Rectangle(0.0, 0.0, pane, wall.y), templates,
            sweep = 0.0..0.5, instant = instant, dissolves = dissolves, seed = 101, concretes = stones)
        right = PhotoMosaic(Rectangle(pane, 0.0, pane, wall.y), templates,
            sweep = 0.5..1.0, instant = instant, dissolves = dissolves, seed = 202, concretes = stones)
        left.prepare(sources.indices)
        right.prepare(sources.indices)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (views.isEmpty()) return
        val px = Vector2(stage.width / wall.x, stage.height / wall.y)
        val from: Int
        val s: Double
        val next: Int?
        val opening: Double?
        if (cycle != null) {
            // On the wall's own clock: the first view builds, and then every view holds [cycle]
            // seconds and hands over to the next, the last back round to the first — a function
            // of the frame, so it scrubs and films like every backdrop.
            val t = stage.frame / frames(1.0).toDouble()
            val open = PhotoMosaic.OPENING
            val period = cycle + handoverLength
            if (t < open + cycle) {
                from = 0; s = 0.0; next = null; opening = t
            } else {
                val u = t - open - cycle
                val k = floor(u / period).toInt()
                val within = u - k * period
                if (within < handoverLength) {
                    from = k % views.size; next = (k + 1) % views.size; s = within
                } else {
                    from = (k + 1) % views.size; next = null; s = 0.0
                }
                opening = null
            }
        } else {
            val at = stage.position.coerceIn(0.0, (views.size - 1).toDouble())
            from = at.toInt()
            s = linear(at - from) * PhotoMosaic.CLICK          // seconds into the click, evenly
            next = (from + 1).takeIf { s > 0.0 && it < views.size }
            // The opening runs only on the first state, so stepping back finds it whole.
            opening = if (stage.step == 0 && stage.position == 0.0) stage.frame / frames(1.0).toDouble() else null
        }
        val zoom = 1.0 + DRIFT * breathe.at(stage.frame)
        val a = views[from]
        val b = next?.let { views[it] }
        val picture = { key: Int -> pictures[key] }

        drawer.isolated {
            drawer.scale(px.x, px.y)
            if (b == null) {
                left.draw(drawer, px, zoom, picture, a.left, opening = opening)
                right.draw(drawer, px, zoom, picture, a.right, opening = opening)
            } else {
                left.draw(drawer, px, zoom, picture, a.left, b.left, s)
                right.draw(drawer, px, zoom, picture, a.right, b.right, s)
            }

            // The label: the project's, changing half way through a handover into a new one.
            val middle = PhotoMosaic.REFORM + PhotoMosaic.HANDOVER / 2.0
            val words = if (opening != null) smoothstep((opening - PhotoMosaic.BUILD * 0.5) / WORDS_IN) else 1.0
            if (b != null && b.project != a.project) {
                val out = smoothstep(1.0 - (s - PhotoMosaic.REFORM) / (middle - PhotoMosaic.REFORM))
                val inn = smoothstep((s - middle) / (middle - PhotoMosaic.REFORM))
                label(drawer, projects[a.project], out)
                label(drawer, projects[b.project], inn)
            } else label(drawer, projects[a.project], words)
        }
    }

    /**
     * A project's lettering in the four corners of **both** projectors, the same on each: its name
     * top left in the bold with its client under it, what it is top right from the middle of the
     * pane on two lines, where it is bottom left and how long it took bottom right. The layout of
     * the blueprint sketch handed over on 22 September; it replaced a label in a hole in the grid.
     * A band of shade under the top and foot keeps the white type readable over a photograph.
     */
    private fun label(drawer: Drawer, project: CaseProject, shown: Double) {
        if (shown <= 0.0) return
        fun fact(key: String) = project.facts.firstOrNull { it.first == key }?.second
        val function = fact("Functie")?.split(" · ") ?: listOf(project.line).filter { it.isNotEmpty() }
        val client = fact("Opdrachtgever")
        val place = fact("Locatie")
        val time = fact("Realisatietijd")?.let { "Realisatie: $it" }
        val pane = wall.x / 2.0
        val size = wall.y * CORNER
        val lead = size * CORNER_LEAD
        val top = wall.y * CORNER_TOP
        val foot = wall.y * (1.0 - CORNER_FOOT)
        val rise = (1.0 - shown) * size * RISE
        drawer.stroke = null
        for (p in 0..1) {
            val left = p * pane + pane * CORNER_X
            val middle = p * pane + pane * CORNER_MIDDLE
            val room = pane * (1.0 - CORNER_MIDDLE - CORNER_X)
            fun fit(piece: String, face: FontImageMap, em: Double) =
                minOf(size, room / (face.advanceWithSubscripts(piece) / em).coerceAtLeast(1e-6))
            // The shade bands, fading down from the top and up from the foot.
            drawer.shadeStyle = null
            listOf(0.0 to 1.0, wall.y to -1.0).forEach { (edge, dir) ->
                val band = wall.y * SHADE_BAND
                for (i in 0 until 12) {
                    val t = i / 12.0
                    drawer.fill = ColorRGBa.BLACK.opacify(SHADE * (1.0 - t) * (1.0 - t) * shown)
                    val y = edge + dir * band * t
                    drawer.rectangle(Rectangle(p * pane, if (dir > 0) y else y - band / 12.0, pane, band / 12.0))
                }
            }
            drawer.fill = palette.ink.opacify(shown)
            drawer.setLine(project.name, bold, Vector2(left, top + rise), fit(project.name, bold, NAME_EM), NAME_EM)
            client?.let {
                drawer.fill = palette.ink.opacify(shown)
                drawer.setLine(it, text, Vector2(left, top + lead + rise), fit(it, text, TEXT_EM), TEXT_EM)
            }
            drawer.fill = palette.ink.opacify(shown)
            function.forEachIndexed { i, line ->
                drawer.setLine(line, text, Vector2(middle, top + i * lead + rise), fit(line, text, TEXT_EM), TEXT_EM)
            }
            place?.let { drawer.setLine(it, text, Vector2(left, foot - rise), fit(it, text, TEXT_EM), TEXT_EM) }
            time?.let { drawer.setLine(it, text, Vector2(middle, foot - rise), fit(it, text, TEXT_EM), TEXT_EM) }
        }
    }

    private companion object {
        /**
         * The corners, read off the sketch: type at a twenty-fifth of the height, the first
         * baseline a sixteenth down and the last one twenty-fourth up, set in from the pane's edge
         * a thirtieth of its width, the second column from just past its middle.
         */
        const val CORNER = 0.04
        const val CORNER_LEAD = 1.2
        const val CORNER_TOP = 0.075
        const val CORNER_FOOT = 0.045
        const val CORNER_X = 0.032
        const val CORNER_MIDDLE = 0.52
        /** The shade under the type: how dark at the edge, and how far in it fades. */
        const val SHADE = 0.8
        const val SHADE_BAND = 0.24

        const val WORDS_IN = 0.6
        const val DRIFT = 0.05
        const val DRIFT_PERIOD = 50.0

        /** Sizes as shares of the wall's height, and the ems the faces load at — the highlight's. */
        const val NAME = 0.046
        const val TEXT = 0.024
        const val NAME_EM = 64.0
        const val TEXT_EM = 64.0
        const val NAME_LEAD = 1.6
        const val ASCENT = 0.75
        const val DESCENT = 0.3
        const val RISE = 0.35

        val IMAGES = setOf("jpg", "jpeg", "png")
        val QUIET: ColorRGBa = ColorRGBa.fromHex("9A9A9A")
    }
}
