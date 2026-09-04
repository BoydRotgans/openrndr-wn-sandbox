import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.triangulate
import kotlin.math.exp

/**
 * Turning collected map data into something that draws fast, shared by every map sketch.
 *
 * The whole approach rests on one decision: a layer is triangulated **once** at load into
 * a single vertex buffer, and a frame then changes nothing but the view transform. Drawing
 * a filled shape the ordinary way costs a stencil pass per shape, which at fifteen thousand
 * features a frame is hopeless; as buffers it is a handful of draw calls and the geometry
 * is still real, so an edge stays exact however far you zoom in.
 */

// ---------------------------------------------------------------------------------
// geometry
// ---------------------------------------------------------------------------------

/**
 * The feature as OPENRNDR shapes, keeping holes as holes: a GeoJSON polygon is an outer
 * ring followed by its inner rings, so a courtyard stays open rather than being filled in.
 */
fun JsonObject.shapes(): List<Shape> {
    val geometry = this["geometry"]?.jsonObject ?: return emptyList()
    val coordinates = geometry["coordinates"]?.jsonArray ?: return emptyList()
    val polygons = when (geometry["type"]?.jsonPrimitive?.content) {
        "Polygon" -> listOf(coordinates)
        "MultiPolygon" -> coordinates.map { it.jsonArray }
        else -> return emptyList()
    }
    return polygons.mapNotNull { polygon ->
        val contours = polygon.map { ringElement ->
            // GeoJSON repeats the first point to close the ring; ShapeContour closes it itself.
            val points = ringElement.jsonArray.map {
                val pair = it.jsonArray
                Vector2(pair[0].jsonPrimitive.double, pair[1].jsonPrimitive.double)
            }.dropLast(1)
            ShapeContour.fromPoints(points, closed = true)
        }.filter { it.segments.isNotEmpty() }
        if (contours.isEmpty()) null else Shape(contours)
    }
}

// ---------------------------------------------------------------------------------
// meshes
// ---------------------------------------------------------------------------------

/**
 * One vertex buffer holding a whole layer, plus where each feature's triangles end, so a
 * single feature — or every feature up to some point — can be drawn without touching the
 * geometry again.
 */
class Mesh(val buffer: VertexBuffer?, val ends: IntArray) {
    val parts get() = ends.size
    val vertexCount get() = ends.lastOrNull() ?: 0
    fun start(index: Int) = if (index == 0) 0 else ends[index - 1]
    fun count(index: Int) = ends[index] - start(index)

    /** The whole layer in one draw call. */
    fun draw(drawer: Drawer, colour: ColorRGBa, opacity: Double = 1.0) {
        val buffer = buffer ?: return
        if (opacity <= 0.001) return
        drawer.fill = if (opacity >= 1.0) colour else colour.opacify(opacity)
        drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES, 0, vertexCount)
    }

    /** Features `0 until count`, still one draw call, because they are stored in order. */
    fun drawPrefix(drawer: Drawer, colour: ColorRGBa, count: Int) {
        val buffer = buffer ?: return
        if (count <= 0) return
        drawer.fill = colour
        drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES, 0, ends[count - 1])
    }

    /**
     * Features `from until to`, in one draw call. Because features are stored in order,
     * any contiguous run of them is a contiguous run of triangles.
     */
    fun drawRange(drawer: Drawer, from: Int, to: Int, colour: ColorRGBa, opacity: Double = 1.0) {
        val buffer = buffer ?: return
        if (to <= from || opacity <= 0.001) return
        val first = start(from)
        val last = ends[to - 1]
        if (last <= first) return
        drawer.fill = if (opacity >= 1.0) colour else colour.opacify(opacity)
        drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES, first, last - first)
    }
}

/** The point every mesh is stored relative to. See [meshOfTriangles]. */
val MapArea.origin: Vector2 get() = Vector2(centre[0], centre[1])

fun meshOf(features: List<List<Shape>>, origin: Vector2 = Vector2.ZERO): Mesh =
    meshOfTriangles(features.map { shapes -> shapes.flatMap { triangulate(it) } }, origin)

/**
 * A mesh from triangles already worked out, one list per feature, in world coordinates.
 *
 * [origin] is subtracted from every vertex, and it matters more than it looks. A vertex
 * buffer holds 32-bit floats, and an RD New coordinate is around 150 000: at that size a
 * float can only step in units of 0.0156 m, which at a 25x zoom is three quarters of a
 * pixel. Geometry visibly snapped from one position to the next as the camera moved.
 * Stored relative to the middle of the extent the numbers are at most a few thousand, the
 * step falls to 0.00006 m, and the snapping goes away. The camera puts the offset back.
 */
fun meshOfTriangles(triangles: List<List<Vector2>>, origin: Vector2 = Vector2.ZERO): Mesh {
    val ends = IntArray(triangles.size)
    var running = 0
    triangles.forEachIndexed { index, vertices -> running += vertices.size; ends[index] = running }
    if (running == 0) return Mesh(null, ends)

    val buffer = vertexBuffer(vertexFormat { position(3) }, running)
    buffer.put {
        triangles.forEach { vertices ->
            vertices.forEach { write(Vector3(it.x - origin.x, it.y - origin.y, 0.0)) }
        }
    }
    return Mesh(buffer, ends)
}

/** Every feature of a layer as one mesh, stored relative to the extent's centre. */
fun MapArea.meshOfLayer(layer: String) = meshOf(features(layer).map { it.shapes() }, origin)

// ---------------------------------------------------------------------------------
// camera
// ---------------------------------------------------------------------------------

/**
 * Maps RD New metres onto the canvas, with zoom and pan.
 *
 * RD New is already metres with y pointing north, so this is only a flip and a scale — no
 * projection — and zoom is a plain factor on it. Nothing is ever resampled, which is why
 * zooming in stays sharp instead of enlarging pixels.
 */
