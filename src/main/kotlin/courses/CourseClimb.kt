// The Sketches tab: what the sketch's own run is called, then its variants, one a line —
// name | main class (empty for this file's) | .env values | what it is.
// sketch-default: default angle
// sketch-variant: view 1, from above | | COURSE_VIEW=1 | the first saved angle: 39° round, 35° up, looking down on the build
// sketch-variant: view 2, from below | | COURSE_VIEW=2 | the second saved angle: 42° round, 28° below, looking up at the storeys
// sketch-variant: view 3, level | | COURSE_VIEW=3 | the third saved angle: almost square on and level with the build
// sketch-variant: lowered in, cutting between angles | | CLIMB_LEVEL_TIME=120 CLIMB_SWEEP=0.92 CLIMB_ARRIVE=3.5 CLIMB_ORDER=screen CLIMB_EDGES=lines CLIMB_PIECES=WAND_27,WAND_17,WAND_23,WAND_9 CLIMB_ANGLES=60,18/42,-27.9/39.4,34.6/1.2,-1.1 | an easy pace for the background: a storey every two minutes, built left to right across the frame, each panel coming down from just above the frame over 3.5 s, and a hard cut to another angle as each storey starts
// sketch-variant: layers from above | CourseLayersKt | | four kinds of wall panel lying flat, filling a grid turned 45°, rows red and blue by turns over a storey coloured the other way, each piece lowered in from above the frame
// sketch-variant: lowered in and layers, by turns | CourseClimbMixKt | | the calm climb from three angles and the layers from above, a hard cut from one to the next every minute
/**
 * v2, the climb: the panels built storey upon storey for ever, the camera rising with the build.
 * The stack engine of [stackCourse] with the [storeys] layout; `CLIMB_*` in `.env` steers it —
 * `CLIMB_LEVEL_TIME` the seconds a storey takes, `CLIMB_FOG` how fast the storeys below go dark.
 *
 *     ./gradlew run -Popenrndr.application=CourseClimbKt
 */
fun main() = runCourse("course-v2-climb", preview = 60.0) { stackCourse(storeys) }
