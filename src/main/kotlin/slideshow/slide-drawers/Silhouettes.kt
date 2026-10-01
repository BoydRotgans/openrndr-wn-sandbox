package slideshow.drawers

import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One figure out of the people obj, flattened: its triangles, feet on y = 0, y up, centred across. */
class Silhouette(val triangles: List<Vector2>, val height: Double)

/**
 * Every group in the people obj as a flat silhouette, in the file's order.
 *
 * The file is Y up with the feet on y = 0 and centimetres. Each figure is seen along the direction
 * that shows it widest: the principal axis of its footprint in the ground plane is the direction it
 * is *broadest across*, so projecting onto that axis and looking along its perpendicular gives the
 * fullest silhouette the model has. Coordinates come back divided by [unit] — `Crowd` hands its
 * adult height so a figure is in adults, the to-scale wall hands 0.1 so it is in millimetres.
 *
 * Shared by [Crowd] and `ScaleScene`, so the people in the talk and the people standing beside the
 * catalogue are the same people.
 */
fun readSilhouettes(file: File, unit: Double): List<Silhouette> {
    if (!file.isFile) return emptyList()
    val verts = ArrayList<Vector3>()
    val groups = LinkedHashMap<String, MutableList<IntArray>>()
    var current = groups.getOrPut("") { mutableListOf() }
    file.forEachLine { line ->
        when {
            line.startsWith("v ") -> {
                val t = line.trim().split(Regex("\\s+"))
                verts += Vector3(t[1].toDouble(), t[2].toDouble(), t[3].toDouble())
            }
            line.startsWith("g ") -> current = groups.getOrPut(line.substring(2).trim()) { mutableListOf() }
            line.startsWith("f ") -> {
                val ids = line.substring(2).trim().split(Regex("\\s+")).map { it.substringBefore('/').toInt() - 1 }
                for (k in 1 until ids.size - 1) current += intArrayOf(ids[0], ids[k], ids[k + 1])
            }
        }
    }

    return groups.values.filter { it.isNotEmpty() }.map { faces ->
        val used = faces.flatMap { it.toList() }.distinct().map { verts[it] }
        val floor = used.minOf { it.y }
        val height = (used.maxOf { it.y } - floor) / unit

        // the broadest direction across the footprint
        val mx = used.sumOf { it.x } / used.size
        val mz = used.sumOf { it.z } / used.size
        var sxx = 0.0; var szz = 0.0; var sxz = 0.0
        for (v in used) { val dx = v.x - mx; val dz = v.z - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz }
        val theta = 0.5 * atan2(2.0 * sxz, sxx - szz)
        val ax = cos(theta); val az = sin(theta)

        fun across(v: Vector3) = (v.x - mx) * ax + (v.z - mz) * az
        val left = used.minOf { across(it) }
        val right = used.maxOf { across(it) }
        val middle = (left + right) / 2.0

        val triangles = faces.flatMap { f ->
            f.map { i -> val v = verts[i]; Vector2((across(v) - middle) / unit, (v.y - floor) / unit) }
        }
        Silhouette(triangles, height)
    }
}
