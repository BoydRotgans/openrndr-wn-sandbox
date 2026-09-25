import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.Drawer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import java.io.File

/**
 * The five course walls in one window, to be watched and talked about rather than filmed: the
 * sandbox's twin of `SlideStudio`.
 *
 *     ./gradlew run -Popenrndr.application=CourseStudioKt
 *
 * **`r` films the wall** — every frame the window shows, from the press until `q` (and only `q`: `r`
 * again leaves it running; closing the window finishes the file rather than losing it), into `video/courses/<course>[-<element>]-<date>.mp4` at 60 a second and
 * `COURSE_RECORD_SCALE` of the canvas: the picture alone, under the concrete wall when that is on,
 * with everything steered, stepped and switched while it runs. A red mark in the corner says it is
 * filming and is never in the clip. The plate is hidden until `h` (`COURSE_PLATE=true` opens with it).
 *
 * `↑` `↓` or `1`–`9` switch course (6 to 8 are v2, 9 is v3, and the v4 flow and its v2 are the tenth and eleventh, below it), `→` `←` jump ten seconds, `space` holds the clock, `.` `,` step
 * it a frame, `0` goes back to the start, `p` writes the frame on screen to `screenshots/courses/`
 * with its course and second in the name, `h` shows and hides the plate in the corner, and `w` switches the
 * show's concrete wall on and off — the very overlay the show lays over the wall, off its own
 * `SLIDES_CONCRETE*` keys, fixed to the frame and blended over everything (`COURSE_WALL` is where
 * it starts, on).
 *
 * **The cursor steers the camera** of the walls that read it (the v2 stacks and the v3 grid), a full turn both ways:
 * across the window goes once round, down it once up, over the top and under — the default angle
 * at the middle of the window. **`s` saves the angle** it stands at and the cursor goes on steering, so several can
 * be saved in a row; `j` and
 * `k` step forward and back through the angles saved for this course, holding on each (`]` and `[`
 * do the same on every course, and on the grid, which stands one element, `j` and `k` step that
 * element through the catalogue instead), `x` deletes
 * the one held, and `c` lets go of it and hands the camera back to the cursor (`c` again parks the
 * cursor too). The angles are kept per course in `COURSE_VIEWS`, so they survive a restart, and
 * `COURSE_VIEW=n` holds a still or a clip on the n-th.
 *
 * **One clock for all five**, because they stand on one site and one box rhythm: switching course at
 * 40 s shows the same boxes at the same point in their cycle, one step further along. A course is
 * built the first time it is asked for, so the window opens at once and each later switch costs
 * that course's load only once. `COURSE=4` opens on a course and `COURSE_FROM` at a second.
 *
 * The plate naming the course and the second is drawn on the window, not into the canvas, so a
 * still written with `s` is the wall alone — the same arrangement as the show's debug view.
 */
