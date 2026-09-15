package slideshow.drawers

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * What the chart slides share: a bar that grows from its baseline, a column stacked out of
 * bands, an axis, a leader with a label on it, a list of bullets — every one a pure function of
 * its values and one progress number, so a slide built on them is a pure function of
 * `stage.position` the way every drawer here has to be.
 *
 * Nothing in here knows what a click is. A slide hands in `stage.on(n)` for a thing that
 * *travels* — a bar growing, a line drawing across — and `linear(stage.on(n))` for a thing that
 * is *counted*, bullets one after another, which is the distinction the city cull first drew.
 */

/** One band of a stacked column: a key it keeps from state to state, what it says, how much, in what. */
class Band(val key: String, val label: String, val value: Double, val colour: ColorRGBa)

/**
 * [rect] grown from its baseline by [t]: the same foot, [t] of the height. A bar arrives out of
 * its axis rather than fading on, which is the house move for anything charted.
 */
fun grownFromBase(rect: Rectangle, t: Double): Rectangle {
    val h = rect.height * t.coerceIn(0.0, 1.0)
    return Rectangle(rect.x, rect.y + rect.height - h, rect.width, h)
}

/**
 * [values] stacked from the foot of [box] upward, each taking its share of [total] of the box's
 * height, in the order given. A value of zero is a band of no height, which is how a band leaves
 * a stack: the bands above it close down onto the one below without anything fading.
 */
fun stackedFrom(box: Rectangle, values: List<Double>, total: Double): List<Rectangle> {
    var y = box.y + box.height
    return values.map { v ->
        val h = box.height * (v / total).coerceAtLeast(0.0)
        y -= h
        Rectangle(box.x, y, box.width, h)
    }
}

/**
 * Item [i] of [n] things staggered along one progress [t]: each takes the same share of the
 * run, starting [lag] after the one before, and the last still ends at 1.
 */
fun staggered(t: Double, i: Int, n: Int, lag: Double): Double {
    if (n <= 1) return t.coerceIn(0.0, 1.0)
    // The lag is capped so every item still gets a fifth of the run: nine bullets at 0.15
    // apart made the share negative, and every bullet then read as already there at t = 0.
    val l = minOf(lag, 0.8 / (n - 1))
    val each = 1.0 - (n - 1) * l
    return ((t - i * l) / each).coerceIn(0.0, 1.0)
}

/**
 * A vertical axis up the left of [box]: a tick and a figure every [step] from 0 to [max], the
 * figures ranged right against the tick, in [ink]. [size] is pane pixels, [em] what the face
 * was loaded at.
 */
fun Drawer.axis(
    box: Rectangle, max: Double, step: Double, face: FontImageMap, size: Double, em: Double,
    ink: ColorRGBa, tick: Double = 12.0, gap: Double = 10.0, alpha: Double = 1.0,
    format: (Double) -> String = { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(it).replace('.', ',') }
) {
    if (alpha <= 0.0) return
    var v = 0.0
    while (v <= max + 1e-9) {
        val y = box.y + box.height * (1.0 - v / max)
        stroke = ink.opacify(alpha)
        strokeWeight = 2.0
        lineSegment(Vector2(box.x - tick, y), Vector2(box.x, y))
        stroke = null
        fill = ink.opacify(alpha)
        setLine(format(v), face, Vector2(box.x - tick - gap, y + size * 0.34), size, em, align = 1.0)
        v += step
    }
}

/**
 * A label on a leader: the line grows from the words toward [to] on [alpha], the words fading up
 * on the same number. [lines] are set one under another from [at], ranged by [align]; the leader
 * leaves the words from the side facing [to].
 */
fun Drawer.leaderLabel(
    lines: List<String>, at: Vector2, to: Vector2, face: FontImageMap, size: Double, em: Double,
    ink: ColorRGBa, leading: Double, alpha: Double, align: Double = 0.0, gap: Double = 10.0
) {
    if (alpha <= 0.0 || lines.isEmpty()) return
    fill = ink.opacify(alpha)
    val top = at.y - (lines.size - 1) * leading / 2.0 + size * 0.34
    lines.forEachIndexed { i, line -> setLine(line, face, Vector2(at.x, top + i * leading), size, em, align) }
    val widest = lines.maxOf { face.advanceWithSubscripts(it) } * (size / em)
    val from = Vector2(if (to.x < at.x) at.x - widest * align - gap else at.x + widest * (1.0 - align) + gap, at.y)
    stroke = ink.opacify(alpha)
    strokeWeight = 2.0
    lineSegment(from, from + (to - from) * alpha)
    stroke = null
}

/**
 * [items] as a bulleted list from [at], a square [mark] wide as the bullet, each item wrapped to
 * [measure] and arriving on [shown] of its own — hand `staggered(linear(...), i, n, lag)`, since
 * a list is counted rather than travelled. Returns the height the list took.
 */
fun Drawer.bullets(
    items: List<String>, at: Vector2, measure: Double, face: FontImageMap, size: Double, em: Double,
    leading: Double, ink: ColorRGBa, mark: ColorRGBa, markSize: Double, indent: Double, itemGap: Double,
    shown: (Int) -> Double
): Double {
    var y = at.y
    items.forEachIndexed { i, item ->
        val alpha = shown(i)
        val lines = face.wrapped(item, (measure - indent) * em / size)
        if (alpha > 0.0) {
            stroke = null
            fill = mark.opacify(alpha)
            rectangle(Rectangle(at.x, y - markSize * 0.85, markSize, markSize))
            fill = ink.opacify(alpha)
            lines.forEachIndexed { j, line -> setLine(line, face, Vector2(at.x + indent, y + j * leading), size, em) }
        }
        y += lines.size * leading + itemGap
    }
    return y - at.y
}
