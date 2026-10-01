package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolated
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One projector of the wall: how large it is on the desktop, and where the wall's half stands on it.
 *
 * [width] and [height] are the display's size as macOS gives it, in points — its resolution, for a
 * projector that is not in a scaled mode. [x] and [y] are where the half's top left corner stands on
 * the projector, in the projector's own pixels, and [scale] how large the half is drawn there: 1 is
 * its 1920 by 1080 pixel for pixel, so a 1920 by 1200 projector shows it whole with 120 to spare.
 * [model] names the projector where it was chosen by model in the tab — the Epson EB-G7905U, say —
 * and is only a label: the size is what the window is opened at.
 */
data class Projector(
    val width: Int = 1920,
    val height: Int = 1080,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val scale: Double = 1.0,
    val model: String? = null
)

/**
 * The two projectors the wall is shown on, left and right, standing side by side on the desktop and
 * top aligned, and where the left one's corner is on the desktop.
 *
 * The sizes and the desktop place are the window's, so they are read when the show starts and hold
 * for the run; the halves' places on them can be moved while it runs. See [Projectors].
 */
data class ProjectorSetup(
    val left: Projector = Projector(),
    val right: Projector = Projector(),
    /** The left projector's top left corner on the desktop, in points; null to take the rightmost two displays. */
    val desktopX: Int? = null,
    val desktopY: Int? = null
) {
    /** The desktop the two cover together, which is the size of the show's window across them. */
    val width: Int get() = left.width + right.width
    val height: Int get() = max(left.height, right.height)

    /** True when each half stands on a projector of exactly its own size, at the corner, pixel for pixel: the wall as it always was. */
    fun plain(halfWidth: Int, halfHeight: Int) = listOf(left, right).all {
        it.width == halfWidth && it.height == halfHeight && it.x == 0.0 && it.y == 0.0 && it.scale == 1.0
    }

    /** These projectors' sizes and desktop place with [placed]'s halves on them. */
    fun placedAs(placed: ProjectorSetup) = copy(
        left = left.copy(x = placed.left.x, y = placed.left.y, scale = placed.left.scale),
        right = right.copy(x = placed.right.x, y = placed.right.y, scale = placed.right.scale)
    )
}

/**
 * Where the wall's two halves go on two projectors of whatever resolution — set on site, in the
 * organizer's Projection tab, and kept in `show-projection.json` (`SLIDES_PROJECTORS`).
 *
 * **The wall is two 1920 by 1080 halves, and a projector need not be 1920 by 1080.** A 1920 by 1200
 * projector, or one that is 4K, shows its half somewhere inside itself; which way up, how far down
 * and how large is a matter of the room, found by looking at the wall. So each half has a place and
 * a scale on its projector, and the show's window spans the two projectors' own sizes rather than
 * the canvas's.
 *
 * **The sizes need a start, the places do not.** The window is the size of the two projectors and is
 * opened once, so a resolution takes effect at the next start. A half's place and scale are laid on
 * at the very end, as the finished frame goes to the window — [ProjectorSplit] — so they are [shown]
 * live: moved in the page, moved on the wall. Nothing a slide draws knows about any of it, and a
 * film, a still or a preview never sees it.
 *
 * Left alone — both 1920 by 1080, the halves at their corners — it is the wall as it always was and
 * costs nothing.
 */
object Projectors {
    private var path = "show-projection.json"

    /** Keep the setup in [file] rather than `show-projection.json`. */
    @Synchronized
    fun use(file: String?): String {
        file?.takeIf { it.isNotBlank() }?.let { path = it }
        return path
    }

    val file: File get() = File(path)

    /** The setup the file holds over [plain] — the wall as it always was — or [plain] where there is none. */
    fun read(plain: ProjectorSetup = ProjectorSetup()): ProjectorSetup = runCatching {
        if (file.isFile) parse(Json.parseToJsonElement(file.readText()).jsonObject, plain) else null
    }.getOrElse {
        println("projection: could not read ${file.path} (${it.message}); the plain wall")
        null
    } ?: plain

    /** The plain wall for a canvas [width] by [height]: a projector a half, each exactly its size. */
    fun plain(width: Int, height: Int) = ProjectorSetup(Projector(width / 2, height), Projector(width / 2, height))

    /** The setup the running show's window was opened for; null in a process with no show. */
    @Volatile
    var started: ProjectorSetup? = null

