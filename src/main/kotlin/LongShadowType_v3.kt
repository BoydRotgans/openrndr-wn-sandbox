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
import slideshow.drawers.LongShadowV3
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.min

/**
 * Long shadow type, v3: a copy of `LongShadowType_v2` to take somewhere new. It draws through its own
 * copy of the effect ([LongShadowV3]) and reads `LONGSHADOW_V3_*` keys, each falling back to
 * `LONGSHADOW_V2_*` and then `LONGSHADOW_*` when unset — so it opens exactly as v2 does and diverges
 * one key at a time.
 *
 * Long shadow type in a window of its own: the chapter title as a plan of towers rising out of the
 * floor, throwing long shadows under a setting sun, on concrete.
 *
 * ```
 * ./gradlew run -Popenrndr.application=LongShadowType_v3Kt
 * ```
 *
 * The effect is [LongShadowV3], the very class the show's chapter card draws through
 * (`SLIDES_CARD_STYLE=longshadow`), and both are built by [longShadowV3FromEnv] off the same
 * `LONGSHADOW_*` keys — so a value tuned here is the value the show runs. What the sketch adds is
 * the window, the concrete (the show lays its own over every frame) and `LONGSHADOW_IMAGE`, a
 * drawn title read a block of ink at a time.
 *
 * `→` `←` step the chapters, `r` replays, `p` holds and `.` `,` step, `s` writes a still, `esc`
 * quits. `LONGSHADOW_AT=0.5,2,4` writes those seconds and quits, `LONGSHADOW_RECORD` films.
 */
