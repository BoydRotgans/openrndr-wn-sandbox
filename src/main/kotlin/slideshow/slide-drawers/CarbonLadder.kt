package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.color.mix
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.floor
import kotlin.math.min

/** One rung of the ladder: its label, set bold, and what it stands for, set regular. */
class Rung(val label: String, val text: String)

/**
 * The CO₂-prestatieladder as it was and as it is: the five-rung ladder the certificate ran on
 * until 2025, and the three-rung ladder of 2026 it was folded into — with where the Willy
 * Naessens Group stands on each, and a few lines at the top left saying what the audience is
 * looking at. Six states, read off the four Figma frames of the new version:
 *
 *     0  the ladder until 2025, five rungs climbing from bottom-left, built on the slide's clock
 *     1  the group's rung marked: the rungs it holds stay solid, the ones above go to outline
 *     2  five become three: rungs 4 and 5 go, the first three close up into bars
 *     3  the bars merge into the new first rung
 *     4  the new second and third rungs take their steps
 *     5  the group's rung marked on the new ladder, the rungs above it in outline
 *
 * **Every box is a slot, and a slot keeps its box from state to state.** Each state says where
 * each slot stands, what it says and whether it is solid; `position` says which two states the
 * slide is between, and a slot in both travels from the one to the other. So the collapse is the
 * three rungs *becoming* the bars, and the merge is the bars becoming one rung, rather than one
 * picture cut to the next — and clicking back plays it undone. It is `Crowd`'s formations, with
 * rungs for figures. A slot only in the later state arrives by taking its step, as a rung always
 * has on this slide; one only in the earlier state fades.
 *
 * **A rung the group holds is solid and a rung it does not is an outline**, which is the whole
 * of what its position means drawn rather than said: on the old ladder three solid and two open,
 * on the new one one solid and two open — and since the three solid ones are exactly what
 * becomes the new first rung, the audience watches the group's standing carried over rather than
 * lost. The note says it in words, and points.
 *
 * **Text that changes crossfades out and back, never through itself.** A label that stays the
 * same across a move stays put; one that changes is gone by the first third of the click and the
 * new one arrives in the last third, so two paragraphs never overlap and the leaving text is gone
 * before its box has shrunk under it. Each text is wrapped to the box of the state it belongs to,
 * so nothing reflows while a box is moving.
 *
 * **The five-rung ladder is stepped a little wider than the Figma frame**, 0.13 of the pane a run
 * rather than 0.113, so the note fits to the left of the third rung the way the frame fits it to
 * the left of the fifth: at the frame's run there are 479px beside the third rung and the note
 * is 428px wide.
 *
 * The subscript in CO₂ is set rather than asked for — see [setLine] and why Rockwell cannot
 * be asked.
 */
