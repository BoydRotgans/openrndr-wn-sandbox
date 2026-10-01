import org.openrndr.color.ColorRGBa
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.colorBuffer
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

// sketch-variant-of: CourseKit
/**
 * v6 · **the kit, draft 2**: a copy of [kitCourse] (`CourseKit`) to take further, so the kit stands as it
 * was while this one moves. It opened exactly as the kit: its keys are `KIT_DRAFT2_*`, each falling back to
 * the `KIT_*` key of the same name while its own is unset, so it diverges one key at a time. The kit and its
 * schedule are still `AssembleScene`'s, shared with the kit.
 *
 * **It draws the kit plain** (`KIT_DRAFT2_STYLE=layers`): a dark ground, no edge lines, a block of one piece
 * white, and a stack's layers white and OPENRNDR pink in turn (`KIT_DRAFT2_LAYERS_FILL`, `FFFFFF,FFC0CB`), flat,
 * so the fill alone tells one layer from the next. `KIT_DRAFT2_RAINBOW` above 0 shades a white layer by its
 * faces instead, between `_RAINBOW_COLOURS`, cycling `_RAINBOW_TURNS` whole turns a round. `photo` lays
 * a photograph over the pieces instead (`KIT_DRAFT2_PHOTO`, or `_PHOTO_2` … by `_PHOTO_PICK`; `j` and `k` step
 * through them live): whole, the cube shows the picture intact, seen from its corner;
 * exploded, each piece keeps its part of it. The camera goes on round, so the picture is intact from the corner
 * it opens on and again after four rounds, the full circle, and folded across the cube from the three between. `t` (or `w`)
 * switches the show's concrete texture over the live window on and off.
 * `climb` and `grey` are the kit's own looks. **The scene is a square**, 1080 by
 * 1080: the boxes' field, which boxes keep a piece in shot, the close-up and the zoom's fit all read the frame.
 *
 *     ./gradlew run -Popenrndr.application=CourseKit_draft2Kt
 */
fun main() = runCourse("course-v6-kit-draft2", preview = 4.0, canvasWidth = 1080, canvasHeight = 1080) {
    // j and k step through the photographs, as they step a course's element in the course studio.
    val photos = draft2Photos()
    keyboard.keyDown.listen {
        if (it.name == "j" || it.name == "k") {
            photos.step(if (it.name == "j") 1 else -1)
            println("kit draft 2: photograph ${photos.index + 1}/${photos.names.size}, ${File(photos.name).name}")
        }
    }
    kitDraft2Course(photos)
}

/**
 * The photographs draft 2 can lay over the pieces, numbered in .env — `KIT_DRAFT2_PHOTO`, then `_PHOTO_2`, `_3` …
 * to the first gap — opening on `KIT_DRAFT2_PHOTO_PICK`'s. A [PieceChoice], so j and k step it here and in the
 * course studio alike.
 */
fun draft2Photos(): PieceChoice {
    val paths = listOfNotNull(Env["KIT_DRAFT2_PHOTO"]) + generateSequence(2) { it + 1 }.map { Env["KIT_DRAFT2_PHOTO_$it"] }.takeWhile { it != null }.filterNotNull()
    val pick = (Env["KIT_DRAFT2_PHOTO_PICK"]?.toIntOrNull() ?: 1).coerceIn(1, paths.size.coerceAtLeast(1))
    return PieceChoice(paths.ifEmpty { listOf("none") }, paths.getOrElse(pick - 1) { "none" })
}

/**
 * A look for the kit, `KIT_DRAFT2_STYLE` (or `KIT_STYLE`): the ground, the colours dealt to the pieces one each (null keeps the three
 * face tones), the tones, the pieces' edge line — its colour, and its reach either side of the edge in
 * pixels — and the boxes' lines. Every value is also a `KIT_*` key of its own, which wins over the style.
 */
private class KitDraft2Style(val paper: String, val colours: String?, val top: String, val side: String, val sideZ: String,
                       val edge: String, val edgeWidth: Double, val ink: String,
                       /** A stack's layers in turn, from the first; null leaves a stack its block's colour. */
                       val layers: String? = null,
                       /** How strongly the pieces' own edges are drawn; 0 draws none. */
                       val edged: Double = 1.0,
                       /** How strongly a white layer takes its stack's colour wheel at the rim; 0 leaves it white. */
                       val rainbow: Double = 0.0,
                       /** The wheel's two colours, round the circle and back, or `spectrum`. */
                       val wheel: String = "spectrum",
                       /** Every piece carries its part of `KIT_DRAFT2_PHOTO`, and the camera holds its corner. */
                       val photo: Boolean = false)