    /** Writes [setup] for the next start. */
    @Synchronized
    fun save(setup: ProjectorSetup) {
        val o = buildJsonObject {
            put("about", "The two projectors of the wall and where its 1920x1080 halves stand on them, set in the organizer's Projection tab. " +
                    "width and height are each projector's size in points, read at the start; x, y and scale place the half on it and move the running show at once. " +
                    "desktop is the left projector's top left corner on the desktop, null for the rightmost two displays. SLIDES_PROJECTORS names the file.")
            json(setup).forEach { (k, v) -> put(k, v) }
        }
        file.writeText(pretty.encodeToString(JsonObject.serializer(), o) + "\n")
        println("projection: saved ${file.path} — " + describe(setup))
    }

    /** The halves as the running show places them, moved from the page; null while they are where the show started with them. */
    @Volatile
    var shown: ProjectorSetup? = null

    /** The projectors' edges and the halves' outlines drawn on the wall, for lining them up on site. Never saved. */
    @Volatile
    var outlines: Boolean = false

    fun describe(s: ProjectorSetup) = listOf("left" to s.left, "right" to s.right).joinToString(", ") { (side, p) ->
        "$side ${p.model?.let { "$it " } ?: ""}${p.width}x${p.height}, half at %.0f,%.0f x%.3f".format(p.x, p.y, p.scale)
    } + (s.desktopX?.let { ", desktop at $it,${s.desktopY ?: 0}" } ?: ", on the rightmost displays")

    fun json(s: ProjectorSetup): JsonObject = buildJsonObject {
        put("left", projectorJson(s.left))
        put("right", projectorJson(s.right))
        put("desktop", buildJsonObject {
            put("x", s.desktopX)
            put("y", s.desktopY)
        })
    }

    private fun projectorJson(p: Projector) = buildJsonObject {
        put("width", p.width); put("height", p.height); put("x", p.x); put("y", p.y); put("scale", p.scale)
        put("model", p.model)
    }

    /** [o] read over [base]: anything missing or out of range keeps [base]'s value. */
    fun parse(o: JsonObject, base: ProjectorSetup = ProjectorSetup()): ProjectorSetup {
        fun projector(key: String, b: Projector): Projector {
            val p = runCatching { o[key]?.jsonObject }.getOrNull() ?: return b
            fun int(k: String, d: Int) = p[k]?.jsonPrimitive?.intOrNull?.takeIf { it in 320..16384 } ?: d
            fun num(k: String, d: Double) = p[k]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 16384 } ?: d
            return Projector(
                int("width", b.width), int("height", b.height),
                num("x", b.x), num("y", b.y),
                num("scale", b.scale).takeIf { it in 0.1..8.0 } ?: b.scale,
                if ("model" in p) p["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } else b.model
            )
        }
        val desktop = runCatching { o["desktop"]?.jsonObject }.getOrNull()
        return ProjectorSetup(
            projector("left", base.left), projector("right", base.right),
            if (desktop != null) desktop["x"]?.jsonPrimitive?.intOrNull else base.desktopX,
            if (desktop != null) desktop["y"]?.jsonPrimitive?.intOrNull else base.desktopY
        )
    }

