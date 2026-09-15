// ============================================================================ //
//  No `package` declaration: it stands on IsoPieces, loadObjMesh and the register
//  reader, all in the default package, which a named package cannot import from.
//  The folder is slide-drawers because that is where a slide's drawing lives.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import slideshow.drawers.wrapped
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** One element and what research took off it: the piece, the share of it saved, the figure said, what it is for, and how. */
class Reduction(
    val piece: String,
    val share: Double,
    val figure: String,
    val subject: String,
    val measures: List<String> = emptyList()
)

/**
 * The hidden story of an element: one catalogue piece in the round, its register beside it,
 * and a share of it turned red for the CO₂ that research took out of it.
 *
 *     0  the first piece, standing, with the three levers on leaders round it
 *     1  its saving: the lower share of it turns red from the foot up, the figure beside it
 *     2… the next piece for the next saving, each arriving as the last leaves
 *
 * **The red share is a cut, not a second mesh.** `IsoPieces` draws a piece in its tint and,
 * under a world-horizontal plane, in a second one; the plane stands at the share of the piece's
 * height, which for a prism is its share of the volume. It rises with the click.
 *
 * **The leaders end on the silhouette, not on the box.** A piece's any-angle box is a bound
 * and mostly air at the corners, so a leader drawn to it points at nothing. The piece's own
 * corners are projected to the screen every frame and the leaders go to the topmost, rightmost
 * and leftmost of them — on the piece, wherever it has turned to.
 *
 * **Between pieces the camera pulls back.** The leaving piece shrinks to nothing over the first
 * half of the click and the arriving one grows over the second, so the pane is never two pieces
 * at once and the change reads as a cut between subjects rather than a morph. The register
 * crossfades on the ladder's rule with it.
 *
 * The register — name, box in millimetres, IFC class — is read off `objects-115-details.csv`
 * by the piece's name; a piece the register does not name shows its name alone.
 */
