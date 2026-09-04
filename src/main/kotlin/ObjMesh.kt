import org.openrndr.color.ColorHSVa
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Reads the Willy Naessens catalogue as 3D geometry.
 *
 * `data/objects` holds 115 precast concrete pieces exported from an IFC model through
 * Blender; `data/unique_objects` holds the whole building those came out of — 388 unique
 * shapes standing for 89 410 instances, MEP and steel included, with a `manifest.csv`
 * beside them. Both are the same dialect of `.obj` and both load through here.
 *
 * Three things about these files decide how this is written:
 *
 * - **Faces are not triangles.** 88% are quads and the rest run up to 104 sides, because
 *   the exporter kept IFC's profiles whole. So every face has to be triangulated on the
 *   way in.
 * - **328 of those faces are concave**, which is what rules out the obvious fan. A wall
 *   panel with an opening in it — `WAND_10`, `WAND_11` — is exactly the case a fan gets
 *   wrong, and it gets it wrong by drawing a triangle straight across the opening. So the
 *   faces are ear-clipped instead, on a projection onto their own plane.
 * - **Coordinates are real metres, Z up.** IFC's convention, not OPENRNDR's, so the loader
 *   turns them Y up and keeps the real size in [ObjMesh.size] for anyone who wants it.
 *
 * Geometry is normalised to a unit bounding sphere and centred, so a 23 m beam and a 4 cm
 * anchor plate both arrive framed the same way and a camera set once suits every one of
 * them. That is worth doing here rather than in a sketch: the catalogue spans five orders
 * of magnitude, from `VLOER_2` at 144 m across to `RASTERPLAAT` at 5 cm.
 */
data class ObjMesh(
    /** The part name, which for `data/objects` is the file name: `KOLOM`, `TC-BALK`. */
    val name: String,
    /** What the `o` line called it, which carries the IFC class: `156_IfcBeam_BALK_x50`. */
    val objectName: String,
    /** The real size in metres, before normalisation. */
    val size: Vector3,
    val triangles: Int,
    /**
     * How far the piece reaches from the vertical axis, normalised. This is what a camera
     * has to clear horizontally, and because it is measured around the axis rather than
     * across one pair of faces it does not change as the piece turns — so a frame fitted to
     * it stays fitted, and the object does not breathe in and out while it spins.
     */
    val spinRadius: Double,
    /** Half the normalised height, which is what a camera has to clear vertically. */
    val halfHeight: Double,
    /**
     * The normalised corner positions. Kept because the rotation-invariant reaches above are
     * bounds rather than measurements: they are what a camera must clear at *any* angle, and
     * a layout that wants the piece's true width and height on screen has to project these
     * against the view it is actually using.
     */
    val points: List<Vector3>,
    /** Positions and normals, already normalised, centred and turned Y up. */
    val vertexBuffer: VertexBuffer
) {
    /** The IFC class out of [objectName], so `IfcBeam` — or null when it is not named that way. */
    val ifcClass: String?
        get() = Regex("Ifc[A-Za-z]+").find(objectName)?.value
}

/**
 * Distinct colours without a palette to maintain: successive hues a golden angle apart, so
 * any run of them is well spread however many faces a piece turns out to have.
 */
val goldenHues: (Int, Vector3) -> ColorRGBa = { i, _ ->
    ColorHSVa(360.0 * ((i * 0.6180339887498949) % 1.0), 0.85, 0.92).toRGBa()
}

/**
 * Black and white, taken from which way a face points rather than from its position in the
 * file.
 *
 * Face order was the obvious way to do this and it does not work, because the order faces
 * appear in a file has nothing to do with which of them meet. Stepping black and white down
 * the list alternates on `FUND` by luck and leaves every visible face of `WAND_27` white,
 * which comes out as a bare line drawing. Orientation cannot fail that way: the sides of a
 * piece point along different axes by definition, so they land on different tones.
 *
 * Three orientations and two tones means one pair must still share — a top and a face are
 * both white here — and that is what the edges are for: where two like faces meet, the dark
 * line still divides them.
 */
