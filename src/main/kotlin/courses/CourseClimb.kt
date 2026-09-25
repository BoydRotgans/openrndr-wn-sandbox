// The Sketches tab: what the sketch's own run is called, then its variants, one a line —
// name | main class (empty for this file's) | .env values | what it is.
// sketch-default: default angle
// sketch-variant: view 1, from above | | COURSE_VIEW=1 | the first saved angle: 39° round, 35° up, looking down on the build
// sketch-variant: view 2, from below | | COURSE_VIEW=2 | the second saved angle: 42° round, 28° below, looking up at the storeys
// sketch-variant: view 3, level | | COURSE_VIEW=3 | the third saved angle: almost square on and level with the build
/**
 * v2, the climb: the panels built storey upon storey for ever, the camera rising with the build.
 * The stack engine of [stackCourse] with the [storeys] layout; `CLIMB_*` in `.env` steers it —
 * `CLIMB_LEVEL_TIME` the seconds a storey takes, `CLIMB_FOG` how fast the storeys below go dark.
 *
 *     ./gradlew run -Popenrndr.application=CourseClimbKt
 */
fun main() = runCourse("course-v2-climb", preview = 60.0) { stackCourse(storeys) }
