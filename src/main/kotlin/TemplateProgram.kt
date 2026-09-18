import org.openrndr.application
import org.openrndr.color.ColorRGBa

/**
 * The empty OPENRNDR program the project started from: the default ./gradlew run.
 */
fun main() = application {
    configure {
        width = 768
        height = 576
    }

    program {
        sketchPreview("TemplateProgram", at = 2.0)

        extend {
            drawer.fill = ColorRGBa.PINK
            drawer.circle(mouse.position, 50.0)

        }
    }
}