val axisGreys: (Int, Vector3) -> ColorRGBa = { _, n ->
    val ax = abs(n.x)
    val ay = abs(n.y)
    val az = abs(n.z)
    val v = when {
        ay >= ax && ay >= az -> 1.0 // looking up or down
        ax >= az -> 0.0             // one pair of sides
        else -> 1.0                 // the other pair
    }
    ColorRGBa(v, v, v)
}

/** Below this the two faces either side of an edge count as the same surface. */
const val DEFAULT_CREASE_DEGREES = 1.0

private fun quant(v: Vector3) =
    Triple(Math.round(v.x * 1e6), Math.round(v.y * 1e6), Math.round(v.z * 1e6))

/** An edge named by its two endpoints, in an order that does not depend on which way it is walked. */
private fun edgeKey(a: Vector3, b: Vector3): Pair<Triple<Long, Long, Long>, Triple<Long, Long, Long>> {
    val ka = quant(a)
    val kb = quant(b)
    val first = when {
        ka.first != kb.first -> ka.first < kb.first
        ka.second != kb.second -> ka.second < kb.second
        else -> ka.third <= kb.third
    }
    return if (first) ka to kb else kb to ka
}

private val meshFormat = vertexFormat {
    position(3)
    normal(3)
    // A colour per face. Face identity does not survive triangulation — once a polygon is
    // three triangles there is nothing left to say they were one side — so it has to be
    // baked here, at the one moment the loader still knows.
    attribute("faceColor", VertexElementType.VECTOR3_FLOAT32)
    // Barycentric coordinates, and a mask saying which of the triangle's three edges were
    // edges of the original polygon. That mask is the whole point: ear clipping fills a
    // face with diagonals that are not edges of anything, and drawing them would cover the
    // piece in lines that exist only because of how it was cut up.
    attribute("bary", VertexElementType.VECTOR3_FLOAT32)
    attribute("edges", VertexElementType.VECTOR3_FLOAT32)
}

/**
 * Every `.obj` in [directory], in file-name order. Files that carry no drawable triangle
 * are dropped — `KANTA` is 0x0x0 and a few others are single planes with a zero axis, which
 * is the register being honest about a symbol rather than a part, not a parsing fault.
 */
fun loadObjMeshes(
    directory: File,
    faceColor: (Int, Vector3) -> ColorRGBa = axisGreys,
    creaseDegrees: Double = DEFAULT_CREASE_DEGREES
): List<ObjMesh> =
    (directory.listFiles { f: File -> f.extension.equals("obj", true) } ?: emptyArray())
        .sortedBy { it.name }
        .mapNotNull { loadObjMesh(it, faceColor, creaseDegrees) }

