// ============================================================================ //
//  No `package` declaration, deliberately: Env lives in the default package,
//  which Kotlin cannot import into a named one.
// ============================================================================ //

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadImage
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.FPS
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Concrete cut-out shadows: the stencil title cut into a slab of concrete, seen from straight
 * above, with a raking light throwing the cut's own wall across its floor — lettering chiselled
 * into a wall, as in the reference photograph.
 *
 * ```
 * ./gradlew run -Popenrndr.application=ConcreteCutoutShadowsKt
 * ```
 *
 * **Flat, and the depth is in the light.** Seen from straight above a cut has no visible wall; what
 * says it is a cut is the shadow its upper edge throws onto its floor. So the title is drawn once
 * into a coverage mask and one shader asks, per pixel inside a letter, whether the light reaching
 * it has to pass over the slab's surface on the way: step from the pixel toward the light, over the
 * distance a wall of the cut's depth throws a shadow — depth over the tangent of the sun — and if any
 * step lands outside the letter, the rim is between it and the sun and it is in shadow. Outside the
 * letters is the slab's surface, which nothing shades.
 *
 * **The cut deepens from nothing**, so the opening frame is the flat drawing and the letters sink
 * into the slab; then the sun turns slowly round, so the shadow slides from one wall of every letter
 * to the next. The concrete is multiplied through everything against its own average, so it is
 * grain rather than grey, in the Willy Naessens blue.
 *
 * Everything is a function of the frame. `r` replays, `p` holds and `.` `,` step, `s` writes a still,
 * `esc` quits. `CUT_AT=0.5,2,4` writes those seconds and quits, `CUT_RECORD` films.
 */
