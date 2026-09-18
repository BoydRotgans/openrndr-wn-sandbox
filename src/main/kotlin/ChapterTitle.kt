// ============================================================================ //
//  No `package` declaration, deliberately: Env and usableFont live in the default
//  package, which Kotlin cannot import into a named one.
// ============================================================================ //

import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import slideshow.FPS
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setToFit
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.min

/**
 * A prototype chapter title, **outside the show**.
 *
 * ```
 * ./gradlew run -Popenrndr.application=ChapterTitleKt
 * ```
 *
 * The card at the pane's own size, 1920x1080, with the four chapter titles the show carries —
 * and nothing in `Slideshow.kt`, `show-order.json` or the deck knows it exists. When a version
 * is worth keeping it moves into a drawer beside `ShadowChapterPanel` and `chapterCard` points
 * at it; until then it is a sketch.
 *
 * **The whole card is [title], a pure function of the frame**, which is the rule every drawer in
 * the deck is written to — so whatever this becomes can be lifted into a `Slide` by handing it
 * `stage.frame` instead of the counter here. What is in it now is a placeholder: the title set
 * to the frame, a word at a time rising into place.
 *
 * `→` `←` step the chapters, `r` replays, `p` holds the clock and `.` `,` step it, `s` writes a
 * still, `esc` quits. `TITLE_STILLS` writes one png and quits, `TITLE_AT=0.5,1,2` writes the
 * frames at those seconds, `TITLE_RECORD` films to `video/chapter-title-<n>.mp4`.
 */
fun main() = application {
    val scale = Env["TITLE_WINDOW"]?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "chapter title"
    }

    program {
        // The show's four, by number. Stated here rather than read off the show, because
        // loading the show loads every slide in it — ten seconds for a card that takes none.
        val chapters = Env["TITLE_TEXT"]?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                "De wereld van bouwen",
                "Waardekader en verantwoor-delijkheid",
                "Beton: ruggengraat en transitie",
                "The Circle: een nieuwe manier van denken",
            )
        var chapter = ((Env["TITLE_CHAPTER"]?.toIntOrNull() ?: 1) - 1).coerceIn(0, chapters.lastIndex)

        val ink = ColorRGBa.fromHex(Env["TITLE_INK"] ?: "#FFFFFF")
        val paper = ColorRGBa.fromHex(Env["TITLE_PAPER"] ?: "#000000")
        val margin = Env["TITLE_MARGIN"]?.toDoubleOrNull() ?: 120.0
        val lines = Env["TITLE_LINES"]?.toIntOrNull()
        val beat = frames(Env["TITLE_BEAT"]?.toDoubleOrNull() ?: 0.18)
        val rise = frames(Env["TITLE_RISE"]?.toDoubleOrNull() ?: 0.6).coerceAtLeast(1)

        // Loaded once, large, and scaled down by the fit — the atlas is the resolution.
        val em = 160.0
        val font = loadFont(
            usableFont(Env["TITLE_FONT"] ?: Env["SLIDES_CARD_FONT"] ?: "data/fonts/default.otf"),
            em, characterSet = TYPE_CHARACTERS, contentScale = DETAIL
        )
        val leading = Env["TITLE_LEADING"]?.toDoubleOrNull() ?: 1.0

        /** Everything on the card at [frame] frames since it came up. */
        fun title(drawer: Drawer, text: String, frame: Int) {
            val box = Rectangle(margin, margin, WIDE - 2 * margin, HIGH - 2 * margin)
            val block = font.setToFit(text, box, em, leading, lines)
            val line = leading * em * block.scale
            val top = box.center.y - block.height / 2.0

            drawer.fontMap = font
            drawer.fill = ink
            drawer.stroke = null

            // Word by word, in reading order, a beat apart. Each word rises its own height
            // into place and fades up as it does.
            var n = 0
            block.lines.forEachIndexed { row, words ->
                var x = box.x
                val y = top + (row + 0.78) * line
                for (word in words.split(" ")) {
                    val t = ((frame - n * beat).toDouble() / rise).coerceIn(0.0, 1.0)
                    val e = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)
                    if (t > 0.0) {
                        drawer.fill = ink.opacify(e)
                        drawer.isolated {
                            drawer.translate(x, y + (1.0 - e) * line * 0.4)
                            drawer.scale(block.scale)
                            drawer.text(word, 0.0, 0.0)
                        }
                    }
                    x += font.advanceOf("$word ") * block.scale
                    n++
                }
            }
        }

        // ---- composing: the studios' arrangement -------------------------------------- //
        //
        // Composed at 1920x1080 into a target at twice that, and fitted into the window rather
        // than stretched to it, so a still, a filmed frame and the screen are the same pixels.
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("TITLE_RECORD")) {
            val file = "video/chapter-title-${chapter + 1}.mp4"
            println("filming to $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["TITLE_FPS"]?.toIntOrNull() ?: FPS
                contentScale = 1.0 / scale
                maximumDuration = Env["TITLE_DURATION"]?.toDoubleOrNull() ?: 6.0
            })
        }

        val stills = Env.boolean("TITLE_STILLS")
        val at = Env["TITLE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()
        var taken = 0

        var frame = 0
        var start = 0
        var held = false
        var nudge = 0
        var saveNext = false

        // Key handlers move counters and take no timestamp — the ScreenRecorder note under
        // demo01 in CLAUDE.md.
        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT -> { chapter = (chapter + 1) % chapters.size; start = frame }
                event.key == KEY_ARROW_LEFT -> { chapter = (chapter - 1).mod(chapters.size); start = frame }
            }
            when (event.name) {
                "escape" -> application.exit()
                "r" -> start = frame
                "p" -> held = !held
                "." -> nudge++
                "," -> nudge--
                "s" -> saveNext = true
            }
        }

        extend {
            if (!held) frame++
            frame += nudge
            nudge = 0
            val since = (frame - start).coerceAtLeast(0)

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(paper)
                title(drawer, chapters[chapter], since)
            }

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(4.0))) {
                val name = "screenshots/chapter-title-${chapter + 1}" +
                        (if (timed) "-%.1fs".format(seconds(at[taken])) else "") + ".png"
                val file = File(name)
                file.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(file)
                println("saved ${file.path}")
                saveNext = false
                if (timed) {
                    taken++
                    if (taken >= at.size) application.exit()
                } else if (stills) application.exit()
            }
        }
    }
}

/** Real pixels to one canvas pixel: composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0
