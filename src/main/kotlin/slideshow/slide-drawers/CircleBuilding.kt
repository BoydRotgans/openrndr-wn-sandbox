package slideshow.drawers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.lookAt
import org.openrndr.math.transforms.ortho
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A label with a leader to a point on the building, the point as fractions of its box (x, y up, z). */
class Callout(val text: String, val at: Vector3)

/**
 * The Circle itself, out of the webtool: the building as red lines on a dotted ground, the
 * label set swapping on the click, the camera drifting.
 *
 * **It is the IFC, tessellated once by `tools/ifc_to_tri.py`** into two flat binaries the
 * loader hands straight to the GPU — every triangle with its normal, and every creased edge as
 * a line — plus a small json of the box. Nothing is parsed here beyond the json: a float is a
 * float, and a building of a few million triangles is up in the time the file takes to read.
 *
 * **Hidden lines are hidden by the faces, drawn in the ground's own black and pushed in along
 * their normals.** The faces write depth and nothing else, so an edge behind the building is
 * behind it; pushed in a few centimetres, an edge on a face is not fighting that face for the
 * same depth. That is the whole of the line rendering — no wireframe pass, no edge detection.
 *
 * The camera is the isometric of the rest of the deck, orthographic, fitted every frame to the
 * building's box at the yaw it is at; the drift is a slow sine of the frame count.
 */