fun main() = application {
    val scale = Env["CUT_WINDOW"]?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "concrete cut-out shadows"
    }

    program {
        sketchPreview("ConcreteCutoutShadows", at = 4.0)
        fun number(key: String, default: Double) = Env[key]?.toDoubleOrNull() ?: default
        val file = File(Env["CUT_SVG"] ?: "data/titles/v5/title01-test3.svg")
        // The slab and the floor of the cuts, and the shadow, hex: the house blue, a shade under
        // it, and the house navy.
        val surface = ColorRGBa.fromHex(Env["CUT_SURFACE"] ?: "3D5AE0")
        val floor = ColorRGBa.fromHex(Env["CUT_FLOOR"] ?: "2E48B8")
        val shade = ColorRGBa.fromHex(Env["CUT_SHADE"] ?: "10214A")
        // How deep the cut is, in pane pixels, and how long it takes to sink to that from flat.
        val depth = number("CUT_DEPTH", 12.0)
        val sink = number("CUT_SINK", 2.0).coerceAtLeast(0.05)
        // The sun: where the shadow falls at the start (degrees, 0 is to the right, 90 down), how
        // many degrees a second it turns, and how high it stands.
        val angle = number("CUT_ANGLE", 45.0)
        val turn = number("CUT_TURN", 3.0)
        val elevation = number("CUT_ELEVATION", 40.0).coerceIn(3.0, 89.0)
        // How wide the lit wall reads along the far edge of a cut, in pane pixels.
        val lipWidth = number("CUT_LIP_WIDTH", 3.0)
        // How dark the shadow falls, 0 to 1.
        val strength = number("CUT_STRENGTH", 1.0)
        // How much of the frame the title fills.
        val fill = number("CUT_FILL", 0.86)

        // ---- the title as a mask ------------------------------------------------------------ //
        //
        // The black paths only: the artboard's rectangles come through the loader as shapes too.
        val pieces = loadSVG(file).findShapes()
            .filter { node -> node.effectiveFill?.let { it.r + it.g + it.b < 1.5 && it.alpha > 0.5 } ?: false }
            .map { it.shape }
            .filter { !it.empty && it.bounds.width * it.bounds.height < WIDE * HIGH * 0.5 }
        println("concrete cut-out: ${file.path}, ${pieces.size} pieces")
        val bounds = pieces.map { it.bounds }.reduce { a, b ->
            val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
            Rectangle(x0, y0, maxOf(a.x + a.width, b.x + b.width) - x0, maxOf(a.y + a.height, b.y + b.height) - y0)
        }
        val fit = min(WIDE * fill / bounds.width, HIGH * fill / bounds.height)

        val mask = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        drawer.isolatedWithTarget(mask) {
            drawer.ortho(mask)
            drawer.clear(ColorRGBa.BLACK)
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            drawer.translate(WIDE / 2.0, HIGH / 2.0)
            drawer.scale(fit)
            drawer.translate(-bounds.center.x, -bounds.center.y)
            drawer.shapes(pieces)
        }
        mask.colorBuffer(0).filterMin = MinifyingFilter.LINEAR
        mask.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        // ---- the concrete ------------------------------------------------------------------- //
        val stoneFile = Env["CUT_CONCRETE"]?.takeIf { it != "none" }?.let { File(it) }
            ?: File("data/concrete/concrete-052v2_crop.jpg")
        val stone = stoneFile.takeIf { it.isFile && Env["CUT_CONCRETE"] != "none" }?.let { f ->
            loadImage(f).also {
                it.wrapU = WrapMode.REPEAT; it.wrapV = WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
        val blank = colorBuffer(1, 1).also { it.fill(ColorRGBa.WHITE) }
        val stoneScale = number("CUT_CONCRETE_SCALE", 1.0)
        val stoneMix = number("CUT_CONCRETE_MIX", 1.2)

        // ---- the one pass ------------------------------------------------------------------- //
        //
        // Inside a letter, march toward the sun over the shadow's length: any step outside the
        // letter means the rim stands between this pixel and the light. Taking the most coverage
        // missed along the way, rather than a yes or no, keeps the shadow's edge as soft as the
        // mask's own antialiasing, so it is crisp without stepping.
        val slab = shadeStyle {
            fragmentTransform = """
                vec2 uv = va_texCoord0;
                float m = texture(p_mask, uv).r;
                float lit = 0.0;
                if (m > 0.001 && p_reach > 0.01) {
                    float blocked = 0.0;
                    for (int i = 1; i <= 48; i++) {
                        vec2 at = uv + p_toSun * p_reach * (float(i) / 48.0);
                        blocked = max(blocked, 1.0 - texture(p_mask, at).r);
                    }
                    lit = blocked;
                }
                // The wall facing the sun: seen from above it is a sliver along the cut's far edge,
                // and it catches the light the floor under the near wall does not — the bright lip in
                // the photograph. Found the same way, marching *away* from the sun a wall's width.
                float wall = 0.0;
                if (m > 0.001) {
                    for (int i = 1; i <= 8; i++) {
                        vec2 at = uv - p_toSun * p_lip * (float(i) / 8.0);
                        wall = max(wall, 1.0 - texture(p_mask, at).r);
                    }
                }
                vec3 top = p_surface.rgb;
                vec3 bottom = mix(p_floor.rgb, p_shade.rgb, lit * p_strength);
                bottom = mix(bottom, p_lit.rgb, wall * (1.0 - lit) * p_sink);
                vec3 c = mix(top, bottom, m);
                if (p_stoned > 0.5) {
                    vec3 g = texture(p_stone, gl_FragCoord.xy / p_tile).rgb;
                    vec3 mean = textureLod(p_stone, vec2(0.5), 20.0).rgb;
                    c *= mix(vec3(1.0), g / max(mean, vec3(0.01)), p_grain);
                }
                x_fill = vec4(clamp(c, 0.0, 1.0), 1.0);
            """
            parameter("mask", mask.colorBuffer(0))
            parameter("surface", surface)
            parameter("floor", floor)
            parameter("shade", shade)
            parameter("strength", strength)
            parameter("lit", ColorRGBa.fromHex(Env["CUT_LIP"] ?: "7E98F0"))
            parameter("stoned", if (stone != null) 1.0 else 0.0)
            parameter("stone", stone ?: blank)
            parameter("tile", Vector2((stone ?: blank).width * stoneScale * DETAIL, (stone ?: blank).height * stoneScale * DETAIL))
            parameter("grain", stoneMix)
        }

        // ---- composing: the studios' arrangement -------------------------------------------- //
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("CUT_RECORD")) {
            val out = "video/concrete-cutout-shadows.mp4"
            println("filming to $out")
            extend(ScreenRecorder().apply {
                outputFile = out
                frameRate = Env["CUT_FPS"]?.toIntOrNull() ?: FPS
                contentScale = (Env["CUT_RECORD_SCALE"]?.toDoubleOrNull() ?: 1.0) / scale
                maximumDuration = Env["CUT_DURATION"]?.toDoubleOrNull() ?: 10.0
            })
        }

        val stills = Env.boolean("CUT_STILLS")
        val at = Env["CUT_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()
        var taken = 0

        var frame = 0
        var start = 0
        var held = false
        var nudge = 0
        var saveNext = false

        // Key handlers move counters and take no timestamp — the ScreenRecorder note under demo01
        // in CLAUDE.md.
        keyboard.keyDown.listen { event ->
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
            val time = seconds(since)

            // The cut sinks from flat on a smoothstep, then the sun turns.
            val s = (time / sink).coerceIn(0.0, 1.0)
            val sunk = depth * s * s * (3.0 - 2.0 * s)
            val reach = sunk / tan(Math.toRadians(elevation))
            // The lit wall seen from above: as wide as the cut is deep times the cotangent of the
            // view — a fixed sliver here, since the view is square on — growing as the cut sinks.
            val lip = lipWidth * s
            // The shadow falls away from the sun, so the march goes toward it: opposite the fall.
            // The mask is read y-up where the pane is drawn y-down, so y turns once more.
            val fall = Math.toRadians(angle + turn * time)
            val toSun = Vector2(-cos(fall) / WIDE, sin(fall) / HIGH)

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                slab.parameter("reach", reach)
                slab.parameter("toSun", toSun)
                slab.parameter("lip", lip)
                slab.parameter("sink", s)
                drawer.shadeStyle = slab
                drawer.image(mask.colorBuffer(0), 0.0, 0.0, WIDE, HIGH)
                drawer.shadeStyle = null
            }

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val shownFit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * shownFit, HIGH * shownFit)
            if (shownFit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(4.0))) {
                val name = "screenshots/concrete-cutout" + (if (timed) "-%.1fs".format(seconds(at[taken])) else "") + ".png"
                val out = File(name)
                out.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(out)
                println("saved ${out.path}")
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
