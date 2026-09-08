// ============================================================================ //
//  No `package` declaration: it belongs with the backdrop drawers, which are in
//  the default package because they stand on loadObjectSheet.
// ============================================================================ //

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.shape.Rectangle
import slideshow.drawers.advanceOf
import kotlin.math.floor

/**
 * What a blueprint says about one piece, and nothing about where it goes.
 *
 * Every field is the catalogue's own — see [PieceDetail]. [detail] is null only when the
 * register could not be paired with the sheet, and then the piece carries its proportion and
 * nothing else: a wall of mislabelled components is worse than a wall of unlabelled ones.
 */
data class PieceMeta(
    /**
     * The drawing's title, set in caps along the top. It is *stated* rather than taken from
     * the sheet's filename: what the wall says is the maker's name, not the name of the
     * export the silhouettes happen to live in.
     */
    val title: String,
    /** Its place in the sheet, counting from 1, and how many there are. */
    val number: Int,
    val of: Int,
    /** Width over height, as drawn — the fallback when the catalogue has nothing to say. */
    val ratio: Double,
    /** What the catalogue knows about this piece, where the register covers the sheet. */
    val detail: PieceDetail? = null
) {
    fun name(): String = detail?.name?.uppercase().orEmpty()

    /** The assembly this piece belongs to, and that assembly's tag on the drawings. */
    fun assembly(): String = detail?.let {
        listOf(it.assembly, it.tag).filter(String::isNotBlank).joinToString("   ").uppercase()
    }.orEmpty()

    /** What it was exported as. */
    fun type(): String = detail?.shortType().orEmpty()

    /** The register's own profile note. */
    fun profile(): String = detail?.description?.uppercase().orEmpty()

    /**
     * The box, or — where the register has nothing to say about this piece — the one thing a
     * drawing can always be asked.
     *
     * **No weight**, and that is a decision rather than an omission. The register carries
     * none, and multiplying a mesh's volume by a density would be confidently wrong for a
     * good share of the catalogue: `HPKM39` is a steel column shoe and is exported as
     * `IfcBeam`, so the IFC class cannot separate the steel fittings from the concrete
     * elements. A density or material column is all it would take.
     */
    fun size(): String = detail?.size() ?: "RATIO  1 : %.2f".format(ratio)

    /** Which piece this is — the half of the counter that is being searched for. */
    fun no(): String = "%02d".format(number)

    /**
     * How many there are, which is **not** searched for: the catalogue's length is a fixed
     * fact about the wall and is true before any piece is drawn. Churning it made the total
     * appear to change from piece to piece, which reads as the count being unknown rather
     * than as this piece being unknown.
     */
    fun total(): String = "%02d".format(of)
}

/**
 * The annotation layer: small letter-spaced type set around a piece, which **searches for the
 * piece while it is being drawn** and settles as it finishes.
 *
 * **The type is never absent.** Before the register can say anything there is still an
 * outline being drawn, and the layer fills that time by churning through the alphabet — the
 * wall reading out what is arriving rather than waiting for it. It resolves left to right off
 * the same progress that draws the line, so you can tell how far through a piece the wall is
 * from the type alone.
 *
 * **The churn is a pure function of the frame**, like everything else here: a hash of the
 * frame and the character's place, never an accumulated shuffle. So the wall can be scrubbed,
 * paused or filmed and shows the same letters at the same frame.
 *
 * **A churning character keeps its own class** — a letter runs through letters and a digit
 * through digits, and spaces and punctuation never move. That is what holds the block's shape
 * while it settles: the words keep their length and their gaps, so nothing reflows under the
 * reader and what resolves reads as the *same* line coming into focus rather than as one
 * string being swapped for another.
 *
 * It is drawn in **pane pixels rather than in the piece's own space**, and has to be — the
 * piece is fitted to its half by a scale that runs from a 5cm plate to a 24m beam, so type
 * set inside that transform would be a hairline on one piece and enormous on the next.
 *
 * The fields are ranged to the four corners and the mid-height rather than stacked in a
 * block, so the pane is held at its edges and the piece stands in clear space in the middle.
 * Nothing is repeated: each field is said once, in the place its length suits.
 *
 * A ruled [grid] and a bracket ([marks]) are built and off. Each makes a materially different
 * picture rather than a slightly busier one, which is a decision to take rather than a default
 * to inherit.
 */
