package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import kotlin.math.abs

/**
 * A shape one slide leaves standing, in a form the next slide can draw exactly.
 *
 * This is what makes a **seamless** cut possible, which an ordinary cut is not: two slides
 * that both "draw the same element in the middle" agree only to the accuracy of two
 * separate pieces of arithmetic, and a couple of pixels of disagreement is exactly what the
 * eye catches on a hard cut. Handing the shape over instead means the second slide is not
 * reproducing the first — it is drawing the first's own geometry at the first's own size.
 *
 * Everything here is in the units the *receiving* slide draws in: triangles normalised to
 * height 1, centred on the origin, **y down** like a pane rather than y up like a world,
 * and [height] the pane pixels the shape stood at. It carries its [ink] and [paper] too, so
 * the next slide can take the ground it is joining rather than being told it twice.
 */
class Mark(
    val triangles: List<Vector2>,
    val height: Double,
    val ink: ColorRGBa,
    val paper: ColorRGBa
) {
    /** Half its width, as a fraction of its height — where a link should meet its edge. */
    val halfWidth: Double = triangles.maxOfOrNull { abs(it.x) } ?: 0.5

    /**
     * Built once, on the first frame that asks for it: a [Mark] is made in one slide's
     * `load` and drawn in another's, and the buffer belongs to whichever asks first.
     */
    private var buffer: VertexBuffer? = null

    /** Draws it centred on [centre], at [height] pane pixels unless another is given. */
    fun draw(drawer: Drawer, centre: Vector2, height: Double = this.height) {
        if (triangles.isEmpty()) {
            drawer.circle(centre, height / 2.0)
            return
        }
        val vb = buffer ?: build().also { buffer = it }
        drawer.isolated {
            drawer.translate(centre)
            drawer.scale(height, height)
            drawer.vertexBuffer(vb, DrawPrimitive.TRIANGLES)
        }
    }

    private fun build(): VertexBuffer {
        val vb = vertexBuffer(vertexFormat { position(3) }, triangles.size)
        vb.put { triangles.forEach { write(Vector3(it.x, it.y, 0.0)) } }
        return vb
    }
}