fun main() = application {
    configure {
        width = 1920
        height = 540
        title = "course studio"
        windowResizable = true
    }
    program {
        class Course(val key: String, val title: String, val pieces: PieceChoice? = null, val make: () -> (Drawer, Double) -> Unit) {
            var frame: ((Drawer, Double) -> Unit)? = null
        }
        // The walls are CourseWalls' list, so a wall added there is a course here and a wall the
        // organizer can place in the show; the grid is handed the studio's own choice of element, so
        // j and k can step it.
        val gridPieces = PieceChoice.fromEnv()
        val courses = CourseWalls.all.map { w ->
            if (w.key == "course-v3-grid") Course(w.key, w.title, gridPieces) { gridCourse(gridPieces) }
            else Course(w.key, w.title) { w.make(this) }
        }
        var index = ((Env["COURSE"]?.toIntOrNull() ?: 1) - 1).coerceIn(0, courses.size - 1)
        var time = Env["COURSE_FROM"]?.toDoubleOrNull() ?: 0.0
        var paused = false
        // The plate and the saved views are hidden until `h` asks for them, so the window is the wall.
        var plate = Env.boolean("COURSE_PLATE")

        val canvas = renderTarget(3840, 1080, multisample = BufferMultisample.SampleCount(8)) { colorBuffer(); depthBuffer() }
        val resolved = colorBuffer(3840, 1080)
        val font = runCatching { loadFont("data/fonts/default.otf", 18.0) }.getOrNull()

        // The show's own concrete wall, off the show's own keys, laid over the picture where it meets
        // the window — so what is judged here is what the room will see, and a still is still clean.
        val wall = slideshow.ConcreteWall.load(
            Env["SLIDES_CONCRETE"],
            Env["SLIDES_CONCRETE_MIX"]?.toDoubleOrNull() ?: 1.0,
            Env["SLIDES_CONCRETE_SCALE"]?.toDoubleOrNull() ?: 1.0,
            Env["SLIDES_CONCRETE_FLOOR"]?.toDoubleOrNull() ?: 0.0
        )
        val wallStyle = wall?.style(org.openrndr.math.Vector2(3840.0, 1080.0))
        var wallOn = wall != null && (Env["COURSE_WALL"]?.let { it == "true" } ?: true)

        // `r` films the wall as it plays, until `q`: every frame the window shows, at the studio's own 60 a second,
        // straight into ffmpeg — steering, stepping, switching and holding all end up in the clip. It
        // is the picture alone under the concrete wall when that is on, never the plate or the list,
        // at COURSE_RECORD_SCALE of the canvas.
        val recordScale = (Env["COURSE_RECORD_SCALE"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.1, 1.0)
        val rw = (3840 * recordScale).toInt() / 2 * 2
        val rh = (1080 * recordScale).toInt() / 2 * 2
        val recordTarget = renderTarget(rw, rh) { colorBuffer() }
        val recordPixels = java.nio.ByteBuffer.allocateDirect(rw * rh * 4)
        var clip: slideshow.Clip? = null
        var recorded = 0
        fun stopRecording() {
            val c = clip ?: return
            clip = null
            println("course studio: wrote ${c.file.path}, ${c.close()} frames")
        }
        ended.listen { stopRecording() }

        fun current(): (Drawer, Double) -> Unit {
            val c = courses[index]
            return c.frame ?: run {
                val started = System.currentTimeMillis()
                c.make().also { c.frame = it; println("course studio: ${c.title} loaded in ${System.currentTimeMillis() - started} ms") }
            }
        }

        // The cursor steers the camera of any wall that reads it; a click stores the angle and holds it.
        var steering = true
        var held = -1                          // which stored view is held, -1 for none
        fun hold(i: Int) {
            val views = CourseViews.of(courses[index].key)
            if (views.isEmpty()) { held = -1; CourseControl.fixed = null; return }
            held = i.mod(views.size)
            CourseControl.fixed = views[held]
            println("course studio: holding view ${held + 1} of ${views.size} — ${"%.1f".format(views[held].x)}° round, ${"%.1f".format(views[held].y)}° up")
        }
        fun release() { held = -1; CourseControl.fixed = null }
        fun switchTo(i: Int) { index = i; release() }
        CourseViews.asked(courses[index].key)?.let { CourseControl.fixed = it; held = (Env["COURSE_VIEW"]!!.toInt() - 1) }
        // Held to the window, so a cursor past its edge cannot turn the camera below the ground.
        mouse.moved.listen {
            if (steering) CourseControl.pointer = org.openrndr.math.Vector2(
                (it.position.x / width).coerceIn(0.0, 1.0), (it.position.y / height).coerceIn(0.0, 1.0))
        }
        // Saving only stores: the cursor goes on steering, so the next angle can be found and saved in
        // turn. Held, the camera stands still and saving would only store the same angle again.
        fun save() {
            if (held >= 0) { println("course studio: view ${held + 1} is held — c to steer again, then s"); return }
            val angle = CourseControl.current ?: return
            val course = courses[index].key
            val existing = CourseViews.of(course).indexOfFirst { v -> v.distanceTo(angle) < 0.5 }
            if (existing >= 0) { println("course studio: already saved as view ${existing + 1}"); return }
            val n = CourseViews.add(course, angle)
            println("course studio: saved view ${n + 1} for $course — ${"%.1f".format(angle.x)}° round, ${"%.1f".format(angle.y)}° up")
        }
        keyboard.keyDown.listen {
            when (it.name) {
                "arrow-up" -> switchTo((index - 1 + courses.size) % courses.size)
                "arrow-down" -> switchTo((index + 1) % courses.size)
                "1", "2", "3", "4", "5", "6", "7", "8", "9" -> switchTo(it.name.toInt() - 1)
                "s" -> save()
                // j and k step the element on a course that stands one, and the saved views on the rest;
                // ] and [ step the saved views on every course.
                "j", "k" -> {
                    val pieces = courses[index].pieces
                    val by = if (it.name == "j") 1 else -1
                    if (pieces != null) { pieces.step(by); println("course studio: element ${pieces.index + 1}/${pieces.names.size} ${pieces.name}") }
                    else hold(if (by > 0) held + 1 else if (held < 0) -1 else held - 1)
                }
                "]" -> hold(held + 1)
                "[" -> hold(if (held < 0) -1 else held - 1)
                "x" -> {
                    // The held view, or with none held the last one saved; the highlight moves on to
                    // the view that takes its place, so pressing again keeps clearing.
                    val course = courses[index].key
                    val views = CourseViews.of(course)
                    if (views.isNotEmpty()) {
                        val gone = if (held >= 0) held else views.size - 1
                        CourseViews.remove(course, gone)
                        println("course studio: deleted view ${gone + 1} of $course")
                        if (held >= 0 && CourseViews.of(course).isNotEmpty()) hold(gone.coerceAtMost(CourseViews.of(course).size - 1))
                        else release()
                    }
                }
                "arrow-right" -> time += 10.0
                "arrow-left" -> time = (time - 10.0).coerceAtLeast(0.0)
                "space" -> paused = !paused
                "." -> { paused = true; time += 1.0 / 60.0 }
                "," -> { paused = true; time = (time - 1.0 / 60.0).coerceAtLeast(0.0) }
                "0" -> time = 0.0
                "h" -> plate = !plate
                // r starts filming and only q stops it; r while filming leaves it running.
                "q" -> stopRecording()
                "r" -> if (clip != null) println("course studio: already recording — q stops it") else {
                    val piece = courses[index].pieces?.let { "-${it.name}" } ?: ""
                    val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss"))
                    val c = slideshow.Clip(File("video/courses/${courses[index].key}$piece-$stamp.mp4"), rw, rh, 60)
                    if (c.ok) { clip = c; recorded = 0; println("course studio: recording to ${c.file.path}, ${rw}x$rh") }
                }
                "w" -> { wallOn = wall != null && !wallOn; println("course studio: concrete wall ${if (wallOn) "on" else "off"}") }
                "c" -> if (held >= 0) release() else { steering = !steering; if (!steering) CourseControl.pointer = null }
                "p" -> {
                    File("screenshots/courses").mkdirs()
                    val file = File("screenshots/courses/${courses[index].key}-studio-${"%05.1f".format(time)}s.png")
                    resolved.saveToFile(file, async = false)
                    println("course studio: saved ${file.path}")
                }
            }
        }

        extend {
            val frame = current()
            if (!paused) time += 1.0 / 60.0
            drawer.isolatedWithTarget(canvas) {
                ortho(canvas)
                clear(ColorRGBa.BLACK)
                frame(this, time)
            }
            canvas.colorBuffer(0).copyTo(resolved)
            clip?.let { c ->
                drawer.isolatedWithTarget(recordTarget) {
                    ortho(recordTarget)
                    if (wallOn) shadeStyle = wallStyle
                    image(resolved, 0.0, 0.0, rw.toDouble(), rh.toDouble())
                }
                recordTarget.colorBuffer(0).read(recordPixels, org.openrndr.draw.ColorFormat.RGBa, org.openrndr.draw.ColorType.UINT8)
                c.frame(recordPixels)
                recorded++
            }

            // Fitted into the window, never stretched.
            val scale = minOf(width / 3840.0, height / 1080.0)
            val w = 3840.0 * scale
            val h = 1080.0 * scale
            drawer.clear(ColorRGBa.BLACK)
            drawer.isolated {
                if (wallOn) drawer.shadeStyle = wallStyle
                drawer.image(resolved, (width - w) / 2.0, (height - h) / 2.0, w, h)
            }

            // A red mark while filming, on the window only, so it never lands in the clip.
            if (clip != null) {
                drawer.fill = ColorRGBa.fromHex("E3141B")
                drawer.stroke = null
                drawer.circle(width - 18.0, 18.0, 7.0)
                if (font != null) {
                    drawer.fontMap = font
                    val label = "REC ${"%.1f".format(recorded / 60.0)} s   q stops"
                    drawer.fill = ColorRGBa.WHITE
                    drawer.text(label, width - 36.0 - label.length * 9.5, 24.0)
                }
            }

            if (plate && font != null) {
                val stored = CourseViews.of(courses[index].key).size
                val view = when {
                    held >= 0 -> "view ${held + 1}/$stored held"
                    steering -> "cursor" + if (stored > 0) " · $stored stored" else ""
                    else -> "default view" + if (stored > 0) " · $stored stored" else ""
                } + if (wallOn) " · wall" else ""
                val angle = CourseControl.current?.let { "  ${"%.0f".format(it.x)}° / ${"%.0f".format(it.y)}°" } ?: ""
                val element = courses[index].pieces?.let { "    ${it.name} ${it.index + 1}/${it.names.size}" } ?: ""
                val line = "${index + 1}  ${courses[index].title}$element    ${"%.1f".format(time)} s${if (paused) "  (paused)" else ""}    $view$angle"
                drawer.fontMap = font
                drawer.stroke = null
                drawer.fill = ColorRGBa.BLACK.opacify(0.7)
                drawer.rectangle(10.0, 10.0, line.length * 9.5 + 24.0, 32.0)
                drawer.fill = ColorRGBa.WHITE
                drawer.text(line, 22.0, 32.0)

                // The saved angles, one a row under the plate: the held one highlighted in red, and
                // one the cursor is standing on marked, so it is plain which is which before x.
                val views = CourseViews.of(courses[index].key)
                val here = CourseControl.current
                views.forEachIndexed { i, v ->
                    val y = 48.0 + i * 26.0
                    val on = i == held
                    val under = !on && held < 0 && here != null && here.distanceTo(v) < 0.5
                    val row = "${if (on) "▶" else " "} ${i + 1}   ${"%.1f".format(v.x)}° round   ${"%.1f".format(v.y)}° up" +
                        when { v.y < 0.0 -> "   from below"; v.y > 90.0 -> "   over the top"; else -> "" }
                    drawer.fill = when {
                        on -> ColorRGBa.fromHex("E3141B")
                        under -> ColorRGBa.WHITE.opacify(0.35)
                        else -> ColorRGBa.BLACK.opacify(0.7)
                    }
                    drawer.rectangle(10.0, y, 330.0, 24.0)
                    drawer.fill = ColorRGBa.WHITE.opacify(if (on || under) 1.0 else 0.8)
                    drawer.text(row, 18.0, y + 17.0)
                }
                if (views.isNotEmpty()) {
                    val y = 48.0 + views.size * 26.0
                    val hint = if (courses[index].pieces != null) "s save   [ ] hold   j k element   c cursor   x delete"
                        else "s save   j k hold   c cursor   x delete"
                    drawer.fill = ColorRGBa.BLACK.opacify(0.7)
                    drawer.rectangle(10.0, y, maxOf(330.0, hint.length * 8.5 + 16.0), 24.0)
                    drawer.fill = ColorRGBa.WHITE.opacify(0.55)
                    drawer.text(hint, 18.0, y + 17.0)
                }
            }
        }
    }
}
