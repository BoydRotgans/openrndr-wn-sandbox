// ============================================================================ //
//  No `package` declaration, deliberately: Env lives in the default package,
//  which Kotlin cannot import into a named one.
// ============================================================================ //

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.rotateX
import org.openrndr.math.transforms.rotateY
import org.openrndr.math.transforms.scale
import org.openrndr.math.transforms.translate
import org.openrndr.shape.triangulate
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import slideshow.FPS
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.random.Random

/**
 * Rotate type segments, in 2D: the stencil title's pieces turning one by one about their own axes,
 * white on black.
 *
 * ```
 * ./gradlew run -Popenrndr.application=RotateTypeSegments2DKt
 * ```
 *
 * **Read off the reference clip, frame by frame.** Every piece turns about its own vertical axis —
 * it narrows to a sliver about its centre line and opens out again mirrored, so a D's bowl comes
 * back as a reversed C — and nothing else moves: no piece leaves its place. The turns run as a wave
 * across the title, each piece a beat after the one before, the half-turned title holds long enough
 * to be read as a strange word, and then the pieces turn on home one by one and the title reads
 * again. So a piece's turn is a half turn at a time: out to mirrored, hold, back to itself, hold.
 *
 * **A turn about the vertical axis, drawn flat, is a scale in x by its cosine**, negative past a
 * quarter turn, which is the mirror. `ROTATE_ACROSS` gives a share of the pieces the horizontal axis
 * instead. `ROTATE_SHADE` dims a piece as it goes edge on, for a little depth; 0 is flat white.
 *
 * Everything is a function of the frame, so a clip of `ROTATE_PERIOD` loops. `r` replays, `p` holds
 * and `.` `,` step, `s` writes a still, `esc` quits. `ROTATE_AT=1,2,3` writes those seconds and quits,
 * `ROTATE_RECORD` films.
 */
