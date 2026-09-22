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
import kotlin.math.abs
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
 * - **Where the lower pair stands across is set by the outer edges, not the tabs.** They are
 *   1066.0 wide against the bar's 1067.6, so they stand 0.8 in, level with it to under a pixel
 *   either side. Centring the bar's left tab in its socket instead put them 5.2 left of the bar,
 *   a visible step on both outer edges; the few units of play that leaves round each tab are
 *   inside the seal below, so they never show.
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
 *
 * **The same drawer says what each piece stands for, when it is asked to.** [notes] is a list
 * of lines a piece, in the pieces' order; given, each piece arrives with its notes on leaders
 * beside it and the pieces already up dim while it is named, so the one being talked about is
 * the one in full colour. A further click then brings all three back to full, notes gone, and
 * the last closes the joint as before — five states rather than four. Left empty, the drawer is
 * exactly the chapter 2 slide: the code path with no notes does not change.
 *
 * **Where a piece's notes stand is read off where it pushes**, the same direction it stands off
 * the joint before the close: a piece pushed sideways carries its notes on that side, ranged
 * away from it; the bar, pushed up into the title, carries its under its bottom edge instead,
 * spread along it. So the notes are content and the sides are geometry, and neither is stated
 * in the show. A leader grows from the words toward the piece on the number the words fade
 * up on.
 */
