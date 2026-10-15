import org.openrndr.Program
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.floor

// sketch-variant-of: CourseClimb
/**
 * v2, the climb by turns: the calm climb from its three angles and the climb in layers from above, a
 * hard cut from one shot to the next, never a camera move between them.
 *
 * Two scenes and no third drawing: [stackCourse] with the [storeys] layout at the calm pace of the
 * variant "lowered in, cutting between angles" — a storey every two minutes, each panel lowered in from
 * above the frame — held on one angle a shot, and [layersCourse] as it stands. Each runs on the one
 * clock whether it is in shot or not, so a cut back to a scene finds its build further on.
 *
 * `CLIMB_MIX` lists the shots, `climb:yaw,pitch` or `layers`, apart by `/`; `CLIMB_MIX_SEGMENT` is how
 * long each stands. The layers come back between the climb's angles, in the place of its square-on view.
 *
 *     ./gradlew run -Popenrndr.application=CourseClimbMixKt
 */
fun main() = runCourse("course-v2-climb-mix", preview = 30.0) { climbMixCourse() }

fun Program.climbMixCourse(): (Drawer, Double) -> Unit {
    // The climb's calm settings, the variant's own; its angle comes from the shot, so no cut list. It
    // mixes the same kinds of panel the layers lie in, read off the one key so the two cannot differ.
    val calm = mapOf(
        "CLIMB_LEVEL_TIME" to "120", "CLIMB_SWEEP" to "0.92", "CLIMB_ARRIVE" to "3.5",
        "CLIMB_ORDER" to "screen", "CLIMB_ANGLES" to "", "CLIMB_EDGES" to "lines",
        "CLIMB_PIECES" to (Env["LAYERS_PIECES"] ?: "WAND_27,WAND_17,WAND_23,WAND_9")
    ) + listOf("DIM", "DIM_SIZE", "DIM_RED", "DIM_BLUE").mapNotNull { k -> Env["LAYERS_$k"]?.let { "CLIMB_$k" to it } }
    val climb = Env.with(calm) { stackCourse(storeys) }
    val layers = layersCourse()

    class Shot(val layers: Boolean, val angle: Vector2?)
    val shots = (Env["CLIMB_MIX"] ?: "climb:60,18/layers/climb:42,-27.9/layers/climb:39.4,34.6/layers")
        .split("/").map { it.trim() }.filter { it.isNotEmpty() }.map { s ->
            if (s.startsWith("layers")) Shot(true, null)
            else Shot(false, s.substringAfter(":", "").split(",").mapNotNull { it.trim().toDoubleOrNull() }
                .takeIf { it.size >= 2 }?.let { Vector2(it[0], it[1]) })
        }
    val segment = (Env["CLIMB_MIX_SEGMENT"]?.toDoubleOrNull() ?: 60.0).coerceAtLeast(1.0)

    return { drawer: Drawer, time: Double ->
        val shot = shots[floor(time / segment).toInt().mod(shots.size)]
        if (shot.layers) layers(drawer, time)
        else Env.with(calm) {
            // A view held from outside (a stored COURSE_VIEW) still wins; otherwise the shot's angle.
            val held = CourseControl.fixed
            if (held == null && shot.angle != null) CourseControl.fixed = shot.angle
            try { climb(drawer, time) } finally { CourseControl.fixed = held }
        }
    }
}