private val kitDraft2Styles = mapOf(
    // CourseClimb's: flat red and blue, a white line on every real edge, on black.
    "climb" to KitDraft2Style("000000", "${slideshow.Brand.redHex},${slideshow.Brand.blueHex}", slideshow.Brand.redHex, slideshow.Brand.redHex, slideshow.Brand.redHex, "FFFFFF", 0.5, "4A4D55"),
    "grey" to KitDraft2Style("F4F4F4", null, "D5D6D8", "7A7D83", "A6A8AD", "F4F4F4", 0.75, "9EA0A6"),
    // A dark ground, no edges: a block of one piece white, and a stack's layers white and OPENRNDR's own pink
    // (ColorRGBa.PINK, the template's circle) in turn, flat, so the fill alone tells one layer from the next.
    "layers" to KitDraft2Style("18191C", null, "FFFFFF", "FFFFFF", "FFFFFF", "FFFFFF", 0.5, "4A4D55", "FFFFFF,FFC0CB", 0.0, 0.0, "FFD400,8C8E93"),
    // The same dark ground, and every piece carrying its own part of a photograph: whole, the cube shows the
    // picture intact; exploded, each piece keeps its part of it.
    "photo" to KitDraft2Style("18191C", null, "FFFFFF", "FFFFFF", "FFFFFF", "FFFFFF", 0.5, "4A4D55", null, 0.0, 0.0, "FFD400,8C8E93", true)
)

/** How much of the frame's half a piece may reach while the camera keeps it in shot. */
private const val FRAME_MARGIN = 0.97

