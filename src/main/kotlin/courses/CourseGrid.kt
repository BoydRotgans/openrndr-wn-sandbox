import org.openrndr.Program
import org.openrndr.draw.BlendMode
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.buildTransform
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix

// The Sketches tab: what the sketch's own run is called, then its variants, one a line —
// name | main class (empty for this file's) | .env values | what it is.
// sketch-default: frame
// sketch-variant: door panel | | GRID_PIECE=WAND_27 | the grid of WAND_27, the wall panel with a doorway, instead of the frame
// sketch-variant: window panel | | GRID_PIECE=WAND_33 | the grid of WAND_33, the wall panel with a window
// sketch-variant: column | | GRID_PIECE=KOLOM | the grid of a column: twenty times as long as it is wide, a field of turning lines
// sketch-variant: clicking | | GRID_CLICK=45 | the frames turn in eased clicks of 45 degrees, a row a beat after the one above
/**
 * v3, the grid: **one element, many times, on a square grid, each turned a little further than the
 * last.** Down a row the turn steps on by `GRID_ROW_STEP` degrees and across by `GRID_COLUMN_STEP`,
 * so the wall reads as bands of one angle running diagonally through the field; every element turns
 * on its own centre in the plane of the wall, and the bands travel.
 *
 *     ./gradlew run -Popenrndr.application=CourseGridKt
 *
 * **The element is a frame** — a rectangular ring with depth, after the sketch of 24 September —
 * written once to `build/course-grid/` as an `.obj` and read through the catalogue's own loader, so
 * it is lined and shaded exactly as a catalogue piece is. `GRID_FRAME` gives its length, height,
 * border and depth; `GRID_PIECE` names a piece off `data/objects` instead (`WAND_33`, the panel with
 * a window, is the nearest the catalogue has), and in the studio `j` and `k` step through the frame
 * and all of them.
 *
 * **No element ever touches another or the edge of the wall.** Each keeps inside the circle its
 * piece reaches at any angle, and the circles stand `GRID_GAP` of a diameter apart and `GRID_EDGE`
 * of one in from the frame — so the grid is whole, inside the wall, however the elements turn.
 *
 * **Its face takes the house colours by which way it leans**: nearly white, running a touch toward
 * the WN blue as an element tips one way and the red as it tips the other, the sine of twice its
 * angle — so a frame and the same frame half a turn on are the same colour, and the colour bands
 * travel with the angle bands. Its sides and the inside of its opening are dark, lit a little by one
 * light, with a thin line on every real edge.
 *
 * `GRID_TURN` is seconds for a whole turn, steady; `GRID_CLICK` above 0 turns in eased clicks of that
 * many degrees instead, `GRID_LAG` seconds later a row, so each click runs down the wall as a wave.
 */
fun main() = runCourse("course-v3-grid", preview = 8.0) { gridCourse() }

/**
 * Which element the grid stands: the frame, then every piece off `data/objects` in name order, and
 * the one in hand. The studio steps it with `j` and `k`; the grid reads it every frame, so a step is
 * a new element on the next frame with nothing else about the wall changed.
 */
class PieceChoice(val names: List<String>, first: String) {
    var index = names.indexOf(first).coerceAtLeast(0)
        private set
    val name get() = names[index]
    fun step(by: Int) { index = (index + by).mod(names.size) }

