// ============================================================================ //
//  No `package` declaration, deliberately: Env lives in the default package,
//  which Kotlin cannot import into a named one.
// ============================================================================ //

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.loadImage
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.ortho
import org.openrndr.math.transforms.rotateX
import org.openrndr.math.transforms.rotateY
import org.openrndr.math.transforms.scale
import org.openrndr.math.transforms.translate
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import org.openrndr.shape.triangulate
import org.openrndr.extra.svg.loadSVG
import org.openrndr.extra.composition.findShapes
import slideshow.FPS
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sin

/**
 * Extruded type: the title's stencil pieces standing up out of the page as solids, and the whole
 * block turning softly so it reads as a thing in space rather than a drawing of one.
 *
 * ```
 * ./gradlew run -Popenrndr.application=ExtrudedTypeKt
 * ```
 *
 * **Every piece is its own solid, built once from the vector outline.** The svg is 94 separate
 * paths, one a stencil piece, so each is extruded on its own: its face triangulated, its outline
 * walked into side walls. The mesh is built at unit depth and the depth is a uniform, so going from
 * flat to extruded is a number in the shader rather than a rebuild — and each piece carries its own
 * delay as a vertex attribute, so they come up one after another across the title in one draw call.
 *
 * **Flat is exactly flat.** At depth 0 the back face lies on the front one; it is written first and
 * the front drawn over it on an equal depth test, so the opening frame is the drawing itself, not a
 * flicker of two coincident faces.
 *
 * **Curves are shaded smooth and corners sharp.** Walking the outline, a vertex whose two edges turn
 * less than [SMOOTH] degrees shares one normal between them, so the bowl of a D reads as a curve;
 * past that each edge keeps its own, so a bar's corner stays a corner.
 *
 * **The view is parallel.** An orthographic camera turned a little way off square, which is the
 * look of the reference — the depth recedes without converging — and it swings softly around that
 * home angle on two slow sines a golden ratio apart, so it never quite repeats.
 *
 * Everything is a function of the frame. `r` replays, `p` holds and `.` `,` step, `s` writes a
 * still, `esc` quits. `EXTRUDE_AT=0.5,2,4` writes those seconds and quits, `EXTRUDE_RECORD` films.
 */
