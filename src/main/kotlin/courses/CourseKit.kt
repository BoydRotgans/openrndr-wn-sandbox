import org.openrndr.color.ColorRGBa
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

// sketch-default: cube, in boxes
// sketch-variant: grey | | KIT_STYLE=grey | the same kit in greys on a light ground, before it took the climb's colours
// sketch-variant: on a flat grid, in colour | | KIT_STYLE=grey KIT_GRID=lattice KIT_STEP=8 KIT_SCALE=17 KIT_TOP=FF0000 KIT_SIDE=0A0A0F KIT_SIDE_Z=1E3A72 KIT_INK=0A0A0F KIT_SHADOWS=true KIT_LIFT=5 | the pieces lying on a flat isometric grid round the cube, red, navy and black with their shadows
// sketch-variant: building | | KIT_STYLE=grey KIT_FORM=building KIT_GRID=lattice KIT_STEP=5.5 KIT_RADIUS=4 KIT_SCALE=17 KIT_TOP=FF0000 KIT_SIDE=0A0A0F KIT_SIDE_Z=0A0A0F KIT_INK=0A0A0F KIT_SHADOWS=true KIT_EDGED=0 KIT_GRID_AT=23,-23 KIT_LIFT=3 | the precast building of bays and storeys instead of the cube
/**
 * v6 · **the kit: a cube, then a grid**. The catalogue's precast pieces stand fitted together as a cube in
 * the middle box of a hexagon of wire boxes, are taken apart into the boxes round it — each standing in a
 * box of its own as it stood in the cube — and are fitted together again; for ever. **It comes apart as an
 * exploded view**: every piece in one quick straight move to the box that lies outward from it, a soft
 * few hundredths of a second after the one before, the outermost first; and while it stands exploded the
 * camera goes a quarter of the way round it, softly, so each round is seen from the next corner.
 * The boxes stand in layers, as wide as the frame and running off its top and foot, a piece floating at the
 * middle of its box, and the boxes are only there while it stands apart. The camera stands close on the
 * cube while it is whole, pulls out to the field as it comes apart, and eases back in as it goes together. **It wears the climb's look** (`KIT_STYLE=climb`): black ground, every piece one flat
 * red or blue, dealt so blocks touching in the cube differ, and a white line on every real edge of it;
 * `grey` is the kit as it was, greys on a light ground. `KIT_GRID=lattice` lays the pieces flat on an
 * isometric grid instead, a piece at a time, red on top and navy and black at the sides with a flat navy
 * shadow. `KIT_FORM=building` stands the precast building instead.
 *
 *     ./gradlew run -Popenrndr.application=CourseKitKt
 *
 * The schedule and the kit are `AssembleScene`'s catalogue on a `lattice`; this file only draws. The
 * shadow is the piece flattened onto the ground along the sun, one matrix, since there is nothing but the
 * ground for it to fall on — and only a piece at rest throws one, since in the air its shadow lies far off
 * on the ground and reads as loose navy litter. `KIT_*` in `.env` steer it.
 */
fun main() = runCourse("course-v6-kit", preview = 4.0, canvasWidth = 1920) { kitCourse() }

/**
 * A look for the kit, `KIT_STYLE`: the ground, the colours dealt to the pieces one each (null keeps the three
 * face tones), the tones, the pieces' edge line — its colour, and its reach either side of the edge in
 * pixels — and the boxes' lines. Every value is also a `KIT_*` key of its own, which wins over the style.
 */
private class KitStyle(val paper: String, val colours: String?, val top: String, val side: String, val sideZ: String,
                       val edge: String, val edgeWidth: Double, val ink: String)

private val kitStyles = mapOf(
    // CourseClimb's: flat red and blue, a white line on every real edge, on black.
    "climb" to KitStyle("000000", "FF0000,3D5AE0", "FF0000", "FF0000", "FF0000", "FFFFFF", 0.5, "4A4D55"),
    "grey" to KitStyle("F4F4F4", null, "D5D6D8", "7A7D83", "A6A8AD", "F4F4F4", 0.75, "9EA0A6")
)

/** How much of the frame's half a piece may reach while the camera keeps it in shot. */
private const val FRAME_MARGIN = 0.97

