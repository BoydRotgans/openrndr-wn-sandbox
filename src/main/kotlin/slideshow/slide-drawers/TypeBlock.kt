package slideshow.drawers

import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import kotlin.math.ln

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
 * a character missing from the atlas does not fail — **it draws nothing**. The curly quotes
 * around a pull quote are exactly the kind of thing to go silently missing, and so are the
 * diaereses Dutch is full of.
 *
 * It has caught a real one since: `350.000 M³` set as `350.000 M`, which reads as a figure
 * rather than as a fault. Anything a figure might carry — superscripts, a degree sign, a
 * euro — belongs here, because there is nothing to see when it does not.
 */
val TYPE_CHARACTERS: Set<Char> = (' '..'~').toSet() + (
        "“”‘’—–…·" +                        // quotes, dashes, an ellipsis
        "áàäâéèëêíìïîóòöôúùüûñç" +          // the accents Dutch and its neighbours use
        "ÁÀÄÂÉÈËÊÍÌÏÎÓÒÖÔÚÙÜÛÑÇ" +
        "²³°€×½¼¾±"                         // figures: M², M³, degrees, euros
        ).toSet()
// Not in the set, because Rockwell has neither and a missing glyph draws a box: a true minus
// (U+2212) and the arrows. A figure is set with an en dash, "–30%", and a step is written out.

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
fun FontImageMap.wrapped(text: String, measure: Double): List<String> = wrap(text, measure) { advanceOf(it) }

/**
 * [wrapped], measured by [advance] rather than by an atlas — so a caller that measures a face's
 * outlines, which need no GL context, breaks a line exactly where the atlas would.
 */
