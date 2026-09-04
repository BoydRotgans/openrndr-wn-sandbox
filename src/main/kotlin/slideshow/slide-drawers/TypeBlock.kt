package slideshow.drawers

import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * Setting a passage of type to a box: what breaks where, how big it comes out, and where
 * it sits. Shared by the drawers that set type large — the chapter card and the quote.
 *
 * **The type is not set at a size; it is set to the frame.** [setToFit] finds the largest
 * scale the passage still fits at and the line breaks fall out of that, which is the same
 * reasoning as demo02's line count and the Objects grid: try the arrangements, keep
 * whichever lets the drawing be biggest. So a title or a quote of any length is composed
 * rather than clipped, and rewriting one needs nothing in a drawer changed.
 */

/**
 * The characters every face here is baked with.
 *
 * Stated rather than left to `loadFont`'s default, because the default is built lazily and
 * a character missing from the atlas does not fail — it draws nothing. The curly quotes
 * around a pull quote are exactly the kind of thing to go silently missing, and so are the
 * diaereses Dutch is full of.
 */
val TYPE_CHARACTERS: Set<Char> =
    (' '..'~').toSet() + "“”‘’—–…·áàäâéèëêíìïîóòöôúùüûñçÁÀÄÂÉÈËÊÍÌÏÎÓÒÖÔÚÙÜÛÑÇ".toSet()

/**
 * How wide [text] sets in this face.
 *
 * This is the arithmetic `text()` lays glyphs out with — each glyph's advance, plus the
 * kerning against the one before it. Not `characterWidth`, which sounds like this and is
 * not: that returns the width of the glyph's box *in the atlas*, so it is the ink rather
 * than the advance and a space measures zero. Fitting against it under-measures by about a
 * sixth, which is a block that overruns its own frame at exactly the scale the search
 * called a fit.
 */
fun FontImageMap.advanceOf(text: String): Double {
    var width = 0.0
    text.forEachIndexed { i, c ->
        width += glyphMetrics[c]?.advanceWidth ?: 0.0
        if (i > 0) width += kerning(text[i - 1], c)
    }
    return width
}

/**
 * [text] broken into lines no wider than [measure], in font units.
 *
 * Lines break between words and **after a hyphen**, so a compound written
 * "verantwoor-delijkheid" may be set over two lines — which is the only reason to write
 * one that way. A piece taken after a hyphen carries no space in front of it, so the word
 * closes up again whenever it does fit on one line.
 */