fun main() = application {
    val scale = Env["ROTATE_WINDOW"]?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "rotate type segments 2d"
    }

    program {
        sketchPreview("RotateTypeSegments2D", at = 4.0)
        fun number(key: String, default: Double) = Env[key]?.toDoubleOrNull() ?: default
        val file = File(Env["ROTATE_SVG"] ?: "data/titles/v5/title01-test3.svg")
        val ink = ColorRGBa.fromHex(Env["ROTATE_INK"] ?: "FFFFFF")
        val paper = ColorRGBa.fromHex(Env["ROTATE_PAPER"] ?: "000000")
        // Seconds between the moments the sentence reads: every piece turns a whole number of times
        // in it, so this is also the loop.
        val period = number("ROTATE_PERIOD", 24.0).coerceAtLeast(1.0)
        // The turns a piece may make in a period, one dealt to each piece, and the share that
        // turn the other way.
        val speeds = (Env["ROTATE_SPEEDS"] ?: "1,2,3").split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }.ifEmpty { listOf(1) }
        val reverse = number("ROTATE_REVERSE", 0.5).coerceIn(0.0, 1.0)
        val random = Random(Env["ROTATE_SEED"]?.toIntOrNull() ?: 3)
        // The share of pieces turning about the horizontal axis instead of the vertical.
        val across = number("ROTATE_ACROSS", 0.15).coerceIn(0.0, 1.0)
        // How much a piece dims going edge on, 0 to 1.
        val shade = number("ROTATE_SHADE", 0.0).coerceIn(0.0, 1.0)
        val fill = number("ROTATE_FILL", 0.86)

        // ---- the pieces --------------------------------------------------------------------- //
        val pieces: List<Shape> = loadSVG(file).findShapes()
            .filter { node -> node.effectiveFill?.let { it.r + it.g + it.b < 1.5 && it.alpha > 0.5 } ?: false }
            .map { it.shape }
            .filter { !it.empty && it.bounds.width * it.bounds.height < WIDE * HIGH * 0.5 }
        println("rotate type segments: ${file.path}, ${pieces.size} pieces")
        val bounds = pieces.map { it.bounds }.reduce { a, b ->
            val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
            Rectangle(x0, y0, maxOf(a.x + a.width, b.x + b.width) - x0, maxOf(a.y + a.height, b.y + b.height) - y0)
        }
        val fit = min(WIDE * fill / bounds.width, HIGH * fill / bounds.height)

        // Each piece's turns a period — a whole number, signed for its direction — and its axis.
        class Piece(val shape: Shape, val turns: Int, val horizontal: Boolean)
        val laid = pieces.map { shape ->
            val k = speeds[random.nextInt(speeds.size)] * if (random.nextDouble() < reverse) -1 else 1
            Piece(shape, k, random.nextDouble() < across)
        }

        /**
         * How far piece [p] has turned at [time], in half turns. Constant, never easing: every piece
         * spins at its own steady rate, so the pieces drift out of step and the title is only ever
         * glimpsed — but each makes a whole number of turns a period, so once a period all come
         * round together and the sentence stands for a moment. Pieces with an odd number of turns
         * are mirrored at half the period, which is the "ICE WERELD" of the reference passing by.
         */
        fun turned(p: Piece, time: Double): Double = 2.0 * p.turns * time.mod(period) / period

        // ---- the blocks --------------------------------------------------------------------- //
        //
        // Every piece a solid, built once from its outline the way ExtrudedType builds them: face
        // and back triangulated, the outline walked into walls, curves sharing normals so they
        // shade round. Each vertex carries its piece's centre, its turns a period and its axis, so
        // the whole title spins in one draw call.
        val depth = number("ROTATE_DEPTH", 46.0)
        val format = vertexFormat {
            position(3)
            normal(3)
            attribute("centre", VertexElementType.VECTOR2_FLOAT32)
            attribute("turns", VertexElementType.FLOAT32)
            attribute("across", VertexElementType.FLOAT32)
        }
        val verts = mutableListOf<FloatArray>()
        fun put(x: Double, y: Double, z: Double, n: Vector3, p: Piece) {
            val c = p.shape.bounds.center
            verts += floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), n.x.toFloat(), n.y.toFloat(), n.z.toFloat(),
                c.x.toFloat(), c.y.toFloat(), p.turns.toFloat(), if (p.horizontal) 1f else 0f)
        }
        for (p in laid) {
            val tris = triangulate(p.shape)
            for (v in tris) put(v.x, v.y, depth / 2.0, Vector3(0.0, 0.0, 1.0), p)
            for (v in tris) put(v.x, v.y, -depth / 2.0, Vector3(0.0, 0.0, -1.0), p)
            for (contour in p.shape.contours) {
                val pts = contour.adaptivePositions(0.25).let { ps ->
                    if (ps.size > 2 && ps.first().distanceTo(ps.last()) < 1e-6) ps.dropLast(1) else ps
                }
                if (pts.size < 3) continue
                val n = pts.size
                val edge = List(n) { i -> val t = (pts[(i + 1) % n] - pts[i]).normalized; Vector3(t.y, -t.x, 0.0) }
                fun smooth(u: Vector3, v: Vector3) = u.dot(v) > cos(Math.toRadians(35.0))
                for (i in 0 until n) {
                    val a = pts[i]; val b = pts[(i + 1) % n]
                    val prev = edge[(i - 1 + n) % n]; val here = edge[i]; val next = edge[(i + 1) % n]
                    val na = if (smooth(prev, here)) (prev + here).normalized else here
                    val nb = if (smooth(here, next)) (here + next).normalized else here
                    val h = depth / 2.0
                    put(a.x, a.y, h, na, p); put(b.x, b.y, h, nb, p); put(b.x, b.y, -h, nb, p)
                    put(a.x, a.y, h, na, p); put(b.x, b.y, -h, nb, p); put(a.x, a.y, -h, na, p)
                }
            }
        }
        val mesh = vertexBuffer(format, verts.size)
        mesh.put { for (v in verts) for (f in v) write(f) }

        // The concrete, blended in against its own average so it is grain rather than grey.
        val stoneFile = Env["ROTATE_CONCRETE"]?.takeIf { it != "none" }?.let { File(it) }
        val stone = stoneFile?.takeIf { it.isFile }?.let { f ->
            loadImage(f).also {
                it.wrapU = WrapMode.REPEAT; it.wrapV = WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
        val blank = colorBuffer(1, 1).also { it.fill(ColorRGBa.WHITE) }
        val stoneScale = number("ROTATE_CONCRETE_SCALE", 1.0)

        // Each block turned about its own centre by its own constant spin; lit by one light, the
        // faces keyed to how squarely they meet it, so a block turning shows its walls going from
        // lit to dark and reads as a solid; the concrete multiplied through.
        val block = shadeStyle {
            vertexTransform = """
                float a = 3.14159265 * 2.0 * a_turns * p_phase;
                vec3 pivot = vec3(a_centre, 0.0);
                vec3 q = x_position - pivot;
                vec3 nn = x_normal;
                if (a_across > 0.5) {
                    q = vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a));
                    nn = vec3(nn.x, nn.y * cos(a) - nn.z * sin(a), nn.y * sin(a) + nn.z * cos(a));
                } else {
                    q = vec3(q.x * cos(a) + q.z * sin(a), q.y, -q.x * sin(a) + q.z * cos(a));
                    nn = vec3(nn.x * cos(a) + nn.z * sin(a), nn.y, -nn.x * sin(a) + nn.z * cos(a));
                }
                x_position = q + pivot;
                x_normal = nn;
            """
            fragmentTransform = """
                vec3 n = normalize(v_worldNormal);
                // The drawing's y points down and the model flips it, so the light's y is up here.
                vec3 L = normalize(vec3(-0.45, -0.55, 0.7));
                float lit = clamp(dot(n, L) / dot(vec3(0.0, 0.0, 1.0), L), 0.0, 1.0);
                vec3 c = mix(p_dark.rgb, p_ink.rgb, lit);
                if (p_stoned > 0.5) {
                    vec3 g = texture(p_stone, gl_FragCoord.xy / p_tile).rgb;
                    vec3 m = textureLod(p_stone, vec2(0.5), 20.0).rgb;
                    c *= mix(vec3(1.0), g / max(m, vec3(0.01)), p_grain);
                }
                x_fill = vec4(clamp(c, 0.0, 1.0), 1.0);
            """
            parameter("ink", ink)
            parameter("dark", ColorRGBa.fromHex(Env["ROTATE_DARK"] ?: "2A2A2A"))
            parameter("stoned", if (stone != null) 1.0 else 0.0)
            parameter("stone", stone ?: blank)
            parameter("tile", Vector2((stone ?: blank).width * stoneScale * DETAIL, (stone ?: blank).height * stoneScale * DETAIL))
            parameter("grain", number("ROTATE_CONCRETE_MIX", 1.0))
        }
        // The view tipped a few degrees, so the tops of the blocks show and they read as solids.
        val pitch = number("ROTATE_PITCH", 12.0)
        val yaw = number("ROTATE_YAW", -8.0)

        // ---- composing ---------------------------------------------------------------------- //
        //
        // Multisampled, because a piece going edge on is all edge; shapes also need a stencil.
        val msaa = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL,
            multisample = BufferMultisample.SampleCount(8)) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("ROTATE_RECORD")) {
            val out = "video/rotate-type-segments-2d.mp4"
            println("filming to $out")
            extend(ScreenRecorder().apply {
                outputFile = out
                frameRate = Env["ROTATE_FPS"]?.toIntOrNull() ?: FPS
                contentScale = (Env["ROTATE_RECORD_SCALE"]?.toDoubleOrNull() ?: 1.0) / scale
                maximumDuration = Env["ROTATE_DURATION"]?.toDoubleOrNull() ?: period * 2.0
            })
        }

        val stills = Env.boolean("ROTATE_STILLS")
        val at = Env["ROTATE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
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

            drawer.isolatedWithTarget(msaa) {
                drawer.clear(paper)
                drawer.isolated {
                    val span = WIDE / fit
                    drawer.projection = org.openrndr.math.transforms.ortho(-span / 2, span / 2,
                        -span / 2 * HIGH / WIDE, span / 2 * HIGH / WIDE, -4000.0, 4000.0)
                    drawer.view = Matrix44.IDENTITY
                    drawer.model = Matrix44.rotateX(pitch) * Matrix44.rotateY(yaw) * Matrix44.scale(1.0, -1.0, 1.0) *
                        Matrix44.translate(-bounds.center.x, -bounds.center.y, 0.0)
                    drawer.depthWrite = true
                    drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
                    // Where the spin is in the period: each block turns a whole number of times in
                    // it, so the title reads at 0 and the loop is exact.
                    block.parameter("phase", time.mod(period) / period)
                    drawer.shadeStyle = block
                    drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
                    drawer.shadeStyle = null
                }
            }
            msaa.colorBuffer(0).copyTo(canvas.colorBuffer(0))

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val shownFit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * shownFit, HIGH * shownFit)
            if (shownFit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(1.5))) {
                val name = "screenshots/rotate-type-segments" + (if (timed) "-%.1fs".format(seconds(at[taken])) else "") + ".png"
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
