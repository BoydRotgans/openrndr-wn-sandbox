// ============================================================================ //
//  No `package` declaration: it stands on IsoPieces and loadObjMesh, in the
//  default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.ShapeContour
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
import kotlin.random.Random

/**
 * One element's second life, across the pane: a panel stands, the floor it stood on lies
 * beside it, the floor is broken, the rubble is sieved to grit, and a new panel stands at the
 * end. Five states, four clicks.
 *
 *     0  the panel, standing
 *     1  the floor slab lies down beside it
 *     2  the panel goes dark and the slab breaks into shards
 *     3  the panel goes, the shards sieve down to rubble and a cloud of grit lies to the right
 *     4  a new panel, white, stands at the far right
 *
 * **The stages stand in columns and the camera holds the middle of what is up.** The slab and
 * the shards are one column — the slab breaks where it lies — so four columns carry five
 * stages, and the camera's centre is a piecewise-linear function of `position` over the
 * columns each state shows: everything already down travels left as the next stage arrives,
 * and the story reads left to right.
 *
 * **The shatter is a Voronoi of the slab's plan**, seeded, clipped cell by cell against the
 * bisectors of every other seed — twenty-odd cells, so the quadratic clip costs nothing — and
 * each shard is scattered a little from where it broke. The shards are flat polygons on the
 * ground plane in the same isometric view the pieces stand in; the sieving shrinks each one
 * about its own middle, and the grit is a seeded scatter of points on the plane beside them.
 *
 * Everything is a function of `stage.position` and `stage.frame`; the seed makes the same frame
 * draw the same rubble.
 */