/** One `.obj`, or null when it holds nothing that can be drawn. */
fun loadObjMesh(
    file: File,
    faceColor: (Int, Vector3) -> ColorRGBa = axisGreys,
    creaseDegrees: Double = DEFAULT_CREASE_DEGREES
): ObjMesh? {
    val positions = mutableListOf<Vector3>()
    val normals = mutableListOf<Vector3>()
    val faces = mutableListOf<List<Pair<Int, Int>>>() // (position, normal), normal -1 when absent
    var objectName = ""

    file.forEachLine { raw ->
        val line = raw.trim()
        when {
            line.startsWith("o ") -> objectName = line.substring(2).trim()
            line.startsWith("v ") -> line.split(' ').drop(1).filter { it.isNotEmpty() }.let {
                // Z up on the way in, Y up on the way out.
                positions += Vector3(it[0].toDouble(), it[2].toDouble(), -it[1].toDouble())
            }
            line.startsWith("vn ") -> line.split(' ').drop(1).filter { it.isNotEmpty() }.let {
                normals += Vector3(it[0].toDouble(), it[2].toDouble(), -it[1].toDouble())
            }
            line.startsWith("f ") -> {
                val corners = line.split(' ').drop(1).filter { it.isNotEmpty() }.map { corner ->
                    val parts = corner.split('/')
                    val p = parts[0].toInt().let { if (it < 0) positions.size + it else it - 1 }
                    val n = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                        ?.toInt()?.let { if (it < 0) normals.size + it else it - 1 } ?: -1
                    p to n
                }
                if (corners.size >= 3) faces += corners
            }
        }
    }
    if (positions.isEmpty() || faces.isEmpty()) return null

    val min = Vector3(
        positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z }
    )
    val max = Vector3(
        positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z }
    )
    val centre = (min + max) / 2.0
    // A bounding sphere rather than a box, so the object stays inside the frame at every
    // angle the camera can reach rather than only square on.
    val radius = positions.maxOf { (it - centre).length }
    if (radius < 1e-9) return null
    val scale = 1.0 / radius

    // Which edges are edges of the *solid*, as opposed to edges of the file's bookkeeping.
    //
    // Ring adjacency is not enough on its own. The exporter writes a face with a hole in it
    // as two coplanar polygons meeting along a seam — `WAND_33`'s window is a nine-corner
    // ring that detours into the opening and comes back — and those seam edges are adjacent
    // in the ring like any other, so drawing every adjacency puts two long diagonals across
    // a flat panel that has nothing on it.
    //
    // Every edge in `data/objects` is shared by exactly two faces, all 10 431 of them, so
    // there is always a second normal to compare against: an edge is real when the two faces
    // meeting there actually turn a corner. A seam between coplanar halves does not.
    val creaseCos = kotlin.math.cos(Math.toRadians(creaseDegrees))
    val edgeNormals = HashMap<Pair<Triple<Long, Long, Long>, Triple<Long, Long, Long>>, MutableList<Vector3>>()
    val faceNormals = faces.map { corners -> newellNormal(corners.map { positions[it.first] }) }
    for ((fi, corners) in faces.withIndex()) {
        val n = faceNormals[fi] ?: continue
        val ps = corners.map { positions[it.first] }
        for (i in ps.indices) {
            edgeNormals.getOrPut(edgeKey(ps[i], ps[(i + 1) % ps.size])) { mutableListOf() } += n
        }
    }

    /** True when the faces either side of this edge turn a corner, so it is worth drawing. */
    fun creased(a: Vector3, b: Vector3): Boolean {
        val ns = edgeNormals[edgeKey(a, b)] ?: return true
        if (ns.size < 2) return true // an open border: the edge of a sheet is still an edge
        var sharpest = 1.0
        for (i in ns.indices) for (j in i + 1 until ns.size) {
            sharpest = minOf(sharpest, ns[i].dot(ns[j]))
        }
        return sharpest < creaseCos
    }

    class Vertex(
        val position: Vector3, val normal: Vector3,
        val colour: Vector3, val bary: Vector3, val edges: Vector3
    )

    val out = mutableListOf<Vertex>()
    for ((faceIndex, corners) in faces.withIndex()) {
        val p = corners.map { positions[it.first] }
        val faceNormal = faceNormals[faceIndex] ?: continue
        val rgb = faceColor(faceIndex, faceNormal).let { Vector3(it.r, it.g, it.b) }
        val ring = corners.size

        // Two corners of the polygon are joined by a real edge only when they are next to
        // one another around it. Ear clipping hands back positions in that ring, so this
        // stays true however the face was cut.
        fun realEdge(a: Int, b: Int): Double {
            val d = abs(a - b)
            if (d != 1 && d != ring - 1) return 0.0 // a cut made by the triangulator
            return if (creased(p[a], p[b])) 1.0 else 0.0
        }

        for ((a, b, c) in earClip(p, faceNormal)) {
            // Each component describes the edge *opposite* that corner, which is the one
            // its barycentric coordinate goes to zero along.
            val mask = Vector3(realEdge(b, c), realEdge(c, a), realEdge(a, b))
            for ((k, i) in listOf(a, b, c).withIndex()) {
                val supplied = corners[i].second.let { if (it in normals.indices) normals[it] else null }
                // A supplied normal is preferred, but the exporter leaves some at zero and
                // the file has 12 faces that are not quite flat; the face normal covers both.
                val n = supplied?.takeIf { it.length > 1e-6 }?.normalized ?: faceNormal
                out += Vertex(
                    position = (positions[corners[i].first] - centre) * scale,
                    normal = n,
                    colour = rgb,
                    bary = when (k) {
                        0 -> Vector3(1.0, 0.0, 0.0)
                        1 -> Vector3(0.0, 1.0, 0.0)
                        else -> Vector3(0.0, 0.0, 1.0)
                    },
                    edges = mask
                )
            }
        }
    }
    if (out.isEmpty()) return null

    val buffer = vertexBuffer(meshFormat, out.size)
    buffer.put {
        for (v in out) {
            write(v.position)
            write(v.normal)
            write(v.colour)
            write(v.bary)
            write(v.edges)
        }
    }

    val normalised = positions.map { (it - centre) * scale }

    return ObjMesh(
        name = file.nameWithoutExtension,
        objectName = objectName,
        size = max - min,
        triangles = out.size / 3,
        points = normalised,
        spinRadius = normalised.maxOf { hypot(it.x, it.z) },
        halfHeight = normalised.maxOf { abs(it.y) },
        vertexBuffer = buffer
    )
}