class BlueprintLabel(
    private val ink: ColorRGBa,
    /** The small face everything is set in. */
    private val face: FontImageMap,
    /** The margin the type ranges to, in pane pixels. */
    private val margin: Double = 74.0,
    /** Extra space between letters, as a fraction of the face's size. */
    private val tracking: Double = 0.34,
    /**
     * The bracket — hairlines level with the piece's top and bottom, run out to the frame,
     * with `(A)` and `(B)` naming their ends. Off by default: it measures the piece against
     * the frame, which is a drawing *about* the piece rather than a caption on it.
     */
    private val marks: Boolean = false,
    /** The hairlines' weight, in pane pixels, when [marks] is on. */
    private val rule: Double = 1.0,
    /** The grid's pitch, in pane pixels. 0 — the default — draws none; 96 is a fair pitch. */
    private val grid: Double = 0.0,
    /** How strongly the grid reads, when it is on. */
    private val gridWeight: Double = 0.24,
    /** Frames a churning character holds before it changes. 1 is every frame. */
    private val churn: Int = 3
) {

    /**
     * Draws the annotation for a piece standing in [box], inside [pane].
     *
     * [resolve] is how far the piece has been drawn, 0..1 — the type searches at 0 and has
     * settled at 1. [frame] drives the churn and is the slide's own frame count. [shown] fades
     * the whole layer, so the lettering leaves with the drawing it belongs to. [accent] colours
     * the piece's own entry while it is being announced — the title is never accented.
     */
    fun draw(
        drawer: Drawer, pane: Rectangle, box: Rectangle,
        meta: PieceMeta, resolve: Double, frame: Int, shown: Double = 1.0,
        accent: ColorRGBa? = null
    ) {
        if (shown <= 0.0) return
        val paint = ink.opacify(shown)
        // Everything the piece owns takes the accent; the maker's name does not, because it
        // is not a fact about *this* piece and has nothing to announce when one lands.
        val entry = (accent ?: ink).opacify(shown)
        val line = face.leading

        drawer.isolated {
            fontMap = face
            stroke = null

            val left = pane.corner.x + margin
            val right = pane.corner.x + pane.width - margin
            val top = pane.corner.y + margin
            val bottom = pane.corner.y + pane.height - margin
            val middle = pane.corner.y + pane.height / 2.0

            if (grid > 0.0) {
                stroke = ink.opacify(gridWeight * shown)
                strokeWeight = rule
                var x = pane.corner.x + grid
                while (x < pane.corner.x + pane.width) {
                    lineSegment(x, pane.corner.y, x, pane.corner.y + pane.height); x += grid
                }
                var y = pane.corner.y + grid
                while (y < pane.corner.y + pane.height) {
                    lineSegment(pane.corner.x, y, pane.corner.x + pane.width, y); y += grid
                }
            }

            if (marks) {
                stroke = paint
                strokeWeight = rule
                val topEdge = box.corner.y
                val bottomEdge = box.corner.y + box.height
                for (edge in listOf(topEdge, bottomEdge)) {
                    lineSegment(pane.corner.x, edge, left - 14.0, edge)
                    lineSegment(right + 14.0, edge, pane.corner.x + pane.width, edge)
                }
                stroke = null
                fill = paint
                text("(A)", left, topEdge - 10.0)
                text("(B)", left, bottomEdge + line)
            }

            fill = paint

            /** A field, churning until the drawing has caught up with it. */
            fun searching(value: String, seed: Int) = scramble(value, resolve, frame, seed)

            // Two things are *not* searched for, and for the same reason: they are true
            // before the piece is drawn. The maker over the door does not change piece to
            // piece, and neither does how many pieces there are — only *which* one this is
            // is in question, so the counter searches its own half and holds its total.
            tracked(this, meta.title.uppercase(), left, top, tracking * 2.2)

            fill = entry
            rangedRight(
                this, searching(meta.no(), 5) + " / " + meta.total(), right, top, tracking
            )

            tracked(this, searching(meta.assembly(), 11), left, middle, tracking)
            rangedRight(this, searching(meta.type(), 23), right, middle, tracking)

            val foot = listOf(meta.name() to 37, meta.profile() to 53)
                .filter { it.first.isNotBlank() }
            foot.forEachIndexed { i, (entry, seed) ->
                tracked(this, searching(entry, seed), left,
                    bottom - (foot.size - 1 - i) * line * 1.5, tracking)
            }
            rangedRight(this, searching(meta.size(), 71), right, bottom, tracking)
        }
    }

    /**
     * [text] with its first [resolve] settled and the rest still churning.
     *
     * Locked left to right, so a field comes into focus the way it is read.
     */
    private fun scramble(text: String, resolve: Double, frame: Int, seed: Int): String {
        if (text.isEmpty() || resolve >= 1.0) return text
        val settled = floor(text.length * resolve.coerceIn(0.0, 1.0)).toInt()
        val tick = frame / churn.coerceAtLeast(1)
        return buildString {
            text.forEachIndexed { i, c ->
                if (i < settled) append(c) else append(
                    when {
                        c.isDigit() -> DIGITS[noise(tick * 31 + i * 17 + seed) % DIGITS.length]
                        c.isLetter() -> LETTERS[noise(tick * 31 + i * 17 + seed) % LETTERS.length]
                        else -> c
                    }
                )
            }
        }
    }

    /** A cheap integer hash, so the churn is a function of the frame and never of history. */
    private fun noise(n: Int): Int {
        var x = n * 1664525 + 1013904223
        x = x xor (x ushr 15)
        x *= 0x27d4eb2d
        x = x xor (x ushr 15)
        return x and 0x7fffffff
    }

    /** How wide [text] sets once it is tracked — the arithmetic [tracked] lays it out with. */
    private fun trackedWidth(text: String, extra: Double = tracking): Double =
        if (text.isEmpty()) 0.0 else face.advanceOf(text) + face.leading * extra * (text.length - 1)

    /** [text] with [extra] of the face's size added between letters. */
    private fun tracked(drawer: Drawer, text: String, x: Double, y: Double, extra: Double) {
        val step = face.leading * extra
        var at = x
        for (c in text) {
            drawer.text(c.toString(), at, y)
            at += (face.glyphMetrics[c]?.advanceWidth ?: 0.0) + step
        }
    }

    /** [text] ending at [right], letter-spaced — measured the way [tracked] lays it out. */
    private fun rangedRight(drawer: Drawer, text: String, right: Double, y: Double, extra: Double) {
        tracked(drawer, text, right - trackedWidth(text, extra), y, extra)
    }

    private companion object {
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val DIGITS = "0123456789"
    }
}