/** How long, as a share of the move, the zoom may run on after the last piece lands, and settle. */
private const val ZOOM_SETTLE = 0.25

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.kitCourse(): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["KIT_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    fun colour(k: String, default: String) = ColorRGBa.fromHex(key(k) ?: default)
    val style = kitStyles[key("STYLE") ?: "climb"] ?: kitStyles.getValue("climb")
    val paper = colour("PAPER", style.paper)
    val top = colour("TOP", style.top)
    val side = colour("SIDE", style.side)
    val sideZ = colour("SIDE_Z", style.sideZ)
    val edge = colour("EDGE", style.edge)
    val shadow = colour("SHADOW", "1E3A72")
    val ink = colour("INK", style.ink)
    val colours = (key("COLOURS") ?: style.colours)?.takeIf { it != "none" }
        ?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }.orEmpty()

    val gridAt = (key("GRID_AT") ?: "0,0").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        .takeIf { it.size == 2 }?.let { Vector2(it[0], it[1]) } ?: Vector2.ZERO
    fun names(k: String, default: String) = (Env[k] ?: default).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val iso = atan(1.0 / sqrt(2.0))
    // The grid: wire boxes a piece stands in, each the cube's size, or a flat isometric lattice.
    val boxes = (key("GRID") ?: "boxes") == "boxes"
    val cubeSide = number("CUBE", 10.0)
    val step = number("STEP", if (boxes) cubeSide else 8.0)
    // Pixels a cell: the boxes as wide as the frame, running off its top and foot.
    val radius = number("RADIUS", if (boxes) 5.0 else 3.0)
    val layers = if (boxes) number("LAYERS", 1.0) else 0.0
    // Seen from a corner the boxes are a diamond, (2r + 1) steps across on the diagonal, flattened by the
    // elevation and stood up by the layers; the frame is covered when its corners fall inside it.
    fun scaleFor(width: Int, height: Int): Double {
        if (!boxes) return number("SCALE", 17.0)
        val across = (2.0 * radius + 1.0) * step / sqrt(2.0)
        val down = across * sin(iso) + (layers + 0.5) * step * cos(iso)
        return number("SCALE", (width / 2.0 / across + height / 2.0 / down) * number("FILL", 1.0))
    }
    // A box a piece may go to only if the piece stays whole in the frame from every angle the camera turns
    // through: a middle d from the axis and y from the cube's middle stands at most d across and
    // d · sin(elevation) + |y| · cos(elevation) up or down, and the piece reaches half its longest side
    // beyond.
    val frameW = CourseCanvas.width.toDouble(); val frameH = CourseCanvas.height.toDouble()
    val margin = number("MAX_SIDE", 6.0) * 0.75 + 1.0
    fun fitsWhole(m: Vector3): Boolean {
        val k = scaleFor(frameW.toInt(), frameH.toInt())
        val d = sqrt(m.x * m.x + m.z * m.z)
        return d + margin <= frameW / 2.0 / k && d * sin(iso) + kotlin.math.abs(m.y) * cos(iso) + margin <= frameH / 2.0 / k
    }
    val kit = AssembleScene(
        name = "Kit", sheet = File("none"), details = File("none"),
        grammar = "catalogue", render = "solid", layout = if (boxes) "boxes" else "lattice",
        form = key("FORM") ?: "cube",
        cubeSize = cubeSide.toInt(), explode = boxes && (key("EXPLODE") ?: "true") != "false",
        stagger = number("STAGGER", 0.04), blocks = number("BLOCKS", 24.0).toInt(), maxSide = number("MAX_SIDE", 6.0).toInt(),
        stack = number("STACK", 1.5), stackGap = number("STACK_GAP", 0.2), stackMax = number("STACK_MAX", 16.0).toInt(),
        stackOpen = number("STACK_OPEN", 0.8),
        objects = File(Env["SLIDES_YARD_OBJECTS"] ?: "data/objects"),
        walls = names("SLIDES_ASSEMBLE_WALLS", "WAND_27,WAND_33,WAND_17,WAND_23,WAND_6,WAND_8,WAND_11,WAND_28,WAND_10,WAND_22,WAND_12,WAND"),
        floors = names("SLIDES_ASSEMBLE_FLOORS", "VLOER,VLOER_3,PREDAL,VLOER_2"),
        front = names("SLIDES_ASSEMBLE_FRONT", "WAND_27,WAND_17,WAND_33,WAND_23"),
        catalogueAt = gridAt,
        latticeStep = step, latticeRadius = radius.toInt(),
        // In boxes only the middle box is the cube's; on the flat lattice the nodes by the cube are kept clear.
        latticeClear = number("CLEAR", if (boxes) step / 2.0 else cubeSide / 2.0 + number("MAX_SIDE", 6.0) / 2.0 + 1.0),
        latticeLayers = layers.toInt(),
        latticeSpread = number("SPREAD", 3.5),
        latticeFits = { m -> fitsWhole(m) },
        move = number("MOVE", if (boxes) 1.1 else 0.8), gapStart = number("GAP_START", 0.45), gapEnd = number("GAP_END", 0.25),
        curve = (key("EASE") ?: if (boxes) "0.65,0,0.25,1" else "0.4,0,0.2,1").split(",").mapNotNull { it.trim().toDoubleOrNull() }
            .takeIf { it.size == 4 }?.let { slideshow.CubicBezier(it[0], it[1], it[2], it[3]) } ?: slideshow.snap,
        catalogueHold = number("GRID_HOLD", 3.0), buildHold = number("BUILD_HOLD", 3.0),
        guides = false,
        seed = Env["SLIDES_ASSEMBLE_SEED"]?.toIntOrNull() ?: 5
    )
    kit.load(this)

    // The climb's colours, one a piece and flat on every face, dealt so blocks touching in the cube differ
    // where they can: each in turn, the most touching first, takes the colour it shares least face with
    // among the pieces already dealt. Read off the cube standing whole, so a piece keeps its colour
    // wherever it goes.
    val colourOf: Map<Int, ColorRGBa> = if (colours.isEmpty()) emptyMap() else {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        // A block by its seed: a stack's copies all carry it, so the block is the box round all of them.
        val built = kit.catalogue(kit.builtAt).first.groupBy { it.seed }.values.toList()
        val bounds = built.map { stackOfPieces ->
            val corners = stackOfPieces.flatMap { p ->
                val pts = p.mesh.points
                val lo = Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
                val hi = Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
                (0..7).map { i ->
                    (p.model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz
                }
            }
            Vector3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }) to
                Vector3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })
        }
        // Two blocks touch where they overlap on two axes and stand a joint apart on the third; the area is
        // the overlap.
        fun touching(a: Pair<Vector3, Vector3>, b: Pair<Vector3, Vector3>): Double {
            val gaps = (0..2).map { k -> maxOf(c(a.first, k), c(b.first, k)) - minOf(c(a.second, k), c(b.second, k)) }
            val apart = gaps.indices.filter { gaps[it] > -1e-3 }
            return if (apart.size == 1 && gaps[apart[0]] < 0.5) gaps.filterIndexed { k, _ -> k != apart[0] }.fold(1.0) { m, g -> m * -g } else 0.0
        }
        val n = built.size
        val touch = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else touching(bounds[i], bounds[j]) } }
        val dealt = IntArray(n) { -1 }
        for (i in (0 until n).sortedByDescending { touch[it].sum() }) {
            dealt[i] = colours.indices.minBy { k -> (0 until n).sumOf { j -> if (dealt[j] == k) touch[i][j] + 1e-6 else 0.0 } }
        }
        built.indices.associate { built[it].first().seed to colours[dealt[it]] }
    }

    // The true isometric, from a corner [yaw] degrees round; and the sun, down and to the right, low.
    fun eyeAt(yaw: Double) = Math.toRadians(yaw).let { y -> Vector3(sin(y) * cos(iso), sin(iso), cos(y) * cos(iso)) }
    fun upAt(yaw: Double) = Math.toRadians(yaw).let { y -> Vector3(-sin(y) * sin(iso), cos(iso), -cos(y) * sin(iso)) }
    val eye = eyeAt(45.0)
    // Seen exploded, the camera goes a quarter round the kit, softly — each round from the next corner.
    val orbit = boxes && (key("ORBIT") ?: "true") != "false"
    val sunAngle = Math.toRadians(number("SUN_ANGLE", 15.0))
    val sunElevation = Math.toRadians(number("SUN_ELEVATION", 30.0))
    val toSun = Vector3(-cos(sunAngle) * cos(sunElevation), sin(sunElevation), -sin(sunAngle) * cos(sunElevation))
    // A piece laid flat on the ground along the sun: x and z carried by its height, y the ground's.
    val g = kit.ground + 0.01
    val sx = toSun.x / toSun.y; val sz = toSun.z / toSun.y
    val flatten = Matrix44.fromColumnVectors(
        Vector4(1.0, 0.0, 0.0, 0.0), Vector4(-sx, 0.0, -sz, 0.0),
        Vector4(0.0, 0.0, 1.0, 0.0), Vector4(sx * g, g, sz * g, 1.0)
    )

    // Flat: one colour for the whole piece, or the top and a side by which way it faces; and the piece's own
    // creased edges in a line, so the blocks read on every face of the cube and not only on its top — the
    // climb's line, reaching EDGE_WIDTH pixels either side of an edge and fading over EDGE_SOFT.
    val paint = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;"
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            if (dot(n, p_eye) < 0.0) n = -n;
            vec4 c = p_flat == 1 ? p_colour : (n.y > 0.5 ? p_top : (abs(n.x) >= abs(n.z) ? p_side : p_sideZ));
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float line = 1.0 - smoothstep(p_width - p_soft, p_width + p_soft, d);
            x_fill = mix(c, p_edge, p_edged * line);
        """
        parameter("eye", eye); parameter("top", top); parameter("side", side); parameter("sideZ", sideZ)
        parameter("flat", if (colours.isEmpty()) 0 else 1); parameter("colour", top)
        parameter("edge", edge); parameter("width", number("EDGE_WIDTH", style.edgeWidth))
        parameter("soft", number("EDGE_SOFT", 0.6)); parameter("edged", number("EDGED", 1.0))
    }
    val flat = shadeStyle { fragmentTransform = "x_fill = p_shadow;"; parameter("shadow", shadow) }
    val wire = WireCubes(emptyList(), top, 1.0, ink, paper, 1.0)
    var lines: VertexBuffer? = null

    // Where the camera looks: the cube's middle.
    val look = Vector3(gridAt.x, kit.ground + number("LIFT", if (boxes) step / 2.0 else 5.0), gridAt.y)
    val shadows = (key("SHADOWS") ?: "false") == "true"
    val boxesAlways = (key("BOXES_ALWAYS") ?: "false") == "true"
    // Whole, the camera stands close, the cube filling this share of the frame; it pulls out to the field as
    // the kit comes apart and comes back in as it goes together. 0 holds it out.
    val close = if (boxes) number("CLOSE", 0.8) else 0.0
    // It opens on a building standing whole.
    val opening = kit.builtAt

    // The zoom. Whole, the cube fills [close] of the frame — from a corner it is sqrt 2 sides across and
    // cos(elevation) + sqrt 2 · sin(elevation) sides high — and exploded the field is framed as [scaleFor]
    // frames it; between the two in log space, so the pull reads as even, on a smootherstep.
    fun nearFor(width: Int, height: Int) =
        close * minOf(width / (cubeSide * sqrt(2.0)), height / (cubeSide * (cos(iso) + sqrt(2.0) * sin(iso))))
    val (holdApart, lift, holdWhole) = kit.phases
    val cycle = kit.phases.sum()
    fun smoother(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
    // The outermost pieces fly out faster than an even pull and come home after it, so the zoom's timing is
    // fitted to them, once: every frame of both moves is looked at from all four corners the camera comes
    // round to, how far through the pull the camera must be to keep every piece whole in the frame is read
    // off, and each move takes the longest, gentlest ease that is always at least that far through. Coming
    // together, the zoom may run on a moment past the last piece landing, and settle.
    val settle = minOf(ZOOM_SETTLE * lift, holdWhole)
    val (zoomOut, zoomIn) = if (close <= 0.0) lift to lift else {
        val near = ln(nearFor(frameW.toInt(), frameH.toInt())); val far = ln(scaleFor(frameW.toInt(), frameH.toInt()))
        val bounds = HashMap<ObjMesh, Pair<Vector3, Vector3>>()
        fun needed(t: Double) = (0 until 4).maxOf { q ->
            val eye = eyeAt(45.0 + 90.0 * q); val up = upAt(45.0 + 90.0 * q); val right = up.cross(eye)
            var fits = Double.MAX_VALUE
            for (p in kit.catalogue(t).first) {
                val (lo, hi) = bounds.getOrPut(p.mesh) {
                    val pts = p.mesh.points
                    Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
                }
                for (i in 0..7) {
                    val v = (p.model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz - look
                    fits = minOf(fits, FRAME_MARGIN * frameW / 2.0 / kotlin.math.abs(v.dot(right)).coerceAtLeast(1e-9),
                                 FRAME_MARGIN * frameH / 2.0 / kotlin.math.abs(v.dot(up)).coerceAtLeast(1e-9))
                }
            }
            ((near - ln(fits)) / (near - far)).coerceIn(0.0, 1.0)
        }
        val frames = (lift * 60.0).toInt()
        val coming = (0..frames).map { k -> (cycle - lift + k / 60.0).let { it to needed(it) } }
        val going = (0..frames).map { k -> (holdApart + k / 60.0).let { it to needed(it) } }
        val durations = generateSequence(lift) { it - 0.01 }.takeWhile { it > 0.2 }
        val out = durations.firstOrNull { d -> coming.all { (t, n) -> smoother((t - (cycle - lift)) / d) >= n - 1e-6 } } ?: 0.2
        val end = holdApart + lift + settle
        val back = generateSequence(lift + settle) { it - 0.01 }.takeWhile { it > 0.2 }
            .firstOrNull { d -> going.all { (t, n) -> 1.0 - smoother((t - (end - d)) / d) >= n - 1e-6 } } ?: 0.2
        println("kit: the camera pulls out over %.2fs and comes back in over %.2fs".format(out, back))
        out to back
    }
    /** How far through the pull the camera is at kit time [t]: 0 standing close on the cube, 1 out on the field. */
    fun pulled(t: Double): Double {
        val u = t - kotlin.math.floor(t / cycle) * cycle
        return when {
            u < holdApart -> 1.0
            u < holdApart + lift + settle -> 1.0 - smoother((u - (holdApart + lift + settle - zoomIn)) / zoomIn)
            u < cycle - lift -> 0.0
            else -> smoother((u - (cycle - lift)) / zoomOut)
        }
    }

    return { drawer: Drawer, time: Double ->
        val (pieces, drawn) = kit.catalogue(time + opening)
        val yaw = 45.0 + if (orbit) 90.0 * kit.turnsAt(time + opening) else 0.0
        val eye = eyeAt(yaw); val up = upAt(yaw)
        paint.parameter("eye", eye)
        val segments = drawn.filter { it.second > 0.02 }.map { it.first }
        if (segments.isNotEmpty()) lines = wire.segments(segments, lines).first
        val far = scaleFor(drawer.width, drawer.height)
        val scale = if (close <= 0.0) far else exp(ln(nearFor(drawer.width, drawer.height)) +
            (ln(far) - ln(nearFor(drawer.width, drawer.height))) * pulled(time + opening))
        val w = drawer.width / scale; val h = drawer.height / scale
        drawer.clear(paper)
        drawer.isolated {
            drawer.projection = orthoMatrix(-w / 2.0, w / 2.0, -h / 2.0, h / 2.0, -1000.0, 1000.0)
            drawer.view = lookAtMatrix(look + eye * 300.0, look, up)
            drawer.drawStyle.cullTestPass = CullTestPass.ALWAYS
            // The shadows and the grid lie on the ground and hide nothing.
            drawer.depthWrite = false
            drawer.depthTestPass = DepthTestPass.ALWAYS
            // Only a piece at rest throws one: in the air it would lie far off on the ground, loose.
            drawer.shadeStyle = flat
            if (shadows) pieces.filter { it.active < 0.5 }.forEach { drawer.model = flatten * it.model; drawer.vertexBuffer(it.mesh.vertexBuffer, DrawPrimitive.TRIANGLES) }
            drawer.model = Matrix44.IDENTITY
            drawer.shadeStyle = null
            // The boxes stand only while the kit stands apart: in as it explodes, out as it comes together.
            val shown = if (boxes && !boxesAlways) kit.apartAt(time + opening) else 1.0
            fun grid() = lines?.let {
                if (segments.isNotEmpty() && shown > 0.0) wire.edges(drawer, it, segments.size * 6, eye, scale, colour = ink.opacify(shown), width = number("LINE", 1.2))
            }
            // A flat grid lies under the pieces; a box's edges stand in depth with them, the near ones
            // crossing in front of the piece in the box and the far ones behind it.
            if (!boxes) grid()
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.shadeStyle = paint
            pieces.forEach {
                paint.parameter("colour", colourOf[it.seed] ?: top)
                drawer.model = it.model
                drawer.vertexBuffer(it.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
            }
            if (boxes) {
                drawer.model = Matrix44.IDENTITY
                drawer.shadeStyle = null
                drawer.depthWrite = false
                grid()
            }
        }
    }
}