class SecondLife(
    private val title: String = "Recyclage",
    private val objects: File,
    private val panel: String = "WAND_27",
    private val slab: String = "PREDAL",
    /** A caption a stage, "" for none. */
    private val captions: List<String> = listOf("", "", "Puin ter plekke gebroken", "Gezeefd", "Hergebruikt"),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    private val dark: ColorRGBa = ColorRGBa.fromHex("2E2E2E"),
    private val seed: Int = 7,
    private val shards: Int = 26,
    private val grit: Int = 260,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.2),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Second life"
    override val steps get() = 5
    override fun stepName(step: Int): String? = when (step) {
        1 -> "the floor"; 2 -> "broken"; 3 -> "sieved"; 4 -> "reused"; else -> null
    }

    private val iso = IsoPieces(shade = 1.0)
    private var panelFit: IsoFitted? = null
    private var slabFit: IsoFitted? = null
    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    /** A shard: its polygon about its own middle, in the slab's normalised units; its middle; where it scatters to and how it turns. */
    private class Shard(val ring: List<Vector2>, val middle: Vector2, val throwTo: Vector2, val spin: Double)
    private var cells: List<Shard> = emptyList()
    private var slabTop = 0.0
    private var dust: List<Vector2> = emptyList()
    private var dustWhen: List<Double> = emptyList()

    override fun load(program: Program) {
        iso.load()
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        fun mesh(name: String) = File(objects, "$name.obj").takeIf { it.isFile }?.let { loadObjMesh(it) }
            ?: run { println("second life: no mesh $name in $objects"); null }
        panelFit = mesh(panel)?.let { iso.fit(it, WIDEST) }
        slabFit = mesh(slab)?.let { iso.fit(it, WIDEST) }

        val random = Random(seed)
        slabFit?.let { f ->
            val pts = f.mesh.points
            val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }
            val z0 = pts.minOf { it.z }; val z1 = pts.maxOf { it.z }
            slabTop = pts.maxOf { it.y }
            val seeds = List(shards) { Vector2(x0 + random.nextDouble() * (x1 - x0), z0 + random.nextDouble() * (z1 - z0)) }
            cells = seeds.mapIndexed { i, s ->
                var poly = listOf(Vector2(x0, z0), Vector2(x1, z0), Vector2(x1, z1), Vector2(x0, z1))
                seeds.forEachIndexed { j, o -> if (j != i) poly = clip(poly, (s + o) / 2.0, o - s) }
                val middle = poly.fold(Vector2.ZERO) { acc, v -> acc + v } / poly.size.toDouble()
                val dir = (middle - Vector2((x0 + x1) / 2.0, (z0 + z1) / 2.0)).let { if (it.length > 1e-9) it.normalized else Vector2.ZERO }
                Shard(poly.map { it - middle }, middle, dir * (THROW_MIN + random.nextDouble() * THROW_SPREAD), (random.nextDouble() - 0.5) * TURN)
            }
        }
        dust = List(grit) { Vector2(gauss(random), gauss(random)) }
        dustWhen = List(grit) { random.nextDouble() }
    }

    private fun gauss(r: Random): Double {
        val u = r.nextDouble().coerceAtLeast(1e-9)
        val v = r.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
    }

    /** [poly] kept to the side of the line through [m] facing away from [d]: Sutherland–Hodgman against one half-plane. */
    private fun clip(poly: List<Vector2>, m: Vector2, d: Vector2): List<Vector2> {
        if (poly.isEmpty()) return poly
        val out = mutableListOf<Vector2>()
        fun inside(p: Vector2) = (p - m).dot(d) <= 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val pi = inside(p); val qi = inside(q)
            if (pi) out += p
            if (pi != qi) {
                val tp = (p - m).dot(d); val tq = (q - m).dot(d)
                val t = tp / (tp - tq)
                out += p + (q - p) * t
            }
        }
        return out
    }

    /** Which column stage [k] stands in: the slab breaks where it lies. */
    private fun columnOf(k: Int) = when (k) { 0 -> 0; 1, 2 -> 1; 3 -> 2; else -> 3 }

    /** The camera's centre in columns for a continuous [position]: the middle of what each state shows. */
    private fun centreAt(position: Double): Double {
        val at = doubleArrayOf(0.0, 0.5, 0.5, 1.5, 2.0)
        val a = position.toInt().coerceIn(0, at.size - 1)
        val b = (a + 1).coerceAtMost(at.size - 1)
        val t = position - a
        return at[a] + (at[b] - at[a]) * t
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        drawer.fill = paper
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        val panelF = panelFit ?: return
        val slabF = slabFit ?: return

        val p = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val opening = stage.step == 0 && p < 1e-6
        val grown = if (opening) smoothstep(stage.since(0, stepFrames)) else 1.0
        val pitch = w * PITCH
        val unit = h * UNIT
        val floor = -h * FLOOR
        val g = floor / iso.up.y
        fun columnX(c: Int) = (c - centreAt(p)) * pitch
        val turn = 2.0 * PI * stage.frame / frames(SPIN)

        val laid = stage.on(1)
        val broken = stage.on(2)
        val sieved = stage.on(3)
        val reused = stage.on(4)

        val placed = mutableListOf<IsoPlaced>()

        // The first panel: standing, then dark, then gone.
        if (sieved < 1.0) {
            val stood = iso.standing(panelF, columnX(0), floor, unit * grown, turn)
            val tint = ink.mix(dark, broken).mix(background, sieved)
            if (grown > 0.0) placed += IsoPlaced(stood.mesh, stood.centre, stood.scale, stood.angle, tint, casts = false)
        }
        // The slab, lying flat, until it has broken.
        val slabScale = slabF.scale * unit
        if (laid > 0.0 && broken < 0.5) {
            val size = unit * smoothstep(laid)
            val stood = iso.standing(slabF, columnX(1), floor, size, 0.0)
            placed += IsoPlaced(stood.mesh, stood.centre, stood.scale, 0.0, accent.mix(background, (broken / 0.5).coerceIn(0.0, 1.0)), casts = false)
        }
        // The new panel, white, growing from the floor.
        if (reused > 0.0) {
            val stood = iso.standing(panelF, columnX(3), floor, unit * smoothstep(reused), turn)
            placed += IsoPlaced(stood.mesh, stood.centre, stood.scale, stood.angle, paper, casts = false)
        }
        iso.draw(drawer, w, h, placed, ink, background, background)

        // The shards and the grit, on the ground plane in the same view.
        if (broken > 0.0) iso.view(drawer, w, h) {
            // The view leaves the pieces' shade style on the drawer, and a flat polygon drawn
            // through it takes whatever tint was last set — the dark panel's, on the first
            // still. Plain fills here.
            drawer.shadeStyle = null
            // **No depth test.** The shards lie flat on the ground, at nearly the depth of what
            // the pieces left there, and tested against it they came out striped with black
            // wherever the two fought for a pixel. Nothing ever stands in front of them — the
            // panels are columns away — so they are simply painted over it, white on white
            // where they lap, which needs no order.
            drawer.depthWrite = false
            drawer.depthTestPass = DepthTestPass.ALWAYS
            drawer.stroke = null
            val base = iso.right * columnX(1) + Vector3.UNIT_Y * (g + slabTop * slabScale + 0.5)
            val alpha = (broken / 0.5).coerceIn(0.0, 1.0)
            val shrink = 1.0 - SIEVE * smoothstep(sieved)
            cells.forEach { c ->
                val thrown = c.middle + c.throwTo * smoothstep(broken)
                drawer.isolated {
                    drawer.translate(base + Vector3(thrown.x * slabScale, 0.0, thrown.y * slabScale))
                    drawer.rotate(Vector3.UNIT_Y, Math.toDegrees(c.spin * smoothstep(broken)))
                    drawer.scale(slabScale * shrink)
                    drawer.rotate(Vector3.UNIT_X, -90.0)
                    drawer.fill = paper.opacify(alpha)
                    // A black edge a shard, so two that land across each other stay two pieces
                    // rather than merging into one white shape. Divided back out of the scale,
                    // so it is the same hairline on every shard.
                    drawer.stroke = background.opacify(alpha)
                    drawer.strokeWeight = EDGE / (slabScale * shrink)
                    drawer.contour(ShapeContour.fromPoints(c.ring.map { Vector2(it.x, -it.y) }, closed = true))
                }
            }
            if (sieved > 0.0) {
                val at = iso.right * columnX(2) + Vector3.UNIT_Y * (g + 0.5)
                dust.forEachIndexed { i, d ->
                    val shown = ((sieved - dustWhen[i] * 0.6) / 0.4).coerceIn(0.0, 1.0)
                    if (shown <= 0.0) return@forEachIndexed
                    drawer.isolated {
                        drawer.translate(at + Vector3(d.x * w * GRIT_X, 0.0, d.y * w * GRIT_Z))
                        drawer.rotate(Vector3.UNIT_X, -90.0)
                        drawer.fill = paper.opacify(shown)
                        val s = GRAIN * shown
                        drawer.contour(ShapeContour.fromPoints(listOf(Vector2(-s, -s), Vector2(s, -s), Vector2(s, s), Vector2(-s, s)), closed = true))
                    }
                }
            }
        }

        // The captions, under their columns.
        val shown = listOf(grown, laid, broken, sieved, reused)
        captions.forEachIndexed { k, caption ->
            if (caption.isEmpty() || shown.getOrElse(k) { 0.0 } <= 0.0) return@forEachIndexed
            val alpha = shown[k]
            drawer.fill = paper.opacify(alpha)
            drawer.setLine(caption, text, Vector2(w / 2.0 + columnX(columnOf(k)), h * CAPTION_Y), h * TEXT, SIZE, align = 0.5)
        }
    }

    private companion object {
        const val SIZE = 200.0
        const val WIDEST = 1.3
        const val UNIT = 0.42
        const val FLOOR = 0.2
        const val PITCH = 0.3
        const val SPIN = 40.0
        const val SIEVE = 0.7
        /** How far a shard is thrown from where it broke, in the slab's own units, how much that varies, and how far it may turn, in radians. */
        const val THROW_MIN = 0.08
        const val THROW_SPREAD = 0.18
        const val TURN = 1.4
        /** The black edge round a shard, in pixels. */
        const val EDGE = 3.0
        const val GRIT_X = 0.06
        const val GRIT_Z = 0.045
        const val GRAIN = 2.2

        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val TEXT = 0.026
        const val CAPTION_Y = 0.86
    }
}
