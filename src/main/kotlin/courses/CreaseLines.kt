import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector3
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The real edges of a catalogue piece as line segments, in the same space [loadObjMesh] puts its
 * triangles: Z up turned Y up, centred, scaled to a unit sphere — so the lines lie exactly on the
 * mesh. An edge is real where the two faces meeting on it turn a corner by more than [crease]
 * degrees, the loader's own test, so the seams the exporter leaves inside a flat face drop out.
 *
 * It exists because a line drawn in the face shader is measured across the face, and a face turned
 * edge-on to the camera has no width left to measure: an orbiting camera saw the panels' lines break
 * up into dashes as it came round. A line drawn as a line has no such angle.
 */
fun creaseLines(file: File, crease: Double = 10.0): VertexBuffer {
    val positions = mutableListOf<Vector3>()
    val faces = mutableListOf<List<Int>>()
    file.forEachLine { raw ->
        val line = raw.trim()
        when {
            line.startsWith("v ") -> line.split(' ').drop(1).filter { it.isNotEmpty() }.let {
                positions += Vector3(it[0].toDouble(), it[2].toDouble(), -it[1].toDouble())
            }
            line.startsWith("f ") -> {
                val corners = line.split(' ').drop(1).filter { it.isNotEmpty() }
                    .map { c -> c.split('/')[0].toInt().let { if (it < 0) positions.size + it else it - 1 } }
                if (corners.size >= 3) faces += corners
            }
        }
    }
    val min = Vector3(positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z })
    val max = Vector3(positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z })
    val centre = (min + max) / 2.0
    val scale = 1.0 / positions.maxOf { (it - centre).length }

    fun normal(ps: List<Vector3>): Vector3 {
        var n = Vector3.ZERO
        for (i in ps.indices) {
            val a = ps[i]; val b = ps[(i + 1) % ps.size]
            n += Vector3((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y))
        }
        return if (n.length < 1e-12) Vector3.ZERO else n.normalized
    }
    fun key(p: Vector3) = Triple((p.x * 1e5).roundToLong(), (p.y * 1e5).roundToLong(), (p.z * 1e5).roundToLong())

    class Edge(val a: Vector3, val b: Vector3, val normals: MutableList<Vector3> = mutableListOf())
    val edges = LinkedHashMap<Pair<Triple<Long, Long, Long>, Triple<Long, Long, Long>>, Edge>()
    faces.forEach { f ->
        val ps = f.map { positions[it] }
        val n = normal(ps)
        for (i in ps.indices) {
            val a = ps[i]; val b = ps[(i + 1) % ps.size]
            val ka = key(a); val kb = key(b)
            val k = if (ka.toString() < kb.toString()) ka to kb else kb to ka
            edges.getOrPut(k) { Edge(a, b) }.normals += n
        }
    }
    val limit = kotlin.math.cos(Math.toRadians(crease))
    val real = edges.values.filter { e ->
        if (e.normals.size < 2) true
        else e.normals.indices.any { i -> (i + 1 until e.normals.size).any { j -> e.normals[i].dot(e.normals[j]) < limit } }
    }.filter { abs((it.a - it.b).length) > 1e-9 }

    val format = vertexFormat { position(3) }
    return vertexBuffer(format, real.size * 2).also { vb ->
        vb.put { real.forEach { write((it.a - centre) * scale); write((it.b - centre) * scale) } }
    }.also { println("crease lines: ${file.nameWithoutExtension}, ${real.size} edges") }
}
