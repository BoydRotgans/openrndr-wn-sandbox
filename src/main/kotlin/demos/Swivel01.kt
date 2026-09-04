import org.openrndr.WindowMultisample
import org.openrndr.application
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.isolated
import org.openrndr.extra.meshgenerators.boxMesh
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector3
import org.openrndr.math.smoothstep

fun main() {
    application {
        configure {
            width = 1440
            height = 360
            multisample = WindowMultisample.SampleCount(4)
        }
        program {


            val boxMesh = boxMesh(110.0, 370.0, 10.0)

            extend(ScreenRecorder()) {
                maximumDuration = 10.0
                frameRate = 60.0
            }

            extend {

                drawer.ortho(-width / 2.0, width / 2.0, -height / 2.0, height / 2.0, -1000.0, 1000.0)
                drawer.lookAt(Vector3(0.0, 300.0, -300.0), Vector3.ZERO)

                val t =  10.0 -  (seconds*2.0)
                for (i in -5..5) drawer.isolated {
                    drawer.translate(i * 120.0, 0.0, 0.0)
                    val r = 45.0 * smoothstep(i+0.0, i+2.0, t) * smoothstep(i+5.0, i+3.0, t)
                    drawer.rotate(Vector3.UNIT_Y, r )
                    drawer.vertexBuffer(boxMesh, DrawPrimitive.TRIANGLES)
                }


            }
        }
    }
}