fun wrap(text: String, measure: Double, advance: (String) -> Double): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    for (piece in pieces(text)) {
        val candidate = if (line.isEmpty()) piece.text else line + piece.glue + piece.text
        if (line.isNotEmpty() && advance(candidate) > measure) {
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
 *
 * A text with line breaks in it is set exactly as broken, and only the scale is fitted: where
 * no search gives the rag wanted, the show states it.
 */
fun FontImageMap.setToFit(
    text: String,
    box: Rectangle,
    size: Double,
    leading: Double,
    lines: Int? = null,
    /** Break it as a passage is read rather than as it fills a measure — see [compactLines]. For the quotes. */
    compact: Boolean = false
): TypeBlock {
    val broken = breakLines(text, box, size, leading, lines, compact) { advanceOf(it) }
    val widest = broken.maxOf { advanceOf(it) }.coerceAtLeast(1.0)
    val scale = minOf(box.width / widest, box.height / (broken.size * leading * size))
    return TypeBlock(broken, scale, size, leading, this)
}

/**
 * The lines [setToFit] breaks [text] into, measured by [advance] at [size] — the one breaking,
 * for a caller measuring something other than an atlas. The long shadow wall sets its quote
 * from the face's outlines this way and so breaks it exactly where [QuoteSlide] would.
 */
fun breakLines(
    text: String, box: Rectangle, size: Double, leading: Double, lines: Int?, compact: Boolean = false,
    advance: (String) -> Double
): List<String> = when {
    '\n' in text -> text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    compact -> compactLines(text, box, size, leading, lines, advance)
    lines != null -> over(text, lines, advance)
    else -> biggest(text, box, size, leading, advance)
}

/**
 * [text] broken optically (27 October 2026: "visually balanced lines that make effective use of the available width …
 * relatively consistent line lengths and natural phrasing, a wide, compact text block with a subtle ragged-right edge").
 *
 * Every way of breaking it is weighed at once, for every line count and over a range of measures, and the one kept is
 * the best by [judged]: the type as large as the box allows, the lines as even as they can be against the longest —
 * the last counted too, at half weight — fewer lines on a tie, and the phrasing after that: a line ending at a
 * sentence or a pause is a little better, one hanging on a short word ("de", "van") a little worse.
 *
 * **A long word may be hyphenated**, but only at the points [HYPHENATION] gives it — Dutch hyphenation written out by
 * hand for the words the show's quotes use, nine letters or more — so nothing is ever split wrongly, and a word with
 * no entry is never split. The phrases in [KEEP_TOGETHER] are never broken. With [lines] it is that many lines.
 */
fun compactLines(
    text: String, box: Rectangle, size: Double, leading: Double, lines: Int?, advance: (String) -> Double
): List<String> {
    val ps = opticalPieces(text)
    if (ps.size <= 1) return listOf(text.trim())
    val count = ps.size
    fun line(a: Int, b: Int) = buildString {
        for (k in a until b) { if (k > a) append(ps[k].glue); append(ps[k].text) }
        if (ps[b - 1].soft) append('-')
    }.replace(' ', ' ')
    val width = Array(count) { a -> DoubleArray(count + 1) { b -> if (b > a) advance(line(a, b)) else 0.0 } }
    val total = width[0][count]
    val longest = (0 until count).maxOf { width[it][it + 1] }
    var best: Pair<List<String>, Double>? = null
    for (n in (if (lines != null) listOf(lines) else (1..minOf(MOST_LINES, count)).toList())) {
        val low = maxOf(longest, total / n)
        for (step in 0 until MEASURES) {
            val broken = optimal(ps, n, low * (1.0 + step * MEASURE_STEP), width) ?: continue
            val text2 = broken.map { (a, b) -> line(a, b) }
            val score = judged(text2, box, size, leading, advance)
            if (best == null || score > best.second) best = text2 to score
        }
    }
    return best?.first ?: wrap(text, box.width, advance)
}

/** One run [compactLines] may start a line with: [soft] where it ends inside a word, which then shows a hyphen. */
private class Bit(val text: String, val glue: String, val soft: Boolean)

/** [text] in runs: words, split again at their [HYPHENATION] points, with [KEEP_TOGETHER] phrases held as one. */
private fun opticalPieces(text: String): List<Bit> {
    var held = text
    for (phrase in KEEP_TOGETHER) held = held.replace(phrase, phrase.replace(' ', ' '))
    val out = mutableListOf<Bit>()
    held.split(' ').filter { it.isNotEmpty() }.forEachIndexed { w, word ->
        val core = word.trim('“', '”', '"', '‘', '’', '\'', '(', ')', ',', '.', ':', ';', '?', '!')
        val at = if (core.isEmpty()) -1 else word.indexOf(core)
        val points = if (at < 0) null else HYPHENATION[core.lowercase()]
        if (points == null) {
            out += Bit(word, if (w > 0) " " else "", false)
        } else {
            // the word's own letters, cut at the dictionary's points
            val parts = mutableListOf<String>()
            var i = 0
            for (p in points.split('-')) { parts += core.substring(i, i + p.length); i += p.length }
            parts[0] = word.substring(0, at) + parts[0]
            parts[parts.lastIndex] = parts.last() + word.substring(at + core.length)
            parts.forEachIndexed { k, part -> out += Bit(part, if (k == 0 && w > 0) " " else "", k < parts.lastIndex) }
        }
    }
    return out
}

/** The least-cost breaking of [ps] into [n] lines no wider than [measure], as (start, end) runs; null where none fits. */
private fun optimal(ps: List<Bit>, n: Int, measure: Double, width: Array<DoubleArray>): List<Pair<Int, Int>>? {
    val count = ps.size
    if (n < 1 || n > count) return null
    val best = Array(n + 1) { DoubleArray(count + 1) { Double.POSITIVE_INFINITY } }
    val from = Array(n + 1) { IntArray(count + 1) { -1 } }
    best[0][0] = 0.0
    for (k in 1..n) {
        val last = k == n
        for (j in k..count) {
            if (last && j != count) continue
            for (i in (k - 1) until j) {
                if (best[k - 1][i].isInfinite()) continue
                val w = width[i][j]
                if (w > measure) continue
                val r = w / measure
                var c: Double
                if (last) {
                    c = 1.5 * maxOf(0.0, 0.8 - r).let { it * it }
                    if (j - i == 1 && n > 1) c += 1.0
                } else {
                    c = (1.0 - r).let { it * it }
                    val end = ps[j - 1]
                    when {
                        end.soft -> c += HYPHEN_COST + if (i > 0 && ps[i - 1].soft) HYPHEN_COST else 0.0
                        SENTENCE_END.containsMatchIn(end.text) -> c -= 0.012
                        end.text.last() in ",:;" -> c -= 0.006
                        end.text.trim('“', '”', '"', '\'').lowercase() in SHORT_WORDS -> c += 0.04
                    }
                }
                if (best[k - 1][i] + c < best[k][j]) { best[k][j] = best[k - 1][i] + c; from[k][j] = i }
            }
        }
    }
    if (best[n][count].isInfinite()) return null
    val cuts = mutableListOf<Pair<Int, Int>>()
    var j = count
    for (k in n downTo 1) { val i = from[k][j]; cuts += i to j; j = i }
    return cuts.reversed()
}

/** How good a set of [lines] is in [box]: the type's size, the evenness against the longest line, the phrasing. */
private fun judged(lines: List<String>, box: Rectangle, size: Double, leading: Double, advance: (String) -> Double): Double {
    val w = lines.map(advance)
    val n = lines.size
    val longest = w.max().coerceAtLeast(1.0)
    val scale = minOf(box.width / longest, box.height / (n * leading * size))
    val rag = (w.dropLast(1).sumOf { (1.0 - it / longest).let { d -> d * d } } +
            2.0 * maxOf(0.0, 0.85 - w.last() / longest).let { it * it }) / n
    val ended = lines.dropLast(1)
    val hyphens = ended.count { it.endsWith("-") }
    val short = ended.count { it.substringAfterLast(' ').trim('“', '”', '"', '\'').lowercase() in SHORT_WORDS }
    val pauses = ended.count { SENTENCE_END.containsMatchIn(it) || it.last() in ",:;" }
    return ln(scale) - EVENNESS * rag - 0.04 * hyphens - 0.025 * short + 0.01 * pauses - 0.01 * n
}

/**
 * Where the show's quote words may be hyphenated, by hand, for words of nine letters or more (a shorter word split reads
 * as fussy rather than balanced). A word not here is never split.
 *
 * **Only where the word is put together** (28 October 2026, "appropriate Dutch word breaks"): between the parts of a
 * compound, after a prefix that stands before a whole word, or before -heid and -heden, so that each half still reads
 * as a word: "onder-zoek", "verantwoordelijk-heid", "uit-eindelijk". Every one is a syllable break too. A syllable break
 * inside a stem is correct by the Woordenlijst and still reads as a broken word: "verantwoor-delijkheid", "beoor-delen",
 * "moge-lijkheden", "organisa-tie", "syste-matische" were all set on the wall, and were asked away. A word with no
 * such seam (organisatie, systematische, beoordelen, nauwelijks) is left whole.
 */
private val HYPHENATION: Map<String, String> = """
    check-list onder-zoek bouw-keten besluit-vorming
    verantwoordelijk-heid verantwoordelijk-heden mogelijk-heden duurzaam-heid
    uit-eindelijk toe-gevoegde toe-voegingen
""".split(Regex("\\s+")).filter { it.replace("-", "").length >= 9 }.associateBy { it.replace("-", "") }

/** Phrases a line never breaks inside: names, and the table's own words. */
private val KEEP_TOGETHER = listOf("The Circle", "Willy Naessens", "aan tafel", "één vraag")

/** Words a line should not end on: it reads as hanging. */
private val SHORT_WORDS = setOf("de", "het", "een", "van", "en", "op", "in", "te", "om", "met", "na", "is", "we", "je",
    "die", "aan", "voor", "u", "the")

/** A piece that ends a sentence: a full stop, a question or an exclamation mark, a closing quote after it or not. */
private val SENTENCE_END = Regex("[.?!][”\"’']?$")
private const val HYPHEN_COST = 0.05
/** How much evenness weighs against the size of the type: at 14 a wide, even block wins over a tall, ragged one. */
private const val EVENNESS = 14.0
private const val MEASURES = 36
private const val MEASURE_STEP = 0.01
private const val MOST_LINES = 8

/**
 * The breaking that lets the type be biggest: the largest scale that fits both ways.
 *
 * Bisection rather than a formula because the two sides depend on each other — a bigger
 * scale takes fewer words to the line, which takes more lines, which takes more height.
 * Asking "does this scale fit" is easy, so the search asks it thirty times and keeps the
 * largest that does. A single word too long for the measure fails the width test and
 * shrinks the whole block rather than running off the edge of it.
 */
private fun biggest(
    text: String, box: Rectangle, size: Double, leading: Double, advance: (String) -> Double
): List<String> {
    var low = 0.02
    var high = 4.0
    repeat(30) {
        val mid = (low + high) / 2.0
        val at = wrap(text, box.width / mid, advance)
        val fits = at.size * leading * size * mid <= box.height &&
                at.all { advance(it) * mid <= box.width }
        if (fits) low = mid else high = mid
    }
    return wrap(text, box.width / low, advance)
}

/**
 * [text] over exactly [count] lines: the widest measure that still takes that many, so
 * each line carries as much as it can and the rag falls away from the first.
 *
 * The measure is searched rather than the scale, because the count is what was asked for —
 * the scale is then whatever those lines need, back in [setToFit].
 */
private fun over(text: String, count: Int, advance: (String) -> Double): List<String> {
    if (count <= 1) return listOf(text)
    var low = 0.0
    var high = advance(text)
    repeat(30) {
        val mid = (low + high) / 2.0
        if (wrap(text, mid, advance).size >= count) low = mid else high = mid
    }
    return wrap(text, low, advance)
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
     * Draws the block centred on [centre], every line centred in its turn — or ranged left
     * from it where [align] is 0, which is what a passage read as speech wants: a common left
     * edge to come back to, rather than a ragged one on both sides.
     *
     * [align] is the share of a line's own width taken off its start: 0.5 centres it, 0 ranges
     * it left, 1 ranges it right. With 0 the block's left edge sits at [centre]`.x`, so a
     * caller ranging left passes the measure's left edge rather than its middle.
     *
     * One transform for the whole block, so the layout below it is in font units and the
     * centring is arithmetic rather than a pile of measured offsets. [baseline] is where
     * the baseline sits inside a line, as a fraction of the leading.
     */
    fun draw(drawer: Drawer, centre: Vector2, baseline: Double = 0.78, align: Double = 0.5) {
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
                drawer.text(text, -font.advanceOf(text) * align, (i + baseline) * line)
            }
        }
    }

}