/**
 * The face's plane, by Newell's method, which is the one that survives a face that is not
 * quite flat — it averages over the whole ring instead of trusting one corner's cross
 * product, and a corner can be collinear. Null when the face has no area.
 */
private fun newellNormal(p: List<Vector3>): Vector3? {
    var n = Vector3.ZERO
    for (i in p.indices) {
        val a = p[i]
        val b = p[(i + 1) % p.size]
        n += Vector3(
            (a.y - b.y) * (a.z + b.z),
            (a.z - b.z) * (a.x + b.x),
            (a.x - b.x) * (a.y + b.y)
        )
    }
    return if (n.length < 1e-12) null else n.normalized
}

/**
 * Triangulates one face, returning triples of indices into [p].
 *
 * The face is dropped onto the two axes its own normal is weakest in — its dominant plane —
 * and ear-clipped there. Ear clipping is what a fan is not: it will only cut a corner off
 * when the triangle that cut makes is empty, so a panel with a notch or an opening in it
 * comes back with the notch still in it.
 */
private fun earClip(p: List<Vector3>, normal: Vector3): List<Triple<Int, Int, Int>> {
    if (p.size == 3) return listOf(Triple(0, 1, 2))

    val ax = abs(normal.x)
    val ay = abs(normal.y)
    val az = abs(normal.z)
    val flat: (Vector3) -> Vector2 = when {
        ax >= ay && ax >= az -> { v -> Vector2(v.y, v.z) }
        ay >= az -> { v -> Vector2(v.z, v.x) }
        else -> { v -> Vector2(v.x, v.y) }
    }
    val v = p.map(flat)

    // Ear clipping wants one known winding; the projection may have flipped it.
    var area = 0.0
    for (i in v.indices) {
        val a = v[i]
        val b = v[(i + 1) % v.size]
        area += a.x * b.y - b.x * a.y
    }
    val indices = if (area < 0) p.indices.reversed().toMutableList() else p.indices.toMutableList()

    val out = mutableListOf<Triple<Int, Int, Int>>()
    var guard = 0
    while (indices.size > 3 && guard < indices.size * indices.size + 16) {
        var clipped = false
        for (k in indices.indices) {
            val i0 = indices[(k + indices.size - 1) % indices.size]
            val i1 = indices[k]
            val i2 = indices[(k + 1) % indices.size]
            val a = v[i0]
            val b = v[i1]
            val c = v[i2]
            if (cross(a, b, c) <= 0.0) continue // reflex or collinear: not an ear
            val contains = indices.any { j ->
                j != i0 && j != i1 && j != i2 && inTriangle(v[j], a, b, c)
            }
            if (contains) continue
            out += Triple(i0, i1, i2)
            indices.removeAt(k)
            clipped = true
            break
        }
        if (!clipped) break // self-intersecting or degenerate; fall through and fan the rest
        guard++
    }
    for (k in 1 until indices.size - 1) {
        out += Triple(indices[0], indices[k], indices[k + 1])
    }
    return out
}

private fun cross(a: Vector2, b: Vector2, c: Vector2) =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun inTriangle(p: Vector2, a: Vector2, b: Vector2, c: Vector2): Boolean {
    val d1 = cross(p, a, b)
    val d2 = cross(p, b, c)
    val d3 = cross(p, c, a)
    val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
    val hasPos = d1 > 0 || d2 > 0 || d3 > 0
    return !(hasNeg && hasPos)
}
