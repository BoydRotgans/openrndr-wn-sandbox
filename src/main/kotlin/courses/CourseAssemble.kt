import org.openrndr.color.ColorRGBa
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Quaternion
import org.openrndr.math.Vector4
import org.openrndr.math.slerp
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix
import org.openrndr.shape.Rectangle
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.WrapMode
import org.openrndr.draw.loadImage
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.buildTransform
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// sketch-default: gallery, graphic, one projector
// sketch-variant: catalogue on the floor | | ASSEMBLE_CITY_MODE=catalogue ASSEMBLE_CITY_CELL=13 | the catalogue laid out isometric on the plaza, beside the building
// sketch-variant: street, floating and falling | | ASSEMBLE_CITY_MODE=row ASSEMBLE_CITY_CELL=20 | the street on one projector: taken down on the left, the pieces float and fall through the floor, rebuilt on the right
// sketch-variant: street, graphic, the wall | | ASSEMBLE_CITY_MODE=row ASSEMBLE_CITY_WIDTH=3840 ASSEMBLE_CITY_ZOOM=1.536 ASSEMBLE_CITY_CELL=20 ASSEMBLE_CITY_LAG=2 ASSEMBLE_CITY_GRAVITY=0 ASSEMBLE_CITY_FLOAT=0.8 ASSEMBLE_CITY_STREET_Y=0.5 | the street across the whole wall, a piece shrinking away as one grows in
// sketch-variant: street, grey, the wall | | ASSEMBLE_CITY_MODE=row ASSEMBLE_CITY_STYLE=grey ASSEMBLE_CITY_WIDTH=3840 ASSEMBLE_CITY_ZOOM=1.536 ASSEMBLE_CITY_CELL=20 ASSEMBLE_CITY_LAG=2 ASSEMBLE_CITY_GRAVITY=0 ASSEMBLE_CITY_FLOAT=0.8 ASSEMBLE_CITY_STREET_Y=0.5 | the Second course's greys, occlusion and concrete
/**
 * v5 · **assembling in the city**: the catalogue's precast pieces — the door wall, the window walls, the
 * floor plates — built into buildings in the Second course's city, drawn in its light: its isometric
 * view and sun, and they go into its shadow pass and its colour pass, so a piece throws its shadow on
 * the ground and the blocks and takes theirs. `AssembleScene` keeps the schedule, so a wall and this
 * course cannot differ in anything but where they are drawn.
 *
 *     ./gradlew run -Popenrndr.application=CourseAssembleKt
 *
 * **`ASSEMBLE_CITY_MODE=gallery` (the default): the catalogue as a flat sheet over the scene.** The
 * kit is drawn in two dimensions on a white panel on the left — every piece lying face up, seen straight
 * down, a red silhouette in a ruled cell of its own, packed like a specimen sheet — while the city and the
 * building stay isometric. A piece leaves its cell through a camera of its own that turns, over the
 * flight, from straight down to the scene's isometric, while the piece turns from lying to standing and
 * crosses to the air over its place; at the end its camera is the scene's, so it hands over to the scene
 * with nothing to see, and is set down. Back to the sheet the same way. Each round, another building.
 *
 * **`catalogue`: a building, or the kit as a catalogue, and each time another
 * building** — like Lego. On one projector, the camera still: the kit lies flat on the floor on the
 * left, each piece in a ruled cell of its own, packed like a specimen sheet; it lifts out piece by piece,
 * stands up in the air, crosses over and is set down into a building on the right, from the ground up;
 * the building stands; it is taken down from the top and every piece laid back in its own cell; and the
 * next building is another of the kit's massings.
 *
 * **`row`: a street** cut through the blocks along the screen's horizontal, the camera panning along it,
 * a building built at the right and taken down at the left, a piece going as one arrives; on one
 * projector the pieces taken down float and then fall through the floor.
 *
 * **`ASSEMBLE_CITY_STYLE=graphic` is four flat colours** — white, red, navy and black off
 * `ASSEMBLE_CITY_PALETTE` — after a sheet of graphic crops: no greys, no grain, no occlusion, only flat
 * paint and the long hard shadow of a low sun. `grey` is the Second course's own soft greys and concrete,
 * with a piece WN red while it is on its way.
 *
 * `ASSEMBLE_CITY_*` in `.env` steer it.
 */
