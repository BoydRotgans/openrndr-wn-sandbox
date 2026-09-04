import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.renderTarget
import org.openrndr.draw.WrapMode
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.fx.blur.ApproximateGaussianBlur
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.parameters.Description
import org.openrndr.extra.parameters.BooleanParameter
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.extra.parameters.IntParameter
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import java.io.File
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * objects — one piece of the catalogue at a time, in the round.
 *
 *     ./gradlew run -Popenrndr.application=ObjectsKt
 *
 * `J` and `K` step forward and back through the folder. `Q` and `A` add and drop a column,
 * `W` and `S` a row: it opens on the single piece and grows into an irregular field of
 * itself, every copy the same size. Drag to turn it, scroll to come closer, `0` puts the
 * camera back, space stops the spin.
 *
 * **The grid is a property of the projection, not a layout pass.** Because an isometric view
 * is parallel, drawing the piece somewhere else on the paper at some other size is only a
 * shift and a scale of the orthographic window — no second camera, no model transform, and
 * every cell is necessarily the same piece from the same angle. That is also why the grid is
 * orthographic only: under perspective each cell would want its own frustum and its own
 * vanishing point, so a perspective run shows the single piece.
 *
 * It opens on `WAND_27`, a 5.07 x 2.99 m wall panel with a doorway in it, and both halves
 * of that choice were got wrong first.
 *
 * **Almost nothing in the catalogue fits a 16:9 frame.** These are precast structural
 * components, so they are long: the columns run 19:1 to 25:1, the beams 13:1, `TC-BALK` is
 * 23.8 m by 40 cm. Framed in a wide window any of them is a hairline. That rules out most
 * of the folder before anything else is considered.
 *
 * **And the numbers cannot pick from what is left.** Sorting on face count says the richest
 * piece here is `KUNSTSTOFUITSPARING_3` — 135 faces, 1.88:1, half as deep as it is wide, by
 * every measure the best fit. It renders as a smooth grey disc, because its faces are one
 * round profile finely cut rather than any articulation. This is the same trap CLAUDE.md
 * records for the svg sheets, where a slab is a rectangle and `DRST_M24_1500` is a hairline:
 * complexity in the file is not form on the screen. That is what `OBJECTS_CONTACT=true` is
 * for — it draws all 115 at once, and the eye settles in a second what the sort could not.
 *
 * A wall panel wins on all three counts: 1.7:1 is very nearly the frame, a doorway and a
 * reveal give it something to turn around, and it reads as a building the moment it appears.
 * It is also the piece that proves the loader — that opening is only there because the
 * faces are ear-clipped, and a fan would have filled it solid.
 *
 * **Each side takes its own tone, and every edge is drawn twice.** A face is black or white
 * by which way it points — face order cannot do it, because the order faces appear in a file
 * has nothing to do with which of them meet. `OBJECTS_PALETTE=colour` gives every side a hue
 * a golden angle from the last instead. The edges are a dark line on the edge itself with a
 * lighter one just inside; that pair reads against a face of any tone, where one line alone
 * cannot, since black vanishes into a dark face and white into a pale one.
 *
 * Two things make that work, and both live in [ObjMesh]:
 *
 * - **The colour is baked per face at load.** Once a polygon has become three triangles
 *   there is nothing left to say they were ever one side, so it has to be decided at the one
 *   moment the loader still knows.
 * - **Only real edges are drawn.** Ear clipping fills a face with diagonals that are edges
 *   of nothing, and drawing those would cover every piece in lines that exist only because
 *   of how it was cut up. Each triangle carries a mask saying which of its three edges came
 *   from the original polygon, and the shader ignores the rest. It is measured in pixels off
 *   the barycentric derivative, so the line keeps its width whatever angle the piece is at.
 *
 * Every mesh is normalised to a unit bounding sphere by [loadObjMesh], which is what lets
 * one camera suit the whole folder — otherwise stepping from a 144 m floor slab to a 5 cm
 * anchor plate would want a new distance every time. The cost is that the frame no longer
 * says how big anything is, which is what `OBJECTS_CAPTION=true` is for.
 *
 * Everything is composed at the canvas size and the window only shows that image, so the
 * preview and a still are the same pixels at different sizes — the same arrangement as
 * demo02 and decision, and it is what keeps the render 16:9 whatever the window is.
 *
 * `OBJECTS_DIR` points at `data/unique_objects` for the other 273 shapes: the whole
 * building rather than the concrete in it, ducts and bolts and cable tray included.
 * `OBJECTS_STILL=true` writes the opening object to `screenshots/` and quits.
 */
/**
 * How far a piece reaches up and down the frame, whatever angle it is seen from.
 *
 * Tilting the camera lays part of the plan into the vertical of the frame, so the vertical
 * extent at a pitch of `p` is `halfHeight*cos p + spinRadius*sin p`. Fitting *that* is the
 * tightest frame at any one angle, and it was wrong: the fit then changes as the camera
 * tilts, so the piece rescales under the drag instead of just turning.
 *
 * This is the largest that expression gets over every pitch, which is `hypot` of the two.
 * It costs almost nothing — 5% of the height on a wall panel at the isometric pitch, because
 * that pitch is already near the worst case — and being a property of the mesh rather than
 * of the camera, it holds the piece at one size however it is turned.
 */
private fun reachY(mesh: ObjMesh) = hypot(mesh.halfHeight, mesh.spinRadius)

private const val DEFAULT_STYLE = "poster"
private const val DEFAULT_TOP = "B7CEEE"
private const val DEFAULT_LEFT = "8FB2DF"
private const val DEFAULT_RIGHT = "22406C"
private const val DEFAULT_PAPER_SHADE = "E2E4E8"
private const val DEFAULT_FACE_GRADIENT = 0.10
private const val DEFAULT_SHADOW = "8A93A6"
private const val DEFAULT_SHADOW_STRENGTH = 0.5
private const val DEFAULT_SHADOW_SOFTNESS = 9.0
private const val DEFAULT_SHADOW_SLANT = 0.55
private const val DEFAULT_CONCRETE = "data/concrete/concrete-052v2_crop.jpg"
private const val DEFAULT_CONCRETE_AMOUNT = 1.0
private const val DEFAULT_CONCRETE_SCALE = 0.9
private const val DEFAULT_TEXT = "TEST"
private const val DEFAULT_TEXT_INK = "F4F6FA"
private const val DEFAULT_TEXT_SCALE = 0.5
private const val DEFAULT_TEXT_ROWS = 1
private const val DEFAULT_TEXT_SPEED = 0.05
private const val NO_SHEET = "none"

