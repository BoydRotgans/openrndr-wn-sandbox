import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.sin

/**
 * The First course, the third step: **flat, in fill, with shadow — and nothing else.** Seen straight
 * down, so still the plan: the Opening course's plates, now standing just far enough up to throw a
 * long shadow on one plain ground. No streets, no texture, three greys — a light roof, a mid ground,
 * a dark shadow — so from above a block is only its roof and its height is read entirely off the
 * shadow, the way the reference's single cube is. The blocks rise and sink box by box on the Opening
 * course's own cycle, and the sun swings slowly so the shadows turn.
 *
 *     ./gradlew run -Popenrndr.application=CourseFirstKt
 *
 * `FIRST_TOWER` is the tallest block in pixels, `FIRST_SUN` the sun's height in degrees.
 */
fun main() = runCourse("course-3-first") {
    val city = SiteCity(Site.load())
    val tower = Env["FIRST_TOWER"]?.toDoubleOrNull() ?: 120.0
    val sun = Env["FIRST_SUN"]?.toDoubleOrNull() ?: 18.0
    val look = SiteCity.Look(
        tower = tower, cycle = true, flat = -1.0,
        paints = listOf(SiteCity.Paint(
            roof = ColorRGBa.fromHex("F2F2F0"), side = ColorRGBa.fromHex("E2E2E0"), shade = ColorRGBa.fromHex("9C9C9A")
        )),
        groundLit = ColorRGBa.fromHex("C4C4C2"),
        groundShade = ColorRGBa.fromHex("7E7E7C")
    )

    return@runCourse { drawer: Drawer, time: Double ->
        city.draw(
            drawer, time,
            SiteCity.View(
                target = Vector2(1920.0, 540.0),
                half = 1920.0,
                pitch = 90.0,
                sunAngle = 135.0 + 20.0 * sin(2.0 * PI * time / 192.0),
                sunElevation = sun
            ),
            look
        )
    }
}