class HiddenStory(
    private val title: String,
    private val objects: File,
    private val details: File? = null,
    /** The three levers named round the first piece. */
    private val levers: List<String>,
    private val reductions: List<Reduction>,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val lettering: ColorRGBa = ColorRGBa.WHITE,
    /** Seconds a piece takes to turn once. */
    private val spin: Double = 36.0,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.2),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Hidden story"
    override val steps get() = reductions.size + 1
    override fun stepName(step: Int): String? = reductions.getOrNull(step - 1)?.let { "${it.figure} ${it.subject}" }

    private val iso = IsoPieces(shade = 1.0)
    private var fitted: Map<String, IsoFitted> = emptyMap()
    private var register: List<PieceDetail> = emptyList()
    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        iso.load()
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        fitted = reductions.map { it.piece }.distinct().mapNotNull { name ->
            val file = File(objects, "$name.obj")
            val mesh = if (file.isFile) loadObjMesh(file) else null
            if (mesh == null) { println("hidden story: no mesh $file"); null } else name to iso.fit(mesh, WIDEST)
        }.toMap()
        register = details?.takeIf { it.isFile }?.let { loadPieceDetails(it) } ?: emptyList()
    }

    private fun pieceOf(state: Int) = if (state == 0) reductions.first().piece else reductions[state - 1].piece
    private fun shareOf(state: Int) = if (state == 0) 0.0 else reductions[state - 1].share

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        drawer.fill = lettering
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        if (reductions.isEmpty() || fitted.isEmpty()) return

        val p = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val a = floor(p).toInt()
        val b = min(a + 1, steps - 1)
        val t = p - a
        val out = (1.0 - t / FADE).coerceIn(0.0, 1.0)
        val back = ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0)
        val opening = stage.step == 0 && p < 1e-6
        val grown = if (opening) smoothstep(stage.since(0, stepFrames)) else 1.0

        val unit = h * UNIT
        val floor = -h * FLOOR
        val cx = w * CENTRE
        val turn = 2.0 * Math.PI * stage.frame / frames(spin)

        // Which piece stands, how big, and how much of it is red.
        class Standing(val name: String, val size: Double, val share: Double)
        val standing = mutableListOf<Standing>()
        if (pieceOf(a) == pieceOf(b)) {
            standing += Standing(pieceOf(a), grown, shareOf(a) + (shareOf(b) - shareOf(a)) * t)
        } else {
            val leaving = 1.0 - smoothstep((t / HALF).coerceIn(0.0, 1.0))
            val arriving = smoothstep(((t - HALF) / (1.0 - HALF)).coerceIn(0.0, 1.0))
            if (leaving > 0.0) standing += Standing(pieceOf(a), leaving, shareOf(a))
            if (arriving > 0.0) standing += Standing(pieceOf(b), arriving, shareOf(b) * arriving)
        }

        val placed = standing.mapNotNull { s ->
            val f = fitted[s.name] ?: return@mapNotNull null
            val size = unit * s.size
            if (size <= 0.5) return@mapNotNull null
            val stood = iso.standing(f, cx, floor, size, turn)
            val g = floor / iso.up.y
            val cut = if (s.share > 0.0) g + s.share * 2.0 * f.mesh.halfHeight * stood.scale else null
            IsoPlaced(stood.mesh, stood.centre, stood.scale, stood.angle, ink, cut = cut, cutTint = accent, casts = false)
        }
        iso.draw(drawer, w, h, placed, ink, background, background)

        // The piece's own corners on the pane, for the leaders to end on.
        val main = placed.lastOrNull() ?: return
        val corners = main.mesh.points.map { q ->
            val r = Vector3(q.x * cos(main.angle) + q.z * sin(main.angle), q.y, -q.x * sin(main.angle) + q.z * cos(main.angle))
            val world = main.centre + r * main.scale
            Vector2(w / 2.0 + world.dot(iso.right), h / 2.0 - world.dot(iso.up))
        }
        val topmost = corners.minBy { it.y }
        val leftmost = corners.minBy { it.x }
        val rightmost = corners.maxBy { it.x }

        // The register, crossfading when the piece changes.
        fun register(name: String, alpha: Double) {
            if (alpha <= 0.0) return
            val row = register.firstOrNull { it.name == name }
            drawer.fill = lettering.opacify(alpha)
            drawer.setLine(name, bold, Vector2(w * EDGE, h * REGISTER_Y), h * NAME, SIZE)
            if (row == null) return
            drawer.setLine(row.size(), text, Vector2(w * EDGE, h * (REGISTER_Y + LEAD * 1.3)), h * TEXT, SIZE)
            drawer.setLine(row.shortType(), text, Vector2(w * EDGE, h * (REGISTER_Y + LEAD * 2.3)), h * TEXT, SIZE)
        }
        if (pieceOf(a) == pieceOf(b)) register(pieceOf(a), 1.0)
        else { register(pieceOf(a), out); register(pieceOf(b), back) }

        val size = h * LABEL
        val gap = w * GAP
        fun leader(from: Vector2, to: Vector2, alpha: Double) {
            drawer.stroke = lettering.opacify(alpha)
            drawer.strokeWeight = LINE
            drawer.lineSegment(from, from + (to - from) * alpha)
            drawer.stroke = null
        }

        // The levers, round the first piece only.
        val leversAlpha = when {
            a == 0 && b == 0 -> grown
            a == 0 -> out * grown
            else -> 0.0
        }
        if (leversAlpha > 0.0 && levers.isNotEmpty()) {
            drawer.fill = lettering.opacify(leversAlpha)
            levers.getOrNull(0)?.let { l ->
                val at = Vector2(topmost.x, topmost.y - h * LEVER_UP)
                drawer.setLine(l, bold, Vector2(at.x, at.y - gap), size, SIZE, align = 0.5)
                leader(at, topmost, leversAlpha)
            }
            levers.getOrNull(1)?.let { l ->
                val at = Vector2(rightmost.x + w * LEVER_OUT, rightmost.y)
                drawer.setLine(l, bold, Vector2(at.x + gap, at.y + size * 0.34), size, SIZE, align = 0.0)
                leader(at, rightmost, leversAlpha)
            }
            levers.getOrNull(2)?.let { l ->
                val at = Vector2(leftmost.x - w * LEVER_OUT, leftmost.y)
                drawer.setLine(l, bold, Vector2(at.x - gap, at.y + size * 0.34), size, SIZE, align = 1.0)
                leader(at, leftmost, leversAlpha)
            }
        }

        // The saving: the figure beside the piece, what it is for under it, then how. The
        // block stands to the right and above the piece's far end, and its leader leaves the
        // subject line for whichever corner of the piece is nearest — down and to the left,
        // away from the lettering. Pointed at the rightmost corner it ran down through the
        // measures.
        fun saving(r: Reduction, alpha: Double) {
            if (alpha <= 0.0) return
            val x = w * SAVING_X
            val y = h * SAVING_Y
            drawer.fill = accent.opacify(alpha)
            drawer.setLine(r.figure, bold, Vector2(x, y), h * FIGURE, SIZE)
            drawer.fill = lettering.opacify(alpha)
            drawer.setLine(r.subject, bold, Vector2(x, y + h * LEAD * 1.4), size, SIZE)
            r.measures.forEachIndexed { i, m ->
                text.wrapped(m, (w * (1.0 - EDGE) - x) * SIZE / (h * TEXT)).forEachIndexed { j, line ->
                    drawer.setLine(line, text, Vector2(x, y + h * LEAD * (3.0 + i + j * 0.9)), h * TEXT, SIZE)
                }
            }
            val anchor = Vector2(x - gap, y + h * LEAD * 1.4 - size * 0.34)
            val nearest = corners.filter { it.x < anchor.x - gap }.minByOrNull { (it - anchor).length } ?: leftmost
            leader(anchor, nearest, alpha)
        }
        val ra = reductions.getOrNull(a - 1)
        val rb = reductions.getOrNull(b - 1)
        if (ra === rb) ra?.let { saving(it, 1.0) }
        else { ra?.let { saving(it, out) }; rb?.let { saving(it, back) } }
    }

    private companion object {
        const val SIZE = 200.0
        const val WIDEST = 1.7
        const val UNIT = 0.5
        const val FLOOR = 0.22
        const val CENTRE = -0.06

        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val EDGE = 0.02
        const val REGISTER_Y = 0.17
        const val NAME = 0.036
        const val TEXT = 0.025
        const val LEAD = 0.034
        const val LABEL = 0.028
        const val FIGURE = 0.075
        const val SAVING_X = 0.73
        const val SAVING_Y = 0.26
        const val LEVER_UP = 0.06
        const val LEVER_OUT = 0.05
        const val GAP = 0.008
        const val LINE = 2.0

        const val FADE = 0.3
        const val HALF = 0.5
    }
}