fun FontImageMap.wrapped(text: String, measure: Double): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    for (piece in pieces(text)) {
        val candidate = if (line.isEmpty()) piece.text else line + piece.glue + piece.text
        if (line.isNotEmpty() && advanceOf(candidate) > measure) {
            lines += line
            line = piece.text
        } else {
            line = candidate
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

/** A run that may start a line, and what joins it to the run before. */
private class Piece(val text: String, val glue: String)

private fun pieces(text: String): List<Piece> {
    val out = mutableListOf<Piece>()
    text.split(" ").filter { it.isNotBlank() }.forEachIndexed { w, word ->
        // split after each hyphen, keeping the hyphen on the piece that ends the line
        Regex("(?<=-)").split(word).filter { it.isNotEmpty() }.forEachIndexed { i, part ->
            out += Piece(part, glue = if (i == 0 && w > 0) " " else "")
        }
    }
    return out
}

/**
 * [text] set to [box]: the lines it breaks into and the scale that fits them.
 *
 * [lines] asks for exactly that many, rather than however many let the type be biggest.
 * The two are not the same and the widest setting is not always the wanted one — "De
 * wereld van bouwen" maximised comes out over *two* lines, because a third costs more
 * height than the shorter measure wins back in width. Asking for three is asking for
 * smaller type and a better rag, which is a design decision and belongs in the show.
 */
fun FontImageMap.setToFit(
    text: String,
    box: Rectangle,
    size: Double,
    leading: Double,
    lines: Int? = null
): TypeBlock {
    val broken = if (lines != null) over(text, lines) else biggest(text, box, size, leading)
    val widest = broken.maxOf { advanceOf(it) }.coerceAtLeast(1.0)
    val scale = minOf(box.width / widest, box.height / (broken.size * leading * size))
    return TypeBlock(broken, scale, size, leading, this)
}

/**
 * The breaking that lets the type be biggest: the largest scale that fits both ways.
 *
 * Bisection rather than a formula because the two sides depend on each other — a bigger
 * scale takes fewer words to the line, which takes more lines, which takes more height.
 * Asking "does this scale fit" is easy, so the search asks it thirty times and keeps the
 * largest that does. A single word too long for the measure fails the width test and
 * shrinks the whole block rather than running off the edge of it.
 */
private fun FontImageMap.biggest(
    text: String, box: Rectangle, size: Double, leading: Double
): List<String> {
    var low = 0.02
    var high = 4.0
    repeat(30) {
        val mid = (low + high) / 2.0
        val at = wrapped(text, box.width / mid)
        val fits = at.size * leading * size * mid <= box.height &&
                at.all { advanceOf(it) * mid <= box.width }
        if (fits) low = mid else high = mid
    }
    return wrapped(text, box.width / low)
}

/**
 * [text] over exactly [count] lines: the widest measure that still takes that many, so
 * each line carries as much as it can and the rag falls away from the first.
 *
 * The measure is searched rather than the scale, because the count is what was asked for —
 * the scale is then whatever those lines need, back in [setToFit].
 */
private fun FontImageMap.over(text: String, count: Int): List<String> {
    if (count <= 1) return listOf(text)
    var low = 0.0
    var high = advanceOf(text)
    repeat(30) {
        val mid = (low + high) / 2.0
        if (wrapped(text, mid).size >= count) low = mid else high = mid
    }
    return wrapped(text, low)
}

/**
 * Type set to a box: the lines it broke into, and the scale they are drawn at.
 *
 * [size] is the point size the atlas was baked at, and is carried rather than read back
 * off the face. **`FontImageMap.size` is not the point size** — it comes back 0.08 for a
 * face loaded at 190, being an em scale rather than a measurement; the requested size
 * turns up as `leading` instead. Computing a line height from it collapses the height
 * term to nothing, which lets the fit choose an enormous scale and throws the block clean
 * off the top of the frame. Passing the number the caller already knows avoids the whole
 * question.
 */
class TypeBlock(
    val lines: List<String>,
    val scale: Double,
    private val size: Double,
    private val leading: Double,
    private val font: FontImageMap
) {
    /** How tall the block stands once scaled. */
    val height: Double get() = lines.size * leading * size * scale

    /**
     * Draws the block centred on [centre], every line centred in its turn.
     *
     * One transform for the whole block, so the layout below it is in font units and the
     * centring is arithmetic rather than a pile of measured offsets. [baseline] is where
     * the baseline sits inside a line, as a fraction of the leading.
     */
    fun draw(drawer: Drawer, centre: Vector2, baseline: Double = 0.78) {
        // Hoisted out of the lambda below, and it has to be. `isolated` takes a receiver
        // of Drawer, and Drawer carries its own `width` and `height` — so an unqualified
        // `height` inside it resolves to the *render target's*, not the block's, and the
        // block is translated by half the frame instead of half itself. It draws, it is
        // the right size, and it sits in the wrong place, which is a long way to look for
        // a name that was never ambiguous to read.
        val top = centre.y - height / 2.0
        val line = leading * size

        drawer.fontMap = font
        drawer.isolated {
            drawer.translate(centre.x, top)
            drawer.scale(scale)
            lines.forEachIndexed { i, text ->
                drawer.text(text, -font.advanceOf(text) / 2.0, (i + baseline) * line)
            }
        }
    }

}
