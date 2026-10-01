package slideshow

import org.openrndr.Program
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3

/**
 * A backdrop taking the stage behind one wide, soft front: the wall, already moving, uncovered from
 * the left edge to the right across both projectors, the edge [soft] of the wall wide so nothing
 * about it reads as a line. At the end the whole wall is uncovered and the picture is the wall to the
 * pixel, so the driver hands over to the wall itself on the next frame with nothing to see.
 *
 * **No grid, because anything that cuts the wall into pieces cuts what is on it.** Tiles opening one
 * by one were tried first (28 September) and turned the arrival wall's pen line and its lettering into
 * scattered fragments, and the gaps between tiles into a grid over every light wall. A front has no
 * pieces: a line on the wall is only ever more or less uncovered along its length.
 */
class SweepWallBuild(
    /** How wide the front's soft edge is, as a share of the wall's width. */
    private val soft: Double = 0.6
) : WallBuild {
    private var quad: VertexBuffer? = null

    private val style by lazy {
        shadeStyle {
            fragmentTransform = """
                vec2 uv = clamp(v_worldPosition.xy / p_size, 0.0, 1.0);
                x_fill = texture(p_picture, vec2(uv.x, 1.0 - uv.y));
                // Uncovered left of the front: 1 behind it, 0 a soft width ahead of it.
                float a = clamp((p_front + p_soft - uv.x) / p_soft, 0.0, 1.0);
                x_fill.a = a * a * (3.0 - 2.0 * a);
            """
        }
    }

    override fun load(program: Program, width: Int, height: Int) {
        val w = width.toDouble()
        val h = height.toDouble()
        quad = vertexBuffer(vertexFormat { position(3) }, 6).also { vb ->
            vb.put { listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to 0.0, w to h, 0.0 to h).forEach { (x, y) -> write(Vector3(x, y, 0.0)) } }
        }
    }

    override fun draw(drawer: Drawer, picture: ColorBuffer, width: Double, height: Double, progress: Double) {
        val vb = quad ?: run { drawer.image(picture, 0.0, 0.0, width, height); return }
        val p = progress.coerceIn(0.0, 1.0)
        val eased = p * p * (3.0 - 2.0 * p)
        // From a soft width left of the wall, where nothing is uncovered, to its right edge, where all of it is.
        style.parameter("front", -soft + (1.0 + soft) * eased)
        style.parameter("soft", soft)
        style.parameter("picture", picture)
        style.parameter("size", Vector2(width, height))
        drawer.shadeStyle = style
        drawer.vertexBuffer(vb, DrawPrimitive.TRIANGLES)
        drawer.shadeStyle = null
    }
}