/** How long, as a share of the move, the zoom may run on after the last piece lands, and settle. */
private const val ZOOM_SETTLE = 0.25

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.kitDraft2Course(photos: PieceChoice = draft2Photos()): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["KIT_DRAFT2_$k"] ?: Env["KIT_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    fun colour(k: String, default: String) = ColorRGBa.fromHex(key(k) ?: default)
    val style = kitDraft2Styles[key("STYLE") ?: "layers"] ?: kitDraft2Styles.getValue("layers")
    val paper = colour("PAPER", style.paper)
    val top = colour("TOP", style.top)
    val side = colour("SIDE", style.side)
    val sideZ = colour("SIDE_Z", style.sideZ)
    val edge = colour("EDGE", style.edge)
    val shadow = colour("SHADOW", slideshow.Brand.blueHex)
    val ink = colour("INK", style.ink)
    val colours = (key("COLOURS") ?: style.colours)?.takeIf { it != "none" }
        ?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }.orEmpty()
    val layerColours = (key("LAYERS_FILL") ?: style.layers)?.takeIf { it != "none" }
        ?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }.orEmpty()
    // The colour wheel's two colours, round the circle and back; `spectrum` (or one colour) is the full rainbow.
    val wheelColours = (key("RAINBOW_COLOURS") ?: style.wheel).takeIf { it != "spectrum" }
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

    // The photograph: every piece carries the part of it that falls on it with the cube standing whole, seen from
    // the camera's home corner — so whole, the three faces in view show the picture intact and undistorted, and
    // exploded, since a piece only ever moves and never turns, each keeps its part and the picture comes apart.
    // A piece's part is read off where it stands whole, so each is told how far it has moved from there.
    // Every photograph [photos] names is loaded up front, brought down to 2560 on its longer side, so j and k
    // switch between them with no wait; the one [photos] stands on is the one laid.
    val images = if (!style.photo) emptyList() else photos.names.map { path ->
        File(path).takeIf { it.isFile }?.let { loadPhoto(drawer, it) } ?: run { println("kit: no photograph at $path"); null }
    }
    val photoOn = images.any { it != null }
    fun photoNow() = images.getOrNull(photos.index) ?: images.firstNotNullOf { it }
    val homeAt = kit.catalogue(kit.builtAt).first.map { (it.model * Vector4(0.0, 0.0, 0.0, 1.0)).xyz }

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
            // A white layer is shaded by its own faces: every flat face one even tone between the two colours,
            // by which way it points round its stack's axis — so the sides around a layer's outline each take
            // another tone, and the top and the foot tones of their own — and the tones cycle as [p_hue] turns,
            // passing the yellow and the grey from face to face.
            // A piece's part of the photograph: where the point stood with the cube whole, carried back by how far
            // the piece has moved, seen from the home corner, on the picture fitted to cover the cube.
            if (p_photo == 1) {
                vec3 home = v_worldPosition - p_shift - p_look;
                vec2 uv = vec2(0.5 + dot(home, p_pRight) / p_pSize.x, 0.5 + dot(home, p_pUp) / p_pSize.y);
                c.rgb = texture(p_image, uv).rgb;
            }
            if (p_rainbow > 0.0) {
                vec3 a = abs(p_axis.x) > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 b = cross(p_axis, a);
                float along = dot(n, p_axis);
                float turn = (abs(along) > 0.7 ? (along > 0.0 ? 0.1875 : 0.6875) : atan(dot(n, b), dot(n, a)) / 6.2831853) + p_hue;
                // The full spectrum round the turn, or two colours: the one, the other half way round, and back.
                vec3 tone = p_spectrum == 1
                    ? clamp(abs(mod(turn * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0)
                    : mix(p_wheelA.rgb, p_wheelB.rgb, 0.5 - 0.5 * cos(6.2831853 * turn));
                c.rgb = mix(c.rgb, tone, p_rainbow);
            }
            x_fill = mix(c, p_edge, p_edged * line);
        """
        parameter("rainbow", 0.0); parameter("axis", Vector3.UNIT_Y); parameter("hue", number("RAINBOW_HUE", 0.0))
        parameter("photo", if (photoOn) 1 else 0); parameter("shift", Vector3.ZERO)
        parameter("image", if (photoOn) photoNow() else colorBuffer(1, 1))
        parameter("spectrum", if (wheelColours.size < 2) 1 else 0)
        parameter("wheelA", wheelColours.getOrElse(0) { ColorRGBa.WHITE }); parameter("wheelB", wheelColours.getOrElse(1) { ColorRGBa.WHITE })
        parameter("eye", eye); parameter("top", top); parameter("side", side); parameter("sideZ", sideZ)
        parameter("flat", if (colours.isEmpty() && layerColours.isEmpty()) 0 else 1); parameter("colour", top)
        parameter("edge", edge); parameter("width", number("EDGE_WIDTH", style.edgeWidth))
        parameter("soft", number("EDGE_SOFT", 0.6)); parameter("edged", number("EDGED", style.edged))
    }
    val flat = shadeStyle { fragmentTransform = "x_fill = p_shadow;"; parameter("shadow", shadow) }
    val wire = WireCubes(emptyList(), top, 1.0, ink, paper, 1.0)
    var lines: VertexBuffer? = null

    // Where the camera looks: the cube's middle.
    val look = Vector3(gridAt.x, kit.ground + number("LIFT", if (boxes) step / 2.0 else 5.0), gridAt.y)
    // The photograph laid from the corner the camera opens on, covering the cube: from a corner the cube is
    // sqrt 2 sides across and cos(elevation) + sqrt 2 · sin(elevation) sides high, and the picture keeps its
    // proportion. The camera goes on round, a quarter a round, so the whole cube is seen from the other three
    // corners with the picture folded across it, and after four rounds — the full circle — it is intact again.
    fun photoSize(image: org.openrndr.draw.ColorBuffer): Vector2 {
        val high = cubeSide * (cos(iso) + sqrt(2.0) * sin(iso)); val wide = cubeSide * sqrt(2.0)
        val aspect = image.width.toDouble() / image.height
        val h = maxOf(high, wide / aspect)
        return Vector2(h * aspect, h)
    }
    if (photoOn) {
        val laid = 45.0 + if (orbit) 90.0 * kit.turnsAt(kit.builtAt) else 0.0
        paint.parameter("look", look); paint.parameter("pSize", photoSize(photoNow()))
        paint.parameter("pRight", upAt(laid).cross(eyeAt(laid))); paint.parameter("pUp", upAt(laid))
    } else {
        paint.parameter("look", look); paint.parameter("pSize", Vector2.ONE)
        paint.parameter("pRight", Vector3.UNIT_X); paint.parameter("pUp", Vector3.UNIT_Y)
    }
    val shadows = (key("SHADOWS") ?: "false") == "true"
    val boxesAlways = (key("BOXES_ALWAYS") ?: "false") == "true"
    // Whole, the camera stands close, the cube filling this share of the frame; it pulls out to the field as
    // the kit comes apart and comes back in as it goes together. 0 holds it out.
    val close = if (boxes) number("CLOSE", 0.8) else 0.0
    // It opens on a building standing whole.
    val opening = kit.builtAt
    // How strongly a white layer takes its colour wheel at the wheel's rim; 0 leaves it white.
    val rainbow = number("RAINBOW", style.rainbow)
    val rainbowHue = number("RAINBOW_HUE", 0.0)
    val rainbowTurns = number("RAINBOW_TURNS", 2.0)
    val meshBox = HashMap<ObjMesh, Pair<Vector3, Vector3>>()
    /** [p]'s box in the world, off its mesh's own box. */
    fun worldBox(p: AssembleScene.RowPiece): Pair<Vector3, Vector3> {
        val (lo, hi) = meshBox.getOrPut(p.mesh) {
            val pts = p.mesh.points
            Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
        }
        val cs = (0..7).map { i -> (p.model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz }
        return Vector3(cs.minOf { it.x }, cs.minOf { it.y }, cs.minOf { it.z }) to Vector3(cs.maxOf { it.x }, cs.maxOf { it.y }, cs.maxOf { it.z })
    }
    /** The axis a stack's layers are stacked along: the world axis from its first copy to its last. */
    fun axisOf(stack: List<AssembleScene.RowPiece>): Vector3 {
        val first = worldBox(stack.first()); val last = worldBox(stack.last())
        val d = (last.first + last.second) * 0.5 - (first.first + first.second) * 0.5
        return when {
            kotlin.math.abs(d.x) >= kotlin.math.abs(d.y) && kotlin.math.abs(d.x) >= kotlin.math.abs(d.z) -> Vector3.UNIT_X
            kotlin.math.abs(d.y) >= kotlin.math.abs(d.z) -> Vector3.UNIT_Y
            else -> Vector3.UNIT_Z
        }
    }

    // The zoom. Whole, the cube fills [close] of the frame — from a corner it is sqrt 2 sides across and
    // cos(elevation) + sqrt 2 · sin(elevation) sides high — and exploded the field is framed as [scaleFor]
    // frames it; between the two in log space, so the pull reads as even, on a smootherstep.
    fun nearFor(width: Int, height: Int) =
        close * minOf(width / (cubeSide * sqrt(2.0)), height / (cubeSide * (cos(iso) + sqrt(2.0) * sin(iso))))
    val (holdApart, lift, holdWhole) = kit.phases
    val cycle = kit.phases.sum()
    fun smoother(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
    // The pull out may start at speed, for a kit that explodes the frame it stands whole: the pieces leave at once,
    // and a camera easing in from rest would lose the first of them off the frame (ZOOM_OUT_EASE: out, or smooth).
    val outEase = (key("ZOOM_OUT_EASE") ?: "smooth") == "out"
    fun pullOut(x: Double) = if (outEase) x.coerceIn(0.0, 1.0).let { 1.0 - (1.0 - it) * (1.0 - it) * (1.0 - it) } else smoother(x)
    // The outermost pieces fly out faster than an even pull and come home after it, so the zoom's timing is
    // fitted to them, once: every frame of both moves is looked at from all four corners the camera comes
    // round to, how far through the pull the camera must be to keep every piece whole in the frame is read
    // off, and each move takes the longest, gentlest ease that is always at least that far through. Coming
    // together, the zoom may run on a moment past the last piece landing, and settle.
    val settle = minOf(number("ZOOM_SETTLE", ZOOM_SETTLE) * lift, holdWhole)
    val (zoomOut, zoomIn) = if (close <= 0.0) lift to lift else {
        val near = ln(nearFor(frameW.toInt(), frameH.toInt())); val far = ln(scaleFor(frameW.toInt(), frameH.toInt()))
        val bounds = HashMap<ObjMesh, Pair<Vector3, Vector3>>()
        fun needed(t: Double) = (0 until if (orbit) 4 else 1).maxOf { q ->
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
        val out = durations.firstOrNull { d -> coming.all { (t, n) -> pullOut((t - (cycle - lift)) / d) >= n - 1e-6 } } ?: 0.2
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
            else -> pullOut((u - (cycle - lift)) / zoomOut)
        }
    }

    // The whole draft runs SPEED times as fast — holds, moves, the turn and the zoom alike — so it keeps its rhythm.
    val speed = number("SPEED", 1.0)
    return { drawer: Drawer, clock: Double ->
        val time = clock * speed
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
            // The tones cycle: RAINBOW_TURNS whole turns a round, so a clip of whole rounds comes back to its colours.
            paint.parameter("hue", rainbowHue + rainbowTurns * time / cycle)
            val wheels = if (rainbow <= 0.0) emptyMap() else
                pieces.filter { it.layers > 1 }.groupBy { it.seed }.mapValues { (_, stack) -> axisOf(stack) }
            // The photograph j and k stand on now.
            if (photoOn) photoNow().let { paint.parameter("image", it); paint.parameter("pSize", photoSize(it)) }
            pieces.forEachIndexed { i, it ->
                if (photoOn) paint.parameter("shift", (it.model * Vector4(0.0, 0.0, 0.0, 1.0)).xyz - homeAt.getOrElse(i) { Vector3.ZERO })
                // A stack's layers take the layer colours in turn; a block of one piece, and every piece
                // without them, its block's colour.
                val fill = if (layerColours.isNotEmpty() && it.layers > 1) layerColours[it.layer % layerColours.size]
                           else colourOf[it.seed] ?: top
                paint.parameter("colour", fill)
                // The white layers are shaded by their faces, round their stack's axis.
                val axis = wheels[it.seed]
                if (axis != null && fill.r > 0.95 && fill.g > 0.95 && fill.b > 0.95) {
                    paint.parameter("rainbow", rainbow); paint.parameter("axis", axis)
                } else paint.parameter("rainbow", 0.0)
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
