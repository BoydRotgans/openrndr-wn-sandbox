import org.openrndr.application
import org.openrndr.color.ColorRGBa

fun main() = application {
    configure {
        width = 768
        height = 576
    }

    program {

        extend {
            drawer.fill = ColorRGBa.PINK
            drawer.circle(mouse.position, 50.0)

        }
    }
}
