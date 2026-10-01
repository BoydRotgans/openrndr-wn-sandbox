// ============================================================================ //
//  No `package` declaration: it reads the life cycle's own phases (LifePhase,
//  LifeStep), which live beside LifeCycle in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.easeInOutCubic
import slideshow.frames

/**
 * The letters of the life cycle, explained before it is shown: A, B and C, a click each.
 *
 * Asked for at the meeting of 30 September — "say briefly what A, B and C stand for", and then "maybe
 * we should think of a slide that explains ABC". The life cycle sets every box with its code (A1, B3,
 * C4), which is the language of a life cycle analysis and reads as jargon to a room that has never
 * seen one. So it stands before the life cycle: three columns, a big letter in each, what the letter
 * stands for, and the steps it holds.
 *
 * **It is read off the life cycle's own phases**, grouped by the letter their code starts with, so the
 * two slides cannot disagree: rename a step in the show and both follow. A phase with no code (The
 * Circle's column) belongs to no letter and is left out. [meaning] names each letter in the words the
 * room should keep, and falls back to the phases' own names.
 *
 * In the life cycle's colours: the blue column frames, the letters and headings white, the steps in
 * the red boxes' white. Each column rises in on its click, so stepping back takes it away again.
 */
class LifeCycleKey(
    private val title: String = "A, B en C: de fasen van een levenscyclus",
    phases: List<LifePhase>,
    /** What each letter stands for, set under it; a letter not named takes its phases' names. */
    private val meaning: Map<String, String> = emptyMap(),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val frame: ColorRGBa = slideshow.Palette.BLUE,
    private val ink: ColorRGBa = slideshow.Palette.RED,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    /** One letter: the phases whose codes start with it, in the order they come. */
    private class Letter(val letter: String, val heading: String, val steps: List<String>)

    private val letters: List<Letter> = phases
        .filter { it.code.isNotEmpty() }
        .groupBy { it.code.take(1) }
        .map { (letter, group) ->
            Letter(
                letter,
                meaning[letter] ?: group.joinToString(" en ") { it.name.lowercase() }.replaceFirstChar { it.uppercase() },
                group.flatMap { phase -> phase.steps.map { it.name } }
            )
        }

    override val name = "Life cycle key"
    override val steps get() = letters.size.coerceAtLeast(1)
    override fun stepName(step: Int) = letters.getOrNull(step)?.letter

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        drawer.stroke = null
        val w = stage.width
        val h = stage.height

        drawer.fill = ColorRGBa.WHITE
        line(drawer, title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE / SIZE)

        val n = letters.size.coerceAtLeast(1)
        val span = w * (1.0 - 2.0 * MARGIN)
        val width = (span - (n - 1) * w * GAP) / n
        letters.forEachIndexed { i, l ->
            // The first letter stands up with the slide, on its own clock; each one after on its click.
            val shown = if (i == 0) easeInOutCubic(stage.since(0, stepFrames)) else stage.on(i)
            if (shown <= 0.0) return@forEachIndexed
            val rise = (1.0 - shown) * h * 0.03
            val box = Rectangle(w * MARGIN + i * (width + w * GAP), h * TOP + rise, width, h * (BOTTOM - TOP))

            drawer.fill = frame.opacify(shown)
            drawer.rectangle(box)

            // The letter, large, and what it stands for under it.
            drawer.fill = ColorRGBa.WHITE.opacify(shown)
            line(drawer, l.letter, bold, Vector2(box.center.x, box.y + box.height * LETTER_Y), h * LETTER / SIZE)
            wrapped(drawer, l.heading, bold, Rectangle(box.x, box.y + box.height * HEADING_Y, box.width, box.height * 0.12), h * HEADING / SIZE)

            // The steps it holds, each in a red box, as the life cycle sets them.
            val listTop = box.y + box.height * STEPS_Y
            val pad = w * PAD
            val avail = box.y + box.height - pad - listTop
            val gap = h * STEP_GAP
            val each = ((avail - (l.steps.size - 1) * gap) / l.steps.size).coerceAtMost(h * STEP_MAX)
            l.steps.forEachIndexed { k, step ->
                val at = Rectangle(box.x + pad, listTop + k * (each + gap), box.width - 2.0 * pad, each)
                drawer.fill = ink.opacify(shown)
                drawer.rectangle(at)
                drawer.fill = ColorRGBa.WHITE.opacify(shown)
                wrapped(drawer, step, text, at, h * LABEL / SIZE)
            }
        }
    }

    /** [content] broken to fit [box] and centred in it. */
    private fun wrapped(drawer: Drawer, content: String, face: FontImageMap, box: Rectangle, scale: Double) {
        val measure = (box.width * 0.88) / scale
        val lines = mutableListOf<String>()
        var current = ""
        for (word in content.split(" ").filter { it.isNotEmpty() }) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && face.advanceOf(next) > measure) { lines += current; current = word } else current = next
        }
        if (current.isNotEmpty()) lines += current
        val leading = SIZE * LEADING * scale
        var y = box.center.y - (lines.size - 1) * leading / 2.0 + SIZE * 0.34 * scale
        for (l in lines) { line(drawer, l, face, Vector2(box.center.x, y), scale); y += leading }
    }

    /** One line, centred on [at]. */
    private fun line(drawer: Drawer, content: String, face: FontImageMap, at: Vector2, scale: Double) {
        drawer.isolated {
            drawer.fontMap = face
            drawer.translate(at)
            drawer.scale(scale)
            drawer.text(content, -face.advanceOf(content) / 2.0, 0.0)
        }
    }

    private companion object {
        const val SIZE = 150.0
        // The life cycle's own frame: the same margins and gutter, the same band for the title.
        const val MARGIN = 0.0131
        const val GAP = 0.0113
        const val TOP = 0.100
        const val BOTTOM = 0.978
        const val PAD = 0.008
        const val TITLE = 0.038
        const val TITLE_Y = 0.060
        /** The letter's baseline and size, as shares of the column and of the pane's height. */
        const val LETTER_Y = 0.30
        const val LETTER = 0.22
        const val HEADING_Y = 0.33
        const val HEADING = 0.036
        const val STEPS_Y = 0.48
        const val STEP_GAP = 0.012
        const val STEP_MAX = 0.085
        const val LABEL = 0.028
        const val LEADING = 1.25
    }
}
