// ============================================================================ //
//  No `package` declaration: the pieces are loaded by loadObjMesh, which lives in
//  the default package. See BlockStacks.
// ============================================================================ //

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.shape.Rectangle
import java.io.File

/**
 * What the cube walls share — [BlockStacks]' city and [BlockCluster]'s crawling cluster: unit cubes
 * drawn as a see-through wire model with a red catalogue piece standing in each, in one
 * multisampled 3D pass with a depth test, so the glass cubes' front edges cross in front of the
 * pieces and their back edges pass behind them.
 *
 * **An edge is a quad turned to face the camera** in the vertex shader, across both the edge and
 * the view. Under a parallel view a pixel is the same world length everywhere, so a quad a fixed
 * world width is a line of a fixed weight on the wall; each end is pushed out by half the width
 * so two edges meeting at a corner close it. The pass is multisampled, quads having no
 * antialiasing of their own.
 *
 * **A piece is fitted to [fill] of a cube by its longest side**, stood at the cube's middle with a
 * quarter turn of its own, and toned by which way a face points in the world — the Objects poster
 * rule — so a red piece still reads as a solid rather than a silhouette.
 */
class WireCubes(
    private val pieces: List<File>,
    private val piece: ColorRGBa,
    private val fill: Double,
    private val ink: ColorRGBa,
    private val paper: ColorRGBa,
    /** Line width in wall pixels. */
    private val line: Double
) {
    var meshes: List<ObjMesh> = emptyList()
        private set
    private var fits: List<Double> = emptyList()

    private var scene: RenderTarget? = null
    private var flat: ColorBuffer? = null

    /** The pieces, off disk. A name with no file is said and left out. */
    fun load() {
        meshes = pieces.mapNotNull { f ->
            if (!f.isFile) { println("wire cubes: no piece at ${f.path}"); null } else loadObjMesh(f)
        }
        fits = meshes.map { m ->
            val xs = m.points.map { it.x }; val ys = m.points.map { it.y }; val zs = m.points.map { it.z }
            fill / maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1e-6)
        }
    }

    /**
     * The edges of a set of cubes, each once: an edge is keyed by its lower end and its axis, so a
     * cube beside or on another adds only what that one did not already have.
     */
    class Edges {
        private val keys = HashSet<Long>()
        val size get() = keys.size

        /** The twelve edges of the cube whose lowest corner is ([x], [y], [z]): four along each axis. */
        fun cube(x: Int, y: Int, z: Int) {
            for (a in 0..1) for (b in 0..1) {
                keys += key(x, y + a, z + b, 0)
                keys += key(x + a, y, z + b, 1)
                keys += key(x + a, y + b, z, 2)
            }
        }

        /** Every edge as its lower end and its axis. */
        fun forEach(action: (Int, Int, Int, Int) -> Unit) {
            for (k in keys) {
                val axis = (k % 4).toInt()
                val z = ((k / 4) % SPAN).toInt() - OFFSET
                val y = ((k / 4 / SPAN) % SPAN).toInt() - OFFSET
                val x = (k / 4 / SPAN / SPAN).toInt() - OFFSET
                action(x, y, z, axis)
            }
        }

        private fun key(x: Int, y: Int, z: Int, axis: Int) =
            (((x + OFFSET).toLong() * SPAN + (y + OFFSET)) * SPAN + (z + OFFSET)) * 4 + axis

        private companion object {
            /** Lattice coordinates run from -OFFSET to SPAN - OFFSET on every axis. */
            const val OFFSET = 4096
            const val SPAN = 8192L
        }
    }

    /**
     * [edges] written into [into] as quads, six vertices an edge, growing it when it is too small;
     * returns the buffer and how many vertices to draw of it.
     */
    fun quads(edges: Edges, into: VertexBuffer? = null): Pair<VertexBuffer, Int> {
        val count = edges.size * 6
        val buffer = if (into != null && into.vertexCount >= count) into
                     else { into?.destroy(); vertexBuffer(LINE_FORMAT, (count * 1.25).toInt().coerceAtLeast(6)) }
        buffer.put {
            edges.forEach { x, y, z, axis ->
                val a = Vector3(x.toDouble(), y.toDouble(), z.toDouble())
                val along = when (axis) { 0 -> Vector3.UNIT_X; 1 -> Vector3.UNIT_Y; else -> Vector3.UNIT_Z }
                val b = a + along
                for ((side, end) in CORNERS) {
                    write(if (end < 0.0) a else b); write(along); write(side.toFloat()); write(end.toFloat())
                }
            }
        }
        return buffer to count
    }

    /**
     * Any [segments] — pairs of ends, anywhere — written into [into] as quads the way [quads] writes
     * the lattice's edges; returns the buffer and how many vertices to draw of it.
     */
    fun segments(segments: List<Pair<Vector3, Vector3>>, into: VertexBuffer? = null): Pair<VertexBuffer, Int> {
        val count = segments.size * 6
        val buffer = if (into != null && into.vertexCount >= count) into
                     else { into?.destroy(); vertexBuffer(LINE_FORMAT, (count * 1.25).toInt().coerceAtLeast(6)) }
        buffer.put {
            for ((a, b) in segments) {
                val d = b - a
                val along = if (d.length < 1e-9) Vector3.UNIT_X else d.normalized
                for ((side, end) in CORNERS) {
                    write(if (end < 0.0) a else b); write(along); write(side.toFloat()); write(end.toFloat())
                }
            }
        }
        return buffer to count
    }

    /**
     * One frame: the pass cleared to the paper under [projection] and [view], [body] drawing into
     * it, and the resolved picture laid into [bounds].
     */
    fun frame(drawer: Drawer, bounds: Rectangle, projection: Matrix44, view: Matrix44, body: () -> Unit) {
        val (target, resolved) = targets(bounds.width.toInt(), bounds.height.toInt())
        drawer.isolatedWithTarget(target) {
            drawer.clear(paper)
            drawer.projection = projection
            drawer.view = view
            drawer.model = Matrix44.IDENTITY
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.drawStyle.cullTestPass = CullTestPass.ALWAYS
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            body()
            drawer.shadeStyle = null
        }
        target.colorBuffer(0).copyTo(resolved)
        drawer.image(resolved, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    /**
     * [count] vertices of [buffer] from [first], moved by [offset], as lines: [toCamera] is the view
     * direction and [unit] pixels a cube. [colour] and [width] default to the wall's ink and line;
     * [lift] draws them that far toward the camera, so a line lying on a face wins over the face.
     */
    fun edges(drawer: Drawer, buffer: VertexBuffer, count: Int, toCamera: Vector3, unit: Double, offset: Vector3 = Vector3.ZERO,
              first: Int = 0, colour: ColorRGBa = ink, width: Double = line, lift: Double = 0.0) {
        if (count <= 0) return
        drawer.isolated {
            drawer.translate(offset)
            wire.parameter("view", toCamera)
            wire.parameter("half", width / 2.0 / unit)
            wire.parameter("ink", colour)
            wire.parameter("lift", lift)
            drawer.shadeStyle = wire
            drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES, first, count)
        }
    }

    /** Readies the pieces' style; call before a run of [piece]s. */
    fun pieces(drawer: Drawer) {
        solid.parameter("top", piece)
        solid.parameter("side", piece.shade(0.78))
        solid.parameter("across", piece.shade(0.58))
        drawer.shadeStyle = solid
    }

    /** Piece [which] standing at [centre] with [quarter] quarter turns. Nothing where there are no pieces. */
    fun piece(drawer: Drawer, centre: Vector3, which: Int, quarter: Int) {
        if (meshes.isEmpty() || which < 0) return
        val i = which % meshes.size
        drawer.isolated {
            drawer.translate(centre)
            drawer.rotate(Vector3.UNIT_Y, quarter * 90.0)
            drawer.scale(fits[i])
            drawer.vertexBuffer(meshes[i].vertexBuffer, DrawPrimitive.TRIANGLES)
        }
    }

    /** The multisampled pass and the buffer it resolves into, at the wall's size. */
    private fun targets(w: Int, h: Int): Pair<RenderTarget, ColorBuffer> {
        val current = scene
        if (current == null || current.width != w || current.height != h) {
            current?.let { it.colorBuffer(0).destroy(); it.depthBuffer?.destroy(); it.destroy() }
            flat?.destroy()
            scene = renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) { colorBuffer(); depthBuffer() }
            flat = colorBuffer(w, h)
        }
        return scene!! to flat!!
    }

    /** A line quad turned to face the camera: across the edge and the view, a constant width under a parallel projection. */
    private val wire = shadeStyle {
        vertexTransform = """
            vec3 across = normalize(cross(a_along, p_view));
            x_position += across * a_side * p_half + a_along * a_end * p_half + p_view * p_lift;
        """
        fragmentTransform = "x_fill = p_ink;"
    }

    /** A piece toned by which way a face points, in the world: roofs lightest, the two wall families below. */
    private val solid = shadeStyle {
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            x_fill = abs(n.y) > 0.6 ? p_top : (abs(n.x) > abs(n.z) ? p_side : p_across);
        """
    }

    private companion object {
        val LINE_FORMAT = vertexFormat {
            position(3)
            attribute("along", VertexElementType.VECTOR3_FLOAT32)
            attribute("side", VertexElementType.FLOAT32)
            attribute("end", VertexElementType.FLOAT32)
        }

        /** An edge's six vertices as (side, end) — which side of the edge, and which end — two triangles over the quad. */
        val CORNERS = listOf(
            -1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0,
            -1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0
        )
    }
}
