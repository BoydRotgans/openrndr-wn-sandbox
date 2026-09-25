import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dessert, the last step: **the Second course, completed, in Willy Naessens' colours.** The same
 * field of shapes in the same isometric view, but every block now stands — nothing rises or sinks
 * any more — and the sun is low, so each throws a long shadow. The buildings are white, the floor
 * is the house blue, and every shadow is red. The same ambient occlusion as the Second course
 * keeps it soft: the floor darkens beside a building and a wall toward its foot.
 *
 * **The picture is all but frozen; the light is what moves.** The view barely drifts, and the sun
 * goes once round the town every `DESSERT_TURN` seconds, rising and falling a little as it goes, so
 * the red shadows sweep round every block — long from the towers, short from the low ones — and
 * which walls stand pale changes as it passes. A whole number of turns in a period, so it loops.
 *
 *     ./gradlew run -Popenrndr.application=CourseDessertKt
 *
 * `DESSERT_SUN` is the sun's height in degrees (lower is longer shadows), `DESSERT_AO` the occlusion,
 * and `DESSERT_INSET` how much of its cell a building takes — below 1 the blue floor shows between them.
 */
fun main() = runCourse("course-5-dessert", preview = 40.0) { dessertCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.dessertCourse(): (Drawer, Double) -> Unit {
    val city = SiteCity(Site.load())
    val sun = Env["DESSERT_SUN"]?.toDoubleOrNull() ?: 30.0
    val turn = Env["DESSERT_TURN"]?.toDoubleOrNull() ?: 180.0
    val inset = Env["DESSERT_INSET"]?.toDoubleOrNull() ?: 0.55
    val ao = Env["DESSERT_AO"]?.toDoubleOrNull() ?: 0.5
    val iso = Math.toDegrees(atan(1.0 / sqrt(2.0)))
    val red = ColorRGBa.fromHex("C8141E")
    val look = SiteCity.Look(
        tower = 150.0, flat = -1.0,
        paints = listOf(SiteCity.Paint(roof = ColorRGBa.fromHex("FFFFFF"), side = ColorRGBa.fromHex("EEF0F4"), shade = red, back = ColorRGBa.fromHex("C9CED8"))),
        groundLit = ColorRGBa.fromHex("1E3A72"),
        groundShade = red,
        ao = ao, aoReach = 70.0, inset = inset, heightPower = 2.0, proportional = true
    )

    return { drawer: Drawer, time: Double ->
        city.draw(
            drawer, time,
            SiteCity.View(
                // A breath of movement, a few pixels over the whole turn, so it is not a still.
                target = Vector2(1920.0 + 40.0 * sin(2.0 * PI * time / turn), 540.0),
                half = 1250.0,
                yaw = 45.0,
                pitch = iso,
                sunAngle = 120.0 + 360.0 * time / turn,
                // Lower on one side of the turn than the other, so the shadows lengthen and shorten as they sweep.
                sunElevation = sun - 6.0 * sin(2.0 * PI * time / turn)
            ),
            look
        )
    }
}