private const val DEFAULT_DIR = "data/objects"
private const val DEFAULT_START = "WAND_27"
private const val DEFAULT_PAPER = "FFFFFF"
private const val DEFAULT_POSTER_PAPER = "F3F3F4"
private const val DEFAULT_INK = "1D1D1B"
private const val DEFAULT_SPIN = 14.0
private const val DEFAULT_FOV = 40.0
private const val DEFAULT_SHADE = 0.35
private const val DEFAULT_EDGE_DARK = "000000"
private const val DEFAULT_EDGE_LIGHT = "FFFFFF"
private const val DEFAULT_EDGE_WIDTH = 2.0
private const val DEFAULT_EDGE_LIGHT_WIDTH = 2.0
private const val MONO = "mono"
private const val COLOUR = "colour"
private const val DEFAULT_PROJECTION = "iso"
private const val CAPTION_HEIGHT = 104.0
/**
 * Big enough that one repeat still has texture behind it at the top of the size slider. At
 * a size of 16 a piece shows about an eighth of a tile, so 1024 left roughly 128 texels
 * carrying 500 pixels of letterform and the type went soft.
 */
private const val TEXT_TILE = 2048
private const val DEFAULT_TEXT_FONT = "data/fonts/default.otf"
/**
 * How much of the tile's width one repeat of the word is allowed to take.
 *
 * Kept well under 1 because the sum of the glyph advances is not the ink: the last letter
 * paints a little past where its advance says it ends, and at 0.88 the trailing S of "WILLY
 * NAESSENS" was being clipped off at the edge of the tile — and, since the tile is wrapped,
 * lost for good rather than merely cropped.
 */
private const val TEXT_TILE_FILL = 0.78
private val FONT_PATH_SAFE = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-', '/')
private const val DEFAULT_GRID = 1
private const val MAX_GRID = 40
private const val DEFAULT_GRID_MARGIN = 0.80
private const val DEFAULT_GRID_GAP = 0.28

/**
 * How unequal the columns and rows are. A cell's share of the axis is drawn from
 * 1 +/- half of this, so 0.8 runs from 0.6 to 1.4 of the average and 0 makes every cell
 * the same — the difference between a field with a beat to it and graph paper.
 */
private const val DEFAULT_GRID_JITTER = 0.8
private val ISO_PITCH = Math.toDegrees(kotlin.math.atan(1.0 / kotlin.math.sqrt(2.0)))

