import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BlendMode
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.buildTransform
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import org.openrndr.math.smoothstep
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix

// The Sketches tab: what the sketch's own run is called, then its variants, one a line —
// name | main class (empty for this file's) | .env values | what it is.
// sketch-default: beams
// sketch-variant: panels in a row | CourseRowKt | | WAND_27 panels standing face to face in seven rows, stepping forward, under the concrete
/**
 * v2, the stacked approach: **one catalogue piece, many times, side by side.** Copies of a real
 * precast beam off `data/objects` lie in a row, each a step further along its own length than the
 * one before, so their ends make a staircase; seen in isometric, as flat faces with a thin line on
 * every real edge of the piece — the notches and pockets of the element drawn as the drawing would.
 *
 * **They click.** Every beam slides along its length a step and back, eased so it sets off softly
 * and settles softly, one after another down the row — a wave that runs across the stack, so each
 * beam's end slides past its neighbour's and behind it. A piece only ever moves along itself, the
 * way a precast element is pushed home.
 *
 *     ./gradlew run -Popenrndr.application=CourseStackKt
 *
 * `STACK_PIECE` names the element (`TC-BALK`), `STACK_COUNT` how many, `STACK_STEP` the stair between
 * neighbours and `STACK_CLICK` how far a click carries, both in the piece's own depths.
 */
fun main() = runCourse("course-v2-stack", preview = 10.0) { stackCourse() }

/**
 * How a stack is laid, and under which `.env` prefix it is steered. The beams (`STACK_*`) lie side
 * by side with their ends stepped; the panels (`ROW_*`) stand face to face in a line, all square.
 * The same engine either way: copies of one piece in a row across its thin axis, each clicking
 * along its length.
 */
class StackLayout(
    val prefix: String,
    val piece: String,
    val count: Int,
    /** The stair between neighbours along the length, in the piece's thickness. */
    val step: Double,
    /** How far a click carries along the length, as a share of the length. */
    val click: Double,
    /** Neighbour to neighbour across the row, as a share of the length. */
    val pitch: Double,
    /** How much of the row the frame shows across. */
    val frame: Double,
    /** Framed on the ends of the pieces (a staircase) or on their middles (a row). */
    val onEnds: Boolean,
    val top: String, val side: String, val end: String,
    /** Lines only, the faces left out, so the edges behind show through the pieces in front. */
    val seeThrough: Boolean = false,
    /** Pieces step forward along the row, one place a click, and the row comes round for ever. */
    val travel: Boolean = false,
    /** Seconds for the camera to go once round the row; 0 holds it still. */
    val orbit: Double = 0.0,
    /** Rows side by side, the middle one and as many again either side of it. */
    val lanes: Int = 1,
    /** Row to row, across, as a share of the piece's length: 1 would have the rows touching end to end. */
    val laneGap: Double = 1.25,
    /** Degrees round from looking straight down the length; 45 is the isometric view. */
    val yaw: Double = 45.0,
    /** The edge line's width in pixels; 0 draws the faces alone. */
    val line: Double = 1.3,
    val paper: String = "FFFFFF",
    /** How far each piece's colour strays from the next, 0 for all alike. */
    val vary: Double = 0.0,
    /** A click's length, the pause after it, and how much later each piece sets off than the one before. */
    val move: Double = 1.1,
    val rest: Double = 1.4,
    val lag: Double = 0.09,
    /** Cast shadows: how dark a face goes where another piece blocks the sun; 0 casts none. */
    val shadow: Double = 0.0,
    /** The edge line's colour, and how many pixels its edge fades over. */
    val ink: String = "2B2B2B",
    val soft: Double = 0.6,
    /**
     * A fill running from a colour at a piece's top to black at its foot; null keeps the flat face
     * colours. Several, comma separated, are dealt to the pieces in turn — one each, a place further
     * along in every row — so neighbours in a row and across rows differ.
     */
    val gradient: String? = null,
    /** How hard the concrete bites into the faces, against its own average; 0 leaves them clean. */
    val concrete: Double = 0.0,
    /**
     * Build upward for ever: the row is a storey, each new storey's pieces drop onto the one below
     * in a wave, and the camera rises with the build. Old storeys sink into the dark below.
     */
    val climb: Boolean = false,
    /** The camera's elevation in degrees; null is the isometric angle. */
    val viewPitch: Double? = null,
    /** Seconds for the red and blue wave to travel a wavelength through the pieces; 0 keeps each piece's own colour. */
    val flow: Double = 0.0,
    /** The colour runs down to black at a piece's foot; off, it is flat from top to foot. */
    val toBlack: Boolean = true
)

