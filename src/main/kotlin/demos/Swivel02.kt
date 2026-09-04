import org.openrndr.WindowMultisample
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.isolated
import org.openrndr.draw.parameter
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.meshgenerators.boxMesh
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector3
import org.openrndr.math.smoothstep
import kotlin.math.cos

fun main() {
    application {
        configure {
            width = 1440
            height = 360
            multisample = WindowMultisample.SampleCount(4)
        }
        program {


            // The palette this was written against was not in the file, so the sketch did
            // not build. Three values: the base fill, and the two the shader picks between
            // by which way a face points.
            val colors = listOf(
                ColorRGBa.fromHex("F5F3EE"),
                ColorRGBa.fromHex("9B9B9B"),
                ColorRGBa.fromHex("F5F3EE")
            )

            val boxMesh = boxMesh(220.0, 280.0, 100.0)

            extend(ScreenRecorder()) {
                maximumDuration = 40.0
                frameRate = 60.0
            }

            val rotations = listOf(-45.0, 0.0, 45.0, 0.0)
            val depths = listOf(0.0, 90.0, 0.0, -90.0)

            extend {

                drawer.ortho(-width / 2.0, width / 2.0, -height / 2.0, height / 2.0, -1000.0, 1000.0)
                drawer.lookAt(Vector3(0.0, 300.0, -300.0), Vector3.ZERO)


                val s = seconds * Math.PI * 2.0 * 0.1
                val f = cos(s)
                val f2 = (s / (8*Math.PI)).mod(1.0) * 4.0

                drawer.fill = colors[0]
                drawer.shadeStyle = shadeStyle {

                    fragmentTransform = """
                       
                        if (va_normal.z < va_normal.x && va_normal.z < va_normal.y) {
                            x_fill = p_color0;
                        } else {
                            x_fill = p_color1;
                        }
                    """.trimIndent()
                    parameter("color0", colors[2])
                    parameter("color1", colors[1])
                }
                val t =  10.0 -  (seconds*2.0)
                for (i in -10..10) drawer.isolated {
                    drawer.translate((i+f2) * 230.0, 0.0, f * depths[i.mod(4)])
                    val r = 45.0 * smoothstep(i+0.0, i+2.0, t) * smoothstep(i+5.0, i+3.0, t)
                    drawer.rotate(Vector3.UNIT_Y, f * rotations[i.mod(4)] )
                    drawer.vertexBuffer(boxMesh, DrawPrimitive.TRIANGLES)
                }


            }
        }
    }
}