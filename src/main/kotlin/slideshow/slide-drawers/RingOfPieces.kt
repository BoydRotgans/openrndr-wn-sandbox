// ============================================================================ //
//  No `package` declaration: it stands on IsoPieces and loadObjMeshes, in the
//  default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.setLine
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Circle, in elements: the whole catalogue placed along the mark's own path, one small
 * piece after another, each turning slowly on its own axis. No clicks — it lays itself down
 * on the slide's own frame count, a piece at a time along the path, and then keeps turning.
 *
 * **The path is a drawing, and until the drawing arrives it is a plain ring open at the top.**
 * [path] names an svg of the mark; its longest contour is resampled at even spacing — the
 * opening wall's rule for a pen at constant speed — and fitted to the pane. Without one, the
 * ring stands in as a placeholder and the load says so: the mark's own path is the one thing
 * this slide is waiting on.
 *
 * The pieces are placed on the pane, not on a floor: a point on the path is a screen position,
 * and a screen position is `right * x + up * y` in the iso camera's world, so the ring is drawn
 * flat on the wall while every piece on it is a solid in the round. Grey on black, three tones
 * keyed to the axes so the solids read with no shadow under them.
 */
class RingOfPieces(
    private val title: String = "The Circle, in elementen",
    private val objects: File,
    /** The mark, as an svg; null stands a plain open ring in its place. */
    private val path: File? = null,
    private val boldPath: String = "data/fonts/default.otf",
    private val ink: ColorRGBa = ColorRGBa.fromHex("8C8C8C"),
    private val lettering: ColorRGBa = ColorRGBa.WHITE,
    /** Seconds between one piece landing and the next, and seconds a piece takes to land. */
    private val cadence: Double = 0.07,
    private val landing: Double = 0.6,
    /** Seconds a piece takes to turn once. */
    private val spin: Double = 30.0,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null
) : Slide() {

    override val name = "Ring"
    override val steps get() = 1
    override val settle get() = frames(cadence) * (fitted.size.coerceAtLeast(1) - 1) + frames(landing)

    private val iso = IsoPieces(shade = 1.0)
    private var fitted: List<IsoFitted> = emptyList()
    /** The path's points in a unit box, 0..1 both ways, y down. */
    private var points: List<Vector2> = emptyList()
    private var pathBox = Rectangle(0.0, 0.0, 1.0, 1.0)
    private lateinit var bold: FontImageMap

    override fun load(program: Program) {
        iso.load()
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        fitted = loadObjMeshes(objects).map { iso.fit(it, WIDEST) }
        val n = fitted.size.coerceAtLeast(2)

        val drawn = path?.takeIf { it.isFile }?.let { file ->
            val contour = loadSVG(file).findShapes().flatMap { it.effectiveShape.contours }.maxByOrNull { it.length }
            contour?.let { c ->
                println("ring: ${fitted.size} pieces along ${file.path}")
                c.equidistantPositions(n)
            }
        }
        points = drawn ?: run {
            println("ring: no mark at ${path?.path ?: "(none)"} — a plain ring open at the top stands in; " +
                    "the WN mark as svg is still to come")
            // Open at the top by GAP either side, running clockwise from the left lip round to the right.
            val from = -PI / 2.0 + Math.toRadians(GAP_DEG)
            val to = -PI / 2.0 - Math.toRadians(GAP_DEG) + 2.0 * PI
            List(n) { i -> val a = from + (to - from) * i / (n - 1); Vector2(0.5 + 0.5 * cos(a), 0.5 + 0.5 * sin(a)) }
        }
        val x0 = points.minOf { it.x }; val x1 = points.maxOf { it.x }
        val y0 = points.minOf { it.y }; val y1 = points.maxOf { it.y }
        pathBox = Rectangle(x0, y0, (x1 - x0).coerceAtLeast(1e-6), (y1 - y0).coerceAtLeast(1e-6))
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        drawer.fill = lettering
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        if (fitted.isEmpty() || points.isEmpty()) return

        // The path fitted into the pane under the title, its proportions kept.
        val room = Rectangle(w * MARGIN, h * TOP, w * (1.0 - 2.0 * MARGIN), h * (BOTTOM - TOP))
        val scale = minOf(room.width / pathBox.width, room.height / pathBox.height)
        val span = Vector2(pathBox.width, pathBox.height) * scale
        val corner = room.center - span / 2.0

        val unit = h * UNIT
        val turn = 2.0 * PI * stage.frame / frames(spin)
        val placed = fitted.mapIndexedNotNull { i, f ->
            val landed = smoothstep(stage.since(i * frames(cadence), frames(landing)))
            if (landed <= 0.0) return@mapIndexedNotNull null
            val q = points[i % points.size]
            // pane pixels, then the wall's centred frame: x right, y up
            val px = corner.x + (q.x - pathBox.x) * scale
            val py = corner.y + (q.y - pathBox.y) * scale - h * DROP * (1.0 - landed)
            val sx = px - w / 2.0
            val sy = h / 2.0 - py
            val size = unit * landed
            val angle = turn + 2.0 * PI * ((i * 0.6180339887498949) % 1.0)
            IsoPlaced(f.mesh, iso.right * sx + iso.up * sy, f.scale * size, angle, ink, casts = false)
        }
        iso.draw(drawer, w, h, placed, ink, background, background)
    }

    private companion object {
        const val SIZE = 200.0
        const val WIDEST = 2.4
        const val UNIT = 0.075
        const val DROP = 0.05
        const val GAP_DEG = 26.0
        const val MARGIN = 0.16
        const val TOP = 0.15
        const val BOTTOM = 0.95
        const val TITLE = 0.036
        const val TITLE_Y = 0.06
    }
}
