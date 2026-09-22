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
import slideshow.Leader
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
 * **The leaders end on the spin axis, and the labels stand off the any-angle reach.** Both are
 * constants of the piece, so nothing written about it moves while it turns. They were read off
 * its turning corners before, and walked with them.
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
        surfaces = fitted.mapValues { (_, f) -> f.mesh.surface }
    }

    /** Each piece's triangles in its own normalised frame, for finding the solid under a leader. */
    private var surfaces: Map<String, List<Vector3>> = emptyMap()

    /**
     * Every distance along the line [o] + t·[d] at which it crosses one of [tris], sorted and with
     * the crossings on a shared edge counted once — taken in pairs, in and out, they are the
     * spans of the line that lie inside the solid.
     */
    private fun spans(tris: List<Vector3>, o: Vector3, d: Vector3): List<ClosedFloatingPointRange<Double>> {
        val hits = mutableListOf<Double>()
        for (i in 0 until tris.size - 2 step 3) {
            val a = tris[i]; val e1 = tris[i + 1] - a; val e2 = tris[i + 2] - a
            val pv = d.cross(e2)
            val det = e1.dot(pv)
            if (kotlin.math.abs(det) < 1e-12) continue
            val tv = o - a
            val u = tv.dot(pv) / det
            if (u < -1e-9 || u > 1.0 + 1e-9) continue
            val qv = tv.cross(e1)
            val v = d.dot(qv) / det
            if (v < -1e-9 || u + v > 1.0 + 1e-9) continue
            hits += e2.dot(qv) / det
        }
        val crossings = hits.sorted().fold(mutableListOf<Double>()) { out, t ->
            if (out.isEmpty() || t - out.last() > 1e-6) out += t; out
        }
        return crossings.chunked(2).filter { it.size == 2 }.map { it[0]..it[1] }
    }

    /**
     * Where on the piece a leader asked to end at [height] (mesh units, from its centre) ends, in
     * the piece's own frame. On the spin axis when the axis is inside the piece there — the one
     * point that stays put as it turns. Where it is not, as in `WAND_27`'s doorway, the nearest
     * solid along either of the piece's horizontal axes at that height: a point on the jamb,
     * close to the axis, which comes round smoothly with the piece rather than jumping.
     * Given [toEye], the point is carried out to the near face first.
     */
    private fun leaderEnd(tris: List<Vector3>, height: Double, toEye: Vector3? = null): Vector3 {
        val here = Vector3(0.0, height, 0.0)
        // Asked for the face the camera sees: from the axis toward the eye to where the piece is
        // left — so the saving's leader lands on the red edge on the near face rather than on a
        // point inside the piece, which projects onto the blue above it.
        if (toEye != null && tris.isNotEmpty()) {
            val front = spans(tris, here, toEye).maxOfOrNull { it.endInclusive }
            if (front != null && front >= 0.0) return here + toEye * front
        }
        if (tris.isEmpty() || spans(tris, Vector3.ZERO, Vector3.UNIT_Y).any { height in it }) return here
        val sideways = listOf(Vector3.UNIT_X, Vector3.UNIT_Z).flatMap { axis ->
            spans(tris, here, axis).map { r ->
                val t = if (0.0 in r) 0.0 else if (kotlin.math.abs(r.start) < kotlin.math.abs(r.endInclusive)) r.start else r.endInclusive
                here + axis * t
            }
        }
        return sideways.minByOrNull { (it - here).length } ?: here
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

        // Where the leaders end and the labels stand, **none of it read off the turning piece**.
        // They ended on its topmost, rightmost and leftmost corner once, and as it turned a
        // different corner became each of those, so the labels walked and the lines jumped from
        // corner to corner (review of 22 September). A leader now ends on the spin axis, which
        // stays put however the piece turns, and a label stands just clear of the piece's
        // any-angle reach, which is a constant.
        val main = placed.lastOrNull() ?: return
        fun project(world: Vector3) = Vector2(w / 2.0 + world.dot(iso.right), h / 2.0 - world.dot(iso.up))
        val reach = main.mesh.spinRadius * main.scale
        val half = main.mesh.halfHeight * main.scale
        val middle = project(main.centre)
        val topReach = middle.y - (half * cos(iso.pitch) + reach * sin(iso.pitch))
        val tris = surfaces[main.mesh.name] ?: emptyList()
        // The camera's direction across the ground, in the piece's own frame as it now stands.
        val eyeWorld = Vector3(iso.eye.x, 0.0, iso.eye.z).normalized
        val toEye = Vector3(eyeWorld.x * cos(main.angle) - eyeWorld.z * sin(main.angle), 0.0,
            eyeWorld.x * sin(main.angle) + eyeWorld.z * cos(main.angle))
        fun onPiece(height: Double, facing: Boolean = false): Vector2 {
            val q = leaderEnd(tris, height / main.scale, if (facing) toEye else null)
            val r = Vector3(q.x * cos(main.angle) + q.z * sin(main.angle), q.y, -q.x * sin(main.angle) + q.z * cos(main.angle))
            return project(main.centre + r * main.scale)
        }
        val topmost = onPiece(half)
        val rightmost = onPiece(half * SIDE_HIGH)
        val leftmost = onPiece(-half * SIDE_LOW)

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
        fun leader(from: Vector2, to: Vector2, alpha: Double) = Leader.draw(drawer, from, to, alpha, lettering.opacify(alpha))

        // The levers, round the first piece only.
        val leversAlpha = when {
            a == 0 && b == 0 -> grown
            a == 0 -> out * grown
            else -> 0.0
        }
        if (leversAlpha > 0.0 && levers.isNotEmpty()) {
            drawer.fill = lettering.opacify(leversAlpha)
            levers.getOrNull(0)?.let { l ->
                val at = Vector2(topmost.x, topReach - h * LEVER_UP)
                drawer.setLine(l, bold, Vector2(at.x, at.y - gap), size, SIZE, align = 0.5)
                leader(at, topmost, leversAlpha)
            }
            levers.getOrNull(1)?.let { l ->
                val at = Vector2(middle.x + reach + w * LEVER_OUT, rightmost.y)
                drawer.setLine(l, bold, Vector2(at.x + gap, at.y + size * 0.34), size, SIZE, align = 0.0)
                leader(at, rightmost, leversAlpha)
            }
            levers.getOrNull(2)?.let { l ->
                val at = Vector2(middle.x - reach - w * LEVER_OUT, leftmost.y)
                drawer.setLine(l, bold, Vector2(at.x - gap, at.y + size * 0.34), size, SIZE, align = 1.0)
                leader(at, leftmost, leversAlpha)
            }
        }

        // The saving: the figure beside the piece, what it is for under it, then how. The
        // block stands to the right and above the piece's far end, and its leader leaves the
        // subject line for **the red level itself**, on the spin axis: the height where the red
        // begins, at the one point of the piece that does not move as it turns. It went to the
        // corner nearest the lettering before, and that corner changed as the piece came round.
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
            val level = main.cut
            val target = if (level != null) onPiece(level - main.centre.y, facing = true) else middle
            leader(anchor, target, alpha)
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
        /** Where on the axis the side levers' leaders end, as a share of the half height. */
        const val SIDE_HIGH = 0.35
        const val SIDE_LOW = 0.35
        const val GAP = 0.008
        const val LINE = 2.0

        const val FADE = 0.3
        const val HALF = 0.5
    }
}
