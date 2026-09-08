package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import java.io.File
import kotlin.math.min

/**
 * The ESG assessment framework as three interlocking pieces.
 *
 * Environmental across the top, Social and Governance under it, each arriving on its own
 * click — and then, on the last click, the three closing together so their teeth mesh. The
 * picture is the argument: three pillars that are only a framework once they fit.
 *
 * **The pieces are drawings, not code.** `data/svg/3-shapes` holds one svg a piece and the
 * whole of the form is in them; nothing here knows what a pillar looks like. Same split as
 * `Decision`, where both keyframes are drawings and the sketch works out the rest.
 *
 * **Where they sit when closed is read off their own teeth**, rather than tuned by eye. The
 * three files were cut from one layout, so they share a scale — one unit is one unit in all
 * three — and the joints state themselves in the path data:
 *
 * - Social's right edge runs out to 535.4 and is cut back to 502.0 in four places; Governance's
 *   body starts at x=33 with four tabs reaching to x=0. So a tab is 33 deep and a socket 33.5,
 *   and closed, Governance's tab tips sit on Social's socket floor: **Governance stands 501.0
 *   right of Social.** Social's 534.4 plus that offset is 1066.0 against the bar's 1067.6 —
 *   the two agree to a pixel and a half, which is what says the reading is right rather than
 *   plausible.
 * - The bar's underside is flat at y=250.0 with two tabs hanging to 274–276; Social's top is
 *   cut 27.1 deep and Governance's 30. So the bar's *body* rests on their tops: **the lower
 *   pieces sit 250.0 below the bar**, and the tabs sink into the sockets with a pixel to spare.
 *
 * Those three numbers are the only geometry stated here, as fractions of the bar's own width,
 * so the assembly is one shape at any size.
 *
 * **Black outlines are dropped.** Figma writes an inside stroke as a second, black-filled path
 * over the red one. Drawn, it eats a hairline out of every edge — and its bounds are *wider*
 * than the piece, so it would throw the fit off as well. A shape drawn in the ground colour is
 * not part of the mark.
 */
