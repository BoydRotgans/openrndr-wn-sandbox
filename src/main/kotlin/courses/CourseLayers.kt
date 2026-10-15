import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BlendMode
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.buildTransform
import org.lwjgl.opengl.GL33C
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix

// sketch-variant-of: CourseClimb
/**
 * v2, the climb in layers: the climb's panels laid flat and built up storey on storey, seen from high
 * above, after a sketch of wall panels turned a quarter with a red layer over a blue one.
 *
 * **The climb's elements and the climb's drawing**: the same catalogue panels in 3D at their own
 * thickness, flat red and blue faces with a thin white line on every real edge, on black — only lying
 * flat rather than standing.
 *
 * **Up to four kinds of panel fill a grid turned 45°**, so no ground shows: one connected field. A square
 * always holds the same kind the same way round, so every storey is the same grid, lying exactly over the
 * one below. **Rows are red and blue by turns, and the storey below the other way round**, so through
 * every window and doorway the panel under shows in the other colour, a storey lower and so a little
 * further down the frame. `LAYERS_COLOUR=storeys` makes a whole storey one colour instead.
 *
 * **Nothing fades.** A piece is lowered in from above the frame, left to right across the wall over most
 * of a storey's time, and slows to land, so the new storey's colours sweep across the wall; the camera
 * rises with the build, and the storey two down dims slowly away underneath, piece by piece in the same
 * order — the one fade here, asked for: it is only ever seen through two layers of openings.
 * Everything is a function of the clock.
 *
 *     ./gradlew run -Popenrndr.application=CourseLayersKt
 *
 * `LAYERS_*` in `.env` steers it.
 */
fun main() = runCourse("course-v2-layers", preview = 40.0) { layersCourse() }