    companion object {
        fun fromEnv() = PieceChoice(
            listOf("frame") + (File("data/objects").listFiles { f -> f.extension == "obj" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()),
            Env["GRID_PIECE"] ?: "frame")
    }
}

fun Program.gridCourse(pieces: PieceChoice = PieceChoice.fromEnv()): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["GRID_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default

    /** A piece as the grid stands it: its mesh, turned to lie on the wall, and how far it reaches. */
    class Piece(val mesh: ObjMesh, val lay: Matrix44, val reach: Double)
    val loaded = HashMap<String, Piece?>()
    fun piece(name: String): Piece? = loaded.getOrPut(name) {
        val file = if (name == "frame") frameObj(key("FRAME") ?: "3.2,1,0.2,0.5") else File("data/objects/$name.obj")
        val mesh = loadObjMesh(file) ?: return@getOrPut null.also { println("grid: could not read ${file.path}") }
        // Turned so its longest side runs across the wall, its shortest out of it and the third up it,
        // whichever way the file has them: a column lies down, a panel shows its face.
        val span = Vector3(mesh.points.maxOf { it.x } - mesh.points.minOf { it.x },
            mesh.points.maxOf { it.y } - mesh.points.minOf { it.y },
            mesh.points.maxOf { it.z } - mesh.points.minOf { it.z })
        val order = listOf(0 to span.x, 1 to span.y, 2 to span.z).sortedByDescending { it.second }.map { it.first }
        fun unit(axis: Int) = when (axis) { 0 -> Vector3.UNIT_X; 1 -> Vector3.UNIT_Y; else -> Vector3.UNIT_Z }
        val long = unit(order[0]); val mid = unit(order[1])
        // A turn, not a mirror: the third row's sign is whatever keeps the determinant positive.
        val thin = unit(order[2]).let { if (long.cross(mid).dot(it) < 0.0) -it else it }
        val lay = Matrix44(
            long.x, long.y, long.z, 0.0,
            mid.x, mid.y, mid.z, 0.0,
            thin.x, thin.y, thin.z, 0.0,
            0.0, 0.0, 0.0, 1.0)
        println("grid: ${mesh.name}, ${mesh.triangles} triangles")
        // The loader centres a piece and fits it to a unit sphere, so it reaches 1 from its centre at
        // any angle — measured anyway, in case it ever does not.
        Piece(mesh, lay, mesh.points.maxOf { it.length })
    }

    // Spacing: every element is kept inside a circle it can never leave, whatever its angle, its
    // tilt or the angle it is seen from, and the circles are held `GRID_GAP` of a diameter apart and
    // `GRID_EDGE` of one in from the edge of the wall. So no two elements ever touch, and none is cut
    // by the frame. The loader fits every piece to one size, so the grid holds when the piece changes.
    val gap = number("GAP", 0.1)
    val edge = number("EDGE", 0.2)
    val rows = key("ROWS")?.toIntOrNull() ?: 10
    val rowStep = number("ROW_STEP", 33.0)
    val columnStep = number("COLUMN_STEP", 3.0)
    val start = number("ANGLE", 30.0)
    val tilt = number("TILT", 0.0)
    val turn = number("TURN", 72.0)
    val click = number("CLICK", 0.0)
    val move = number("MOVE", 2.4)
    val rest = number("REST", 1.6)
    val lag = number("LAG", 0.12)
    fun ease(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }

    /** How far every element has turned by [time], in degrees, the row and column it stands in given. */
    fun turned(row: Int, column: Int, time: Double): Double {
        if (click <= 0.0) return if (turn > 0.0) 360.0 * time / turn else 0.0
        // Clicks: a row sets off a lag after the one above it and a column a fifth of that after the
        // one to its left, so a click runs down and across the wall.
        val local = (time - lag * (row + column * 0.2)).coerceAtLeast(0.0)
        val beat = move + rest
        val k = floor(local / beat)
        return (k + ease((local - k * beat) / move)) * click
    }

    val paper = ColorRGBa.fromHex(key("PAPER") ?: "000000")
    val tints = (key("TINTS") ?: "3D5AE0,FF0000").split(",").map { ColorRGBa.fromHex(it.trim()) }
    val style = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;"
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            // The face, front or back, takes the element's own tint; its sides and the inside of its
            // opening are dark, lit a little by one light.
            float face = abs(dot(n, normalize(p_faceAxis)));
            float lean = p_lean;
            vec3 tint = mix(vec3(1.0), lean > 0.0 ? p_tintA.rgb : p_tintB.rgb, abs(lean) * p_tintAmount);
            float lambert = max(dot(n, normalize(p_light)), 0.0);
            vec3 side = mix(p_sideDark.rgb, p_sideLit.rgb, lambert);
            vec3 c = mix(side, tint * p_face.rgb, smoothstep(0.6, 0.8, face));
            // A line on every real edge, the same width in pixels however the element stands.
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float line = p_width <= 0.0 ? 0.0 : 1.0 - smoothstep(p_width - p_soft, p_width + p_soft, d);
            x_fill = vec4(mix(c, p_ink.rgb, line), 1.0);
        """
        parameter("tintA", tints.getOrElse(0) { ColorRGBa.WHITE })
        parameter("tintB", tints.getOrElse(1) { tints.getOrElse(0) { ColorRGBa.WHITE } })
        parameter("tintAmount", number("TINT", 0.2))
        parameter("face", ColorRGBa.fromHex(key("FACE") ?: "FAFAFA"))
        parameter("sideDark", ColorRGBa.fromHex(key("SIDE_DARK") ?: "0C0C0E"))
        parameter("sideLit", ColorRGBa.fromHex(key("SIDE_LIT") ?: "4E5056"))
        parameter("ink", ColorRGBa.fromHex(key("INK") ?: "9A9CA2"))
        parameter("width", number("LINE", 1.0))
        parameter("soft", number("SOFT", 0.6))
        parameter("light", Vector3(-0.45, 0.75, 0.5))
    }

    return { drawer: Drawer, time: Double ->
        drawer.clear(paper)
        // The camera looks straight at the wall, so the grid stays square to the frame; what the angle
        // turns is every element, each on its own centre, as if it were seen from there — a little left
        // of it and below by default, so its depth shows the way the sketch has it. Yaw 0 and pitch 0 is
        // every element square on. The cursor and the saved views steer it as they steer the climb.
        val pointer = CourseControl.pointer
        val fixed = CourseControl.fixed
        val baseYaw = number("YAW", -32.0)
        val basePitch = number("VIEW_PITCH", -24.0)
        val yawDegrees = (fixed?.x ?: if (pointer != null) baseYaw + (pointer.x - 0.5) * 360.0 else baseYaw).mod(360.0)
        val pitchDegrees = ((fixed?.y ?: if (pointer != null) basePitch + (pointer.y - 0.5) * 360.0 else basePitch) + 180.0).mod(360.0) - 180.0
        CourseControl.current = org.openrndr.math.Vector2(yawDegrees, pitchDegrees)
        val yaw = Math.toRadians(yawDegrees)
        val up0 = Math.toRadians(pitchDegrees)
        val eye = Vector3(sin(yaw) * cos(up0), sin(up0), cos(yaw) * cos(up0))
        val up = Vector3(-sin(yaw) * sin(up0), cos(up0), -cos(yaw) * sin(up0))
        // The view from there, less its distance: a turn that, laid on an element, shows it from a
        // camera straight on exactly as the camera at `eye` would have seen it.
        val seen = lookAtMatrix(eye, Vector3.ZERO, up).copy(c3r0 = 0.0, c3r1 = 0.0, c3r2 = 0.0)

        val piece = piece(pieces.name) ?: piece("frame")!!
        // `rows` circles top to bottom with the gaps between them and the edge above and below, and as
        // many columns as fit across the same way; what is left over across is shared by both ends.
        val diameter = 2.0 * piece.reach
        val pitch = diameter * (1.0 + gap)
        val halfHeight = (rows * diameter + (rows - 1) * diameter * gap) / 2.0 + edge * diameter
        val aspect = drawer.width.toDouble() / drawer.height
        val halfWidth = halfHeight * aspect
        val columns = floor((2.0 * halfWidth - 2.0 * edge * diameter + diameter * gap) / pitch).toInt().coerceAtLeast(1)

        drawer.isolated {
            drawer.projection = orthoMatrix(-halfWidth, halfWidth, -halfHeight, halfHeight, -400.0, 400.0)
            drawer.view = lookAtMatrix(Vector3(0.0, 0.0, 100.0), Vector3.ZERO, Vector3.UNIT_Y)
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.shadeStyle = style
            for (row in 0 until rows) for (column in 0 until columns) {
                // Counted from the top left, so the angles read down and across.
                val x = (column - (columns - 1) / 2.0) * pitch
                val y = ((rows - 1) / 2.0 - row) * pitch
                val angle = start + row * rowStep + column * columnStep + turned(row, column, time)
                val place = buildTransform { translate(x, y, 0.0) } * seen * buildTransform {
                    rotate(Vector3.UNIT_Z, angle)
                    rotate(Vector3.UNIT_X, tilt)
                }
                style.parameter("faceAxis", (place * Vector4(0.0, 0.0, 1.0, 0.0)).xyz)
                style.parameter("lean", sin(Math.toRadians(2.0 * angle)))
                drawer.model = place * piece.lay
                drawer.vertexBuffer(piece.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
            }
            drawer.model = Matrix44.IDENTITY
        }
    }
}

/**
 * A rectangular frame as an `.obj` — length, height, border and depth, in that order and comma
 * separated, any unit — written under `build/course-grid/` for the catalogue's loader to read. Every
 * face is a quad: the front and back each as four trapezoids round the opening, whose seams are
 * coplanar and so draw no line, then the four outer sides and the four inside the opening.
 */
fun frameObj(spec: String): File {
    val (length, height, border, depth) = spec.split(",").map { it.trim().toDouble() }
    val a = length / 2.0; val b = height / 2.0
    val ia = a - border; val ib = b - border
    val t = depth / 2.0
    val outer = listOf(-a to -b, a to -b, a to b, -a to b)
    val inner = listOf(-ia to -ib, ia to -ib, ia to ib, -ia to ib)
    // Written in the file's Z-up convention, which the loader turns Y up: x stays, the loader's y is
    // the file's z, and its z the file's -y.
    val points = mutableListOf<Vector3>()
    fun at(p: Pair<Double, Double>, z: Double): Int { points += Vector3(p.first, p.second, z); return points.size }
    val oF = outer.map { at(it, t) }; val oB = outer.map { at(it, -t) }
    val iF = inner.map { at(it, t) }; val iB = inner.map { at(it, -t) }
    val faces = mutableListOf<List<Int>>()
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        faces += listOf(oF[i], oF[j], iF[j], iF[i])     // front, facing +z
        faces += listOf(oB[i], iB[i], iB[j], oB[j])     // back, facing -z
        faces += listOf(oB[i], oB[j], oF[j], oF[i])     // outer side, facing out
        faces += listOf(iF[i], iF[j], iB[j], iB[i])     // inside the opening, facing in
    }
    val file = File("build/course-grid/frame-${spec.replace(",", "_").replace(" ", "")}.obj")
    file.parentFile.mkdirs()
    file.writeText(buildString {
        appendLine("o frame")
        points.forEach { appendLine("v ${it.x} ${-it.z} ${it.y}") }
        faces.forEach { f -> appendLine("f " + f.joinToString(" ")) }
    })
    return file
}