// ------------------------------------------------------------------------------ //
//  Subscripts, set rather than asked for
// ------------------------------------------------------------------------------ //

/**
 * Rockwell has no subscript glyphs — checked in the face's own cmap, not guessed — so `CO₂`
 * cannot simply be put in [TYPE_CHARACTERS]: a character missing from the face draws nothing
 * and advances nothing, and the line comes out as "CO -uitstoot", which reads as a spacing
 * bug rather than an absent glyph. It is the trap the `×` fell into, one step further along.
 * So a subscript digit is **set** — drawn as the ordinary digit at [SUB] of the size, dropped
 * [SUB_DROP] of an em — which is what setting one means. Shared by every slide that says CO₂.
 */
const val SUBSCRIPTS = "₀₁₂₃₄₅₆₇₈₉"
const val SUB = 0.62
const val SUB_DROP = 0.13

/** [piece] split into ordinary and subscript runs, the subscripts as plain digits. */
fun subscriptRuns(piece: String): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    val run = StringBuilder()
    var lowered = false
    for (c in piece) {
        val digit = SUBSCRIPTS.indexOf(c)
        val low = digit >= 0
        if (low != lowered && run.isNotEmpty()) {
            out += run.toString() to lowered; run.clear()
        }
        lowered = low
        run.append(if (low) '0' + digit else c)
    }
    if (run.isNotEmpty()) out += run.toString() to lowered
    return out
}