class CircleBuilding(
    private val title: String,
    private val folder: File = File("data/circle"),
    /** One label set a state, swapping on the click. */
    private val callouts: List<List<Callout>>,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val line: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val dots: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    /** Degrees the camera swings either side of the isometric yaw, and seconds a swing takes. */
    private val drift: Double = 6.0,
    private val period: Double = 60.0,
    /** The camera's elevation in degrees, and how opaque a line is — density then reads as tone. */
    private val elevation: Double = 18.0,
    private val opacity: Double = 0.45,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "The Circle"
    override val steps get() = callouts.size.coerceAtLeast(1)

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var faces: VertexBuffer? = null
    private var edges: VertexBuffer? = null
    private var lo = Vector3.ZERO
    private var hi = Vector3.ONE
    private lateinit var recessed: org.openrndr.draw.ShadeStyle
    private lateinit var wire: org.openrndr.draw.ShadeStyle
    private lateinit var dotted: org.openrndr.draw.ShadeStyle

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        val meta = File(folder, "circle.json")
        if (!meta.isFile) { println("the circle: no ${meta.path} — run tools/ifc_to_tri.py on the IFC"); return }
        val root = Json.parseToJsonElement(meta.readText()).jsonObject
        fun v(key: String) = root[key]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }.let { Vector3(it[0], it[1], it[2]) }
        // the model is z up: (x, y, z) becomes (x, z, -y)
        val a = v("min"); val b = v("max")
        lo = Vector3(a.x, a.z, -b.y); hi = Vector3(b.x, b.z, -a.y)
        faces = upload(File(folder, "circle.tri"), 6)
        edges = upload(File(folder, "circle.edge"), 3)
        println("the circle: ${root["triangles"]?.jsonPrimitive?.content} triangles, ${root["edges"]?.jsonPrimitive?.content} edges, %.0f x %.0f x %.0f m".format(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z))

        // The file is z up; both styles stand it y up in the vertex shader, (x, y, z) to
        // (x, z, -y), so no model matrix is involved and the two cannot disagree.
        recessed = shadeStyle {
            vertexTransform = """
                x_position = vec3(x_position.x, x_position.z, -x_position.y) - vec3(x_normal.x, x_normal.z, -x_normal.y) * p_push;
                x_normal = vec3(x_normal.x, x_normal.z, -x_normal.y);
            """.trimIndent()
            fragmentTransform = "x_fill = p_ground;"
        }
        wire = shadeStyle {
            vertexTransform = "x_position = vec3(x_position.x, x_position.z, -x_position.y);"
            fragmentTransform = "x_fill = p_line;"
        }
        dotted = shadeStyle {
            fragmentTransform = """
                vec2 g = fract(v_worldPosition.xz / p_pitch) - 0.5;
                float d = length(g) * p_pitch;
                float dot = 1.0 - smoothstep(p_radius - 0.6 * fwidth(d), p_radius + 0.6 * fwidth(d), d);
                x_fill = vec4(mix(p_ground.rgb, p_dots.rgb, dot), 1.0);
            """.trimIndent()
        }
    }

    /** A flat float32 file as a vertex buffer of [floats] a vertex: positions, and normals when six. */
    private fun upload(file: File, floats: Int): VertexBuffer? {
        if (!file.isFile) { println("the circle: no ${file.path}"); return null }
        val bytes = file.length()
        val count = (bytes / (4L * floats)).toInt()
        if (count == 0) return null
        val format = vertexFormat {
            position(3)
            if (floats == 6) normal(3)
        }
        val buffer = vertexBuffer(format, count)
        // One read into one direct buffer and one write: chunked writes at a byte offset left a
        // second, displaced copy of the hall standing over the first.
        FileChannel.open(file.toPath()).use { channel ->
            val whole = ByteBuffer.allocateDirect((count.toLong() * 4 * floats).toInt()).order(ByteOrder.nativeOrder())
            while (whole.hasRemaining()) { if (channel.read(whole) < 0) break }
            whole.flip()
            buffer.write(whole)
        }
        return buffer
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        fun title() {
            drawer.fill = ink
            drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        }
        val faces = faces ?: return title()
        val edges = edges ?: return title()

        // The camera: the deck's isometric, drifting, fitted to the box at this yaw.
        val yaw = Math.toRadians(45.0 + drift * sin(2.0 * PI * stage.frame / frames(period)))
        // Lower than the deck's isometric: from 35 degrees the roof is all there is of a 290 m
        // hall 13 m tall, and its ribbed slabs' edges are a solid red plate. From `elevation`
        // the facade and the columns under the roof show.
        val pitch = Math.toRadians(elevation)
        val e = Vector3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw))
        val centre = (lo + hi) / 2.0
        val right = (-e).cross(Vector3.UNIT_Y).normalized
        val up = right.cross((-e).normalized).normalized
        val corners = listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vector3(x, y, z) - centre } } }
        val halfW = corners.maxOf { kotlin.math.abs(it.dot(right)) }
        val halfH = corners.maxOf { kotlin.math.abs(it.dot(up)) }
        val room = Rectangle2(w * MARGIN, h * TOP, w * (1.0 - 2.0 * MARGIN), h * (BOTTOM - TOP))
        val scale = min(room.w / (2.0 * halfW), room.h / (2.0 * halfH))     // pane pixels per metre
        val view = lookAt(centre + e * 2000.0, centre, Vector3.UNIT_Y)
        val cx = room.x + room.w / 2.0 - w / 2.0        // the box's middle on the pane, in wall-centred pixels
        val cy = h / 2.0 - (room.y + room.h / 2.0)
        val depth = corners.maxOf { kotlin.math.abs(it.dot(e)) } + 10.0
        fun project(p: Vector3): Vector2 {
            val q = p - centre
            return Vector2(w / 2.0 + cx + q.dot(right) * scale, h / 2.0 - cy - q.dot(up) * scale)
        }

        drawer.isolated {
            drawer.ortho(-w / 2.0 / scale - cx / scale, w / 2.0 / scale - cx / scale, -h / 2.0 / scale - cy / scale, h / 2.0 / scale - cy / scale, 2000.0 - depth, 2000.0 + depth)
            drawer.view = view
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.stroke = null

            // The ground: a dotted sheet under the building, in the model's own metres.
            drawer.model = org.openrndr.math.Matrix44.IDENTITY
            dotted.parameter("pitch", DOT_PITCH)
            dotted.parameter("radius", DOT_RADIUS)
            dotted.parameter("ground", background)
            dotted.parameter("dots", dots)
            drawer.shadeStyle = dotted
            drawer.fill = background
            val reach = (hi - lo).length * 1.2
            drawer.translate(centre.x, lo.y - 0.05, centre.z)
            drawer.rotate(Vector3.UNIT_X, -90.0)
            drawer.rectangle(-reach, -reach, 2 * reach, 2 * reach)

            drawer.model = org.openrndr.math.Matrix44.IDENTITY

            // The faces, in the ground's black, recessed along their normals; they hide the lines behind them.
            recessed.parameter("push", PUSH)
            recessed.parameter("ground", background)
            drawer.shadeStyle = recessed
            drawer.vertexBuffer(faces, DrawPrimitive.TRIANGLES)

            // The lines.
            wire.parameter("line", line.opacify(opacity))
            drawer.shadeStyle = wire
            drawer.fill = line
            drawer.vertexBuffer(edges, DrawPrimitive.LINES)
        }

        // The title after the model, which writes depth across the pane.
        title()

        // The callouts, one set a state, out and back on the ladder's rule.
        val p = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val a = floor(p).toInt()
        val b = min(a + 1, steps - 1)
        val t = p - a
        val out = (1.0 - t / FADE).coerceIn(0.0, 1.0)
        val back = ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0)
        fun set(list: List<Callout>, alpha: Double) {
            if (alpha <= 0.0) return
            list.forEachIndexed { i, c ->
                val target = project(Vector3(lo.x + (hi.x - lo.x) * c.at.x, lo.y + (hi.y - lo.y) * c.at.y, lo.z + (hi.z - lo.z) * c.at.z))
                val at = Vector2(w * (1.0 - EDGE), h * (LABEL_Y + i * LABEL_PITCH))
                drawer.leaderLabel(listOf(c.text), at, target, bold, h * LABEL, SIZE, ink, h * LABEL_LEAD, alpha, align = 1.0, gap = w * 0.008)
            }
        }
        if (a == b) set(callouts.getOrElse(a) { emptyList() }, 1.0)
        else { set(callouts.getOrElse(a) { emptyList() }, out); set(callouts.getOrElse(b) { emptyList() }, back) }
    }

    private class Rectangle2(val x: Double, val y: Double, val w: Double, val h: Double)

    private companion object {
        const val SIZE = 200.0
        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val MARGIN = 0.04
        const val TOP = 0.12
        const val BOTTOM = 0.96
        const val EDGE = 0.02
        const val LABEL = 0.028
        const val LABEL_LEAD = 0.034
        const val LABEL_Y = 0.2
        const val LABEL_PITCH = 0.09
        /** Metres the faces are pushed in along their normals, so an edge on a face wins the depth test. */
        const val PUSH = 0.04
        const val DOT_PITCH = 6.0
        const val DOT_RADIUS = 0.35
        const val FADE = 0.3
    }
}