val beams = StackLayout("STACK", "TC-BALK", 30, 1.4, 0.034, 0.038, 0.17, true, "F6B3BC", "FBC7CE", "FFFFFF")
val panels = StackLayout("ROW", "WAND_27", 48, 0.0, 0.0, 0.3, 0.2, false, "FFFFFF", "FFFFFF", "FFFFFF",
    seeThrough = false, travel = true, orbit = 0.0, lanes = 7, yaw = 60.0, line = 1.1, paper = "000000", vary = 0.0,
    ink = "C4C5C9", soft = 1.4, gradient = "FF0000,3D5AE0", concrete = 0.75,
    move = 3.6, rest = 4.2, lag = 0.22, shadow = 0.0)

/** The panels as storeys: the row built level upon level, for ever, the camera climbing with it. */
val storeys = StackLayout("CLIMB", "WAND_27", 22, 0.0, 0.0, 0.3, 0.5, false, "FFFFFF", "FFFFFF", "FFFFFF",
    seeThrough = false, travel = false, orbit = 0.0, lanes = 7, yaw = 60.0, line = 1.1, paper = "000000",
    ink = "FFFFFF", soft = 0.6, gradient = "FF0000,3D5AE0", concrete = 0.0, move = 2.2, climb = true, viewPitch = 18.0,
    flow = 0.0, toBlack = false)

/** The wall panels standing in a row, face to face, in line only. */
fun rowMain() = runCourse("course-v2-row", preview = 10.0) { stackCourse(panels) }

