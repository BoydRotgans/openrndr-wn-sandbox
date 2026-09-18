// ============================================================================ //
//  No `package` declaration, deliberately: Env and usableFont live in the default
//  package, which Kotlin cannot import into a named one.
// ============================================================================ //

import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import slideshow.FPS
import slideshow.drawers.LongShadow
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.min

/**
 * Long shadow type in a window of its own: the chapter title as a plan of towers rising out of the
 * floor, throwing long shadows under a setting sun, on concrete.
 *
 * ```
 * ./gradlew run -Popenrndr.application=LongShadowTypeKt
 * ```
 *
 * The effect is [LongShadow], the very class the show's chapter card draws through
 * (`SLIDES_CARD_STYLE=longshadow`), and both are built by [longShadowFromEnv] off the same
 * `LONGSHADOW_*` keys — so a value tuned here is the value the show runs. What the sketch adds is
 * the window, the concrete (the show lays its own over every frame) and `LONGSHADOW_IMAGE`, a
 * drawn title read a block of ink at a time.
 *
 * `→` `←` step the chapters, `r` replays, `p` holds and `.` `,` step, `s` writes a still, `esc`
 * quits. `LONGSHADOW_AT=0.5,2,4` writes those seconds and quits, `LONGSHADOW_RECORD` films.
 */