fun main() = application {
    val scale = Env["EXTRUDE_WINDOW"]?.toDoubleOrNull() ?: 0.5

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "extruded type"
    }

    program {
        sketchPreview("ExtrudedType", at = 7.0)
        fun number(key: String, default: Double) = Env[key]?.toDoubleOrNull() ?: default
        val file = File(Env["EXTRUDE_SVG"] ?: "data/titles/v5/title01-test3.svg")
        val paper = ColorRGBa.fromHex(Env["EXTRUDE_PAPER"] ?: "000000")
        val face = ColorRGBa.fromHex(Env["EXTRUDE_FACE"] ?: "4674D6")
        val light = ColorRGBa.fromHex(Env["EXTRUDE_LIGHT"] ?: "2A55A8")
        val dark = ColorRGBa.fromHex(Env["EXTRUDE_DARK"] ?: "0B2451")
        // How deep the pieces stand at full extrusion, in the drawing's own pixels.
        val depth = number("EXTRUDE_DEPTH", 90.0)
        // When the extrusion starts, how long one piece takes, and how long from the first piece
        // to the last across the title.
        // The float: how far the exploded view pushes each piece out from the title's middle (0.3
        // is 30% further out), how far toward or away from the eye it floats, how much it bobs, and
        // the most it is tipped, degrees.
        val explode = number("EXTRUDE_EXPLODE", 0.22)
        val floatZ = number("EXTRUDE_FLOAT_Z", 160.0)
        val bob = number("EXTRUDE_BOB", 10.0)
        val turn = number("EXTRUDE_TURN", 24.0)
        // Seconds the pieces float before gathering, and how long one takes to glide home.
        val gather = number("EXTRUDE_GATHER", 1.5)
        val move = number("EXTRUDE_MOVE", 1.8).coerceAtLeast(0.05)
        val stagger = number("EXTRUDE_STAGGER", 1.8)
        // The home angle, degrees: turned about the vertical and tipped about the horizontal.
        val yaw = number("EXTRUDE_YAW", 0.0)
        val pitch = number("EXTRUDE_PITCH", 0.0)
        // The soft turn: degrees either side of home, and seconds for one swing.
        val sway = number("EXTRUDE_SWAY", 0.0)
        val swayPeriod = number("EXTRUDE_SWAY_PERIOD", 14.0).coerceAtLeast(1.0)
        // The camera opens square on, so the flat title is the drawing, and turns to home as the
        // pieces come up over `turnIn` seconds.
        val turnIn = number("EXTRUDE_TURN_IN", 2.6).coerceAtLeast(0.05)
        // The oblique: which way the depth goes on the page (degrees up from the right) and how much
        // of its true length it is drawn at.
        val obliqueAngle = number("EXTRUDE_OBLIQUE_ANGLE", 40.0)
        val obliqueScale = number("EXTRUDE_OBLIQUE_SCALE", 0.5)
        // `iso` folds the view into a true isometric as the pieces come up; `oblique` keeps every
        // front where the drawing has it and lays the depth off along one direction.
        val isometric = (Env["EXTRUDE_VIEW"] ?: "iso") != "oblique"
        // How much of the frame the title may fill at home angle.
        val fill = number("EXTRUDE_FILL", 0.726)

        // ---- the pieces ------------------------------------------------------------------ //
        //
        // Only the black paths: the artboard's white rectangles come through the loader as shapes
        // too, and extruded they would be one slab behind everything.
        val random = kotlin.random.Random(Env["EXTRUDE_SEED"]?.toIntOrNull() ?: 5)
        // Each piece turns softly about its own centre: degrees at most, and swings a second.
        val wiggle = number("EXTRUDE_WIGGLE", 7.0)
        val wiggleSpeed = number("EXTRUDE_WIGGLE_SPEED", 0.12)
        val composition = loadSVG(file)
        val pieces: List<Shape> = composition.findShapes()
            .filter { node -> node.effectiveFill?.let { it.r + it.g + it.b < 1.5 && it.alpha > 0.5 } ?: false }
            .map { it.shape }
            // Nor anything the size of the artboard: one of its rectangles comes through the fill
            // test with a stroke and no fill of its own, and stood as a slab over the whole title.
            .filter { !it.empty && it.bounds.width * it.bounds.height < WIDE * HIGH * 0.5 }
        println("extruded type: ${file.path}, ${pieces.size} pieces")
        val bounds = pieces.map { it.bounds }.reduce { a, b ->
            val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
            Rectangle(x0, y0, maxOf(a.x + a.width, b.x + b.width) - x0, maxOf(a.y + a.height, b.y + b.height) - y0)
        }

        val format = vertexFormat {
            position(3)
            normal(3)
            attribute("delay", VertexElementType.FLOAT32)
            attribute("centre", VertexElementType.VECTOR2_FLOAT32)
            attribute("seed", VertexElementType.FLOAT32)
        }
        data class V(val p: Vector3, val n: Vector3, val d: Double, val c: Vector2, val seed: Double)
        val back = mutableListOf<V>()
        val front = mutableListOf<V>()
        val walls = mutableListOf<V>()

        for (shape in pieces) {
            // A piece's beat is where it stands across the title, left to right.
            val d = (shape.bounds.center.x - bounds.x) / bounds.width * 0.8
            // What each piece turns about, and a seed of its own so no two turn together.
            val c = shape.bounds.center
            val sd = random.nextDouble()
            val dd = d + 0.2 * random.nextDouble()
            val tris = triangulate(shape)
            // The drawing's y points down; the model flips it, so these stay in the drawing's units.
            for (p in tris) back += V(Vector3(p.x, p.y, -1.0), Vector3(0.0, 0.0, -1.0), dd, c, sd)
            for (p in tris) front += V(Vector3(p.x, p.y, 0.0), Vector3(0.0, 0.0, 1.0), dd, c, sd)
            for (contour in shape.contours) {
                val pts = contour.adaptivePositions(0.25).let { ps ->
                    if (ps.size > 2 && ps.first().distanceTo(ps.last()) < 1e-6) ps.dropLast(1) else ps
                }
                if (pts.size < 3) continue
                val n = pts.size
                val edge = List(n) { i ->
                    val a = pts[i]; val b = pts[(i + 1) % n]
                    val t = (b - a).normalized
                    Vector3(t.y, -t.x, 0.0)
                }
                fun turn(u: Vector3, v: Vector3) = Math.toDegrees(acos(u.dot(v).coerceIn(-1.0, 1.0)))
                for (i in 0 until n) {
                    val a = pts[i]; val b = pts[(i + 1) % n]
                    val prev = edge[(i - 1 + n) % n]; val here = edge[i]; val next = edge[(i + 1) % n]
                    val na = if (turn(prev, here) < SMOOTH) (prev + here).normalized else here
                    val nb = if (turn(here, next) < SMOOTH) (here + next).normalized else here
                    val a0 = Vector3(a.x, a.y, 0.0); val a1 = Vector3(a.x, a.y, -1.0)
                    val b0 = Vector3(b.x, b.y, 0.0); val b1 = Vector3(b.x, b.y, -1.0)
                    walls += V(a0, na, dd, c, sd); walls += V(b0, nb, dd, c, sd); walls += V(b1, nb, dd, c, sd)
                    walls += V(a0, na, dd, c, sd); walls += V(b1, nb, dd, c, sd); walls += V(a1, na, dd, c, sd)
                }
            }
        }
        // Back first and front last, so at depth 0 the equal depth test leaves the front on top.
        val all = back + walls + front
        val mesh = vertexBuffer(format, all.size)
        mesh.put {
            for (v in all) {
                write(v.p)
                write(v.n)
                write(v.d.toFloat())
                write(v.c)
                write(v.seed.toFloat())
            }
        }

        // The concrete, or none: `EXTRUDE_CONCRETE=none` gives the plain pieces.
        val concreteFile = Env["EXTRUDE_CONCRETE"]?.takeIf { it != "none" }?.let { File(it) }
        val stone = concreteFile?.takeIf { it.isFile }?.let { f ->
            loadImage(f).also {
                it.wrapU = WrapMode.REPEAT; it.wrapV = WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
        if (concreteFile != null && stone == null) println("extruded type: no concrete at ${concreteFile.path}")
        val blank = colorBuffer(1, 1).also { it.fill(ColorRGBa.WHITE) }
        val concreteScale = number("EXTRUDE_CONCRETE_SCALE", 1.0)
        val concreteMix = number("EXTRUDE_CONCRETE_MIX", 1.0)

        // Depth rises per piece off its own delay, the same ease as the long shadow's towers; the
        // tone is keyed to which way a face points in view, two-sided, so a wall shows whichever
        // way its outline was wound.
        val solid = shadeStyle {
            vertexTransform = """
                // **Floating, then assembled.** Every piece opens as a solid a little way off its
                // place in an exploded view — pushed out from the title's middle by p_explode, so
                // the pieces stand clear of one another, lifted toward or away from the eye, tipped
                // at an angle of its own and bobbing — and then glides home on its own beat and
                // squares up. `free` is how far from home a piece still is; at 0 it is exactly the
                // drawing's piece, straight and still, so the type the pieces make is neat.
                float t = clamp((p_time - p_gather - a_delay * p_stagger) / p_move, 0.0, 1.0);
                float e = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);   // smootherstep: leaves and lands gently
                float free = 1.0 - e;
                x_position.z *= p_depth;

                float s = a_seed;
                float tt = p_time * p_speed * 6.2831853;
                // Where it floats: outward from the middle, and up or down in depth off its seed,
                // with a slow bob so the floating reads as floating rather than as a still.
                vec2 outward = (a_centre - p_mid) * p_explode;
                float lift = (fract(s * 7.31) * 2.0 - 1.0) * p_floatZ;
                vec3 bob = vec3(sin(tt * 0.9 + s * 9.1), sin(tt * 1.13 + s * 4.7), sin(tt * 0.71 + s * 2.3)) * p_bob;
                vec3 away = (vec3(outward, lift) + bob) * free;

                // How it is tipped: a fixed angle of its own plus the soft wiggle, both gone at home.
                float w = radians(p_wiggle);
                float k = radians(p_turn);
                float ax = free * (k * (fract(s * 3.17) * 2.0 - 1.0) + w * sin(tt * 1.00 + s * 6.2831853));
                float ay = free * (k * (fract(s * 5.93) * 2.0 - 1.0) + w * sin(tt * 1.37 + s * 11.513));
                float az = free * (k * 0.6 * (fract(s * 2.41) * 2.0 - 1.0) + w * 0.6 * sin(tt * 0.73 + s * 17.071));
                mat3 rx = mat3(1.0, 0.0, 0.0,  0.0, cos(ax), sin(ax),  0.0, -sin(ax), cos(ax));
                mat3 ry = mat3(cos(ay), 0.0, -sin(ay),  0.0, 1.0, 0.0,  sin(ay), 0.0, cos(ay));
                mat3 rz = mat3(cos(az), sin(az), 0.0,  -sin(az), cos(az), 0.0,  0.0, 0.0, 1.0);
                mat3 r = rz * ry * rx;
                vec3 pivot = vec3(a_centre, -0.5 * p_depth);
                x_position = r * (x_position - pivot) + pivot + away;
                x_normal = r * x_normal;
            """
            fragmentTransform = """
                // Front or wall is read off the piece's own normal, which no turn changes. A wall's
                // tone comes from which way it faces across the page — its world normal's x and y,
                // which the oblique shear leaves alone — so tops read light, undersides dark, and a
                // piece's wiggle carries its walls through the tones as it tilts.
                float front = step(0.5, abs(va_normal.z));
                vec2 across = v_worldNormal.xy;
                float lit = length(across) > 1e-4 ? dot(normalize(across), normalize(vec2(-0.35, 1.0))) : 0.0;
                vec3 side = mix(p_dark.rgb, p_light.rgb, clamp(lit * 0.5 + 0.5, 0.0, 1.0));
                vec3 c = mix(side, p_face.rgb, front);
                // The concrete, blended in against its own average: grain in the pieces without the
                // stone's grey pulling their colour down. Nailed to the screen, so the pieces move
                // through it rather than carrying it — the facade's way with its stone.
                if (p_stoned > 0.5) {
                    vec3 g = texture(p_stone, gl_FragCoord.xy / p_tile).rgb;
                    vec3 m = textureLod(p_stone, vec2(0.5), 20.0).rgb;
                    c *= mix(vec3(1.0), g / max(m, vec3(0.01)), p_grain);
                }
                x_fill = vec4(clamp(c, 0.0, 1.0), 1.0);
            """
            parameter("depth", depth)
            parameter("gather", gather)
            parameter("move", move)
            parameter("explode", explode)
            parameter("floatZ", floatZ)
            parameter("bob", bob)
            parameter("turn", turn)
            parameter("mid", bounds.center)
            parameter("stagger", stagger)
            parameter("wiggle", wiggle)
            parameter("speed", wiggleSpeed)
            parameter("face", face)
            parameter("light", light)
            parameter("dark", dark)
            parameter("stoned", if (stone != null) 1.0 else 0.0)
            parameter("stone", stone ?: blank)
            parameter("tile", Vector2((stone ?: blank).width * concreteScale * DETAIL, (stone ?: blank).height * concreteScale * DETAIL))
            parameter("grain", concreteMix)
        }

        // ---- composing ------------------------------------------------------------------- //
        //
        // Multisampled, because a solid's silhouette is all edge and nothing else here smooths it,
        // then resolved into the canvas the window and the recorder show.
        val msaa = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL,
            multisample = BufferMultisample.SampleCount(8)) {
            colorBuffer()
            depthBuffer()
        }
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("EXTRUDE_RECORD")) {
            val out = "video/extruded-type.mp4"
            println("filming to $out")
            extend(ScreenRecorder().apply {
                outputFile = out
                frameRate = Env["EXTRUDE_FPS"]?.toIntOrNull() ?: FPS
                contentScale = (Env["EXTRUDE_RECORD_SCALE"]?.toDoubleOrNull() ?: 1.0) / scale
                maximumDuration = Env["EXTRUDE_DURATION"]?.toDoubleOrNull() ?: 10.0
            })
        }

        val stills = Env.boolean("EXTRUDE_STILLS")
        val at = Env["EXTRUDE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
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

            // Square on at the start, easing round to home as the pieces come up, then the soft
            // swing on two sines a golden ratio apart, so it drifts rather than rocks.
            val into = (time / turnIn).coerceIn(0.0, 1.0)
            val home = into * into * (3.0 - 2.0 * into)
            val drift = (time - turnIn).coerceAtLeast(0.0)
            val swing = (1.0 - kotlin.math.exp(-drift / 2.0))
            val ry = yaw * home + swing * sway * sin(2.0 * PI * drift / swayPeriod)
            val rx = pitch * home + swing * sway * 0.5 * sin(2.0 * PI * drift / (swayPeriod * GOLDEN))

            val flip = Matrix44.rotateX(rx) * Matrix44.rotateY(ry) * Matrix44.scale(1.0, -1.0, 1.0) *
                Matrix44.translate(-bounds.center.x, -bounds.center.y, 0.0)
            val model: Matrix44
            val span: Double
            if (isometric) {
                // **Ending isometric.** The view folds from square on into a true isometric — turned
                // 45 degrees and tipped atan(1/sqrt 2), so the three axes are foreshortened alike:
                // verticals stay vertical, the letters' horizontals run down to the right at 30
                // degrees and the depth up to the right at 30, which is how the reference is drawn.
                // The fold is a straight blend of the two parallel views, matrix for matrix, rather
                // than a camera swinging round, so it reads as the drawing folding into space; it
                // runs with the extrusion and then holds, and only the pieces go on moving.
                val iso = Matrix44.rotateX(ISO_PITCH) * Matrix44.rotateY(-45.0)
                val lin = Matrix44.IDENTITY * (1.0 - home) + iso * home
                // Sized for the larger of the flat title and the folded one, and centred on the
                // folded one as it folds, so nothing breathes or drifts.
                val corners = listOf(-1.0, 1.0).flatMap { sx -> listOf(-1.0, 1.0).flatMap { sy -> listOf(0.0, -depth).map { z ->
                    Vector3(sx * bounds.width / 2.0, sy * bounds.height / 2.0, z) } } }
                val projected = corners.map { (iso * it.xyz1).xyz }
                val minX = projected.minOf { it.x }; val maxX = projected.maxOf { it.x }
                val minY = projected.minOf { it.y }; val maxY = projected.maxOf { it.y }
                val middle = Vector2((minX + maxX) / 2.0, (minY + maxY) / 2.0)
                span = maxOf(bounds.width, bounds.height * WIDE / HIGH, maxX - minX, (maxY - minY) * WIDE / HIGH) / fill
                model = Matrix44.translate(-middle.x * home, -middle.y * home, 0.0) * lin * flip
            } else {
                // **Oblique**: every front stays exactly where the flat title has it and the depth
                // is laid off along one direction on the page — `obliqueAngle` degrees up from the
                // right, `obliqueScale` of its true length — so each piece extrudes from where it
                // stands.
                val oa = Math.toRadians(obliqueAngle)
                val ox = obliqueScale * kotlin.math.cos(oa)
                val oy = obliqueScale * kotlin.math.sin(oa)
                val shear = Matrix44(
                    1.0, 0.0, -ox, 0.0,
                    0.0, 1.0, -oy, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, 0.0, 1.0)
                val reachX = ox * depth
                val reachY = oy * depth
                span = maxOf(bounds.width + kotlin.math.abs(reachX), (bounds.height + kotlin.math.abs(reachY)) * WIDE / HIGH) / fill
                model = Matrix44.translate(-reachX / 2.0, -reachY / 2.0, 0.0) * shear * flip
            }

            drawer.isolatedWithTarget(msaa) {
                drawer.clear(paper)
                drawer.isolated {
                    drawer.projection = org.openrndr.math.transforms.ortho(-span / 2, span / 2, -span / 2 * HIGH / WIDE, span / 2 * HIGH / WIDE, -4000.0, 4000.0)
                    drawer.view = Matrix44.IDENTITY
                    drawer.model = model
                    drawer.depthWrite = true
                    drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
                    solid.parameter("time", time)
                    drawer.shadeStyle = solid
                    drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
                    drawer.shadeStyle = null
                }
            }
            msaa.colorBuffer(0).copyTo(canvas.colorBuffer(0))

            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= frames(6.0))) {
                val name = "screenshots/extruded-type" + (if (timed) "-%.1fs".format(seconds(at[taken])) else "") + ".png"
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

/** Two edges turning less than this share a normal, so a curve shades smooth. */
private const val SMOOTH = 35.0

private const val GOLDEN = 1.618033988749895

/** The isometric tip: atan(1/sqrt 2), which foreshortens all three axes alike. */
private val ISO_PITCH = Math.toDegrees(kotlin.math.atan(1.0 / kotlin.math.sqrt(2.0)))

/** Real pixels to one canvas pixel: composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0
