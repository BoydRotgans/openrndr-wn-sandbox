package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.shape.Rectangle

/**
 * The debug overlay: where the show stands, how long everything takes, and the whole deck
 * at a glance. `d` puts it up and takes it down.
 *
 * It is drawn on the **window**, on top of the finished frame, rather than into the
 * canvas — so it scales with the screen instead of the composition, stays readable at any
 * window size, and never lands in a still or a recording made at the canvas size.
 *
 * Durations are quoted in seconds *and* frames, because frames are what the deck actually
 * runs on and seconds are what a rehearsal is timed in. The table on the right is the one
 * that answers "how long is that reveal, and how long does it take to build back down" —
 * both are a slide's click duration, and a slide sets its own.
 */
class DebugOverlay(private val font: FontImageMap?) {

    fun draw(drawer: Drawer, deck: Deck, stage: Stage, width: Int, height: Int, fps: Double, paused: Boolean) {
        drawer.isolated {
            drawer.stroke = null
            drawer.shadeStyle = null
            font?.let { drawer.fontMap = it }

            panel(drawer, state(deck, stage, fps, paused), PADDING)

            // The deck's timings, at the other end of the frame. Dropped on a window too
            // narrow to hold both rather than overlapping the live readout.
            if (width > 1100) {
                panel(drawer, table(deck), width - PADDING - TABLE, TABLE)
            }

            bottom(drawer, deck, width, height)
        }
    }

    /** Where the show stands this frame, and how long what it is doing takes. */
    private fun state(deck: Deck, stage: Stage, fps: Double, paused: Boolean) = buildList {
        val place = deck.outline[deck.index]

        // where this slide sits in the running order, when it is under a chapter at all
        place?.takeIf { it.path.isNotBlank() }?.let { add("%s  %s".format(it.number, it.path)) }
        add("%d/%d  %s".format(deck.index + 1, deck.count, place?.title ?: deck.slide.name))
        add("step %d/%d   position %.2f".format(stage.step + 1, stage.steps, stage.position))

        // the click being played, named by which way it is going: a click forward reveals,
        // a click back takes it away again
        val name = deck.slide.stepName(deck.movingStep)
            ?: if (deck.direction < 0) "build down" else "reveal"

        add(
            if (deck.direction == 0) "held         %.2fs a click".format(seconds(deck.slide.stepFrames))
            else "%s %.2f / %.2fs   %.0f%%".format(
                name.take(12).padEnd(12),
                seconds(deck.moveElapsed), seconds(deck.moveFrames),
                100.0 * deck.moveElapsed / deck.moveFrames.coerceAtLeast(1)
            )
        )

        if (deck.slide.loop > 0) add(
            "loop         %.2f / %.2fs   cycle %d".format(
                stage.loop * seconds(deck.slide.loop), seconds(deck.slide.loop), stage.cycle
            )
        )

        add(
            "frame %d   %.0f fps%s".format(
                stage.frame, fps,
                if (paused) "   PAUSED" else ""
            )
        )

        if (deck.transitioning) add(
            "%s        %.2f / %.2fs".format(
                deck.transitionName.lowercase().padEnd(10),
                seconds((deck.handover * deck.transitionFrames).toInt()),
                seconds(deck.transitionFrames)
            )
        )

        // whatever the deck file remembers about this slide
        place?.notes?.let { addAll(wrap(it, NOTE_WIDTH, NOTE_LINES)) }
    }

    /** [text] broken on whole words into at most [lines] lines of [width] characters. */
    private fun wrap(text: String, width: Int, lines: Int): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                out += line.toString()
                if (out.size == lines) return out.dropLast(1) + (out.last().take(width - 1) + "…")
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /**
     * Every slide and what it is timed at: clicks, how long one takes, its loop and how it
     * arrives. This is the whole deck's timing on one plate, so a reveal and a build down
     * on different slides can be compared without clicking to either of them.
     */
    private fun table(deck: Deck) = deck.slides.mapIndexed { index, slide ->
        "%s%d %s %s %s %s".format(
            if (index == deck.index) ">" else " ",
            index + 1,
            slide.name.take(9).padEnd(9),
            "%dx".format(slide.steps).padEnd(3),
            "%.2fs".format(seconds(slide.stepFrames)).padEnd(6),
            (if (slide.loop > 0) "loop %.1fs  ".format(seconds(slide.loop)) else "").padEnd(12) +
                    arrival(slide)
        )
    }

    private fun arrival(slide: Slide) = when (val transition = slide.transition) {
        is Cut -> "cut"
        else -> "%s %.2fs".format(transition::class.simpleName?.lowercase(), seconds(transition.length))
    }

    private fun panel(drawer: Drawer, lines: List<String>, x: Double, panelWidth: Double = PANEL) {
        val plate = Rectangle(x, PADDING, panelWidth, LINE * lines.size + 20.0)
        drawer.fill = ColorRGBa.BLACK.opacify(0.85)
        drawer.rectangle(plate)

        if (font == null) return
        drawer.fill = ColorRGBa.WHITE
        lines.forEachIndexed { i, line ->
            drawer.text(line, plate.corner.x + 12.0, plate.corner.y + 24.0 + i * LINE)
        }
    }

    /**
     * The key hints and the deck strip, on a plate of their own: white at half strength is
     * unreadable over a pale slide, and the overlay has to work over any of them.
     */
    private fun bottom(drawer: Drawer, deck: Deck, width: Int, height: Int) {
        val plate = Rectangle(0.0, height - PLATE, width.toDouble(), PLATE)
        drawer.fill = ColorRGBa.BLACK.opacify(0.8)
        drawer.rectangle(plate)

        if (font != null) {
            drawer.fill = ColorRGBa.WHITE.opacify(0.5)
            drawer.text(KEYS, PADDING, plate.corner.y + 22.0)
        }

        // The strip breaks where the deck file breaks: a wider gap at every chapter, so
        // the shape of the talk is visible and not just its length.
        val span = width - 2 * PADDING
        val gaps = DoubleArray(deck.count) { i ->
            when {
                i == 0 -> 0.0
                deck.outline[i]?.chapter != deck.outline[i - 1]?.chapter -> CHAPTER_GAP
                else -> GAP
            }
        }
        val slot = (span - gaps.sum()) / deck.count
        val y = height - PADDING - BAR
        var x = PADDING

        deck.slides.forEachIndexed { index, slide ->
            x += gaps[index]
            val here = index == deck.index

            // clicks inside the slide, filled up to the one showing
            val ticks = slide.steps
            val tickGap = if (ticks > 1) 2.0 else 0.0
            val tick = (slot - tickGap * (ticks - 1)) / ticks
            for (s in 0 until ticks) {
                drawer.fill = when {
                    here && s <= deck.step -> ColorRGBa.WHITE
                    here -> ColorRGBa.WHITE.opacify(0.3)
                    else -> ColorRGBa.WHITE.opacify(0.22)
                }
                drawer.rectangle(x + s * (tick + tickGap), y, tick, BAR)
            }
            x += slot
        }
    }

    private companion object {
        const val PADDING = 16.0
        const val LINE = 18.0
        const val BAR = 6.0
        const val PANEL = 360.0
        const val TABLE = 430.0
        const val GAP = 4.0
        const val CHAPTER_GAP = 22.0

        /** A deck file's notes, wrapped into the panel. */
        const val NOTE_WIDTH = 44
        const val NOTE_LINES = 3

        /** Height of the plate along the bottom: the key hints and the deck strip. */
        const val PLATE = 52.0

        const val KEYS =
            "-> next   <- back   up/down slide   0 first   r replay   p pause   . frame   d debug"
    }
}