fun main() = application {
    val scale = Env["LONGSHADOW_WINDOW"]?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "long shadow type"
    }

    program {
        sketchPreview("LongShadowType", at = 8.0)
        val chapters = Env["LONGSHADOW_TEXT"]?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                "De wereld van bouwen",
                "Waardekader en verantwoor-delijkheid",
                "Beton: ruggengraat en transitie",
                "The Circle: een nieuwe manier van denken",
            )
        var chapter = ((Env["LONGSHADOW_CHAPTER"]?.toIntOrNull() ?: 1) - 1).coerceIn(0, chapters.lastIndex)

        val shadow = longShadowFromEnv(
            concrete = File(Env["LONGSHADOW_CONCRETE"] ?: "data/concrete/concrete-052v2_crop.jpg"),
            detail = DETAIL,
            field = true
        )
        shadow.load(this)
        // A picture of the title instead of type, {n} the chapter's number; a chapter with no
        // picture is set as type.
        val imagePattern = Env["LONGSHADOW_IMAGE"]
        fun plateFor(chapter: Int) = imagePattern?.let { shadow.plate(File(it.replace("{n}", "${chapter + 1}"))) }

        // ---- composing: the studios' arrangement -------------------------------------- //
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("LONGSHADOW_RECORD")) {
            val file = "video/long-shadow-type-${chapter + 1}.mp4"
            println("filming to $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["LONGSHADOW_FPS"]?.toIntOrNull() ?: FPS
                // A multiple of the pane: 1 is 1920x1080, 2 is 3840x2160 — the canvas's own detail,
                // so a 4K clip is the composed pixels one to one rather than an upscale.
                contentScale = (Env["LONGSHADOW_RECORD_SCALE"]?.toDoubleOrNull() ?: 1.0) / scale
                maximumDuration = Env["LONGSHADOW_DURATION"]?.toDoubleOrNull() ?: 12.0
            })
        }

        val stills = Env.boolean("LONGSHADOW_STILLS")
        val at = Env["LONGSHADOW_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
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
                shadow.draw(drawer, Rectangle(0.0, 0.0, WIDE, HIGH), chapters[chapter], since, plateFor(chapter))
            }

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(4.0))) {
                val name = "screenshots/long-shadow-type-${chapter + 1}" +
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

/**
 * The effect as `.env` sets it, for the sketch and the show alike. The face is `LONGSHADOW_FONT`
 * and `_FACE`, falling back to the talk's own Rockwell Bold; [concrete] and [detail] are the
 * caller's, since the show lays its own stone over every frame and composes at canvas scale.
 */
fun longShadowFromEnv(concrete: File? = null, detail: Double = 1.0, field: Boolean = false): LongShadow {
    fun number(key: String, default: Double) = Env[key]?.toDoubleOrNull() ?: default
    fun colour(key: String, default: String) = ColorRGBa.fromHex(Env[key] ?: default)
    return LongShadow(
        fontPath = usableFont(
            Env["LONGSHADOW_FONT"] ?: Env["SLIDES_FONT"] ?: "/System/Library/Fonts/Supplemental/Rockwell.ttc",
            Env["LONGSHADOW_FACE"] ?: Env["SLIDES_FONT_BOLD"] ?: "Rockwell-Bold"
        ),
        ink = colour("LONGSHADOW_INK", "FFFFFF"),
        paper = colour("LONGSHADOW_PAPER", "3D5AE0"),
        shade = colour("LONGSHADOW_SHADE", "1E3A72"),
        margin = number("LONGSHADOW_MARGIN", 160.0),
        lines = Env["LONGSHADOW_LINES"]?.toIntOrNull(),
        leading = number("LONGSHADOW_LEADING", 1.0),
        beat = number("LONGSHADOW_BEAT", 0.2),
        rise = number("LONGSHADOW_RISE", 1.6),
        tower = number("LONGSHADOW_HEIGHT", 260.0),
        angle = number("LONGSHADOW_ANGLE", 45.0),
        turn = number("LONGSHADOW_TURN", 1.5),
        high = number("LONGSHADOW_SUN_HIGH", 40.0),
        low = number("LONGSHADOW_SUN_LOW", 12.0),
        sunset = number("LONGSHADOW_SUNSET", 40.0),
        spread = number("LONGSHADOW_SPREAD", 2.4),
        scatter = number("LONGSHADOW_SCATTER", 0.35).coerceIn(0.0, 1.0),
        concrete = concrete,
        concreteScale = number("LONGSHADOW_CONCRETE_SCALE", 1.0),
        concreteMix = number("LONGSHADOW_CONCRETE_MIX", 1.0),
        roofMix = number("LONGSHADOW_CONCRETE_ROOF", 1.0),
        detail = detail,
        // The field of lower towers round the title is the sketch's experiment: the show's card
        // asks for none, whatever the keys say.
        field = field && Env.boolean("LONGSHADOW_FIELD"),
        fieldLow = number("LONGSHADOW_FIELD_LOW", 0.1),
        fieldHigh = number("LONGSHADOW_FIELD_HIGH", 0.9),
        fieldJitter = number("LONGSHADOW_FIELD_JITTER", 0.2),
        fieldDark = number("LONGSHADOW_FIELD_DARK", 0.25),
        fieldLight = number("LONGSHADOW_FIELD_LIGHT", 0.85),
        fieldUnit = number("LONGSHADOW_FIELD_UNIT", 40.0).coerceAtLeast(8.0),
        fieldSizes = (Env["LONGSHADOW_FIELD_SIZES"] ?: "4x4,3x3,4x2,2x4,3x2,2x3,2x2,2x1,1x2,1x1")
            .split(",").mapNotNull { size ->
                size.trim().lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
                    .takeIf { it.size == 2 && it.all { n -> n > 0 } }?.let { it[0] to it[1] }
            }.ifEmpty { listOf(1 to 1) },
        fieldTake = number("LONGSHADOW_FIELD_TAKE", 0.4),
        fieldFillGaps = Env.boolean("LONGSHADOW_FIELD_FILL_GAPS"),
        fieldAvenues = Env["LONGSHADOW_FIELD_AVENUES"]?.toIntOrNull() ?: 2,
        fieldStreets = Env["LONGSHADOW_FIELD_STREETS"]?.toIntOrNull() ?: 12,
        fieldGardens = Env["LONGSHADOW_FIELD_GARDENS"]?.toIntOrNull() ?: 6,
        fieldGardenTone = number("LONGSHADOW_FIELD_GARDEN_TONE", 0.35),
        fieldGap = number("LONGSHADOW_FIELD_GAP", 4.0),
        fieldClear = number("LONGSHADOW_FIELD_CLEAR", 18.0),
        fieldRound = number("LONGSHADOW_FIELD_ROUND", 0.5),
        fieldSolid = number("LONGSHADOW_FIELD_SOLID", 0.7),
        fieldDelay = number("LONGSHADOW_FIELD_DELAY", 0.2),
        fieldSpread = number("LONGSHADOW_FIELD_SPREAD", 3.0),
        fieldSeed = Env["LONGSHADOW_FIELD_SEED"]?.toIntOrNull() ?: 11,
    )
}

/** Real pixels to one canvas pixel: composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0
