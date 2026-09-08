package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import slideshow.Backdrop
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.easeInOutCubic
import slideshow.frames
import kotlin.math.min

/**
 * Who is speaking: a name, the company under it, and the job under that.
 *
 * The card the talk opens on, and it is deliberately the plainest thing in the deck. Every
 * other slide here is made of something — components, a city, a turning globe — and this one
 * is type on a black ground, because the only job it has is to be read once and remembered.
 *
 * **Ranged left, not centred**, which is the one thing that separates it from [QuoteSlide]:
 * a quote is a passage and sits in the middle of its measure, where a name tag is a label and
 * hangs off an edge. They share [advanceOf] and nothing else.
 *
 * **The three fields are stated separately rather than as one line.** Written out as
 * "Erik Koremans - Willy Naessens NEDERLAND" it is one string that has to be broken by hand
 * at whatever size it lands; as three it composes — the name as large as the measure allows,
 * the rest set under it in their own sizes — and a longer name or a reworded title needs
 * nothing in this file changed.
 *
 * **Sizes come off the pane's height and positions off its width**, the rule the whole deck
 * follows, so the same card composes on a 1920 pane and on the whole 3840 wall.
 *
 * It builds on [Stage.frame] rather than on clicks: the presenter is introduced while they are
 * saying their own name, so waiting on a click to finish the sentence would be backwards.
 */
class NameTag(
    /** The line that is read first and remembered. */
    private val presenter: String,
    /** Who they are with. */
    private val organisation: String,
    /** What they do there, set small — it is the longest line and the least urgent. */
    private val role: String,
    private val fontPath: String = "data/fonts/default.otf",
    /** The rule under the name. The house red, where the show hands one in. */
    private val accent: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null
) : Backdrop() {

    override val name = "Name tag"

    private val ink = ColorRGBa.WHITE
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        // One face, scaled per line: three sizes of the same atlas rather than three atlases.
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val inset = stage.width * INSET
        val measure = stage.width - 2.0 * inset

        val big = fit(presenter, stage.height * NAME, measure)
        val mid = fit(organisation, stage.height * ORGANISATION, measure)
        val small = fit(role, stage.height * ROLE, measure)

        // The block is built from the top down and then centred as a whole, so adding a
        // field or resetting a size re-balances it rather than shifting everything by hand.
        val nameHeight = big * SIZE
        val gapToRule = stage.height * 0.055
        val gapAfterRule = stage.height * 0.055
        val gapToRole = stage.height * 0.032
        val block = nameHeight + gapToRule + gapAfterRule +
                mid * SIZE + gapToRole + small * SIZE
        var y = stage.center.y - block / 2.0 + nameHeight

        val arrived = easeInOutCubic(stage.since(0, frames(0.9)))
        drawer.fill = ink.opacify(arrived)
        // A hand's breadth of rise as it comes up, so the card settles rather than appears
        line(drawer, presenter, inset, y - (1.0 - arrived) * stage.height * 0.015, big)

        y += gapToRule
        val drawn = easeInOutCubic(stage.since(frames(0.35), frames(0.7)))
        drawer.stroke = null
        drawer.fill = accent
        drawer.rectangle(inset, y, big * face.advanceOf(presenter) * RULE * drawn, stage.height * WEIGHT)

        y += gapAfterRule
        drawer.fill = ink.opacify(easeInOutCubic(stage.since(frames(0.7), frames(0.6))))
        line(drawer, organisation, inset, y + mid * SIZE, mid)

        y += mid * SIZE + gapToRole
        drawer.fill = ink.opacify(0.62 * easeInOutCubic(stage.since(frames(0.95), frames(0.6))))
        line(drawer, role, inset, y + small * SIZE, small)
    }

    /**
     * The scale that sets [text] at [height], or smaller where that would run past [measure].
     *
     * Measured with [advanceOf] rather than `characterWidth` — the note in `TypeBlock` — so
     * the width this fits against is the width `text()` will actually lay down.
     */
    private fun fit(text: String, height: Double, measure: Double): Double =
        min(height / SIZE, measure / face.advanceOf(text).coerceAtLeast(1.0))

    /** One line, ranged left from [x], sitting on the baseline [y]. */
    private fun line(drawer: Drawer, text: String, x: Double, y: Double, scale: Double) {
        drawer.fontMap = face
        drawer.isolated {
            drawer.translate(Vector2(x, y))
            drawer.scale(scale)
            drawer.text(text, 0.0, 0.0)
        }
    }

    private companion object {
        /** The atlas size everything is scaled down from. Large, so nothing is ever scaled up. */
        const val SIZE = 240.0

        // Sizes as a share of the pane's height, positions as a share of its width.
        const val NAME = 0.095
        const val ORGANISATION = 0.040
        const val ROLE = 0.029
        const val INSET = 0.085
        const val WEIGHT = 0.0035

        /**
         * How far the rule runs, **as a share of the name it sits under** rather than of the
         * measure.
         *
         * Against the measure it would double when the card is stood on the whole wall, where
         * the type does not — the sizes come off the height and only the width changes — and a
         * rule twice the length of the name it belongs to reads as a divider across the frame
         * instead of a mark under a word.
         */
        const val RULE = 0.62
    }
}
