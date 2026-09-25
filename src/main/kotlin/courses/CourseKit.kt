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
import kotlin.math.sin
import kotlin.math.sqrt

// sketch-default: cube, in boxes
// sketch-variant: on a flat grid, in colour | | KIT_GRID=lattice KIT_STEP=8 KIT_SCALE=17 KIT_TOP=FF0000 KIT_SIDE=0A0A0F KIT_SIDE_Z=1E3A72 KIT_INK=0A0A0F KIT_SHADOWS=true KIT_LIFT=5 | the pieces lying on a flat isometric grid round the cube, red, navy and black with their shadows
// sketch-variant: building | | KIT_FORM=building KIT_GRID=lattice KIT_STEP=5.5 KIT_RADIUS=4 KIT_SCALE=17 KIT_TOP=FF0000 KIT_SIDE=0A0A0F KIT_SIDE_Z=0A0A0F KIT_INK=0A0A0F KIT_SHADOWS=true KIT_EDGED=0 KIT_GRID_AT=23,-23 KIT_LIFT=3 | the precast building of bays and storeys instead of the cube
/**
 * v6 · **the kit: a cube, then a grid**. The catalogue's precast pieces stand fitted together as a cube in
 * the middle box of a hexagon of wire boxes, are taken apart into the boxes round it — each standing in a
 * box of its own as it stood in the cube — and are fitted together again; for ever. **It comes apart as an
 * exploded view**: every piece in one quick straight move to the box that lies outward from it, a soft
 * few hundredths of a second after the one before, the outermost first; and while it stands exploded the
 * camera goes a quarter of the way round it, softly, so each round is seen from the next corner.
 * The boxes stand in layers, as wide as the frame and running off its top and foot, a piece floating at the
 * middle of its box; the pieces are grey for now, with no shadow. `KIT_GRID=lattice` lays the pieces flat
 * on an isometric grid instead, a piece at a time. Nothing else: a light
 * ground, the pieces red on top and navy and black at their sides with their own edges in a light line, a
 * flat navy shadow, the grid's black lines. `KIT_FORM=building` stands the precast building instead.
 *
 *     ./gradlew run -Popenrndr.application=CourseKitKt
 *
 * The schedule and the kit are `AssembleScene`'s catalogue on a `lattice`; this file only draws. The
 * shadow is the piece flattened onto the ground along the sun, one matrix, since there is nothing but the
 * ground for it to fall on — and only a piece at rest throws one, since in the air its shadow lies far off
 * on the ground and reads as loose navy litter. `KIT_*` in `.env` steer it.
 */
fun main() = runCourse("course-v6-kit", preview = 4.0, canvasWidth = 1920) { kitCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.kitCourse(): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["KIT_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    fun colour(k: String, default: String) = ColorRGBa.fromHex(key(k) ?: default)
    val paper = colour("PAPER", "F4F4F4")
    val top = colour("TOP", "D5D6D8")
    val side = colour("SIDE", "7A7D83")
    val sideZ = colour("SIDE_Z", "A6A8AD")
    val edge = colour("EDGE", "F4F4F4")
    val shadow = colour("SHADOW", "1E3A72")
    val ink = colour("INK", "9EA0A6")

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

    // Flat: the top red, a side by which way it faces, and the piece's own creased edges in a light line,
    // so the blocks read on every face of the cube and not only on its top.
    val paint = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;"
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            if (dot(n, p_eye) < 0.0) n = -n;
            vec4 c = n.y > 0.5 ? p_top : (abs(n.x) >= abs(n.z) ? p_side : p_sideZ);
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float line = 1.0 - smoothstep(p_width * 0.5 - 0.6, p_width * 0.5 + 0.6, d);
            x_fill = mix(c, p_edge, p_edged * line);
        """
        parameter("eye", eye); parameter("top", top); parameter("side", side); parameter("sideZ", sideZ)
        parameter("edge", edge); parameter("width", number("EDGE_WIDTH", 1.5)); parameter("edged", number("EDGED", 1.0))
    }
    val flat = shadeStyle { fragmentTransform = "x_fill = p_shadow;"; parameter("shadow", shadow) }
    val wire = WireCubes(emptyList(), top, 1.0, ink, paper, 1.0)
    var lines: VertexBuffer? = null

    // Where the camera looks: the cube's middle.
    val look = Vector3(gridAt.x, kit.ground + number("LIFT", if (boxes) step / 2.0 else 5.0), gridAt.y)
    val shadows = (key("SHADOWS") ?: "false") == "true"
    // It opens on a building standing whole.
    val opening = kit.builtAt

    return { drawer: Drawer, time: Double ->
        val (pieces, drawn) = kit.catalogue(time + opening)
        val yaw = 45.0 + if (orbit) 90.0 * kit.turnsAt(time + opening) else 0.0
        val eye = eyeAt(yaw); val up = upAt(yaw)
        paint.parameter("eye", eye)
        val segments = drawn.filter { it.second > 0.02 }.map { it.first }
        if (segments.isNotEmpty()) lines = wire.segments(segments, lines).first
        val scale = scaleFor(drawer.width, drawer.height)
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
            fun grid() = lines?.let { if (segments.isNotEmpty()) wire.edges(drawer, it, segments.size * 6, eye, scale, colour = ink, width = number("LINE", 1.2)) }
            // A flat grid lies under the pieces; a box's edges stand in depth with them, the near ones
            // crossing in front of the piece in the box and the far ones behind it.
            if (!boxes) grid()
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.shadeStyle = paint
            pieces.forEach { drawer.model = it.model; drawer.vertexBuffer(it.mesh.vertexBuffer, DrawPrimitive.TRIANGLES) }
            if (boxes) {
                drawer.model = Matrix44.IDENTITY
                drawer.shadeStyle = null
                drawer.depthWrite = false
                grid()
            }
        }
    }
}