class EsgFramework(
    private val title: String = "ESG Beoordelingskader",
    /** The three pieces' labels, **in the order they arrive**: the bar, then left, then right. */
    private val labels: List<String> = listOf("ENVIRONMENTAL", "SOCIAL", "GOVERNANCE"),
    private val folder: File = File("data/svg/3-shapes"),
    private val fontPath: String = "data/fonts/default.otf",
    /** The pieces. The house red, as the draaiboek draws them. */
    private val ink: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /**
     * How far a piece stands off the joint before it is closed, as a share of the bar's width.
     *
     * The pieces are held apart until the last click and then brought home, so what that click
     * shows is the *fit*, which is the whole point of the drawing. Each is pushed straight out
     * from the middle of the assembly, so the bar rises and the two below it part left and
     * right — one number rather than three offsets, and it cannot come out lopsided.
     */
    private val spread: Double = 0.03,
    override val stepFrames: Int = frames(0.7),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "ESG"

    /** One click a piece, and a last one that closes them. */
    override val steps = labels.size + 1

    override fun stepName(step: Int): String? =
        if (step >= labels.size) "close the joint" else "reveal ${labels.getOrElse(step) { "" }}"

    /**
     * A piece: its shapes in the file's own units, the box they cover there, and where its
     * top-left corner sits in the assembly once closed.
     */
    private class Piece(
        val label: String,
        val shapes: List<Shape>,
        val box: Rectangle,
        val at: Vector2,
        val size: Vector2
    )

    private var pieces: List<Piece> = emptyList()
    private var assembly = Rectangle(0.0, 0.0, 1.0, 1.0)
    private var unit = 1.0
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)

        // Named rather than taken as the folder sorts: `3` is the bar and sets the unit, `1`
        // is the left piece and `2` the right — the order they were cut in, which is not the
        // order they arrive in.
        val bar = read(File(folder, "3.svg")) ?: return
        val left = read(File(folder, "1.svg")) ?: return
        val right = read(File(folder, "2.svg")) ?: return

        unit = bar.second.width

        pieces = listOf(
            bar to Vector2(0.0, 0.0),
            left to Vector2(SOCIAL_X, DROP),
            right to Vector2(SOCIAL_X + TOOTH, DROP)
        ).mapIndexed { i, (read, at) ->
            val (shapes, box) = read
            Piece(labels.getOrElse(i) { "" }, shapes, box, at, Vector2(box.width, box.height) / unit)
        }

        // Whatever the three cover once placed — the assembly states itself.
        assembly = cover(pieces.map { Rectangle(it.at, it.size.x, it.size.y) })
    }

    /** The coloured shapes of one file, and the box they cover. A black outline is not the mark. */
    private fun read(file: File): Pair<List<Shape>, Rectangle>? {
        if (!file.isFile) return null
        val shapes = loadSVG(file).findShapes()
            .filter { it.effectiveShape.contours.isNotEmpty() }
            .filter { node ->
                val fill = node.effectiveFill ?: return@filter false
                fill.alpha > 0.0 && (fill.r + fill.g + fill.b) > 0.15
            }
            .map { it.effectiveShape }
        if (shapes.isEmpty()) return null
        return shapes to cover(shapes.map { it.bounds })
    }

    private fun cover(boxes: List<Rectangle>) = Rectangle(
        boxes.minOf { it.corner.x }, boxes.minOf { it.corner.y },
        boxes.maxOf { it.corner.x + it.width } - boxes.minOf { it.corner.x },
        boxes.maxOf { it.corner.y + it.height } - boxes.minOf { it.corner.y }
    )

    override fun draw(drawer: Drawer, stage: Stage) {
        val head = stage.height * HEAD

        drawer.stroke = null
        drawer.fill = ColorRGBa.WHITE
        drawer.fontMap = face
        set(drawer, title, Vector2(stage.center.x, head), stage.height * TITLE / SIZE)

        if (pieces.isEmpty()) return

        // The assembly fitted into what the title leaves, its own proportions kept.
        val box = Rectangle(
            stage.width * MARGIN, head + stage.height * GAP,
            stage.width * (1.0 - 2.0 * MARGIN),
            stage.height - head - stage.height * (GAP + FOOT)
        )
        // Fitted on the **spread** bounds rather than the closed ones, so the box holds the
        // largest state the slide ever shows. Fitted closed, the pieces would be sized to fill
        // the frame and then pushed past its edges — the bar straight up into the title, which
        // is exactly what it did.
        val open = assembly.width + 2.0 * spread to assembly.height + 2.0 * spread
        val scale = min(box.width / open.first, box.height / open.second)
        val corner = box.center - Vector2(assembly.width, assembly.height) * (scale / 2.0)
        val home = corner - assembly.corner * scale

        // The last click closes the joint: 1 while the pieces stand apart, 0 once they are home.
        val apart = 1.0 - stage.on(labels.size)
        val middle = assembly.center

        pieces.forEachIndexed { i, piece ->
            val shown = stage.on(i)
            if (shown <= 0.0) return@forEachIndexed

            val centre = piece.at + piece.size / 2.0
            val push = (centre - middle).let { if (it.length > 1e-9) it.normalized else Vector2.ZERO }
            val at = piece.at + push * (spread * apart)

            drawer.isolated {
                drawer.translate(home + at * scale)
                // file units to pane pixels: every piece shares this, which is what makes the
                // three fit at all — they were cut from one layout at one scale
                drawer.scale(scale / unit)
                drawer.translate(-piece.box.corner)
                drawer.fill = ink.opacify(shown)
                drawer.stroke = null
                piece.shapes.forEach { drawer.shape(it) }
            }

            drawer.fill = ColorRGBa.WHITE.opacify(shown)
            drawer.fontMap = face
            set(
                drawer, piece.label, home + (at + piece.size / 2.0) * scale,
                stage.height * LABEL / SIZE, middle = true
            )
        }
    }

    /** One line centred on [at]; [middle] centres it vertically as well as across. */
    private fun set(drawer: Drawer, text: String, at: Vector2, scale: Double, middle: Boolean = false) {
        if (text.isEmpty()) return
        drawer.isolated {
            drawer.translate(at)
            drawer.scale(scale)
            drawer.text(text, -face.advanceOf(text) / 2.0, if (middle) SIZE * 0.34 else 0.0)
        }
    }

    private companion object {
        const val SIZE = 200.0

        /**
         * The joint, in units of the bar's own width, read off the path data — see the class
         * note. [TOOTH] is how far the right piece stands from the left, [DROP] how far both
         * stand below the bar, and [SOCIAL_X] the nudge that centres the bar's left tab in the
         * left piece's socket.
         */
        const val TOOTH = 500.982 / 1067.6
        const val DROP = 250.037 / 1067.6
        const val SOCIAL_X = -5.2 / 1067.6

        const val TITLE = 0.036
        const val LABEL = 0.024
        const val HEAD = 0.075
        const val GAP = 0.04
        const val FOOT = 0.06
        const val MARGIN = 0.10
    }
}