fun main() = runCourse(
    "course-v5-assemble", preview = 30.0,
    canvasWidth = Env["ASSEMBLE_CITY_WIDTH"]?.toIntOrNull() ?: 1920
) { assembleCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.assembleCourse(): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["ASSEMBLE_CITY_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    val mode = key("MODE") ?: "gallery"
    val catalogueMode = mode == "catalogue"
    val galleryMode = mode == "gallery"
    val graphic = (key("STYLE") ?: "graphic") == "graphic"

    val stoneMap = if (graphic) null else (key("CONCRETE") ?: "data/concrete/concrete-052v2_crop.jpg").takeIf { it != "none" }
        ?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            loadImage(f).also {
                // Mirrored: the stone does not tile, and a plain repeat rules its seams across the city.
                it.wrapU = WrapMode.MIRRORED_REPEAT; it.wrapV = WrapMode.MIRRORED_REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
    val city = SiteCity(Site.load(), stoneMap = stoneMap)

    // Site pixels a cell of the kit (a bay is five, a storey three), and canvas pixels a site pixel.
    val cell = number("CELL", 13.0)
    val zoom = number("ZOOM", 1.8)
    val period = number("PERIOD", 22.0)
    val lag = number("LAG", 1.0).toInt().coerceAtLeast(1)

    val ease = (key("EASE") ?: "0.4,0,0.2,1").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        .takeIf { it.size == 4 }?.let { slideshow.CubicBezier(it[0], it[1], it[2], it[3]) } ?: slideshow.snap
    fun names(k: String, default: String) = (Env[k] ?: default).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val catalogueAt = (key("CATALOGUE_AT") ?: "-20,20").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        .takeIf { it.size == 2 }?.let { Vector2(it[0], it[1]) } ?: Vector2(-20.0, 20.0)
    val kit = AssembleScene(
        name = "Assemble, city",
        sheet = File("none"), details = File("none"),
        grammar = when { galleryMode -> "gallery"; catalogueMode -> "catalogue"; else -> "row" }, render = "solid",
        objects = File(Env["SLIDES_YARD_OBJECTS"] ?: "data/objects"),
        walls = names("SLIDES_ASSEMBLE_WALLS", "WAND_27,WAND_33,WAND_17,WAND_23,WAND_6,WAND_8,WAND_11,WAND_28,WAND_10,WAND_22,WAND_12,WAND"),
        floors = names("SLIDES_ASSEMBLE_FLOORS", "VLOER,VLOER_3,PREDAL,VLOER_2"),
        front = names("SLIDES_ASSEMBLE_FRONT", "WAND_27,WAND_17,WAND_33,WAND_23"),
        plates = Env["SLIDES_ASSEMBLE_PLATES"]?.toIntOrNull() ?: 8,
        panels = Env["SLIDES_ASSEMBLE_PANELS"]?.toIntOrNull() ?: 20,
        storeys = Env["SLIDES_ASSEMBLE_STOREYS"]?.toIntOrNull() ?: 3,
        spread = number("SPREAD", 1.35), rise = number("RISE", 2.2), fromGround = true,
        move = number("MOVE", 0.8), gapStart = number("GAP_START", 0.45), gapEnd = number("GAP_END", 0.25),
        rowPeriod = period, rowLag = lag, curve = ease, grow = number("GROW", 0.5),
        wait = 0.5, dwell = number("FLOAT", 2.0), fade = 1.2,
        gravity = number("GRAVITY", 30.0), bob = number("BOB", 0.4),
        catalogueAt = catalogueAt, catalogueWidth = number("CATALOGUE_WIDTH", 24.0),
        catalogueHold = number("CATALOGUE_HOLD", 2.5), buildHold = number("BUILD_HOLD", 3.0),
        flight = number("FLIGHT", 1.8),
        seed = Env["SLIDES_ASSEMBLE_SEED"]?.toIntOrNull() ?: 5
    )
    kit.load(this)

    // The screen's horizontal, at 45 degrees round, is x one way and z the other.
    val along = Vector2(1.0, -1.0).normalized
    val origin = Vector2(1920.0, 540.0)
    val iso = Math.toDegrees(atan(1.0 / sqrt(2.0)))
    val toCamera = Vector3(sin(Math.toRadians(45.0)) * cos(Math.toRadians(iso)), sin(Math.toRadians(iso)),
        cos(Math.toRadians(45.0)) * cos(Math.toRadians(iso)))
    // A step toward the top of the frame, on the ground: away from the camera.
    val away = Vector2(-1.0, -1.0).normalized

    val accent = ColorRGBa.fromHex(key("ACCENT") ?: "FF0000")
    val palette = (key("PALETTE") ?: "F5F7FA,FF0000,1E3A72,0A0A0F").split(",").map { ColorRGBa.fromHex(it.trim()) }
    val white = palette.getOrElse(0) { ColorRGBa.WHITE }; val red = palette.getOrElse(1) { accent }
    val navy = palette.getOrElse(2) { ColorRGBa.BLUE }; val black = palette.getOrElse(3) { ColorRGBa.BLACK }
    val sunAngle = number("SUN_ANGLE", if (graphic) 15.0 else 120.0)
    val sunElevation = number("SUN_ELEVATION", if (graphic) 20.0 else 34.0)
    val look = if (graphic) SiteCity.Look(
        tower = number("TOWER", 240.0), cycle = false, lift = 1.0, flat = -1.0, proportional = true, ao = 0.0,
        paints = listOf(
            SiteCity.Paint(white, white, navy, 0.55, back = navy),
            SiteCity.Paint(navy, navy, black, 0.3, back = black),
            SiteCity.Paint(black, black, black, 0.15, back = black)
        ),
        groundLit = white, groundShade = navy
    ) else SiteCity.Look(
        tower = number("TOWER", 240.0), cycle = false, lift = 1.0, flat = -1.0,
        toneLow = 0.72, toneHigh = 0.97, litSide = 0.82, darkSide = 0.55, shadow = 0.62,
        groundTone = 0.74, ao = number("AO", 0.5), aoReach = 70.0,
        proportional = true,
        stone = number("STONE", 1.2), stoneScale = number("STONE_SCALE", 900.0)
    )
    val piecePaint = if (graphic) SiteCity.Paint(red, red, black, back = black) else null
    val guide = if (graphic) black else ColorRGBa(0.18, 0.18, 0.2, 1.0)
    val wire = WireCubes(emptyList(), accent, 1.0, guide, ColorRGBa.WHITE, 1.2)
    var lineBuffer: VertexBuffer? = null

    fun sceneFrame(drawer: Drawer, time: Double) {
        val half = drawer.width / (2.0 * zoom)
        // Pieces and lines in the kit's cells, and where the camera looks.
        val (pieces, lines, target, lift) = if (catalogueMode) {
            // The building on the right of the frame, the catalogue on the left; the camera between them.
            val (p, l) = kit.catalogue(time)
            val middle = Vector2(catalogueAt.x / 2.0, catalogueAt.y / 2.0) * cell
            Quad(p, l, origin + middle, origin)
        } else {
            // Along the street, from the middle of the frame: where a building is begun and where it goes.
            val buildAt = number("BUILD_AT", 0.56) * half
            val leaveAt = -number("LEAVE_AT", 0.88) * half
            val spacing = (buildAt - leaveAt) / lag
            val speed = spacing / period
            // The street stands low in the frame, so the pieces have room to float above it.
            val low = (number("STREET_Y", 0.62) - 0.5) * (drawer.height / zoom) / sin(Math.toRadians(iso))
            val (p, l) = kit.row(time) { k, _ -> val q = origin + along * (k * spacing + buildAt); Vector3(q.x / cell, 0.0, q.y / cell) }
            Quad(p, l, origin + along * (speed * time) + away * low, Vector2.ZERO)
        }
        // From the kit's cells to the site: its ground on the floor, a building standing at [lift].
        val place = buildTransform { translate(lift.x, 0.0, lift.y); scale(cell); translate(0.0, -kit.ground, 0.0) }
        val drawn = lines.filter { it.second > 0.02 }
        if (drawn.isNotEmpty()) lineBuffer = wire.segments(drawn.map { transformed(place, it.first.first) to transformed(place, it.first.second) }, lineBuffer).first
        val street = SiteCity.Street(origin, along, number(if (catalogueMode) "PLAZA" else "STREET", if (catalogueMode) 330.0 else 230.0))
        city.draw(
            drawer, time,
            SiteCity.View(target = target, half = half, yaw = 45.0, pitch = iso, sunAngle = sunAngle, sunElevation = sunElevation),
            look,
            street = street,
            pieces = pieces.map { SiteCity.Piece(it.mesh.vertexBuffer, place * it.model, it.active) },
            pieceTone = number("PIECE_TONE", 0.92),
            accent = accent,
            piecePaint = piecePaint,
            overlay = { d ->
                val buffer = lineBuffer
                if (buffer != null && drawn.isNotEmpty()) {
                    d.depthWrite = false
                    wire.edges(d, buffer, drawn.size * 6, toCamera, zoom,
                        colour = if (graphic) guide else guide.opacify(0.55), width = 1.4)
                    d.depthWrite = true
                }
            }
        )
    }

    // ---- the gallery: the kit as a flat sheet over the scene ---------------------------------------- //

    val seed = Env["SLIDES_ASSEMBLE_SEED"]?.toIntOrNull() ?: 5
    val pad = number("SHEET_PAD", 0.7)
    val setRoof = piecePaint?.roof ?: ColorRGBa(0.92, 0.92, 0.92, 1.0)
    val setSide = piecePaint?.side ?: ColorRGBa(0.8, 0.8, 0.8, 1.0)
    val setBack = piecePaint?.back ?: ColorRGBa(0.55, 0.55, 0.55, 1.0)
    val a = Math.toRadians(sunAngle); val el = Math.toRadians(sunElevation)
    val toSun = Vector3(-cos(a) * cos(el), sin(el), -sin(a) * cos(el)).normalized

    // A piece off the sheet or in the air: flat paint by the way a face points in the world, the scene's
    // own rule, so the piece arriving is the piece the scene then draws.
    val flying = shadeStyle {
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            if (dot(n, p_eye) < 0.0) n = -n;
            vec3 c = n.y > 0.5 ? p_roof.rgb : (dot(n, p_toSun) > 0.0 ? p_side.rgb : p_back.rgb);
            x_fill = vec4(c, 1.0);
        """
    }

    /** The sheet: its panel, a cell a piece, and pixels a cell of the kit. */
    class Sheet(val panel: Rectangle, val cells: List<Rectangle>, val scale: Double)

    /**
     * The sheet packed like the specimen page: every piece lying face up with its length across, a
     * margin round it, rows the panel's width with their cells stretched to fill it and the rows stretched
     * to fill its height, at the largest size that fits. The order is dealt from the seed.
     */
    fun layoutSheet(w: Double, h: Double): Sheet {
        val margin = number("SHEET_MARGIN", 48.0)
        val panel = Rectangle(margin, margin, number("SHEET_WIDTH", 0.3) * w, h - 2.0 * margin)
        val order = (0 until kit.pieceCount).shuffled(kotlin.random.Random(seed))
        fun cellOf(e: Int) = kit.footprint(e) + Vector2(2.0 * pad)
        fun rows(k: Double): List<List<Int>> {
            val out = mutableListOf<MutableList<Int>>()
            var used = 0.0
            for (e in order) {
                val cw = cellOf(e).x * k
                if (out.isEmpty() || (out.last().isNotEmpty() && used + cw > panel.width)) { out += mutableListOf<Int>(); used = 0.0 }
                out.last() += e; used += cw
            }
            return out
        }
        fun height(k: Double) = rows(k).sumOf { r -> r.maxOf { cellOf(it).y * k } }
        var lo = 1.0; var hi = 400.0
        repeat(40) { val mid = (lo + hi) / 2.0; if (height(mid) <= panel.height) lo = mid else hi = mid }
        val k = lo
        val rs = rows(k)
        val natural = rs.map { r -> r.maxOf { cellOf(it).y * k } }
        val tall = panel.height / natural.sum()
        val cells = arrayOfNulls<Rectangle>(kit.pieceCount)
        var y = panel.y
        rs.forEachIndexed { i, r ->
            val rh = natural[i] * tall
            val wide = panel.width / r.sumOf { cellOf(it).x * k }
            var x = panel.x
            for (e in r) { val cw = cellOf(e).x * k * wide; cells[e] = Rectangle(x, y, cw, rh); x += cw }
            y += rh
        }
        return Sheet(panel, cells.map { it!! }, k)
    }

    var sheet: Sheet? = null
    fun cameraAt(yawDegrees: Double, pitchDegrees: Double): Pair<Vector3, Vector3> {
        val y = Math.toRadians(yawDegrees); val p = Math.toRadians(pitchDegrees)
        return Vector3(sin(y) * cos(p), sin(p), cos(y) * cos(p)) to Vector3(-sin(y) * sin(p), cos(p), -cos(y) * sin(p))
    }
    fun turn(axis: Vector3, degrees: Double): Quaternion {
        val h = Math.toRadians(degrees) / 2.0
        return Quaternion(axis.x * sin(h), axis.y * sin(h), axis.z * sin(h), cos(h))
    }

    /**
     * The gallery frame: the scene with the building, the sheet over it, and every piece not in the scene
     * drawn through a camera of its own — straight down on its cell at 0, the scene's isometric at 1 — as it
     * turns from lying face up to standing and crosses from its cell to the air over its place. At 1 its
     * camera is the scene's, so it hands over to the scene with nothing to see.
     */
    fun galleryFrame(drawer: Drawer, time: Double) {
        val w = drawer.width.toDouble(); val h = drawer.height.toDouble()
        val half = w / (2.0 * zoom)
        val s = sheet ?: layoutSheet(w, h).also { sheet = it }
        // The building in the middle of what the sheet leaves, standing low in the frame.
        val bx = (s.panel.x + s.panel.width + w) / 2.0
        val by = number("BUILDING_Y", 0.7) * h
        val target = origin - along * ((bx - w / 2.0) / zoom) + away * ((by - h / 2.0) / zoom / sin(Math.toRadians(iso)))
        val place = buildTransform { translate(origin.x, 0.0, origin.y); scale(cell); translate(0.0, -kit.ground, 0.0) }
        val all = kit.gallery(time)
        city.draw(
            drawer, time,
            SiteCity.View(target = target, half = half, yaw = 45.0, pitch = iso, sunAngle = sunAngle, sunElevation = sunElevation),
            look,
            street = SiteCity.Street(origin, along, number("PLAZA", 330.0)),
            pieces = all.filter { it.flight >= 1.0 }.map { SiteCity.Piece(it.mesh.vertexBuffer, place * it.model, 0.0) },
            pieceTone = number("PIECE_TONE", 0.92),
            accent = accent,
            piecePaint = piecePaint
        )
        // The sheet, ruled.
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.fill = white; drawer.stroke = black; drawer.strokeWeight = 2.0
            drawer.rectangle(s.panel)
            drawer.strokeWeight = 1.5
            drawer.lineSegments(s.cells.flatMap { r ->
                listOf(r.corner, Vector2(r.x + r.width, r.y), Vector2(r.x + r.width, r.y), Vector2(r.x + r.width, r.y + r.height),
                    Vector2(r.x + r.width, r.y + r.height), Vector2(r.x, r.y + r.height), Vector2(r.x, r.y + r.height), r.corner)
            })
        }
        // The scene's camera, to find where a piece stands on the frame.
        val (eye1, up1) = cameraAt(45.0, iso)
        val right1 = up1.cross(eye1).normalized
        val t3 = Vector3(target.x, 0.0, target.y)
        for (g in all.filter { it.flight < 1.0 }.sortedBy { it.flight }) {
            val f = g.flight
            val centre = transformed(place, g.centre)
            val s0 = s.cells[g.index].center
            val s1 = Vector2(w / 2.0 + (centre - t3).dot(right1) * zoom, h / 2.0 - (centre - t3).dot(up1) * zoom)
            val at = s0 + (s1 - s0) * f
            val k0 = s.scale / cell
            val k = kotlin.math.exp(kotlin.math.ln(k0) + (kotlin.math.ln(zoom) - kotlin.math.ln(k0)) * f)
            val (eye, up) = cameraAt(45.0 * f, 90.0 - (90.0 - iso) * f)
            // Lying face up with its length across, turned to how it stands over its place.
            val flat = when {
                g.plate -> Quaternion.IDENTITY
                g.alongX -> turn(Vector3.UNIT_X, -90.0)
                else -> turn(Vector3.UNIT_X, -90.0) * turn(Vector3.UNIT_Y, 90.0)
            }
            val r = slerp(flat, Quaternion.IDENTITY, f).matrix
            val spin = Matrix44.fromColumnVectors(
                Vector4(r.c0r0, r.c0r1, r.c0r2, 0.0), Vector4(r.c1r0, r.c1r1, r.c1r2, 0.0),
                Vector4(r.c2r0, r.c2r1, r.c2r2, 0.0), Vector4(0.0, 0.0, 0.0, 1.0)
            )
            val model = buildTransform { translate(centre) } * spin * buildTransform { translate(-centre) } * (place * g.model)
            drawer.isolated {
                RenderTarget.active.clearDepth(1.0)
                drawer.projection = orthoMatrix(-at.x / k, (w - at.x) / k, -(h - at.y) / k, at.y / k, -20000.0, 20000.0)
                drawer.view = lookAtMatrix(centre + eye * 6000.0, centre, up)
                drawer.model = model
                drawer.depthWrite = true
                drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
                drawer.drawStyle.cullTestPass = CullTestPass.ALWAYS
                flying.parameter("eye", eye)
                flying.parameter("toSun", toSun)
                flying.parameter("roof", setRoof); flying.parameter("side", setSide); flying.parameter("back", setBack)
                drawer.shadeStyle = flying
                drawer.vertexBuffer(g.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
            }
        }
    }

    return { drawer: Drawer, time: Double -> if (galleryMode) galleryFrame(drawer, time) else sceneFrame(drawer, time) }
}

/** What a frame of the course needs: the pieces, their lines, where the camera looks, and where the kit's origin stands. */
private data class Quad(
    val pieces: List<AssembleScene.RowPiece>,
    val lines: List<Pair<Pair<Vector3, Vector3>, Double>>,
    val target: Vector2,
    val lift: Vector2
)

/** [v] carried by [m], as a point. */
private fun transformed(m: Matrix44, v: Vector3): Vector3 = (m * org.openrndr.math.Vector4(v.x, v.y, v.z, 1.0)).xyz