fun Program.layersCourse(): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["LAYERS_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default

    // The kinds: each laid flat, its face across x (the length) and z, its thickness up y at its own
    // proportion, centred, and brought to a unit length and a unit width so it fills the cell it is
    // scaled to.
    class Kind(val name: String, val mesh: ObjMesh, val lines: org.openrndr.draw.VertexBuffer, val lay: Matrix44, val aspect: Double)
    val kinds = (key("PIECES") ?: "WAND_27,WAND_17,WAND_23,WAND_9").split(",").map { it.trim() }
        .filter { it.isNotEmpty() }.take(4).mapNotNull { name ->
            val mesh = loadObjMesh(File("data/objects/$name.obj")) ?: return@mapNotNull null.also { println("layers: no data/objects/$name.obj") }
            val lo = Vector3(mesh.points.minOf { it.x }, mesh.points.minOf { it.y }, mesh.points.minOf { it.z })
            val hi = Vector3(mesh.points.maxOf { it.x }, mesh.points.maxOf { it.y }, mesh.points.maxOf { it.z })
            val e = doubleArrayOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
            val c = doubleArrayOf((hi.x + lo.x) / 2.0, (hi.y + lo.y) / 2.0, (hi.z + lo.z) / 2.0)
            val thin = (0..2).minBy { e[it] }
            val (long, wide) = (0..2).filter { it != thin }.sortedByDescending { e[it] }
            fun column(axis: Int) = Vector4(
                if (axis == long) 1.0 / e[long] else 0.0,
                if (axis == thin) 1.0 / e[long] else 0.0,
                if (axis == wide) 1.0 / e[wide] else 0.0,
                0.0
            )
            val lay = Matrix44.fromColumnVectors(column(0), column(1), column(2),
                Vector4(-c[long] / e[long], -c[thin] / e[long], -c[wide] / e[wide], 1.0))
            Kind(name, mesh, creaseLines(File("data/objects/$name.obj")), lay, e[long] / e[wide])
        }
    require(kinds.isNotEmpty()) { "layers: none of LAYERS_PIECES could be loaded" }
    // One cell for every kind, at their average proportion: each is stretched to it by a few percent at
    // most when the kinds are near one proportion, which is what the default four are (1.69 to 1.86).
    val aspect = kinds.map { it.aspect }.average()
    val cellLong = 1.0
    val cellShort = 1.0 / aspect
    val joint = number("JOINT", 0.02)
    println("layers: ${kinds.joinToString { "${it.name} ${"%.2f".format(it.aspect)}" }}, cell ${"%.2f".format(aspect)}:1")

    val red = ColorRGBa.fromHex(key("RED") ?: slideshow.Brand.redHex)
    val blue = ColorRGBa.fromHex(key("BLUE") ?: slideshow.Brand.blueHex)
    val paper = ColorRGBa.fromHex(key("PAPER") ?: "000000")
    val ink = ColorRGBa.fromHex(key("INK") ?: "FFFFFF")
    val line = number("LINE", 1.1)
    val soft = number("SOFT", 0.6)
    // Rows red and blue by turns, the other way round on the storey below; or `storeys`, a storey one colour.
    val byRows = (key("COLOUR") ?: "rows") == "rows"

    val levelTime = number("LEVEL_TIME", 120.0)
    val sweep = number("SWEEP", 0.9).coerceIn(0.1, 1.0)
    val arrive = number("ARRIVE", 3.5)
    // Seconds the storey two down takes to dim away under the new one, piece by piece in the same order.
    val fadeOut = number("FADE_OUT", 15.0).coerceAtLeast(0.1)
    val landing = slideshow.CubicBezier(0.25, 0.5, 0.3, 1.0)
    val frame = number("FRAME", 1.5)
    val seed = key("SEED")?.toIntOrNull() ?: 7
    // A storey's height, in panel lengths: the gap that lets the storey under show through an opening.
    val storey = number("STOREY", 0.5)
    val yaw = Math.toRadians(number("YAW", 45.0))
    val pitch = Math.toRadians(number("PITCH", 60.0).coerceIn(5.0, 85.0))
    val eye = Vector3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
    val up = Vector3(-sin(yaw) * sin(pitch), cos(pitch), -cos(yaw) * sin(pitch))
    // The view with its eye on the origin: where a point on the ground lands on the frame. A point a
    // height `h` above the camera's own lands `h * cos(pitch)` further up, since `up` climbs that much.
    val baseView = lookAtMatrix(eye * 20.0, Vector3.ZERO, up)
    fun onFrame(x: Double, z: Double) = (baseView * Vector4(x, 0.0, z, 1.0)).let { Vector2(it.x, it.y) }

    class Piece(val i: Int, val j: Int, val kind: Kind, val flipX: Boolean, val flipZ: Boolean, val screen: Vector2, var order: Double = 0.0)
    fun cellOf(i: Int, j: Int): Piece {
        val r = kotlin.random.Random(i * 73856093 xor j * 19349663 xor seed)
        return Piece(i, j, kinds[r.nextInt(kinds.size)], r.nextBoolean(), r.nextBoolean(), onFrame(i * cellLong, j * cellShort))
    }
    // How far a piece reaches across the frame from its middle, at most.
    val reach = 0.5 * sqrt(cellLong * cellLong + cellShort * cellShort)

    // A storey: every cell of the grid that can reach the frame, the storeys below included, in order
    // left to right, a little shuffled, which is the order its pieces come in and later go out. Every
    // storey is the same grid.
    val storeys = HashMap<Int, List<Piece>>()
    fun storeyOf(level: Int, halfW: Double, halfH: Double): List<Piece> = storeys.getOrPut(level) {
        val r = kotlin.random.Random(seed * 1000003 + level * 7919)
        val span = (halfW + halfH) / sin(pitch) + 2.0 * reach + 3.0 * storey
        val pieces = mutableListOf<Piece>()
        for (i in floor(-span / cellLong).toInt() - 1..floor(span / cellLong).toInt() + 1)
            for (j in floor(-span / cellShort).toInt() - 1..floor(span / cellShort).toInt() + 1) {
                val at = onFrame(i * cellLong, j * cellShort)
                // A storey stands at most three storeys under the camera, so it reaches that much lower.
                if (abs(at.x) < halfW + reach && at.y > -halfH - reach && at.y < halfH + reach + 3.0 * storey * cos(pitch)) pieces += cellOf(i, j)
            }
        val jitter = pieces.associateWith { r.nextDouble() * 0.6 }
        val sorted = pieces.sortedBy { it.screen.x + jitter.getValue(it) }
        sorted.forEachIndexed { n, p -> p.order = if (sorted.size > 1) n.toDouble() / (sorted.size - 1) else 0.0 }
        if (storeys.size > 64) storeys.keys.filter { it < level - 8 }.forEach { storeys.remove(it) }
        sorted
    }

    // The climb's drawing: one flat colour a piece and a line on every real edge. The edges are drawn as
    // lines, depth-tested and a hair toward the camera (`EDGES=lines`, the default): painted inside the
    // faces instead (`EDGES=faces`), they break into dashes on the faces seen nearly edge on.
    val edgeLines = (key("EDGES") ?: "lines") == "lines"
    val lineStyle = shadeStyle { fragmentTransform = "x_fill = vec4(mix(p_paper.rgb, p_ink.rgb, p_presence), 1.0);" }
    val nudges = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)
    val dim = DimWave("LAYERS")
    val style = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;\n${DimWave.GLSL}"
        fragmentTransform = """
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float l = p_width <= 0.0 ? 0.0 : 1.0 - smoothstep(p_width - p_soft, p_width + p_soft, d);
            // A leaving piece dims into the ground, which is all there is behind the bottom storey.
            x_fill = vec4(mix(p_paper.rgb, mix(dimWave(p_colour.rgb), p_ink.rgb, l), p_presence), 1.0);
        """
    }

    return { drawer: Drawer, time: Double ->
        drawer.clear(paper)
        val halfH = frame
        val halfW = frame * drawer.width.toDouble() / drawer.height
        val progress = time / levelTime
        val current = floor(progress).toInt()
        // The camera rises with the build, aimed at the storey going up.
        val camera = progress * storey
        val climb = cos(pitch)
        drawer.isolated {
            drawer.projection = orthoMatrix(-halfW, halfW, -halfH, halfH, -200.0, 200.0)
            drawer.view = lookAtMatrix(Vector3(0.0, camera, 0.0) + eye * 20.0, Vector3(0.0, camera, 0.0), up)
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            style.parameter("ink", ink)
            style.parameter("width", if (edgeLines) 0.0 else line)
            style.parameter("soft", soft)
            style.parameter("paper", paper)
            dim.apply(style, time, drawer.height.toDouble())
            lineStyle.parameter("ink", ink)
            lineStyle.parameter("paper", paper)
            val projection = drawer.projection

            // Three storeys, and the one under them while its last pieces finish dimming away.
            for (level in current - 3..current) {
                val y = level * storey
                for (p in storeyOf(level, halfW, halfH)) {
                    // Its turn this storey to come in, and two storeys on, to go out.
                    val inAt = (level + p.order * sweep) * levelTime
                    val outAt = inAt + 2.0 * levelTime
                    if (time < inAt || time >= outAt + fadeOut) continue
                    // Where its middle stands on the frame once landed, and how high it has to be lifted
                    // to start just above the frame.
                    val restY = p.screen.y + (y - camera) * climb
                    val above = (halfH + reach - restY).coerceAtLeast(2.0 * reach) / climb
                    val raise = if (time < inAt + arrive) (1.0 - landing((time - inAt) / arrive)) * above else 0.0
                    val presence = if (time < outAt) 1.0
                        else ((time - outAt) / fadeOut).coerceIn(0.0, 1.0).let { 1.0 - it * it * (3.0 - 2.0 * it) }
                    style.parameter("presence", presence)
                    lineStyle.parameter("presence", presence)
                    style.parameter("colour", if ((level + if (byRows) p.j else 0).mod(2) == 0) red else blue)
                    drawer.model = buildTransform {
                        translate(p.i * cellLong, y + raise, p.j * cellShort)
                        scale((cellLong - joint) * (if (p.flipX) -1.0 else 1.0), 1.0, (cellShort - joint) * (if (p.flipZ) -1.0 else 1.0))
                    } * p.kind.lay
                    drawer.shadeStyle = style
                    // The faces pushed back by how steeply each is seen, so an edge line wins on its own
                    // face and loses to anything really in front of it.
                    if (edgeLines) { GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL); GL33C.glPolygonOffset(1f, 1f) }
                    drawer.vertexBuffer(p.kind.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
                    if (edgeLines) GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_FILL)
                    if (edgeLines) {
                        // Four one-pixel lines a fraction of a pixel apart make the weight.
                        drawer.shadeStyle = lineStyle
                        drawer.depthWrite = false
                        nudges.forEach { (dx, dy) ->
                            drawer.projection = buildTransform {
                                translate(dx * line / drawer.width, dy * line / drawer.height, 0.0)
                            } * projection
                            drawer.vertexBuffer(p.kind.lines, DrawPrimitive.LINES)
                        }
                        drawer.projection = projection
                        drawer.depthWrite = true
                    }
                }
            }
            drawer.model = Matrix44.IDENTITY
        }
    }
}