/**
 * The modifier letters set raised, as [SUPERSCRIPT_LETTERS] maps them: the course names say "1ᵉ gang" (27 October
 * 2026), and Rockwell has no `ᵉ` either — checked in its cmap — so it is set the way a subscript is, the ordinary
 * letter at [SUP] of the size raised [SUP_RISE] of an em, its top about level with the capitals.
 */
const val SUPERSCRIPTS = "ᵃᵇᵈᵉᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ"
const val SUPERSCRIPT_LETTERS = "abdeghijklmnoprstuvwxyz"
const val SUP = 0.62
const val SUP_RISE = 0.34

/** [piece] in runs, each with its shift: -1 a subscript run, 1 a superscript run, 0 ordinary, all as plain characters. */
fun scriptRuns(piece: String): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    val run = StringBuilder()
    var shift = 0
    for (c in piece) {
        val digit = SUBSCRIPTS.indexOf(c)
        val letter = SUPERSCRIPTS.indexOf(c)
        val now = if (digit >= 0) -1 else if (letter >= 0) 1 else 0
        if (now != shift && run.isNotEmpty()) {
            out += run.toString() to shift; run.clear()
        }
        shift = now
        run.append(if (digit >= 0) '0' + digit else if (letter >= 0) SUPERSCRIPT_LETTERS[letter] else c)
    }
    if (run.isNotEmpty()) out += run.toString() to shift
    return out
}

/** How wide [piece] sets in this face, in font units, its subscripts and superscripts counted at their own size. */
fun FontImageMap.advanceWithSubscripts(piece: String): Double =
    scriptRuns(piece).sumOf { (run, shift) -> advanceOf(run) * when (shift) { -1 -> SUB; 1 -> SUP; else -> 1.0 } }

/**
 * One line of [piece] in [face] at [size] pane pixels, its subscripts and superscripts set, ranged by [align]
 * about [at] — 0 for left, 0.5 for centred, 1 for right. [em] is the size the face was loaded
 * at, which is what the scale is taken against.
 */
fun Drawer.setLine(piece: String, face: FontImageMap, at: Vector2, size: Double, em: Double, align: Double = 0.0) {
    if (piece.isEmpty()) return
    val scale = size / em
    fontMap = face
    isolated {
        translate(at)
        scale(scale)
        var x = -face.advanceWithSubscripts(piece) * align
        for ((run, shift) in scriptRuns(piece)) {
            if (shift != 0) {
                val k = if (shift < 0) SUB else SUP
                isolated {
                    translate(x, if (shift < 0) em * SUB_DROP else -em * SUP_RISE)
                    scale(k)
                    text(run, 0.0, 0.0)
                }
                x += face.advanceOf(run) * k
            } else {
                text(run, x, 0.0)
                x += face.advanceOf(run)
            }
        }
    }
}