class EsgFramework(
    private val title: String = "ESG Beoordelingskader",
    /** The three pieces' labels, **in the order they arrive**: the bar, then left, then right. */
    private val labels: List<String> = listOf("ENVIRONMENTAL", "SOCIAL", "GOVERNANCE"),
    /**
     * What each piece stands for, a list of lines a piece in the pieces' order, or empty for the
     * plain framework. A line too wide for the room beside its piece is wrapped.
     */
    private val notes: List<List<String>> = emptyList(),
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
    /** How much of its colour a piece keeps while another is being named. */
    private val dim: Double = 0.3,
    override val stepFrames: Int = frames(0.7),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "ESG"

    /** Whether the pieces are named one at a time — which costs the click that un-dims them. */
    private val named get() = notes.isNotEmpty()

    /** The click that closes the joint. */
    private val closing get() = labels.size + if (named) 1 else 0

    /** One click a piece, a last one that closes them — and, named, one between that shows all three. */
    override val steps = closing + 1

    override fun stepName(step: Int): String? = when {
        step >= closing -> "close the joint"
        step >= labels.size -> "all three"
        else -> "reveal ${labels.getOrElse(step) { "" }}"
    }

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
        val apart = 1.0 - stage.on(closing)
        val middle = assembly.center

        // The joint sealed as it closes. The drawings' tabs are a few units short of the sockets
        // they drop into — the bar's by 3.5 and 4, Governance's side teeth by 1 to 3 — so a closed
        // framework showed black hairlines along every joint (review of 22 September). Under the
        // pieces, the region the two lower ones tile is filled in their red over the last part of
        // the close, so nothing shows through once they are home and nothing changes before.
        val sealed = ((stage.on(closing) - SEAL) / (1.0 - SEAL)).coerceIn(0.0, 1.0)
        if (sealed > 0.0 && pieces.size == 3) {
            val (_, left, right) = pieces
            val x0 = left.at.x
            val x1 = right.at.x + right.size.x
            val y0 = min(left.at.y, right.at.y)
            val y1 = min(left.at.y + left.size.y, right.at.y + right.size.y)
            drawer.fill = ink.opacify(sealed * stage.on(pieces.size - 1))
            drawer.stroke = null
            drawer.rectangle(Rectangle(home + Vector2(x0, y0) * scale, (x1 - x0) * scale, (y1 - y0) * scale))
        }

        pieces.forEachIndexed { i, piece ->
            val shown = stage.on(i)
            if (shown <= 0.0) return@forEachIndexed

            val centre = piece.at + piece.size / 2.0
            val push = (centre - middle).let { if (it.length > 1e-9) it.normalized else Vector2.ZERO }
            val at = piece.at + push * (spread * apart)

            // Named, a piece dims from the click after its own until the click that shows all
            // three — the last piece named is never dimmed, so its window is empty.
            val dimmed = if (named && i + 1 < labels.size) stage.between(i + 1, labels.size) else 0.0
            val colour = 1.0 - dimmed * (1.0 - dim)

            drawer.isolated {
                drawer.translate(home + at * scale)
                // file units to pane pixels: every piece shares this, which is what makes the
                // three fit at all — they were cut from one layout at one scale
                drawer.scale(scale / unit)
                drawer.translate(-piece.box.corner)
                drawer.fill = ink.opacify(shown * colour)
                drawer.stroke = null
                piece.shapes.forEach { drawer.shape(it) }
            }

            drawer.fill = ColorRGBa.WHITE.opacify(shown * colour)
            drawer.fontMap = face
            set(
                drawer, piece.label, home + (at + piece.size / 2.0) * scale,
                stage.height * LABEL / SIZE, middle = true
            )

            // Its notes: in over the last third of the piece's own click, and gone by the first
            // third of the next one — the ladder's rule for text that changes, so a leaving note
            // never lies over the piece arriving under it. Filmed without it, "Minder materiaal"
            // was still fading out across Social's top edge as Social came up.
            if (named) {
                val telling = ((stage.on(i) - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0) *
                        (1.0 - stage.on(i + 1) / FADE).coerceIn(0.0, 1.0)
                val lines = notes.getOrNull(i).orEmpty()
                if (telling > 0.0 && lines.isNotEmpty()) {
                    val rect = Rectangle(home + at * scale, piece.size.x * scale, piece.size.y * scale)
                    notes(drawer, stage, rect, push, lines, telling)
                }
            }
        }
    }

    /**
     * [lines] beside [rect], on the side it [push]es toward — left or right of a piece pushed
     * sideways, under a piece pushed up — each on a leader that grows from the words to the
     * piece's edge as the words fade up.
     */
    private fun notes(drawer: Drawer, stage: Stage, rect: Rectangle, push: Vector2, lines: List<String>, alpha: Double) {
        val size = stage.height * NOTE
        val scale = size / SIZE
        val lead = stage.width * LEADER
        val gap = stage.width * NOTE_GAP
        val lineHeight = stage.height * NOTE_LEAD
        val sideways = abs(push.x) > abs(push.y)

        drawer.fill = ColorRGBa.WHITE.opacify(alpha)
        drawer.stroke = ColorRGBa.WHITE.opacify(alpha)
        drawer.strokeWeight = LINE
        drawer.fontMap = face

        lines.forEachIndexed { k, note ->
            val share = (k + 0.5) / lines.size
            if (sideways) {
                // Down the side, each note level with its share of the piece's height and ranged
                // away from it; the leader runs level from the words to the edge.
                val left = push.x < 0.0
                val edge = Vector2(if (left) rect.x else rect.x + rect.width, rect.y + rect.height * share)
                val room = if (left) edge.x - lead - gap - stage.width * EDGE
                else stage.width * (1.0 - EDGE) - (edge.x + lead + gap)
                val wrapped = face.wrapped(note, room / scale)
                val start = Vector2(edge.x + (if (left) -lead else lead), edge.y)
                val x = start.x + if (left) -gap else gap
                val top = edge.y - (wrapped.size - 1) * lineHeight / 2.0 + size * 0.34
                wrapped.forEachIndexed { j, line ->
                    drawer.setLine(line, face, Vector2(x, top + j * lineHeight), size, SIZE, align = if (left) 1.0 else 0.0)
                }
                drawer.lineSegment(start, start + (edge - start) * alpha)
            } else {
                // Under the bottom edge, spread along it; the leader drops from the edge to the words.
                val edge = Vector2(rect.x + rect.width * share, rect.y + rect.height)
                val room = rect.width / lines.size - gap
                val wrapped = face.wrapped(note, room / scale)
                val start = Vector2(edge.x, edge.y + lead)
                val top = start.y + gap + size * 0.78
                wrapped.forEachIndexed { j, line ->
                    drawer.setLine(line, face, Vector2(edge.x, top + j * lineHeight), size, SIZE, align = 0.5)
                }
                drawer.lineSegment(start, start + (edge - start) * alpha)
            }
        }
        drawer.stroke = null
    }

    /**
     * One line at [at], centred on it; [middle] centres it vertically too. The title and the piece
     * labels only — a note goes through [setLine], because a note can say CO₂ and this cannot.
     */
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
         * stand below the bar, and [SOCIAL_X] where the lower pair stands across, set so their outer
         * edges run level with the bar's.
         */
        const val TOOTH = 500.982 / 1067.6
        const val DROP = 250.037 / 1067.6
        const val SOCIAL_X = 0.8 / 1067.6

        /** How far through the closing click the seal under the joint starts to come in. */
        const val SEAL = 0.85

        const val TITLE = 0.036
        const val LABEL = 0.024
        const val HEAD = 0.075
        const val GAP = 0.04
        const val FOOT = 0.06
        const val MARGIN = 0.10

        /** The notes: their size, their leading, the leader's length, the air between it and the words, and the pane's edge. */
        const val NOTE = 0.026
        const val NOTE_LEAD = 0.034
        const val LEADER = 0.04
        const val NOTE_GAP = 0.008
        const val EDGE = 0.02
        const val LINE = 2.0

        /** The share of a click a note takes to come, or to go. */
        const val FADE = 0.3
    }
}
