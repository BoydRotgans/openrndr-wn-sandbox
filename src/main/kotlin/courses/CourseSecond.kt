import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Second course, the fourth step: **isometric, 3D, in fill, with shadow.** The First course's
 * abstract shapes turned to the true isometric view — 45 degrees round and `atan(1/√2)` up, so the
 * three axes foreshorten alike and nothing converges — and now they rise: box by box, on the Opening
 * course's own cycle, each shape comes up out of a plain ground as a block, stands, and goes down
 * again. Soft grey, with an ambient occlusion that darkens the ground beside a block and a wall
 * toward its foot, so the blocks sit in the ground rather than on it. The view drifts slowly.
 *
 *     ./gradlew run -Popenrndr.application=CourseSecondKt
 *
 * `SECOND_TOWER` is the tallest block in pixels, `SECOND_AO` how strong the occlusion is.
 */
fun main() = runCourse("course-4-second", preview = 40.0) { secondCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.secondCourse(): (Drawer, Double) -> Unit {
    val city = SiteCity(Site.load())
    val tower = Env["SECOND_TOWER"]?.toDoubleOrNull() ?: 240.0
    val ao = Env["SECOND_AO"]?.toDoubleOrNull() ?: 0.5
    val iso = Math.toDegrees(atan(1.0 / sqrt(2.0)))

    return { drawer: Drawer, time: Double ->
        city.draw(
            drawer, time,
            SiteCity.View(
                target = Vector2(1920.0 + 1400.0 * sin(2.0 * PI * time / 480.0), 540.0),
                half = 1250.0,
                yaw = 45.0,
                pitch = iso,
                sunAngle = 120.0,
                sunElevation = 34.0
            ),
            SiteCity.Look(
                tower = tower, cycle = true, flat = -1.0,
                toneLow = 0.72, toneHigh = 0.97, litSide = 0.82, darkSide = 0.55, shadow = 0.62,
                groundTone = 0.74, ao = ao, aoReach = 70.0,
                proportional = true
            )
        )
    }
}
