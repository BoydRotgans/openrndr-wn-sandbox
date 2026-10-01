package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The presenter clicker's screen button, held: a ring on the wall that fills, and once it is full the
 * show goes back to its first slide exactly as `0` does. Let go before then and the ring fades and the
 * show carries on where it was — so a brush of the button costs nothing, and starting over has to be
 * meant. How long the hold takes is [Settings.holdRestart].
 *
 * **It is drawn on the window, after the finished frame**, like the grid and the debug overlay, so it
 * never reaches a still, a preview or a film. It is laid out in canvas pixels and mapped through the
 * fit, so it is the same size on the wall whatever window it is shown in; the wall under it is dimmed
 * so it reads over any slide. There is a ring on each projector, since anything read stays in one of
 * them and the presenter may be facing either.
 */
class HoldRestart(private val font: FontImageMap?) {

    /** [progress] is how full the ring is, 0 to 1; [alpha] how present the whole of it is. */
    fun draw(drawer: Drawer, shown: Rectangle, canvas: Rectangle, panelWidth: Int?, progress: Double, alpha: Double) {
        if (alpha <= 0.0) return
        val k = shown.width / canvas.width
        val centres = if (panelWidth != null && panelWidth < canvas.width) listOf(panelWidth / 2.0, (panelWidth + canvas.width) / 2.0)
            else listOf(canvas.width / 2.0)
        drawer.isolated {
            drawer.translate(shown.corner)
            drawer.scale(k)
            drawer.shadeStyle = null
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(VEIL * alpha)
            drawer.rectangle(0.0, 0.0, canvas.width, canvas.height)
            for (x in centres) {
                val c = Vector2(x, canvas.height / 2.0 - LIFT)
                drawer.fill = null
                drawer.strokeWeight = STROKE
                drawer.stroke = ColorRGBa.WHITE.opacify(0.22 * alpha)
                drawer.circle(c, RADIUS)
                if (progress > 0.0) {
                    drawer.stroke = Palette.RED.opacify(alpha)
                    drawer.lineCap = LineCap.ROUND
                    drawer.lineStrip(arc(c, progress.coerceAtMost(1.0)))
                }
                font?.let { f ->
                    drawer.fontMap = f
                    drawer.stroke = null
                    drawer.fill = ColorRGBa.WHITE.opacify(alpha)
                    val w = LABEL.sumOf { ch -> f.glyphMetrics[ch]?.advanceWidth ?: 0.0 }
                    drawer.text(LABEL, c.x - w / 2.0, c.y + RADIUS + STROKE + 70.0)
                }
            }
        }
    }

    /** The filled part of the ring, from the top, clockwise on the wall: a line strip, which needs no stencil. */
    private fun arc(c: Vector2, progress: Double): List<Vector2> {
        val n = maxOf(2, (SEGMENTS * progress).toInt() + 1)
        return List(n) { i ->
            val a = -PI / 2.0 + 2.0 * PI * progress * i / (n - 1)
            c + Vector2(cos(a), sin(a)) * RADIUS
        }
    }

    companion object {
        /** In canvas pixels: the ring's radius and weight, and how far above the middle it stands. */
        const val RADIUS = 170.0
        const val STROKE = 22.0
        const val LIFT = 40.0
        const val VEIL = 0.55
        const val SEGMENTS = 180
        const val LABEL = "opnieuw beginnen"
        /** The label's size, loaded once by the driver. */
        const val LABEL_SIZE = 44.0
    }
}