fun Program.stackCourse(layout: StackLayout = beams): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["${layout.prefix}_$k"]
    val name = key("PIECE") ?: layout.piece
    val mesh = loadObjMesh(File("data/objects/$name.obj")) ?: error("stack: no data/objects/$name.obj")
    val count = key("COUNT")?.toIntOrNull() ?: layout.count

    // The piece's own extent, which axis is its length and which its depth across the row.
    val lo = Vector3(mesh.points.minOf { it.x }, mesh.points.minOf { it.y }, mesh.points.minOf { it.z })
    val hi = Vector3(mesh.points.maxOf { it.x }, mesh.points.maxOf { it.y }, mesh.points.maxOf { it.z })
    val extent = hi - lo
    val alongZ = extent.z > extent.x
    val length = if (alongZ) extent.z else extent.x
    val depth = if (alongZ) extent.x else extent.z
    // Turned so its length runs along x and its foot stands on y = 0.
    val lay = buildTransform {
        translate(0.0, -lo.y, 0.0)
        if (alongZ) rotate(Vector3.UNIT_Y, 90.0)
    }
    val pitch = length * (key("PITCH")?.toDoubleOrNull() ?: layout.pitch)
    val step = depth * (key("STEP")?.toDoubleOrNull() ?: layout.step)
    val click = length * (key("CLICK")?.toDoubleOrNull() ?: layout.click)
    println("stack: $name, ${"%.2f".format(length)} long, ${"%.3f".format(depth)} deep, $count copies")

    // A click: set off, arrive, rest. The sequence out, home, back, home is the whole loop.
    val move = key("MOVE")?.toDoubleOrNull() ?: layout.move
    val rest = key("REST")?.toDoubleOrNull() ?: layout.rest
    val lag = key("LAG")?.toDoubleOrNull() ?: layout.lag
    val sequence = doubleArrayOf(0.0, 1.0, 0.0, -1.0)
    fun ease(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
    val travel = key("TRAVEL")?.let { it == "true" } ?: layout.travel
    val seeThrough = key("SEE_THROUGH")?.let { it == "true" } ?: layout.seeThrough
    val orbit = key("ORBIT")?.toDoubleOrNull() ?: layout.orbit
    /** How many places piece [i] has stepped forward by [time]: whole clicks, the current one eased. */
    fun stepped(i: Int, time: Double): Double {
        val beat = move + rest
        val local = (time - i * lag).coerceAtLeast(0.0)
        val k = floor(local / beat)
        return k + ease((local - k * beat) / move)
    }
    fun offset(i: Int, time: Double): Double {
        val beat = move + rest
        val local = (time - i * lag).coerceAtLeast(0.0)
        val k = floor(local / beat).toInt()
        val u = (local - k * beat) / move
        val from = sequence[k.mod(4)]
        val to = sequence[(k + 1).mod(4)]
        return (from + (to - from) * ease(u)) * click
    }

    val pink = ColorRGBa.fromHex(key("TOP") ?: layout.top)
    val side = ColorRGBa.fromHex(key("SIDE") ?: layout.side)
    val end = ColorRGBa.fromHex(key("END") ?: layout.end)
    val ink = ColorRGBa.fromHex(key("INK") ?: layout.ink)
    val paper = ColorRGBa.fromHex(key("PAPER") ?: layout.paper)
    val vary = key("VARY")?.toDoubleOrNull() ?: layout.vary
    /** A piece's own slight tint, off where it stands in the stack so it travels with it. */
    fun tint(lane: Int, i: Int): ColorRGBa {
        fun h(k: Int) = kotlin.random.Random(lane * 7919 + i * 104729 + k).nextDouble() * 2.0 - 1.0
        val v = 1.0 - vary * (0.5 + 0.5 * h(1))            // lighter or darker
        val warm = vary * 0.25 * h(2)                       // a touch warmer or cooler
        return ColorRGBa(v * (1.0 + warm), v, v * (1.0 - warm), 1.0)
    }

    val style = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges; out vec3 vObjPosition; out vec3 vObjNormal;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges; vObjPosition = va_position; vObjNormal = va_normal;"
        fragmentPreamble = """
            in vec3 vBary; in vec3 vEdges; in vec3 vObjPosition; in vec3 vObjNormal;
            // The stone read off the piece's own face, so it travels with the piece: a face takes the
            // two object coordinates that lie in it. Divided by the stone's own average, so the colour
            // keeps its value and only the grain shows.
            float stone() {
                if (p_concrete <= 0.0) return 1.0;
                vec3 m = abs(normalize(vObjNormal));
                vec3 q = vObjPosition;
                vec2 uv = (m.y >= m.x && m.y >= m.z) ? q.xz : (m.x >= m.z ? q.zy : q.xy);
                float s = texture(p_stoneMap, uv / p_stoneScale).r;
                float mean = textureLod(p_stoneMap, vec2(0.5), 20.0).r;
                return mix(1.0, s / max(mean, 0.05), p_concrete);
            }
            float lit(vec3 wp) {
                vec4 lp = p_lightVP * vec4(wp, 1.0);
                vec2 uv = lp.xy * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;
                float sum = 0.0;
                vec2 texel = vec2(1.0 / p_map);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    sum += lp.z - p_bias > texture(p_depth, uv + vec2(i, j) * texel).r ? 0.0 : 1.0;
                return sum / 9.0;
            }
        """
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            // Three flat colours by which way a face points: the top, the long sides, the ends.
            vec3 c = abs(n.y) > 0.6 ? p_top.rgb : (abs(n.x) > 0.6 ? p_end.rgb : p_side.rgb);
            // Or grey at the top of the piece running down to black at its foot, the same on every face.
            if (p_gradient == 1) {
                // With a flow, the colour is not the piece's own but a wave between the two that travels
                // through the whole structure: a soft sine along the pieces' length and a little across
                // and up, moving on, so red slides into blue along each piece and on into the next.
                vec3 top = p_gradientTop.rgb;
                if (p_flow > 0.0) {
                    float along = dot(v_worldPosition, normalize(vec3(1.0, 0.35, 0.45))) / p_wave;
                    float w = 0.5 + 0.5 * sin(6.2831853 * (along - p_time / p_flow));
                    top = mix(p_flowA.rgb, p_flowB.rgb, smoothstep(0.0, 1.0, w));
                }
                // Down to black at the foot, or the colour flat all the way down.
                c = p_toBlack == 1 ? mix(vec3(0.0), top, clamp((vObjPosition.y - p_lowY) / p_height, 0.0, 1.0)) : top;
            }
            // A line on every real edge, the same width in pixels however the piece stands.
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float line = p_width <= 0.0 ? 0.0 : 1.0 - smoothstep(p_width - p_soft, p_width + p_soft, d);
            if (p_seeThrough == 1) {
                if (line < 0.02) discard;
                x_fill = vec4(p_ink.rgb, line * p_fade);
            } else {
                // Where another piece stands between a face and the sun, the face goes darker.
                float shade = 1.0;
                if (p_shadow > 0.0) {
                    vec3 L = normalize(p_toSun);
                    // Turned from the sun, a face is in shade; turned toward it, it is lit unless another
                    // piece stands in the way.
                    float sun = dot(n, L) > 0.0 ? lit(v_worldPosition + n * 0.004) : 0.0;
                    shade = mix(1.0 - p_shadow, 1.0, sun);
                }
                x_fill = vec4(mix(c * p_tint.rgb * shade * stone(), p_ink.rgb, line), 1.0);
            }
        """
    }

    val iso = Math.toDegrees(atan(1.0 / sqrt(2.0)))
    val shadow = key("SHADOW")?.toDoubleOrNull() ?: layout.shadow
    // A shadow map only where there are shadows: the stacks run without, and a 4096 float map is 128 MB
    // standing idle — which counts once every variant is loaded as a wall in the show.
    val map = if (shadow > 0.0) 4096 else 1
    val shadowMap = org.openrndr.draw.renderTarget(map, map) {
        colorBuffer(type = org.openrndr.draw.ColorType.FLOAT32); depthBuffer()
    }
    val depthStyle = shadeStyle {
        fragmentTransform = "vec4 lp = p_lightVP * vec4(v_worldPosition, 1.0); x_fill = vec4(lp.z, 0.0, 0.0, 1.0);"
    }
    val concrete = key("CONCRETE_AMOUNT")?.toDoubleOrNull() ?: layout.concrete
    val stoneMap = File(key("CONCRETE") ?: "data/concrete/concrete-052v2_crop.jpg").takeIf { concrete > 0.0 && it.isFile }?.let { f ->
        org.openrndr.draw.loadImage(f).also {
            it.wrapU = org.openrndr.draw.WrapMode.REPEAT; it.wrapV = org.openrndr.draw.WrapMode.REPEAT
            it.generateMipmaps(); it.filterMin = org.openrndr.draw.MinifyingFilter.LINEAR_MIPMAP_LINEAR
        }
    }
    val lines = if (seeThrough) creaseLines(File("data/objects/$name.obj")) else null
    val lineStyle = shadeStyle { fragmentTransform = "x_fill = vec4(p_ink.rgb, p_fade);" }
    // A line two pixels wide out of one-pixel GL lines: drawn four times, a pixel apart on the canvas.
    val nudges = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)

    return { drawer: Drawer, time: Double ->
        drawer.clear(paper)
        // In the studio the cursor steers the camera; a stored view, when one is held, wins over it.
        // A stored view, when one is held, wins over both.
        val pointer = CourseControl.pointer
        val fixed = CourseControl.fixed
        val baseYaw = (key("YAW")?.toDoubleOrNull() ?: layout.yaw) + if (orbit > 0.0) 360.0 * time / orbit else 0.0
        // A full turn both ways: across the window goes once round, down the window once up and over
        // the top, the default angle in the middle of each. Past straight down the camera carries on
        // over to the far side and the picture comes up the other way round, and past level it goes
        // under the floor — a whole orbit rather than a stop at either end. Round is kept in 0..360
        // and up in -180..180, so a saved angle reads the same however it was reached.
        val basePitch = key("VIEW_PITCH")?.toDoubleOrNull() ?: layout.viewPitch ?: iso
        val yawDegrees = (fixed?.x ?: if (pointer != null) baseYaw + (pointer.x - 0.5) * 360.0 else baseYaw).mod(360.0)
        val pitchDegrees = ((fixed?.y ?: if (pointer != null) basePitch + (pointer.y - 0.5) * 360.0 else basePitch) + 180.0).mod(360.0) - 180.0
        CourseControl.current = org.openrndr.math.Vector2(yawDegrees, pitchDegrees)
        val yaw = Math.toRadians(yawDegrees)
        val pitchAngle = Math.toRadians(pitchDegrees)
        val eye = Vector3(sin(yaw) * cos(pitchAngle), sin(pitchAngle), cos(yaw) * cos(pitchAngle))
        val up = Vector3(-sin(yaw) * sin(pitchAngle), cos(pitchAngle), -cos(yaw) * sin(pitchAngle))
        // Framed on the staircase of ends, half way down the row.
        val middle = count / 2
        // Aimed at half the piece's height, so a standing panel is framed whole and not cut at its foot.
        val climb = layout.climb
        val levelHeight = extent.y * (1.0 + (key("LEVEL_GAP")?.toDoubleOrNull() ?: 0.12))
        val levelTime = key("LEVEL_TIME")?.toDoubleOrNull() ?: 20.0
        val progress = time / levelTime
        val target = if (climb) Vector3(0.0, progress * levelHeight + extent.y * 0.1, 0.0)
            else if (travel) Vector3(0.0, extent.y / 2.0, 0.0)
            else Vector3((if (layout.onEnds) length / 2.0 else 0.0) + middle * step, extent.y / 2.0, middle * pitch)
        val half = pitch * count * (key("FRAME")?.toDoubleOrNull() ?: layout.frame)
        val aspect = drawer.width.toDouble() / drawer.height
        drawer.isolated {
            drawer.projection = orthoMatrix(-half, half, -half / aspect, half / aspect, -100.0, 100.0)
            drawer.view = lookAtMatrix(target + eye * 20.0, target, up)
            if (seeThrough) {
                // Nothing hides anything: every edge of every piece is drawn, over one another.
                drawer.drawStyle.blendMode = BlendMode.BLEND
                drawer.depthWrite = false
                drawer.depthTestPass = DepthTestPass.ALWAYS
            } else {
                drawer.drawStyle.blendMode = BlendMode.REPLACE
                drawer.depthWrite = true
                drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            }
            style.parameter("seeThrough", if (seeThrough) 1 else 0)
            style.parameter("top", pink)
            style.parameter("side", side)
            style.parameter("end", end)
            style.parameter("ink", ink)
            style.parameter("width", key("LINE")?.toDoubleOrNull() ?: layout.line)
            style.parameter("soft", key("SOFT")?.toDoubleOrNull() ?: layout.soft)
            val gradient = (key("GRADIENT") ?: layout.gradient)?.takeIf { it != "none" }
            style.parameter("gradient", if (gradient != null) 1 else 0)
            val gradients = gradient?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }.orEmpty()
            style.parameter("gradientTop", gradients.firstOrNull() ?: ColorRGBa.WHITE)
            style.parameter("height", extent.y)
            // The flow: seconds for the wave to travel one wavelength (0 holds each piece's own colour),
            // and the wavelength in the piece's own lengths.
            val flow = key("FLOW")?.toDoubleOrNull() ?: layout.flow
            style.parameter("flow", if (gradients.size >= 2) flow else 0.0)
            style.parameter("wave", length * (key("WAVE")?.toDoubleOrNull() ?: 2.5))
            style.parameter("time", time)
            style.parameter("toBlack", if (key("TO_BLACK")?.let { it == "true" } ?: layout.toBlack) 1 else 0)
            style.parameter("flowA", gradients.getOrElse(0) { ColorRGBa.WHITE })
            style.parameter("flowB", gradients.getOrElse(1) { ColorRGBa.WHITE })
            style.parameter("lowY", lo.y)
            style.parameter("concrete", if (stoneMap != null) concrete else 0.0)
            style.parameter("stoneMap", stoneMap ?: shadowMap.colorBuffer(0))
            style.parameter("stoneScale", key("CONCRETE_SCALE")?.toDoubleOrNull() ?: 0.9)
            drawer.shadeStyle = style
            val span = count * pitch
            val lanes = key("LANES")?.toIntOrNull() ?: layout.lanes
            val laneWidth = length * (key("LANE_GAP")?.toDoubleOrNull() ?: layout.laneGap)
            // Where every piece stands this frame, worked out once so the light's pass and the camera's
            // draw exactly the same scene.
            class Placed(val model: Matrix44, val fade: Double, val tint: ColorRGBa, val top: ColorRGBa?)
            val placed = mutableListOf<Placed>()
            if (climb) {
                // Storeys: the one being built and the finished ones under it. A piece drops onto its
                // level at its turn in a wave across the storey, over one click; a storey further below
                // the build is further into the dark.
                val current = floor(progress).toInt()
                val keep = key("LEVELS")?.toIntOrNull() ?: 6
                val snap = key("SNAP")?.toDoubleOrNull() ?: 0.0
                val seat = key("SEAT")?.toDoubleOrNull() ?: 0.0
                for (level in current - keep..current) {
                    val depthBelow = progress - level
                    val fog = kotlin.math.exp(-depthBelow * (key("FOG")?.toDoubleOrNull() ?: 0.0)).coerceIn(0.0, 1.0)
                    for (lane in 0 until lanes) {
                        val across = (lane - (lanes - 1) / 2.0) * laneWidth
                        for (i in 0 until count) {
                            // A diagonal wave: along the row and across the rows, so a storey fills in a sweep.
                            val order = (i.toDouble() / count + lane.toDouble() / lanes * 0.6) / 1.6
                            val start = (level + order * 0.8) * levelTime
                            if (time < start) continue
                            // Clicked in, not dropped: the piece is there at once and only seats itself,
                            // settling `CLIMB_SEAT` of a storey over `CLIMB_SNAP` seconds on an ease-out.
                            // 0 for either makes it appear exactly in place.
                            val u = if (snap <= 0.0) 1.0 else ((time - start) / snap).coerceIn(0.0, 1.0)
                            val drop = (1.0 - u) * (1.0 - u) * (1.0 - u) * levelHeight * seat
                            val y = level * levelHeight + drop
                            val z = i * pitch - span / 2.0
                            val t = tint(lane, i)
                            placed += Placed(buildTransform { translate(across, y, z) } * lay, 1.0,
                                ColorRGBa(t.r * fog, t.g * fog, t.b * fog, 1.0),
                                gradients.takeIf { it.isNotEmpty() }?.let { it[(i + lane + level).mod(it.size)] })
                        }
                    }
                }
            }
            for (lane in 0 until if (climb) 0 else lanes) {
                val across = (lane - (lanes - 1) / 2.0) * laneWidth
                // Each row a share of a beat out of step with the next, so the seven never click as one.
                val shift = lane * 0.37 * (move + rest)
                for (i in 0 until count) {
                    val z: Double
                    val fade: Double
                    if (travel) {
                        // Stepping forward along the row, which wraps: a piece leaving the far end comes
                        // back at the near one, faded out over the last stretch of either end.
                        z = (i * pitch + stepped(i, time + shift) * pitch).mod(span) - span / 2.0
                        fade = 1.0 - smoothstep(0.32, 0.48, abs(z) / span)
                    } else {
                        z = i * pitch
                        fade = 1.0
                    }
                    if (fade <= 0.0) continue
                    placed += Placed(buildTransform { translate(across + i * step + offset(i, time), 0.0, z) } * lay, fade, tint(lane, i),
                        gradients.takeIf { it.isNotEmpty() }?.let { it[(i + lane).mod(it.size)] })
                }
            }

            if (shadow > 0.0 && !seeThrough) {
                val a = Math.toRadians(key("SUN_ANGLE")?.toDoubleOrNull() ?: 235.0)
                val e = Math.toRadians(key("SUN")?.toDoubleOrNull() ?: 32.0)
                val toSun = Vector3(-cos(a) * cos(e), sin(e), -sin(a) * cos(e)).normalized
                val centre = Vector3(0.0, extent.y / 2.0, if (travel) 0.0 else middle * pitch)
                val lightView = lookAtMatrix(centre + toSun * 60.0, centre, Vector3.UNIT_Y)
                val reachX = lanes * laneWidth / 2.0 + length
                val reachZ = span / 2.0 + length
                val corners = listOf(-reachX, reachX).flatMap { x -> listOf(-reachZ, reachZ).flatMap { z ->
                    listOf(0.0, extent.y).map { y -> (lightView * org.openrndr.math.Vector4(x, y, z + centre.z, 1.0)).xyz } } }
                val lightProjection = orthoMatrix(
                    corners.minOf { it.x }, corners.maxOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.y },
                    -corners.maxOf { it.z } - 10.0, -corners.minOf { it.z } + 10.0
                )
                val lightVP = lightProjection * lightView
                val cameraProjection = drawer.projection
                val cameraView = drawer.view
                drawer.isolatedWithTarget(shadowMap) {
                    shadowMap.clearColor(0, ColorRGBa(4.0, 0.0, 0.0, 1.0))
                    shadowMap.clearDepth(1.0, 0)
                    drawer.projection = lightProjection
                    drawer.view = lightView
                    drawer.drawStyle.blendMode = BlendMode.REPLACE
                    drawer.depthWrite = true
                    drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
                    depthStyle.parameter("lightVP", lightVP)
                    drawer.shadeStyle = depthStyle
                    placed.forEach { drawer.model = it.model; drawer.vertexBuffer(mesh.vertexBuffer, DrawPrimitive.TRIANGLES) }
                }
                drawer.projection = cameraProjection
                drawer.view = cameraView
                drawer.shadeStyle = style
                style.parameter("lightVP", lightVP)
                style.parameter("toSun", toSun)
                style.parameter("depth", shadowMap.colorBuffer(0))
                style.parameter("map", map.toDouble())
                style.parameter("bias", 0.0008)
            } else {
                style.parameter("lightVP", Matrix44.IDENTITY)
                style.parameter("toSun", Vector3.UNIT_Y)
                style.parameter("depth", shadowMap.colorBuffer(0))
                style.parameter("map", map.toDouble())
                style.parameter("bias", 0.0)
            }
            style.parameter("shadow", if (seeThrough) 0.0 else shadow)

            placed.forEach { p ->
                style.parameter("fade", p.fade)
                style.parameter("tint", p.tint)
                p.top?.let { style.parameter("gradientTop", it) }
                drawer.model = p.model
                if (lines != null) {
                    lineStyle.parameter("ink", ink)
                    lineStyle.parameter("fade", p.fade)
                    drawer.shadeStyle = lineStyle
                    val projection = drawer.projection
                    val weight = (key("LINE")?.toDoubleOrNull() ?: 1.6)
                    nudges.forEach { (dx, dy) ->
                        drawer.projection = buildTransform {
                            translate(dx * weight / 2.0 * 2.0 / drawer.width, dy * weight / 2.0 * 2.0 / drawer.height, 0.0)
                        } * projection
                        drawer.vertexBuffer(lines, DrawPrimitive.LINES)
                    }
                    drawer.projection = projection
                } else {
                    drawer.shadeStyle = style
                    drawer.vertexBuffer(mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
                }
            }
            drawer.model = Matrix44.IDENTITY
        }
    }
}