class MapCamera(
    private val area: MapArea,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    /**
     * The extent is square and a canvas usually is not. `cover` fills the frame and crops;
     * `contain` fits the whole extent inside it, letterboxed against empty paper.
     */
    private val cover: Boolean = true,
    private val homeZoom: Double = 1.0,
    /**
     * The world point the frame is built around. Defaults to the middle of the extent,
     * which is right for a radius extent because that is already MAP_CENTRE. On a margin
     * extent it is the middle of the whole region, which can be kilometres from where the
     * reveal starts — name a point here to hold the frame somewhere that matters instead.
     */
    focusPoint: Vector2? = null
) {
    var zoom = homeZoom
    var pan = Vector2.ZERO

    private var homeFocus = focusPoint ?: Vector2(area.centre[0], area.centre[1])

    /** Vertices are stored relative to this; see [meshOfTriangles]. */
    private val origin = area.origin

    /** The world point the canvas is centred on. Move it to fly the camera somewhere. */
    var focus = homeFocus

    val areaCentre get() = homeFocus

    private val canvasCentre = Vector2(canvasWidth / 2.0, canvasHeight / 2.0)

    private fun fit() = if (cover) maxOf(canvasWidth / area.width, canvasHeight / area.height)
                        else minOf(canvasWidth / area.width, canvasHeight / area.height)

    private fun scale() = fit() * zoom

    /** Canvas pixels per metre at [atZoom], for sizing things that must stay legible. */
    fun pixelsPerMetre(atZoom: Double) = fit() * atZoom

    fun worldToCanvas(world: Vector2): Vector2 {
        val s = scale()
        return Vector2((world.x - focus.x) * s, -(world.y - focus.y) * s) + canvasCentre + pan
    }

    fun canvasToWorld(point: Vector2): Vector2 {
        val s = scale()
        val local = point - canvasCentre - pan
        return Vector2(local.x / s + focus.x, -local.y / s + focus.y)
    }

    fun reset() { zoom = homeZoom; pan = Vector2.ZERO; focus = homeFocus }

    /**
     * Move the point the frame is built around, once, before the shot starts.
     *
     * This is not the camera following anything — it is choosing which single fixed point
     * the whole flight is held on. [zoomFillingFrame] and [reset] read the same value, so
     * the pull-back floor and the 0 key both follow it rather than going stale.
     */
    fun recentre(point: Vector2) { homeFocus = point; focus = point }

    /**
     * The smallest zoom at which the frame is still entirely inside the collected extent —
     * pull back any further and the edge of the data comes into shot as a straight line of
     * background. It depends on where the frame is held: the distance from the focus to the
     * nearest edge is what binds, so a frame held off-centre can pull back less than one
     * held in the middle.
     */
    fun zoomFillingFrame(): Double {
        val marginX = minOf(homeFocus.x - area.bbox[0], area.bbox[2] - homeFocus.x)
        val marginY = minOf(homeFocus.y - area.bbox[1], area.bbox[3] - homeFocus.y)
        if (marginX <= 0.0 || marginY <= 0.0) return 1.0
        return maxOf(
            (canvasWidth / 2.0) / (fit() * marginX),
            (canvasHeight / 2.0) / (fit() * marginY)
        )
    }

    /** Zoom about a canvas point, keeping whatever is under it pinned there. */
    fun zoomAt(point: Vector2, amount: Double) {
        val anchor = canvasToWorld(point)
        zoom = (zoom * exp(amount * 0.15)).coerceIn(0.25, 500.0)
        pan += point - worldToCanvas(anchor)
    }

    fun panBy(delta: Vector2) { pan += delta }

    /** A window pointer position in canvas coordinates, whatever scale the window shows. */
    fun pointer(position: Vector2, windowWidth: Int) = position * (canvasWidth / windowWidth.toDouble())

    /**
     * Meshes are stored relative to [origin], so what is translated here is the offset from
     * it, never the absolute coordinate — which is the whole point of storing them that way.
     */
    fun apply(drawer: Drawer) {
        drawer.translate(canvasCentre + pan)
        drawer.scale(scale(), -scale())
        drawer.translate(-(focus.x - origin.x), -(focus.y - origin.y))
    }

    /**
     * The world rectangle the canvas covers at [atZoom], about wherever the frame is held.
     *
     * Independent of the camera's current state, so a shot can be asked at load time what
     * it will be looking at when it comes to rest — which is how the elements that take
     * part in the final composition are chosen before a frame is drawn.
     */
    fun worldFrame(atZoom: Double): Rectangle {
        val s = fit() * atZoom
        val width = canvasWidth / s
        val height = canvasHeight / s
        return Rectangle(focus.x - width / 2, focus.y - height / 2, width, height)
    }

    /**
     * The collected extent, in canvas coordinates and cropped to the canvas.
     *
     * A bbox query returns every feature that *touches* the box, so a long canal or rail
     * surface can reach kilometres past it and streak across the paper. Clipping to the
     * extent cuts them at the edge they were asked for.
     */
    fun clip(): Rectangle {
        val topLeft = worldToCanvas(Vector2(area.bbox[0], area.bbox[3]))
        val bottomRight = worldToCanvas(Vector2(area.bbox[2], area.bbox[1]))
        val left = topLeft.x.coerceIn(0.0, canvasWidth.toDouble())
        val top = topLeft.y.coerceIn(0.0, canvasHeight.toDouble())
        val right = bottomRight.x.coerceIn(0.0, canvasWidth.toDouble())
        val bottom = bottomRight.y.coerceIn(0.0, canvasHeight.toDouble())
        return Rectangle(left, top, right - left, bottom - top)
    }
}