    /**
     * The displays macOS has, left to right: name, size and place on the desktop in points (top left,
     * y down, as a window is placed), and how many pixels a point is. Empty off a Mac.
     */
    fun displays(): JsonArray = runCatching {
        val script = """
            ObjC.import("AppKit");
            const screens = ${'$'}.NSScreen.screens.js;
            const main = screens[0].frame;
            JSON.stringify(screens.map(s => { const f = s.frame; return {
                name: s.localizedName.js, x: f.origin.x, y: main.size.height - (f.origin.y + f.size.height),
                width: f.size.width, height: f.size.height, scale: s.backingScaleFactor }; }))
        """.trimIndent()
        val out = ProcessBuilder("osascript", "-l", "JavaScript", "-e", script)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        JsonArray(Json.parseToJsonElement(out).jsonArray.sortedBy { it.jsonObject["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0 })
    }.getOrElse { JsonArray(emptyList()) }

    private val pretty = Json { prettyPrint = true }
}

/**
 * The window drawn across the two projectors: the show paints its frame as it always does, into a
 * picture the size of the window, and each half of it is then laid onto its projector at the place
 * and scale [Projectors] gives — outlined, when lining up. An extension added ahead of the show's
 * draw, so the show's own code, overlays and all, is untouched: it binds the picture before the show
 * draws and lays it out after. With the plain wall and no outlines it binds nothing and costs nothing.
 *
 * [started] is the setup the window was opened for; its sizes hold for the run and only the halves'
 * places follow [Projectors.shown]. [canvasWidth] by [canvasHeight] is the frame the show fits into
 * the window, split down its middle.
 */
class ProjectorSplit(
    private val started: ProjectorSetup,
    private val canvasWidth: Int,
    private val canvasHeight: Int
) : Extension {
    override var enabled = true
    private var picture: RenderTarget? = null
    private var laying: ProjectorSetup? = null

    override fun beforeDraw(drawer: Drawer, program: Program) {
        val setup = started.placedAs(Projectors.shown ?: started)
        val outlines = Projectors.outlines
        laying = setup.takeUnless { it.plain(canvasWidth / 2, canvasHeight) && !outlines }
        if (laying == null) return
        val w = program.width
        val h = program.height
        val target = picture?.takeIf { it.width == w && it.height == h }
            ?: renderTarget(w, h, program.window.contentScale) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }.also { picture?.destroy(); picture = it }
        target.bind()
    }

    override fun afterDraw(drawer: Drawer, program: Program) {
        val setup = laying ?: return
        val target = picture ?: return
        laying = null
        target.unbind()
        lay(drawer, target.colorBuffer(0), program.width.toDouble(), program.height.toDouble(), setup,
            canvasWidth.toDouble(), canvasHeight.toDouble(), Projectors.outlines)
    }

    companion object {
        /**
         * [picture] — the window's frame, the canvas fitted into it as the show fits it — laid out on
         * a window [w] by [h] that covers [setup]'s two projectors: each half of the canvas onto its
         * projector at its place and scale, clipped to the projector so it never spills onto the other.
         */
        fun lay(
            drawer: Drawer, picture: ColorBuffer, w: Double, h: Double, setup: ProjectorSetup,
            canvasWidth: Double, canvasHeight: Double, outlines: Boolean
        ) {
            // The two projectors in the window: at 1:1 on site, scaled down in a window on a laptop.
            val k = min(w / setup.width, h / setup.height)
            val ox = (w - setup.width * k) / 2.0
            val oy = (h - setup.height * k) / 2.0
            // Where the show put the canvas in the picture: fitted and centred, as it does in the window.
            val fit = min(w / canvasWidth, h / canvasHeight)
            val shown = Rectangle.fromCenter(Vector2(w / 2.0, h / 2.0), canvasWidth * fit, canvasHeight * fit)
            drawer.isolated {
                drawer.clear(ColorRGBa.BLACK)
                drawer.shadeStyle = null
                listOf(setup.left, setup.right).forEachIndexed { i, p ->
                    val frame = Rectangle(ox + (if (i == 0) 0.0 else setup.left.width * k), oy, p.width * k, p.height * k)
                    val source = Rectangle(shown.x + i * shown.width / 2.0, shown.y, shown.width / 2.0, shown.height)
                    val half = Rectangle(frame.x + p.x * k, frame.y + p.y * k,
                        canvasWidth / 2.0 * p.scale * k, canvasHeight * p.scale * k)
                    drawer.drawStyle.clip = frame
                    drawer.fill = ColorRGBa.WHITE
                    drawer.stroke = null
                    drawer.image(picture, source, half)
                    if (outlines) {
                        drawer.fill = null
                        drawer.strokeWeight = 2.0
                        // The projector's own edge in red, the half's in green with a cross through its
                        // middle: on the wall the red has to meet the projector's edge exactly, and the
                        // green sits where the half does.
                        drawer.stroke = ColorRGBa.fromHex("FF2020")
                        drawer.rectangle(frame.offsetEdges(-1.0))
                        drawer.stroke = ColorRGBa.fromHex("20FF60")
                        drawer.rectangle(half.offsetEdges(-1.0))
                        drawer.lineSegment(half.x, half.center.y, half.x + half.width, half.center.y)
                        drawer.lineSegment(half.center.x, half.y, half.center.x, half.y + half.height)
                    }
                    drawer.drawStyle.clip = null
                }
            }
        }
    }
}