fun main() = application {
    val canvasWidth = Env["OBJECTS_WIDTH"]?.toDoubleOrNull() ?: 1920.0
    val canvasHeight = Env["OBJECTS_HEIGHT"]?.toDoubleOrNull() ?: 1080.0
    val windowScale = Env["OBJECTS_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.75

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
        title = "objects"
    }

    program {
        val directory = File(Env["OBJECTS_DIR"] ?: DEFAULT_DIR)

        // A colour per side. Left unset the hues step by a golden angle, which spreads them
        // whatever the face count; a list of hex values is cycled through instead. It is
        // decided here rather than in the shader because the colour is baked per face at
        // load, which is the last moment a face is still one thing — see ObjMesh.
        val paletteSetting = Env["OBJECTS_PALETTE"]
        val palette = paletteSetting
            ?.takeUnless { it.equals(MONO, true) || it.equals(COLOUR, true) }
            ?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }
        val faceColor: (Int, Vector3) -> ColorRGBa = when {
            !palette.isNullOrEmpty() -> { i, _ -> palette[i.mod(palette.size)] }
            paletteSetting.equals(COLOUR, true) -> goldenHues
            else -> axisGreys
        }

        val meshes = loadObjMeshes(
            directory, faceColor,
            Env["OBJECTS_CREASE"]?.toDoubleOrNull() ?: DEFAULT_CREASE_DEGREES
        )
        require(meshes.isNotEmpty()) {
            "No .obj files in ${directory.path}. Set OBJECTS_DIR to the folder holding them."
        }

        // Two ways of drawing the same geometry. `line` is the black-and-white technical
        // drawing — flat tones, every real edge inked. `poster` is the soft one: three tones
        // taken from which way a face points, a gradient down each face, and no outlines at
        // all, so the forms are told apart by tone alone.
        val poster = (Env["OBJECTS_STYLE"] ?: DEFAULT_STYLE).equals("poster", true)
        // Nothing else in the sketch needs to know about the style: the poster shader simply
        // never reads the edge uniforms, so no outline is drawn.
        val topFace = ColorRGBa.fromHex(Env["OBJECTS_TOP"] ?: DEFAULT_TOP)
        val leftFace = ColorRGBa.fromHex(Env["OBJECTS_LEFT"] ?: DEFAULT_LEFT)
        val rightFace = ColorRGBa.fromHex(Env["OBJECTS_RIGHT"] ?: DEFAULT_RIGHT)
        val paperShade = ColorRGBa.fromHex(Env["OBJECTS_PAPER_SHADE"] ?: DEFAULT_PAPER_SHADE)
        val faceGradient = Env["OBJECTS_FACE_GRADIENT"]?.toDoubleOrNull() ?: DEFAULT_FACE_GRADIENT
        val shadowColour = ColorRGBa.fromHex(Env["OBJECTS_SHADOW"] ?: DEFAULT_SHADOW)
        val shadowStrength = Env["OBJECTS_SHADOW_STRENGTH"]?.toDoubleOrNull() ?: DEFAULT_SHADOW_STRENGTH
        val shadowSoftness = Env["OBJECTS_SHADOW_SOFTNESS"]?.toDoubleOrNull() ?: DEFAULT_SHADOW_SOFTNESS
        val shadowSlant = Env["OBJECTS_SHADOW_SLANT"]?.toDoubleOrNull() ?: DEFAULT_SHADOW_SLANT

        // Lettering laid over the faces. `none` leaves them bare.
        val text = Env["OBJECTS_TEXT"] ?: DEFAULT_TEXT
        val lettering = text.isNotBlank() && !text.equals(NO_SHEET, true)
        val textInk = ColorRGBa.fromHex(Env["OBJECTS_TEXT_INK"] ?: DEFAULT_TEXT_INK)
        /** How much of the piece one repeat of the word covers. Smaller means bigger type. */
        val textScale = Env["OBJECTS_TEXT_SCALE"]?.toDoubleOrNull() ?: DEFAULT_TEXT_SCALE
        /** Repeats a second the lettering travels across the surface. */
        val textSpeed = Env["OBJECTS_TEXT_SPEED"]?.toDoubleOrNull() ?: DEFAULT_TEXT_SPEED

        // The face for the lettering. `data/fonts` holds one weight of IBM Plex and no bold,
        // so a bold has to come from outside the project — which means the path can be wrong
        // on another machine, and a missing file should not stop the sketch from running.
        val wantedFont = File(Env["OBJECTS_TEXT_FONT"] ?: DEFAULT_TEXT_FONT)
        val textFontPath = when {
            !wantedFont.isFile -> File(DEFAULT_TEXT_FONT).also {
                if (lettering) println("OBJECTS_TEXT_FONT ${wantedFont.path} not found — using ${it.path}")
            }
            // A collection has to have the wanted face lifted out of it first: stb_truetype
            // will not open a .ttc at all, and most of this machine's fonts are collections.
            isFontCollection(wantedFont) -> {
                val face = Env["OBJECTS_TEXT_FONT_FACE"]
                val plain = (face ?: wantedFont.nameWithoutExtension).replace(Regex("[^A-Za-z0-9]"), "-")
                extractFontFace(wantedFont, face, File("build/fonts/$plain.ttf")).also {
                    if (lettering) println("font: ${wantedFont.name} → ${face ?: fontCollectionFaces(wantedFont).first()}")
                }
            }
            // `loadFont` parses its argument as a URI, which rejects a space — and nearly
            // every font on this machine lives at a path with one in it ("DIN Alternate
            // Bold.ttf"). Copying it somewhere plainer is cheaper than giving up the font.
            wantedFont.path.any { it !in FONT_PATH_SAFE } -> {
                val cached = File("build/fonts/${wantedFont.name.replace(Regex("[^A-Za-z0-9.]"), "-")}")
                cached.parentFile?.mkdirs()
                if (!cached.isFile || cached.lastModified() < wantedFont.lastModified()) {
                    wantedFont.copyTo(cached, overwrite = true)
                }
                cached
            }
            else -> wantedFont
        }

        val paper = ColorRGBa.fromHex(
            Env["OBJECTS_PAPER"] ?: if (poster) DEFAULT_POSTER_PAPER else DEFAULT_PAPER
        )
        val ink = ColorRGBa.fromHex(Env["OBJECTS_INK"] ?: DEFAULT_INK)

        /** Degrees a second the piece turns on its own, so it reads as solid without input. */
        val spin = Env["OBJECTS_SPIN"]?.toDoubleOrNull() ?: DEFAULT_SPIN
        val fov = Env["OBJECTS_FOV"]?.toDoubleOrNull() ?: DEFAULT_FOV
        val still = Env.boolean("OBJECTS_STILL")

        // The name, size and count under the piece. Off, so what is on the paper is the
        // drawing and nothing else; `OBJECTS_CAPTION=true` puts it back, which is worth
        // having when stepping through a folder of 115 and wanting to know which one this is.
        val caption = Env.boolean("OBJECTS_CAPTION")
        // With no caption the drawing has the whole frame; with one it stops above it.
        val skirt = if (caption) CAPTION_HEIGHT / canvasHeight else 0.0

        // Isometric by default. A perspective view is one env key away, and is the better
        // one for judging a piece as an object rather than as a drawing.
        val isometric = !(Env["OBJECTS_PROJECTION"] ?: DEFAULT_PROJECTION).equals("perspective", true)

        // How much the light is allowed to vary a face's colour. The colours do the work of
        // telling the sides apart, so this stays low — 0 is wholly flat, like the reference.
        val shade = Env["OBJECTS_SHADE"]?.toDoubleOrNull() ?: DEFAULT_SHADE

        // Every real edge is drawn twice: a dark line on the edge itself and a lighter one
        // just inside it. That pair reads against a face of any colour, which a single line
        // cannot — black disappears into a dark face and white into a pale one.
        val edgeDark = ColorRGBa.fromHex(Env["OBJECTS_EDGE_DARK"] ?: DEFAULT_EDGE_DARK)
        val edgeLight = ColorRGBa.fromHex(Env["OBJECTS_EDGE_LIGHT"] ?: DEFAULT_EDGE_LIGHT)
        val edgeWidth = Env["OBJECTS_EDGE_WIDTH"]?.toDoubleOrNull() ?: DEFAULT_EDGE_WIDTH
        val edgeLightWidth = Env["OBJECTS_EDGE_LIGHT_WIDTH"]?.toDoubleOrNull() ?: DEFAULT_EDGE_LIGHT_WIDTH

        // The piece repeated across a grid, every copy the same size and the same distance
        // from its neighbours in both directions. The pieces
        // are all one size whatever the cells do — it is where they stand that varies, which
        // is why the gaps between them are uneven. OBJECTS_GRID_JITTER=0 evens them out.
        var gridX = (Env["OBJECTS_GRID_X"]?.toIntOrNull() ?: DEFAULT_GRID).coerceIn(1, MAX_GRID)
        var gridY = (Env["OBJECTS_GRID_Y"]?.toIntOrNull() ?: DEFAULT_GRID).coerceIn(1, MAX_GRID)
        val gridMargin = Env["OBJECTS_GRID_MARGIN"]?.toDoubleOrNull() ?: DEFAULT_GRID_MARGIN
        /** The space between neighbours, as a fraction of the piece's own larger dimension. */
        val gridGap = Env["OBJECTS_GRID_GAP"]?.toDoubleOrNull() ?: DEFAULT_GRID_GAP
        val gridJitter = (Env["OBJECTS_GRID_JITTER"]?.toDoubleOrNull() ?: DEFAULT_GRID_JITTER)
            .coerceIn(0.0, 1.8)

        // A contact sheet of the whole folder in one image. Worth having as a mode rather
        // than as a script, because picking a piece off the numbers does not work: face
        // count says `KUNSTSTOFUITSPARING_3` is the richest thing in the catalogue and it
        // renders as a smooth disc, because its faces are all one round profile finely cut.
        // The only reliable way to tell an articulated part from a tessellated blob is to
        // look at them, so this draws all 115 at once and lets the eye do it.
        val contact = Env.boolean("OBJECTS_CONTACT")
        val contactColumns = Env["OBJECTS_CONTACT_COLUMNS"]?.toIntOrNull() ?: 12

        // Named rather than indexed, so it survives a folder change.
        val start = Env["OBJECTS_START"] ?: DEFAULT_START
        var index = meshes.indexOfFirst { it.name.equals(start, true) }.coerceAtLeast(0)

        // The space left between one piece and the next, across and down, measured against
        // the piece itself rather than the frame so it means the same thing whatever is being
        // shown. Held apart on the two axes here: one shared gap is what makes the spacing
        // read as even, and these let that be broken on purpose. Below zero the pieces
        // overlap, which is why the fit guards its denominator.
        val spacing = @Description("Spacing") object {
            @DoubleParameter("x gap", -0.4, 2.0, order = 0)
            var gapX = Env["OBJECTS_GAP_X"]?.toDoubleOrNull() ?: gridGap

            @DoubleParameter("y gap", -0.4, 2.0, order = 1)
            var gapY = Env["OBJECTS_GAP_Y"]?.toDoubleOrNull() ?: gridGap
        }

        // How much of the piece one repeat of the word covers, so larger is larger type.
        // It reaches the shader as a uniform rather than being baked into the tile, which is
        // what lets it move on a slider: the tile is drawn once and only ever sampled at a
        // different rate. Nothing is re-rendered when this changes.
        val concrete = @Description("Concrete") object {
            @DoubleParameter("grain", 0.0, 3.0, order = 0)
            var amount = Env["OBJECTS_CONCRETE_AMOUNT"]?.toDoubleOrNull() ?: DEFAULT_CONCRETE_AMOUNT

            @DoubleParameter("scale", 0.05, 4.0, order = 1)
            var scale = Env["OBJECTS_CONCRETE_SCALE"]?.toDoubleOrNull() ?: DEFAULT_CONCRETE_SCALE
        }

        val type = @Description("Type") object {
            @DoubleParameter("size", 0.15, 16.0, order = 0)
            var size = textScale

            /**
             * Off, every copy carries the same word in the same place, so the field reads as
             * one piece repeated. On, each copy is given the lettering that belongs to where
             * it stands: the uv is shifted by the cell's own position, so all of them are
             * windows onto a single text plane lying behind the whole field. Zoom out and
             * the words run on across the gaps as one line rather than restarting in every
             * piece.
             */
            @IntParameter("rows", 1, 10, order = 1)
            var rows = Env["OBJECTS_TEXT_ROWS"]?.toIntOrNull() ?: DEFAULT_TEXT_ROWS

            @BooleanParameter("continuous", order = 2)
            var continuous = Env.boolean("OBJECTS_TEXT_CONTINUOUS", true)
        }

        val font = loadFont("data/fonts/default.otf", 15.0)

        val canvas = renderTarget(canvasWidth.toInt(), canvasHeight.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        // Records whatever happens in the window — stepping with J K, growing the grid with
        // Q A W S, turning it by hand — so the clip is the session rather than a set piece.
        if (Env.boolean("OBJECTS_RECORD")) {
            extend(ScreenRecorder().apply {
                outputFile = Env["OBJECTS_VIDEO"] ?: "video/objects.mp4"
                frameRate = Env["OBJECTS_FPS"]?.toIntOrNull() ?: 60
                // the window shows the canvas shrunk, so the clip is captured at the
                // canvas's own size rather than the window's — same trick as demo02
                contentScale = 1.0 / windowScale
                Env["OBJECTS_DURATION"]?.toDoubleOrNull()?.let { maximumDuration = it }
            })
        }

        // How far back the camera has to stand for a given piece to fill the frame.
        //
        // Fitting the bounding sphere is the easy version and it wastes the frame, because
        // nothing in this catalogue is ball-shaped: a wall panel fitted by its sphere sits
        // in about 60% of the height. So each piece is fitted by what it actually presents
        // instead — its reach around the spin axis and its half height — and both the
        // horizontal and the vertical field have to clear it, whichever binds. Neither of
        // those two numbers changes as the piece turns, so the fit holds all the way round
        // and the object does not breathe while it spins.
        val tanY = tan(Math.toRadians(fov / 2.0))
        val tanX = tanY * canvasWidth / canvasHeight
        fun fitDistance(mesh: ObjMesh): Double =
            maxOf(reachY(mesh) / tanY, mesh.spinRadius / tanX) * 1.12

        // True isometric: the three axes equally foreshortened, which is a 45 degree turn
        // and an elevation of atan(1/sqrt 2). Anything else is some other axonometric.
        val homeYaw = Env["OBJECTS_YAW"]?.toDoubleOrNull() ?: if (isometric) 45.0 else -28.0
        val homePitch = Env["OBJECTS_PITCH"]?.toDoubleOrNull() ?: if (isometric) ISO_PITCH else 22.0

        var yaw = homeYaw
        var pitch = homePitch
        /** Scroll multiplies the fitted distance rather than replacing it, so it survives a step. */
        var zoom = 1.0
        var spinning = true
        var spun = 0.0
        var last = 0.0
        var frames = 0
        var drawnRows = Env["OBJECTS_TEXT_ROWS"]?.toIntOrNull() ?: DEFAULT_TEXT_ROWS

        mouse.dragged.listen {
            yaw += it.dragDisplacement.x * 0.4
            pitch = (pitch - it.dragDisplacement.y * 0.4).coerceIn(-89.0, 89.0)
        }
        mouse.scrolled.listen {
            zoom = (zoom * (1.0 - it.rotation.y * 0.08)).coerceIn(0.15, 6.0)
        }
        keyboard.keyDown.listen {
            when (it.name) {
                "j" -> index = (index + 1).mod(meshes.size)
                "k" -> index = (index - 1).mod(meshes.size)
                "0" -> {
                    zoom = 1.0; yaw = homeYaw; pitch = homePitch
                }
            }
            when (it.name) {
                "q" -> gridX = (gridX + 1).coerceAtMost(MAX_GRID)
                "a" -> gridX = (gridX - 1).coerceAtLeast(1)
                "w" -> gridY = (gridY + 1).coerceAtMost(MAX_GRID)
                "s" -> gridY = (gridY - 1).coerceAtLeast(1)
            }
            if (it.key == KEY_SPACEBAR) spinning = !spinning
        }

        // The ground: off-white, lifted where the piece stands and falling away to the
        // corners. Flat paper reads as empty; this reads as a room.
        val groundShade = shadeStyle {
            fragmentTransform = """
                float d = distance(c_boundsPosition.xy, vec2(0.44, 0.34));
                x_fill.rgb = mix(p_near.rgb, p_far.rgb, smoothstep(0.0, 0.95, d));
                // A ramp this shallow crosses far fewer than 256 values across the frame, so
                // it quantises into rings. Half a level of noise scatters the boundaries and
                // they go away; it is well under what the eye can see as grain.
                float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                x_fill.rgb += (grain - 0.5) / 255.0;
                x_fill.a = 1.0;
            """.trimIndent()
            parameter("near", paper)
            parameter("far", paperShade)
        }

        // The concrete itself. `none` leaves the faces flat.
        //
        // The map is used as *grain*, not as colour: it is taken relative to its own mean
        // brightness and used to modulate the face tone, so the three tones that tell the
        // sides apart survive and the piece does not simply turn the colour of the photo.
        // The two maps average 0.59 and 0.81, so the mean has to be measured rather than
        // assumed — divide by the wrong one and every face shifts light or dark.
        val concreteSetting = Env["OBJECTS_CONCRETE"] ?: DEFAULT_CONCRETE
        val concreteMap = concreteSetting
            .takeUnless { it.isBlank() || it.equals(NO_SHEET, true) }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { loadImage(it) }
        concreteMap?.let {
            it.wrapU = WrapMode.REPEAT
            it.wrapV = WrapMode.REPEAT
            it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            it.filterMag = MagnifyingFilter.LINEAR
            it.generateMipmaps()
        }

        // Its own average brightness, read off the pixels rather than guessed at. The two
        // maps in `data/concrete` sit at 0.59 and 0.81, so a hardcoded middle would shift
        // every face light or dark the moment the other one was chosen.
        val concreteMid = concreteMap?.let { map ->
            val shadow = map.shadow
            shadow.download()
            val step = maxOf(1, map.width / 96)
            var total = 0.0
            var taken = 0
            for (y in 0 until map.height step step) {
                for (x in 0 until map.width step step) {
                    total += shadow[x, y].g
                    taken++
                }
            }
            shadow.destroy()
            if (taken > 0) total / taken else 0.6
        } ?: 0.6

        // The word, drawn into a square tile that is then wrapped over the surfaces. The
        // meshes carry no texture coordinates — these are structural components, not models
        // made for rendering — so there is nothing to unwrap; the shader takes a face's two
        // in-plane object coordinates as its uv instead. Which means the lettering runs
        // continuously across every face of a piece rather than being fitted to each one,
        // and steps round a corner the way a decal would.
        //
        // Rows are drawn *into the tile* rather than got by shrinking the type: one line per
        // tile and a smaller scale would space the lines by the tile, so they would only ever
        // be as far apart as they are wide. Setting them here lets the leading be its own
        // thing, and lets `|` put different words on different lines.
        val letterTile = renderTarget(TEXT_TILE, TEXT_TILE) { colorBuffer() }

        fun drawLetterTile(rows: Int) {
            val lines = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf(text) }
            val nominal = TEXT_TILE / (4.0 * rows)
            val probe = loadFont(textFontPath.path, nominal)
            // The word is set to the tile rather than the tile to the word, so the type stays
            // the same size on the surface whatever is typed. Measured against the longest
            // line, so a short line and a long one keep one common size.
            val widest = lines.maxOf { line -> line.sumOf { probe.characterWidth(it) } }
            val fitted = if (widest > TEXT_TILE * TEXT_TILE_FILL) {
                nominal * (TEXT_TILE * TEXT_TILE_FILL / widest)
            } else nominal
            val tileFont = if (fitted == nominal) probe else loadFont(textFontPath.path, fitted)

            drawer.isolatedWithTarget(letterTile) {
                drawer.defaults()
                drawer.clear(ColorRGBa.TRANSPARENT)
                drawer.fontMap = tileFont
                drawer.fill = ColorRGBa.WHITE
                for (r in 0 until rows) {
                    val line = lines[r % lines.size]
                    val w = line.sumOf { tileFont.characterWidth(it) }
                    drawer.text(
                        line,
                        (TEXT_TILE - w) / 2.0,
                        TEXT_TILE * (r + 0.58) / rows
                    )
                }
            }
            letterTile.colorBuffer(0).generateMipmaps()
        }

        letterTile.colorBuffer(0).wrapU = WrapMode.REPEAT
        letterTile.colorBuffer(0).wrapV = WrapMode.REPEAT
        letterTile.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        letterTile.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
        if (lettering) drawLetterTile(Env["OBJECTS_TEXT_ROWS"]?.toIntOrNull() ?: DEFAULT_TEXT_ROWS)

        // The shadow is the piece itself, flattened onto the floor it stands on and blurred.
        // It is drawn into its own buffer rather than into the scene, because a blur needs a
        // finished image to work on and because the flattened copy overlaps itself — laid
        // straight into the frame with alpha it would darken where it doubled back.
        val shadowTarget = renderTarget(canvasWidth.toInt(), canvasHeight.toInt()) { colorBuffer() }
        val shadowBlurred = colorBuffer(canvasWidth.toInt(), canvasHeight.toInt())
        val shadowBlur = ApproximateGaussianBlur().apply {
            window = 25
            sigma = shadowSoftness
        }
        val flatShade = shadeStyle {
            fragmentTransform = "x_fill = vec4(p_tint.rgb, 1.0);"
            parameter("tint", shadowColour)
        }

        val meshShade = if (poster) shadeStyle {
            // The object-space normal and height, which is what the tone and the gradient
            // are taken from. Both have to be carried across from the vertex shader: the
            // view normal would swing the tones around as the piece turns, and the whole
            // point is that a face keeps its tone whatever angle it is seen from.
            vertexPreamble = """
                out vec3 vObjNormal;
                out vec3 vObjPosition;
            """.trimIndent()
            vertexTransform = """
                vObjNormal = va_normal;
                vObjPosition = va_position;
            """.trimIndent()
            fragmentPreamble = """
                in vec3 vObjNormal;
                in vec3 vObjPosition;
            """.trimIndent()
            fragmentTransform = """
                vec3 n = normalize(vObjNormal);
                float ax = abs(n.x), ay = abs(n.y), az = abs(n.z);

                // Three tones, one per axis, so the three faces meeting at any corner are
                // always three different values and the form reads with no line on it.
                vec3 base = (ay >= ax && ay >= az) ? p_top.rgb
                          : (ax >= az)             ? p_right.rgb
                                                   : p_left.rgb;

                // A gradient down the piece rather than across each face on its own, so the
                // whole object is lit as one thing and faces of a tone stay a family.
                float g = clamp(vObjPosition.y * 0.5 + 0.5, 0.0, 1.0);
                base *= mix(1.0 - p_gradient, 1.0 + p_gradient, g);

                // The two object axes that lie *in* this face, picked by the axis it points
                // along. There are no texture coordinates on these meshes, so the surface's
                // own coordinates stand in for them — which is what makes anything laid on
                // them carry across a corner instead of restarting on each face. Which way
                // round the two axes go depends on which side of the face is being looked
                // at: taken raw they put the word on back to front wherever the normal
                // points down a negative axis. Flipping u by the sign of the normal is the
                // rule a cube map uses.
                vec3 p = vObjPosition;
                vec2 plane = (ay >= ax && ay >= az) ? vec2(p.x, n.y > 0.0 ? -p.z : p.z)
                           : (ax >= az)             ? vec2(n.x > 0.0 ? -p.z : p.z, p.y)
                                                    : vec2(n.z > 0.0 ? p.x : -p.x, p.y);

                if (p_concreting > 0.5) {
                    // Grain, not colour. Taken against the map's own mean brightness so it
                    // varies the face tone rather than replacing it — the three tones that
                    // tell the sides apart still do their job, and the piece does not simply
                    // become the colour of the photograph.
                    float g = texture(p_concrete, plane / p_concreteScale).g;
                    base *= 1.0 + (g - p_concreteMid) * p_concreteAmount;
                }

                if (p_lettering > 0.5) {
                    // Centred on the tile, not on its corner. The piece straddles zero, so
                    // without this the uv range sits across the tile's edges — where there
                    // is no word, the word being in the middle — and at a scale of about one
                    // repeat per piece the lettering disappears altogether.
                    vec2 uv = plane / p_textScale + 0.5 + p_cellOffset;
                    uv.x += p_time * p_textSpeed;
                    base = mix(base, p_textInk.rgb, texture(p_letters, uv).a);
                }

                float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                x_fill.rgb = base + (grain - 0.5) / 255.0;
                x_fill.a = 1.0;
            """.trimIndent()
            parameter("top", topFace)
            parameter("left", leftFace)
            parameter("right", rightFace)
            parameter("gradient", faceGradient)
            parameter("letters", letterTile.colorBuffer(0))
            parameter("lettering", if (lettering) 1.0 else 0.0)
            parameter("textInk", textInk)
            parameter("textScale", textScale)
            parameter("textSpeed", textSpeed)
            parameter("time", 0.0)
            parameter("cellOffset", Vector2.ZERO)
            parameter("concreting", if (concreteMap != null) 1.0 else 0.0)
            parameter("concrete", concreteMap ?: letterTile.colorBuffer(0))
            parameter("concreteMid", concreteMid)
            parameter("concreteAmount", concrete.amount)
            parameter("concreteScale", concrete.scale)
        } else shadeStyle {

            vertexPreamble = """
                out vec3 vFaceColor;
                out vec3 vBary;
                out vec3 vEdges;
            """.trimIndent()
            vertexTransform = """
                vFaceColor = va_faceColor;
                vBary = va_bary;
                vEdges = va_edges;
            """.trimIndent()
            fragmentPreamble = """
                in vec3 vFaceColor;
                in vec3 vBary;
                in vec3 vEdges;
            """.trimIndent()
            fragmentTransform = """
                vec3 n = normalize(v_viewNormal);
                if (!gl_FrontFacing) n = -n;
                // Shading is deliberately weak. The colours are what tell the sides apart,
                // so a gradient across them only muddies it; this is just enough to stop
                // two faces of the same colour from merging where they meet.
                float key = clamp(dot(n, normalize(vec3(0.35, 0.62, 0.70))), 0.0, 1.0);
                vec3 face = vFaceColor * mix(1.0, 0.62 + 0.38 * key, p_shade);

                // How far this pixel is from the nearest real edge, measured in pixels. A
                // barycentric coordinate falls to zero along the edge opposite its corner,
                // so dividing by its screen-space derivative turns it into a distance that
                // is the same width everywhere regardless of how the piece is turned.
                vec3 w = max(fwidth(vBary), vec3(1e-6));
                vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
                float d = min(min(sel.x, sel.y), sel.z);

                float dark  = 1.0 - smoothstep(p_edgeWidth - 0.7, p_edgeWidth + 0.7, d);
                float light = 1.0 - smoothstep(
                    p_edgeWidth + p_edgeLightWidth - 0.7,
                    p_edgeWidth + p_edgeLightWidth + 0.7, d);

                if (p_lettering > 0.5) {
                    vec3 m = normalize(vObjNormal);
                    float mx = abs(m.x), my = abs(m.y), mz = abs(m.z);
                    vec3 q = vObjPosition;
                    vec2 uv = (my >= mx && my >= mz) ? vec2(q.x, m.y > 0.0 ? -q.z : q.z)
                            : (mx >= mz)             ? vec2(m.x > 0.0 ? -q.z : q.z, q.y)
                                                     : vec2(m.z > 0.0 ? q.x : -q.x, q.y);
                    // Centred on the tile, not on its corner. The piece straddles zero, so
                    // without this the uv range sits across the tile's edges — where there
                    // is no word, the word being in the middle — and at a scale of about one
                    // repeat per piece the lettering disappears altogether.
                    uv = uv / p_textScale + 0.5 + p_cellOffset;
                    uv.x += p_time * p_textSpeed;
                    face = mix(face, p_textInk.rgb, texture(p_letters, uv).a);
                }

                vec3 c = mix(face, p_edgeLight.rgb, clamp(light - dark, 0.0, 1.0));
                c = mix(c, p_edgeDark.rgb, dark);
                x_fill.rgb = c;
                x_fill.a = 1.0;
            """.trimIndent()
            parameter("shade", shade)
            parameter("edgeDark", edgeDark)
            parameter("edgeLight", edgeLight)
            parameter("edgeWidth", edgeWidth)
            parameter("edgeLightWidth", edgeLightWidth)
            parameter("letters", letterTile.colorBuffer(0))
            parameter("lettering", if (lettering) 1.0 else 0.0)
            parameter("textInk", textInk)
            parameter("textScale", textScale)
            parameter("textSpeed", textSpeed)
            parameter("time", 0.0)
            parameter("cellOffset", Vector2.ZERO)
        }

        fun eyeAt(yawDegrees: Double, pitchDegrees: Double, d: Double): Vector3 {
            val a = Math.toRadians(yawDegrees)
            val e = Math.toRadians(pitchDegrees)
            return Vector3(cos(e) * sin(a), sin(e), cos(e) * cos(a)) * d
        }

        /**
         * Half the width and half the height the piece actually covers, seen from this angle.
         *
         * This is measured rather than bounded: [ObjMesh.spinRadius] and [reachY] are the
         * worst case over a whole turn, and the slack between a bound and the truth is what
         * made the gaps in the grid uneven — 145 across against 167 down on a wall panel,
         * because the vertical bound had the most slack in it. Projecting the corners onto
         * the view's own axes gives the two numbers the layout actually wants.
         */
        fun shownHalfExtent(mesh: ObjMesh, yawDegrees: Double, pitchDegrees: Double): Pair<Double, Double> {
            val forward = -eyeAt(yawDegrees, pitchDegrees, 1.0).normalized
            val right = forward.cross(Vector3.UNIT_Y).normalized
            val up = right.cross(forward).normalized
            return mesh.points.maxOf { abs(it.dot(right)) } to mesh.points.maxOf { abs(it.dot(up)) }
        }


        /** The piece itself, filling whatever target is current. */
        /**
         * Lays the piece flat on the floor it stands on, leaning it over as it goes, which is
         * what an object's shadow is under a light that is off to one side. A parallel light
         * on a parallel camera makes this a single shear — there is no projection to do.
         */
        fun flattenToFloor(mesh: ObjMesh): Matrix44 {
            val floor = -mesh.halfHeight
            val k = -shadowSlant
            return Matrix44(
                1.0, k, 0.0, -k * floor,
                0.0, 0.0, 0.0, floor,
                0.0, k, 1.0, -k * floor,
                0.0, 0.0, 0.0, 1.0
            )
        }

        fun drawMesh(
            mesh: ObjMesh, yawDegrees: Double, pitchDegrees: Double,
            aspect: Double, zoom: Double, asShadow: Boolean = false
        ) {
            // The shadow buffer carries no depth, and it does not need one: the flattened
            // copy is all on one plane, so anything it covers twice is the same colour.
            drawer.depthWrite = !asShadow
            drawer.depthTestPass = if (asShadow) DepthTestPass.ALWAYS else DepthTestPass.LESS_OR_EQUAL
            drawer.model = if (asShadow) flattenToFloor(mesh) else Matrix44.IDENTITY
            if (isometric) {
                // Isometric means parallel, not merely a camera angle: the projection has to
                // be orthographic or the far end of a beam still tapers. With no vanishing
                // point the camera's distance stops mattering, so it only has to stand clear
                // of the piece, and the frame is sized instead of the camera moved.
                drawer.lookAt(eyeAt(yawDegrees, pitchDegrees, 10.0), Vector3.ZERO, Vector3.UNIT_Y)
                drawer.shadeStyle = if (asShadow) flatShade else meshShade

                // One cell is one orthographic window. Because the projection is parallel,
                // putting the piece somewhere else on the paper at some other size is only a
                // shift and a scale of that window — no second camera and no model transform,
                // which is why the whole grid is the same piece drawn from the same angle.
                // Measured at the home angle rather than bounded over every angle, so the
                // space left between neighbours is the space that is actually seen. Being
                // taken at the *home* angle it is still a constant, so the layout does not
                // shift when the camera is dragged.
                val (reachX, reachY) = shownHalfExtent(mesh, homeYaw, homePitch)
                val biggest = maxOf(reachX, reachY)

                val halfX = aspect
                val halfY = 1.0 - skirt

                // **One gap, used in both directions.** Unequal cells were tried first and
                // are wrong for this: with every copy drawn at one size, a grid of unequal
                // cells necessarily leaves unequal space between the pieces, and what the
                // eye reads in a field like this is the space, not the cell.
                //
                // So the gap is the quantity that is held fixed — measured against the piece
                // rather than the frame, so it stays the same gap whatever is being shown —
                // and the size is whatever then fits. A piece that is wide and short still
                // sits on a wide, short pitch; the gap between neighbours is the same number
                // across and down.
                val spanX = (gridX * reachX + (gridX - 1) * biggest * spacing.gapX).coerceAtLeast(1e-3)
                val spanY = (gridY * reachY + (gridY - 1) * biggest * spacing.gapY).coerceAtLeast(1e-3)
                val fit = minOf(halfX / spanX, halfY / spanY) * gridMargin / zoom

                val stepX = 2.0 * reachX * fit + 2.0 * biggest * spacing.gapX * fit
                val stepY = 2.0 * reachY * fit + 2.0 * biggest * spacing.gapY * fit

                for (i in 0 until gridX) {
                    val cx = (i - (gridX - 1) / 2.0) * stepX
                    for (j in 0 until gridY) {
                        val cy = skirt + (j - (gridY - 1) / 2.0) * stepY
                        if (lettering) {
                            // View units back to object units through `fit`, then to repeats
                            // through the type size — the same two steps the shader took to
                            // get from a surface coordinate to a uv, run the other way.
                            meshShade.parameter(
                                "cellOffset",
                                if (type.continuous) Vector2(cx, cy) / (fit * type.size)
                                else Vector2.ZERO
                            )
                        }
                        drawer.ortho(
                            -cx / fit - aspect / fit, -cx / fit + aspect / fit,
                            -cy / fit - 1.0 / fit, -cy / fit + 1.0 / fit,
                            0.01, 100.0
                        )
                        drawer.vertexBuffer(mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
                    }
                }
                return
            }
            run {
                // The grid is an orthographic trick and does not carry over: under a
                // perspective camera every cell would need its own frustum and its own
                // vanishing point. A perspective run therefore shows the single piece.
                drawer.perspective(fov, aspect, 0.01, 100.0)
                drawer.lookAt(
                    eyeAt(yawDegrees, pitchDegrees, fitDistance(mesh) * zoom),
                    Vector3.ZERO, Vector3.UNIT_Y
                )
                drawer.shadeStyle = if (asShadow) flatShade else meshShade
                drawer.vertexBuffer(mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
            }
        }

        val mm = { v: Double -> if (v < 1.0) "${(v * 1000).roundToInt()} mm" else "%.2f m".format(v) }

        // The whole folder as one image: each piece drawn into a small cell, at the same
        // three-quarter angle so they can be compared, with its name under it.
        if (contact) {
            val columns = contactColumns
            val rows = (meshes.size + columns - 1) / columns
            val cellWidth = 320.0
            val cellHeight = 200.0
            val cell = renderTarget(cellWidth.toInt(), (cellWidth * 9 / 16).toInt()) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            val sheet = renderTarget((columns * cellWidth).toInt(), (rows * cellHeight).toInt()) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            drawer.isolatedWithTarget(sheet) {
                drawer.defaults()
                drawer.clear(paper)
            }
            for ((i, mesh) in meshes.withIndex()) {
                drawer.isolatedWithTarget(cell) {
                    drawer.clear(paper)
                    val (wasX, wasY) = gridX to gridY
                    gridX = 1; gridY = 1
                    drawMesh(mesh, homeYaw, homePitch, 16.0 / 9.0, 1.0)
                    gridX = wasX; gridY = wasY
                }
                val x = (i % columns) * cellWidth
                val y = (i / columns) * cellHeight
                drawer.isolatedWithTarget(sheet) {
                    drawer.defaults()
                    drawer.image(cell.colorBuffer(0), x, y, cellWidth, cellWidth * 9 / 16)
                    drawer.fontMap = font
                    drawer.fill = ink
                    drawer.text(mesh.name.take(30), x + 10.0, y + cellWidth * 9 / 16 + 20.0)
                }
            }
            val out = File("screenshots/objects-contact-${directory.name}.png")
            out.parentFile?.mkdirs()
            sheet.colorBuffer(0).saveToFile(out)
            println("wrote ${out.path}  (${meshes.size} pieces, ${columns}x$rows)")
            application.exit()
        }

        extend {
            val mesh = meshes[index]

            // Time is only ever read in here, never in a handler — see the ScreenRecorder
            // note in CLAUDE.md. The spin is accumulated rather than read off `seconds`
            // outright, so pausing it does not make it jump.
            val dt = (seconds - last).coerceIn(0.0, 0.1)
            last = seconds
            if (spinning && !still) spun += dt * spin
            // The clock reaches the shader from here and nowhere else — see the
            // ScreenRecorder note in CLAUDE.md, which is why `seconds` is read in the draw
            // loop and never in a handler.
            if (lettering) {
                meshShade.parameter("time", seconds)
                meshShade.parameter("textScale", type.size)
                // Rows live in the tile, so changing them means drawing it again — cheap, and
                // only when the number actually moves.
                if (type.rows != drawnRows) {
                    drawLetterTile(type.rows)
                    drawnRows = type.rows
                }
            }
            if (concreteMap != null) {
                meshShade.parameter("concreteAmount", concrete.amount)
                meshShade.parameter("concreteScale", concrete.scale)
            }

            if (poster) {
                drawer.isolatedWithTarget(shadowTarget) {
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    drawMesh(mesh, yaw + spun, pitch, canvasWidth / canvasHeight, zoom, asShadow = true)
                }
                shadowBlur.apply(shadowTarget.colorBuffer(0), shadowBlurred)
            }

            drawer.isolatedWithTarget(canvas) {
                drawer.clear(paper)
                if (poster) {
                    drawer.defaults()
                    drawer.depthWrite = false // the ground is behind everything, not in it
                    drawer.shadeStyle = groundShade
                    drawer.stroke = null
                    drawer.rectangle(0.0, 0.0, canvasWidth, canvasHeight)

                    drawer.shadeStyle = shadeStyle {
                        fragmentTransform = "x_fill.a *= p_strength;"
                        parameter("strength", shadowStrength)
                    }
                    drawer.image(shadowBlurred, 0.0, 0.0, canvasWidth, canvasHeight)
                    drawer.shadeStyle = null
                }
                drawMesh(mesh, yaw + spun, pitch, canvasWidth / canvasHeight, zoom)

                if (caption) {
                    drawer.defaults()
                    // A band of paper under the type. The grid runs to the frame edge, so
                    // without this it would land on top of the bottom row of pieces.
                    drawer.fill = paper
                    drawer.stroke = null
                    drawer.rectangle(0.0, canvasHeight - CAPTION_HEIGHT, canvasWidth, CAPTION_HEIGHT)
                    drawer.fontMap = font
                    drawer.fill = ink
                    drawer.text(mesh.name, 40.0, canvasHeight - 84.0)
                    drawer.fill = ink.opacify(0.55)
                    // Reported in the file's own axis order: the loader turns the geometry Y
                    // up, this turns the numbers back so they read as the register has them.
                    drawer.text(
                        listOfNotNull(
                            mesh.ifcClass?.removePrefix("Ifc"),
                            "${mm(mesh.size.x)} x ${mm(mesh.size.z)} x ${mm(mesh.size.y)}",
                            "${mesh.triangles} triangles"
                        ).joinToString("   ·   "), 40.0, canvasHeight - 60.0
                    )
                    drawer.text(
                        "${index + 1} / ${meshes.size}   ·   ${directory.path}   ·   " +
                            "J K to step   ·   ${gridX} x ${gridY}   Q A W S",
                        40.0, canvasHeight - 36.0
                    )
                }
            }

            canvas.colorBuffer(0).generateMipmaps()
            drawer.image(canvas.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())

            frames++
            if (still && frames >= 3) {
                val out = File("screenshots/objects-${mesh.name}.png")
                out.parentFile?.mkdirs()
                canvas.colorBuffer(0).saveToFile(out)
                println("wrote ${out.path}")
                application.exit()
            }
        }

        // Added after the drawing extension so the panel sits over the scene rather than
        // under it. It stays up while recording, because being able to dial the piece in on
        // camera is worth more than a permanently clean frame — but ScreenRecorder captures
        // exactly what the window shows, so `G` hides it when a clean take is wanted.
        if (!still && !contact) {
            val gui = GUI()
            extend(gui) {
                add(spacing, "Spacing")
                add(type, "Type")
                add(concrete, "Concrete")
            }
            keyboard.keyDown.listen { if (it.name == "g") gui.visible = !gui.visible }
        }
    }
}