class CarbonLadder(
    private val before: String = "CO₂-prestatieladder tot 2025",
    private val after: String = "CO₂-prestatieladder 2026",
    /** The ladder until 2025, bottom first. */
    private val oldRungs: List<Rung> = listOf(
        Rung("Trede 1:", "De organisatie krijgt inzicht in haar eigen energieverbruik en de belangrijkste mogelijkheden om CO₂ te besparen."),
        Rung("Trede 2:", "De organisatie meet en registreert haar energieverbruik structureel en stelt eerste reductiedoelen vast."),
        Rung("Trede 3:", "De organisatie berekent haar CO₂-uitstoot, voert een concreet reductieplan uit en rapporteert daarover."),
        Rung("Trede 4:", "De organisatie onderzoekt en vermindert ook CO₂-uitstoot bij belangrijke onderdelen van haar keten, zoals materialen, transport en leveranciers."),
        Rung("Trede 5:", "De organisatie neemt een actieve voortrekkersrol in de keten en sector om gezamenlijk substantiële CO₂-reductie te realiseren.")
    ),
    /** The ladder from 2026, bottom first. */
    private val newRungs: List<Rung> = listOf(
        Rung("Trede 1:", "CO₂-reductie in de eigen organisatie"),
        Rung("Trede 2:", "CO₂-reductie in de keten"),
        Rung("Trede 3:", "Naar nul CO₂-uitstoot in 2050")
    ),
    /** How many of the old rungs the new first rung takes in. */
    private val merged: Int = 3,
    /** The rung the group is certified on, counting from 1, on the old ladder and on the new. */
    private val oldLevel: Int = 3,
    private val newLevel: Int = 1,
    private val holder: String = "De Willy Naessens Group",
    /**
     * What is said beside the ladder, one paragraph a state: what the ladder is, what the group's
     * rung means, why five became three, the merge, the new ladder, and the group's rung on it.
     * A blank one keeps the paragraph before it.
     */
    private val ledes: List<String> = listOf(
        "De CO₂-prestatieladder is een certificaat voor hoe ver een bedrijf is met het meten en " +
            "verminderen van zijn CO₂-uitstoot. Hoe hoger de trede, hoe verder dat reikt: van het " +
            "eigen verbruik tot de hele sector.",
        "Trede 3 betekent: de groep berekent haar CO₂-uitstoot, voert een concreet reductieplan " +
            "uit en rapporteert daarover. Treden 4 en 5 reiken verder, naar de keten en de sector.",
        "In 2026 gaat de ladder van vijf naar drie treden. De eerste drie treden – inzicht, meten " +
            "en een reductieplan – gaan samen op in één trede.",
        "Wat eerst de hele onderkant van de ladder was, is nu het vertrekpunt: CO₂-reductie in de " +
            "eigen organisatie.",
        "Daarboven vraagt de ladder reductie in de hele keten, en een pad naar nul CO₂-uitstoot " +
            "in 2050. Eenvoudiger, met één einddoel.",
        "Trede 1 is geen stap terug: alles wat trede 3 vroeg, valt nu onder trede 1. De volgende " +
            "trede is reductie in de keten."
    ),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    /** The rungs, and the lettering on the ground; the lettering on a solid rung is the ground's colour. */
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Ladder"

    override val steps get() = STATES

    /** The old ladder builds a rung at a time before the first click is due. */
    override val settle get() = frames(STAGGER) * (oldRungs.size - 1) + stepFrames

    override fun stepName(step: Int): String? = when (step) {
        1 -> "the certificate, until 2025"
        2 -> "five become three"
        3 -> "the first three merge"
        4 -> "the new ladder"
        5 -> "the certificate, 2026"
        else -> null
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    /** Where one slot stands in one state, what it says, and how solid it is. */
    private class Box(val rect: Rectangle, val label: String, val body: String, val solid: Double = 1.0)

    /** The note: which slot it points at, and its lines. */
    private class Note(val slot: String, val lines: List<String>)

    private class State(val title: String, val lede: String, val boxes: Map<String, Box>, val note: Note?)

    /** The six states, laid out on a pane of [w] by [h]. */
    private fun states(w: Double, h: Double): List<State> {
        /** Rung [k] of a ladder: stepped [run] right and one rung up from the one below. */
        fun rung(k: Int, height: Double, gap: Double, run: Double, width: Double, left: Double, foot: Double): Rectangle {
            val x = w * (left + k * run)
            val y = h * (1.0 - foot) - h * height - k * h * (height + gap)
            return Rectangle(x, y, min(w * width, w * (1.0 - RIGHT) - x), h * height)
        }
        fun old(k: Int) = rung(k, RUNG5, GAP5, RUN5, WIDTH5, LEFT5, FOOT5)
        fun new(k: Int) = rung(k, RUNG, GAP, RUN, WIDTH, LEFT, FOOT)
        val base = new(0)
        fun bar(k: Int): Rectangle {
            val height = (base.height - (merged - 1) * h * BAR_GAP) / merged
            return Rectangle(base.x + k * w * BAR_RUN, base.y + base.height - height - k * (height + h * BAR_GAP), w * BAR_WIDTH, height)
        }

        val note = { level: Int -> listOf(holder, "is gecertificeerd op Trede $level") }
        fun lede(i: Int): String = ledes.getOrNull(i)?.takeIf { it.isNotBlank() } ?: (if (i > 0) lede(i - 1) else "")

        val oldLadder = { held: Boolean ->
            oldRungs.indices.associate { k ->
                "o$k" to Box(old(k), oldRungs[k].label, oldRungs[k].text, if (held && k >= oldLevel) 0.0 else 1.0)
            }
        }
        val bars = (0 until merged).associate { k -> "o$k" to Box(bar(k), oldRungs[k].label, "") }
        val one = (0 until merged).associate { k ->
            "o$k" to if (k == 0) Box(base, newRungs[0].label, newRungs[0].text) else Box(base, "", "")
        }
        val newLadder = { held: Boolean ->
            one + (1 until newRungs.size).associate { k ->
                "n$k" to Box(new(k), newRungs[k].label, newRungs[k].text, if (held && k >= newLevel) 0.0 else 1.0)
            }
        }

        return listOf(
            State(before, lede(0), oldLadder(false), null),
            State(before, lede(1), oldLadder(true), Note("o${oldLevel - 1}", note(oldLevel))),
            State(after, lede(2), bars, null),
            State(after, lede(3), one, null),
            State(after, lede(4), newLadder(false), null),
            State(after, lede(5), newLadder(true), Note(if (newLevel <= 1) "o0" else "n${newLevel - 1}", note(newLevel)))
        )
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        val states = states(w, h)

        val p = stage.position.coerceIn(0.0, (states.size - 1).toDouble())
        val a = floor(p).toInt()
        val b = min(a + 1, states.size - 1)
        val t = p - a
        val from = states[a]
        val to = states[b]

        // Text that changes is gone by the first third of the click and back in the last.
        val out = (1.0 - t / FADE).coerceIn(0.0, 1.0)
        val back = ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0)

        drawer.stroke = null

        // The title and the paragraph under it.
        // The title crossfades straight through: both begin "CO₂-prestatieladder", so only the
        // year is seen to change, where fading out and back would blank the title mid-click.
        crossfade(from.title, to.title, 1.0 - t, t) { line, alpha ->
            drawer.fill = ink.opacify(alpha)
            drawer.setLine(line, bold, Vector2(w * TITLE_X, h * TITLE_Y), h * TITLE, SIZE)
        }
        crossfade(from.lede, to.lede, out, back) { para, alpha ->
            drawer.fill = ink.opacify(alpha)
            paragraph(drawer, para, Vector2(w * TITLE_X, h * LEDE_Y), w * LEDE_W, h)
        }

        // Where every slot stands this frame. The rungs are drawn first and the lettering after,
        // so a slot merging over another cannot cover the text of the one it merges into.
        val placed = mutableListOf<Pair<Rectangle, () -> Unit>>()
        val lettering = mutableListOf<() -> Unit>()

        for (slot in (from.boxes.keys + to.boxes.keys).distinct()) {
            val f = from.boxes[slot]
            val g = to.boxes[slot]
            when {
                f != null && g != null -> {
                    // The old ladder builds itself on the slide's clock, a rung at a time, each taking its step.
                    val built = if (a == 0 && slot.startsWith("o") && stage.step == 0 && p < 1e-6) {
                        val k = slot.drop(1).toInt()
                        smoothstep(stage.since(k * frames(STAGGER), stepFrames))
                    } else 1.0
                    if (built <= 0.0) continue
                    val rect = lerp(f.rect, g.rect, t).movedBy(Vector2(-w * RUN5 * (1.0 - built), 0.0))
                    val solid = f.solid + (g.solid - f.solid) * t
                    placed += rect to { box(drawer, rect, solid, built) }
                    val textInk = mix(ink, background, solid)
                    lettering += {
                        crossfade(f.label, g.label, out, back) { label, alpha ->
                            drawer.fill = textInk.opacify(alpha * built)
                            drawer.setLine(label, bold, Vector2(rect.x + w * INSET, rect.y + h * LABEL_Y), h * TEXT, SIZE)
                        }
                        crossfade(f.body, g.body, out, back) { body, alpha ->
                            drawer.fill = textInk.opacify(alpha * built)
                            val measure = (if (body == g.body) g.rect.width else f.rect.width) - 2 * w * INSET
                            paragraph(drawer, body, Vector2(rect.x + w * INSET, rect.y + h * TEXT_Y), measure, h)
                        }
                    }
                }
                g != null -> {
                    // Arriving: a new rung takes its step, the second and third a little apart.
                    val k = slot.drop(1).toIntOrNull() ?: 0
                    val shown = ((t - (k - 1) * ARRIVE_LAG) / (1.0 - ARRIVE_LAG)).coerceIn(0.0, 1.0)
                    if (shown <= 0.0) continue
                    val rect = g.rect.movedBy(Vector2(-w * RUN * (1.0 - shown), 0.0))
                    placed += rect to { box(drawer, rect, g.solid, shown) }
                    lettering += { letters(drawer, rect, g, shown, w, h) }
                }
                f != null -> {
                    // Leaving: fades where it stands.
                    val shown = (1.0 - t / LEAVE).coerceIn(0.0, 1.0)
                    if (shown <= 0.0) continue
                    placed += f.rect to { box(drawer, f.rect, f.solid, shown) }
                    lettering += { letters(drawer, f.rect, f, shown, w, h) }
                }
            }
        }
        placed.forEach { it.second() }
        lettering.forEach { it() }

        // The note, pointing at the rung the group holds.
        val noteFrom = from.note
        val noteTo = to.note
        if (noteFrom != null && noteTo != null && noteFrom.slot == noteTo.slot && noteFrom.lines == noteTo.lines) {
            note(drawer, noteTo, lerp(from.boxes.getValue(noteFrom.slot).rect, to.boxes.getValue(noteTo.slot).rect, t), 1.0, w, h)
        } else {
            noteFrom?.let { note(drawer, it, from.boxes.getValue(it.slot).rect, out, w, h) }
            noteTo?.let { note(drawer, it, to.boxes.getValue(it.slot).rect, back, w, h) }
        }
    }

    /** [before] fading on [out] and [after] on [back]; once, steady, when they are the same. */
    private inline fun crossfade(before: String, after: String, out: Double, back: Double, draw: (String, Double) -> Unit) {
        if (before == after) {
            if (after.isNotEmpty()) draw(after, 1.0)
            return
        }
        if (before.isNotEmpty() && out > 0.0) draw(before, out)
        if (after.isNotEmpty() && back > 0.0) draw(after, back)
    }

    /** A rung: filled by [solid], always edged, at [alpha]. */
    private fun box(drawer: Drawer, rect: Rectangle, solid: Double, alpha: Double) {
        drawer.fill = ink.opacify(alpha * solid)
        drawer.stroke = ink.opacify(alpha)
        drawer.strokeWeight = EDGE
        drawer.rectangle(rect)
        drawer.stroke = null
    }

    private fun letters(drawer: Drawer, rect: Rectangle, box: Box, alpha: Double, w: Double, h: Double) {
        drawer.fill = mix(ink, background, box.solid).opacify(alpha)
        drawer.setLine(box.label, bold, Vector2(rect.x + w * INSET, rect.y + h * LABEL_Y), h * TEXT, SIZE)
        paragraph(drawer, box.body, Vector2(rect.x + w * INSET, rect.y + h * TEXT_Y), box.rect.width - 2 * w * INSET, h)
    }

    /** [para] wrapped to [measure] pane pixels, its first baseline at [at]. */
    private fun paragraph(drawer: Drawer, para: String, at: Vector2, measure: Double, h: Double) {
        if (para.isEmpty()) return
        val size = h * TEXT
        text.wrapped(para, measure * SIZE / size).forEachIndexed { i, line ->
            drawer.setLine(line, text, Vector2(at.x, at.y + i * h * LEAD), size, SIZE)
        }
    }

    /**
     * The note beside [rect]: to its left where the pane leaves room, top-aligned with it and a
     * hairline from the words to its edge; otherwise to its right, level with its middle. The
     * line grows from the words toward the rung on the same number the words fade up on.
     */
    private fun note(drawer: Drawer, note: Note, rect: Rectangle, alpha: Double, w: Double, h: Double) {
        if (alpha <= 0.0) return
        val size = h * TEXT
        val widest = note.lines.maxOf { text.advanceWithSubscripts(it) } * (size / SIZE)
        val gap = w * NOTE_GAP
        val left = w * NOTE_X + widest + gap + w * NOTE_MIN_LINE <= rect.x

        val x: Double
        val top: Double
        val from: Vector2
        val to: Vector2
        if (left) {
            x = w * NOTE_X
            top = rect.y + h * NOTE_Y
            from = Vector2(x + widest + gap, rect.y + h * NOTE_LINE_Y)
            to = Vector2(rect.x, from.y)
        } else {
            x = rect.x + rect.width + w * NOTE_LEADER + gap
            top = rect.center.y - (note.lines.size - 1) * h * NOTE_LEAD / 2.0 + h * 0.01
            from = Vector2(x - gap, rect.center.y)
            to = Vector2(rect.x + rect.width, from.y)
        }

        drawer.fill = ink.opacify(alpha)
        note.lines.forEachIndexed { i, line ->
            drawer.setLine(line, text, Vector2(x, top + i * h * NOTE_LEAD), size, SIZE)
        }
        drawer.stroke = ink.opacify(alpha)
        drawer.strokeWeight = LINE
        drawer.lineSegment(from, from + (to - from) * alpha)
        drawer.stroke = null
    }

    private fun lerp(a: Rectangle, b: Rectangle, t: Double) = Rectangle(
        a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
        a.width + (b.width - a.width) * t, a.height + (b.height - a.height) * t
    )

    private companion object {
        const val SIZE = 200.0
        const val STATES = 6

        /**
         * The 2026 ladder, measured off the reference: the rungs 0.269 tall with a 0.009 gap,
         * standing 0.148 further right each, the bottom one 0.0245 in from the left and 0.037 up
         * from the foot, and the top one held 0.0135 short of the right.
         */
        const val RUNG = 0.269
        const val GAP = 0.0093
        const val RUN = 0.148
        const val WIDTH = 0.696
        const val LEFT = 0.0245
        const val FOOT = 0.037
        const val RIGHT = 0.0135

        /** The ladder until 2025, off the Figma frame: five rungs 0.184 tall, 0.54 wide — stepped 0.13, see above. */
        const val RUNG5 = 0.184
        const val GAP5 = 0.004
        const val RUN5 = 0.13
        const val WIDTH5 = 0.54
        const val LEFT5 = 0.0235
        const val FOOT5 = 0.033

        /** The bars the first three rungs close up into, stacked inside the new first rung. */
        const val BAR_GAP = 0.0045
        const val BAR_RUN = 0.04
        const val BAR_WIDTH = 0.6

        /** The lettering: the title top-left, the paragraph under it, and on a rung the label over its text. */
        const val TITLE = 0.041
        const val TITLE_X = 0.02
        const val TITLE_Y = 0.055
        const val LEDE_Y = 0.118
        const val LEDE_W = 0.29
        const val TEXT = 0.0287
        const val LEAD = 0.035
        const val INSET = 0.0104
        const val LABEL_Y = 0.045
        const val TEXT_Y = 0.079

        /** The note, to the left of its rung where there is room, to the right where there is not. */
        const val NOTE_X = 0.017
        const val NOTE_Y = 0.023
        const val NOTE_LEAD = 0.035
        const val NOTE_LINE_Y = 0.031
        const val NOTE_GAP = 0.008
        const val NOTE_MIN_LINE = 0.02
        const val NOTE_LEADER = 0.022
        const val LINE = 2.0
        const val EDGE = 2.0

        /** Seconds between the old rungs as they build; the click's share a changing text takes to go, or to come. */
        const val STAGGER = 0.15
        const val FADE = 0.3
        const val LEAVE = 0.6
        /** How far into the click the third new rung starts after the second. */
        const val ARRIVE_LAG = 0.35
    }
}