fun main() = application {
    val scale = v3Env("WINDOW")?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "long shadow type v3"
    }

    program {
        sketchPreview("LongShadowType_v3", at = 8.0)
        val chapters = v3Env("TEXT")?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                "De wereld van bouwen",
                "Waardekader en verantwoor-delijkheid",
                "Beton: ruggengraat en transitie",
                "The Circle: een nieuwe manier van denken",
            )
        var chapter = ((v3Env("CHAPTER")?.toIntOrNull() ?: 1) - 1).coerceIn(0, chapters.lastIndex)

        val shadow = longShadowV3FromEnv(
            concrete = File(v3Env("CONCRETE") ?: "data/concrete/concrete-052v2_crop.jpg"),
            detail = DETAIL,
            field = true
        )
        shadow.load(this)
        // A picture of the title instead of type, {n} the chapter's number; a chapter with no
        // picture is set as type.
        // `none` sets every chapter's own words in the face instead of reading a picture.
        val imagePattern = v3Env("IMAGE")?.takeIf { it != "none" }
        fun plateFor(chapter: Int) = imagePattern?.let { shadow.plate(File(it.replace("{n}", "${chapter + 1}"))) }
        // With reveal, a drawn title as the final type, {n} the chapter's number: a chapter with no
        // svg is set in the face. `none` sets every chapter in the face.
        val svgPattern = (v3Env("SVG") ?: "data/titles/v5/title0{n}-test3.svg").takeIf { it != "none" }
        fun svgFor(chapter: Int) = svgPattern?.let { File(it.replace("{n}", "${chapter + 1}")) }

        // ---- composing: the studios' arrangement -------------------------------------- //
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if ((v3Env("RECORD")?.lowercase() in setOf("true","1","yes"))) {
            val file = v3Env("VIDEO") ?: "video/long-shadow-type-v3-${chapter + 1}.mp4"
            println("filming to $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = v3Env("FPS")?.toIntOrNull() ?: FPS
                // A multiple of the pane: 1 is 1920x1080, 2 is 3840x2160 — the canvas's own detail,
                // so a 4K clip is the composed pixels one to one rather than an upscale.
                contentScale = (v3Env("RECORD_SCALE")?.toDoubleOrNull() ?: 1.0) / scale
                maximumDuration = v3Env("DURATION")?.toDoubleOrNull() ?: 12.0
            })
        }

        val stills = (v3Env("STILLS")?.lowercase() in setOf("true","1","yes"))
        val at = v3Env("AT")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
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
                shadow.draw(drawer, Rectangle(0.0, 0.0, WIDE, HIGH), chapters[chapter], since, plateFor(chapter), svgFor(chapter))
            }

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(4.0))) {
                val name = "screenshots/long-shadow-type-v3-${chapter + 1}" +
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
fun longShadowV3FromEnv(
    concrete: File? = null, detail: Double = 1.0, field: Boolean = false,
    /** The show's own timing for the reveal, over the `LONGSHADOW_V3_REVEAL_*` keys when given. */
    revealFill: Double? = null, revealAt: Double? = null
): LongShadowV3 {
    // Keys are written as v1's and read through v3's fallback, so each can be overridden alone.
    fun read(key: String) = v3Env(key.removePrefix("LONGSHADOW_"))
    fun number(key: String, default: Double) = read(key)?.toDoubleOrNull() ?: default
    fun colour(key: String, default: String) = ColorRGBa.fromHex(read(key) ?: default)
    fun flag(key: String) = read(key)?.lowercase() in setOf("true", "1", "yes")
    return LongShadowV3(
        fontPath = usableFont(
            v3Env("FONT") ?: Env["SLIDES_FONT"] ?: "/System/Library/Fonts/Supplemental/Rockwell.ttc",
            v3Env("FACE") ?: Env["SLIDES_FONT_BOLD"] ?: "Rockwell-Bold"
        ),
        ink = colour("LONGSHADOW_INK", "FFFFFF"),
        paper = colour("LONGSHADOW_PAPER", "3D5AE0"),
        shade = colour("LONGSHADOW_SHADE", "1E3A72"),
        margin = number("LONGSHADOW_MARGIN", 160.0),
        lines = v3Env("LINES")?.toIntOrNull(),
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
        field = field && flag("LONGSHADOW_FIELD"),
        fieldLow = number("LONGSHADOW_FIELD_LOW", 0.1),
        fieldHigh = number("LONGSHADOW_FIELD_HIGH", 0.9),
        fieldJitter = number("LONGSHADOW_FIELD_JITTER", 0.2),
        fieldDark = number("LONGSHADOW_FIELD_DARK", 0.25),
        fieldLight = number("LONGSHADOW_FIELD_LIGHT", 0.85),
        fieldUnit = number("LONGSHADOW_FIELD_UNIT", 40.0).coerceAtLeast(8.0),
        fieldSizes = (v3Env("FIELD_SIZES") ?: "4x4,3x3,4x2,2x4,3x2,2x3,2x2,2x1,1x2,1x1")
            .split(",").mapNotNull { size ->
                size.trim().lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
                    .takeIf { it.size == 2 && it.all { n -> n > 0 } }?.let { it[0] to it[1] }
            }.ifEmpty { listOf(1 to 1) },
        fieldTake = number("LONGSHADOW_FIELD_TAKE", 0.4),
        fieldFillGaps = flag("LONGSHADOW_FIELD_FILL_GAPS"),
        fieldAvenues = v3Env("FIELD_AVENUES")?.toIntOrNull() ?: 2,
        fieldStreets = v3Env("FIELD_STREETS")?.toIntOrNull() ?: 12,
        fieldGardens = v3Env("FIELD_GARDENS")?.toIntOrNull() ?: 6,
        fieldGardenTone = number("LONGSHADOW_FIELD_GARDEN_TONE", 0.35),
        fieldGap = number("LONGSHADOW_FIELD_GAP", 4.0),
        fieldClear = number("LONGSHADOW_FIELD_CLEAR", 18.0),
        fieldRound = number("LONGSHADOW_FIELD_ROUND", 0.5),
        fieldSolid = number("LONGSHADOW_FIELD_SOLID", 0.7),
        fieldDelay = number("LONGSHADOW_FIELD_DELAY", 0.2),
        fieldSpread = number("LONGSHADOW_FIELD_SPREAD", 3.0),
        fieldOrder = read("LONGSHADOW_FIELD_ORDER") ?: "after",
        titleDelay = number("LONGSHADOW_TITLE_DELAY", 3.5),
        fieldLeave = number("LONGSHADOW_FIELD_LEAVE", 0.0),
        fieldLeaveTime = number("LONGSHADOW_FIELD_LEAVE_TIME", 1.4),
        yardStacks = read("LONGSHADOW_YARD_STACKS")?.toIntOrNull() ?: 7,
        yardLayer = number("LONGSHADOW_YARD_LAYER", 0.16),
        yardTone = number("LONGSHADOW_YARD_TONE", 0.78),
        yardSpread = number("LONGSHADOW_YARD_SPREAD", 2.4),
        buildAt = number("LONGSHADOW_BUILD_AT", 3.2),
        buildSpread = number("LONGSHADOW_BUILD_SPREAD", 5.5),
        carry = number("LONGSHADOW_CARRY", 1.4),
        yardSeed = read("LONGSHADOW_YARD_SEED")?.toIntOrNull() ?: 9,
        clickOut = number("LONGSHADOW_CLICK_OUT", 0.18),
        clickIn = number("LONGSHADOW_CLICK_IN", 0.32),
        titleBand = number("LONGSHADOW_TITLE_BAND", 0.56),
        yardUnit = number("LONGSHADOW_YARD_UNIT", 12.0),
        buildOfBlocks = read("LONGSHADOW_BLOCKS")?.lowercase() !in setOf("false", "0", "no"),
        blockUnit = number("LONGSHADOW_BLOCK_UNIT", 14.0),
        blockRound = number("LONGSHADOW_BLOCK_ROUND", 0.3),
        blockGap = number("LONGSHADOW_BLOCK_GAP", 3.0),
        stackMax = read("LONGSHADOW_STACK_MAX")?.toIntOrNull() ?: 8,
        stackLayer = number("LONGSHADOW_STACK_LAYER", 0.07),
        gridColumns = read("LONGSHADOW_GRID_COLUMNS")?.toIntOrNull() ?: 29,
        gridLine = number("LONGSHADOW_GRID_LINE", 0.14),
        gridInShadow = number("LONGSHADOW_GRID_IN_SHADOW", 0.55),
        gridFadeAfter = number("LONGSHADOW_GRID_FADE_AFTER", 0.0),
        gridFadeTime = number("LONGSHADOW_GRID_FADE_TIME", 2.5),
        fieldSeed = v3Env("FIELD_SEED")?.toIntOrNull() ?: 11,
        revealFill = revealFill ?: number("LONGSHADOW_REVEAL_FILL", 2.4),
        revealAt = revealAt ?: number("LONGSHADOW_REVEAL_AT", 3.4),
        revealSpread = number("LONGSHADOW_REVEAL_SPREAD", 5.0),
        revealSink = number("LONGSHADOW_REVEAL_SINK", 0.6),
        revealScatter = number("LONGSHADOW_REVEAL_SCATTER", 0.3).coerceIn(0.0, 1.0),
        revealHold = number("LONGSHADOW_REVEAL_HOLD", 2.0),
        revealLeaveSpread = number("LONGSHADOW_REVEAL_LEAVE_SPREAD", 6.0),
        revealLeaveTime = number("LONGSHADOW_REVEAL_LEAVE_TIME", 1.2),
        revealRise = number("LONGSHADOW_REVEAL_RISE", 1.0),
        revealFinal = number("LONGSHADOW_REVEAL_FINAL", 0.08).coerceIn(0.0, 1.0),
        revealSettle = number("LONGSHADOW_REVEAL_SETTLE", 2.5),
    )
}

/** Real pixels to one canvas pixel: composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0

/** A v3 key: `LONGSHADOW_V3_<key>`, else v2's `LONGSHADOW_V2_<key>`, else v1's `LONGSHADOW_<key>`. */
private fun v3Env(key: String): String? =
    Env["LONGSHADOW_V3_$key"] ?: Env["LONGSHADOW_V2_$key"] ?: Env["LONGSHADOW_$key"]
