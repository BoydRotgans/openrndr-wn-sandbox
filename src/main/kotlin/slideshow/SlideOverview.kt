package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolated
import org.openrndr.draw.loadImage
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.math.abs

/**
 * Every slide of the running order as thumbnails, for jumping about the show from the presenter
 * clicker: the play button pressed twice opens it, forward and back move the pick a slide, and a press
 * of play starts the show at the pick. See the driver in `Show.kt` for the keys.
 *
 * **It reads the way the evening does: a block a chapter, with the walls between them.** Each chapter's
 * slides stand on a panel of their own under the chapter's number and title; the walls of each moment of
 * the evening — the arrival, a course, the exit — stand on one line between the blocks, the moment's
 * name at the head of the line. So the overview is the running order's shape, not a list of it.
 *
 * **It takes the right projector only**, the slide's own pane, and leaves the left as it stands — the
 * chapter card, or the wall's own left half. Anything read stays in one projector.
 *
 * **The thumbnails are the organizer's previews**, `build/previews/<id>-1.png`, the middle of each
 * slide's three frames: the wall as the show composes it, so the picture in the grid is the one the room
 * saw. They are read at startup and again only when a file changes, so opening the overview never waits
 * on a decode; a slide with no preview yet is a plain tile with its title.
 *
 * Drawn on the window after the finished frame, like [HoldRestart] and the grid, so it never reaches a
 * film or a preview, laid out in canvas pixels and mapped through the fit.
 */
class SlideOverview(
    private val folder: File,
    private val header: FontImageMap?,
    private val small: FontImageMap?
) {
    private class Thumb(val stamp: Long, val image: ColorBuffer?)
    private val thumbs = mutableMapOf<String, Thumb>()
    /** How far down the laid-out overview is scrolled, in canvas pixels, eased toward keeping the pick in view. */
    private var scroll = 0.0
    /** Where each slide's thumbnail stood the last time the overview was laid out, from the top of the grid. */
    private var placed: List<Rectangle> = emptyList()

    /** Reads any preview that is new or has changed since it was last read. */
    fun refresh(ids: List<String>) {
        for (id in ids.toSet()) {
            val file = listOf(1, 0, 2).map { File(folder, "$id-$it.png") }.firstOrNull { it.isFile }
            val stamp = file?.lastModified() ?: 0L
            val had = thumbs[id]
            if (had != null && had.stamp == stamp) continue
            had?.image?.destroy()
            val image = file?.let { f ->
                runCatching { loadImage(f) }.getOrNull()?.also {
                    it.generateMipmaps()
                    it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
                }
            }
            thumbs[id] = Thumb(stamp, image)
        }
    }

    /**
     * The slide above or below [pick] ([direction] -1 or 1) in the overview as it is laid out: the one in
     * the nearest row that way, nearest across. Rows are not a fixed length here, so it is read off where
     * the thumbnails stand rather than worked out from a column count.
     */
    fun neighbour(pick: Int, direction: Int): Int {
        val here = placed.getOrNull(pick) ?: return pick
        val rows = placed.indices.filter { i -> if (direction > 0) placed[i].y > here.y + 1.0 else placed[i].y < here.y - 1.0 }
        if (rows.isEmpty()) return pick
        val rowY = if (direction > 0) rows.minOf { placed[it].y } else rows.maxOf { placed[it].y }
        return rows.filter { abs(placed[it].y - rowY) < 1.0 }.minBy { abs(placed[it].center.x - here.center.x) }
    }

    /** One run of slides under one heading: a chapter's block, or the walls of a moment between them. */
    private class Section(val label: String, val wall: Boolean, val slides: List<Int>)

    /**
     * The overview over the right projector. [titles], [groups] and [walls] are per slide, in the running
     * order: its title, the chapter or moment it stands in, and whether that is a moment of walls rather
     * than a chapter. [pick] is the slide chosen, [current] the one on the wall.
     */
    fun draw(
        drawer: Drawer, shown: Rectangle, canvas: Rectangle, panelWidth: Int?,
        ids: List<String>, titles: List<String>, groups: List<String>, walls: List<Boolean>,
        pick: Int, current: Int, alpha: Double, hiddenFrom: Int = ids.size
    ) {
        if (alpha <= 0.0 || ids.isEmpty()) return
        val k = shown.width / canvas.width
        val left = (panelWidth?.toDouble() ?: 0.0).coerceAtMost(canvas.width - 1.0)
        val pane = Rectangle(left, 0.0, canvas.width - left, canvas.height)
        val inner = pane.width - 2.0 * MARGIN
        val cell = (inner - (COLUMNS - 1) * GUTTER) / COLUMNS
        val thumbHeight = cell * THUMB_ASPECT
        val pitch = thumbHeight + LABEL_GAP + LABEL_HEIGHT + ROW_GAP

        // The sections: a new one wherever the chapter or the moment changes.
        val sections = mutableListOf<Section>()
        for (i in ids.indices) {
            val label = groups.getOrElse(i) { "" }
            val wall = walls.getOrElse(i) { true }
            val last = sections.lastOrNull()
            if (last != null && last.label == label && last.wall == wall) (last.slides as MutableList<Int>) += i
            else sections += Section(label, wall, mutableListOf(i))
        }

        // Laid out from the top of the grid down. A chapter is a block: its heading, then its slides a row
        // of COLUMNS at a time, on a panel. A moment's walls stand on a line after the moment's name, which
        // takes the first column.
        val boxes = mutableListOf<Pair<Rectangle, Section>>()
        val headings = mutableListOf<Triple<Double, Double, Section>>()
        val at = MutableList(ids.size) { Rectangle(0.0, 0.0, 0.0, 0.0) }
        var y = 0.0
        for (section in sections) {
            if (section.wall) {
                val per = COLUMNS - 1
                headings += Triple(pane.x + MARGIN, y + thumbHeight / 2.0, section)
                section.slides.forEachIndexed { n, i ->
                    at[i] = Rectangle(pane.x + MARGIN + (1 + n % per) * (cell + GUTTER), y + (n / per) * pitch, cell, thumbHeight)
                }
                y += ((section.slides.size + per - 1) / per) * pitch + SECTION_GAP
            } else {
                val top = y
                headings += Triple(pane.x + MARGIN, y + CHAPTER_HEAD * 0.62, section)
                y += CHAPTER_HEAD
                section.slides.forEachIndexed { n, i ->
                    at[i] = Rectangle(pane.x + MARGIN + (n % COLUMNS) * (cell + GUTTER), y + (n / COLUMNS) * pitch, cell, thumbHeight)
                }
                y += ((section.slides.size + COLUMNS - 1) / COLUMNS) * pitch - ROW_GAP + BOX_PAD
                boxes += Rectangle(pane.x + MARGIN - BOX_PAD, top, inner + 2.0 * BOX_PAD, y - top) to section
                y += SECTION_GAP
            }
        }
        placed = at
        val total = y

        // Scrolled so the pick stands in the middle of what can be seen, as far as the ends allow.
        val gridTop = HEADER
        val view = pane.height - gridTop - MARGIN / 2.0
        val pickY = at.getOrNull(pick)?.y ?: 0.0
        val target = (pickY - (view - pitch) / 2.0).coerceIn(0.0, maxOf(0.0, total - view))
        scroll += (target - scroll) * 0.25
        if (abs(target - scroll) < 0.5) scroll = target
        fun screen(yy: Double) = gridTop + yy - scroll

        drawer.isolated {
            drawer.translate(shown.corner)
            drawer.scale(k)
            drawer.shadeStyle = null
            drawer.stroke = null
            // Opaque: at 0.94 the wall behind showed through as grey shapes among the thumbnails.
            drawer.fill = ColorRGBa.fromHex("0B0B0D").opacify(alpha)
            drawer.rectangle(pane)
            // The grid is cut off a little above its first row, so a row scrolled up goes out of sight under
            // the header rather than over it.
            val gridClip = gridTop - GRID_CLEAR
            drawer.drawStyle.clip = Rectangle(shown.corner.x + pane.x * k, shown.corner.y + gridClip * k, pane.width * k, (pane.height - gridClip) * k)

            // The chapters' panels, under their slides; the sketches past the end of the evening on a
            // panel of another tone, since nothing on it is part of the show.
            for ((box, section) in boxes) {
                val top = screen(box.y)
                if (top > pane.height || top + box.height < gridClip) continue
                drawer.fill = ColorRGBa.fromHex(if (section.slides.first() >= hiddenFrom) "26141A" else "1A1B1F").opacify(alpha)
                drawer.rectangle(box.x, top, box.width, box.height)
            }
            // The headings: a chapter's number and title over its block, a moment's name at the head of its line.
            small?.let { s ->
                drawer.fontMap = s
                for ((x, hy, section) in headings) {
                    val sy = screen(hy)
                    if (sy < gridClip - 40.0 || sy > pane.height + 40.0) continue
                    val holds = pick in section.slides
                    drawer.fill = ColorRGBa.WHITE.opacify(alpha * when {
                        holds -> 1.0
                        section.wall -> 0.45
                        else -> 0.8
                    })
                    val text = section.label.ifBlank { "—" }.uppercase()
                    drawer.text(fit(s, text, if (section.wall) cell else inner), x, sy + if (section.wall) SMALL_SIZE * 0.35 else 0.0)
                }
            }

            for (i in ids.indices) {
                val a = at[i]
                val sy = screen(a.y)
                if (sy > pane.height || sy + pitch < gridClip) continue
                val box = Rectangle(a.x, sy, a.width, a.height)
                val image = thumbs[ids[i]]?.image
                drawer.stroke = null
                if (image != null) {
                    // Dimmed under a veil rather than tinted: every tile but the pick stands back a little.
                    drawer.image(image, box.x, box.y, box.width, box.height)
                    drawer.fill = ColorRGBa.BLACK.opacify(1.0 - alpha * if (i == pick) 1.0 else 0.72)
                    drawer.rectangle(box)
                } else {
                    drawer.fill = ColorRGBa.fromHex("26272B").opacify(alpha)
                    drawer.rectangle(box)
                }
                drawer.fill = null
                when (i) {
                    pick -> { drawer.stroke = Palette.RED.opacify(alpha); drawer.strokeWeight = 8.0; drawer.rectangle(box.offsetEdges(4.0)) }
                    current -> { drawer.stroke = ColorRGBa.WHITE.opacify(0.8 * alpha); drawer.strokeWeight = 3.0; drawer.rectangle(box.offsetEdges(2.0)) }
                }
                small?.let { s ->
                    drawer.fontMap = s
                    drawer.stroke = null
                    drawer.fill = ColorRGBa.WHITE.opacify(alpha * if (i == pick) 1.0 else 0.6)
                    drawer.text(fit(s, "${i + 1}  ${titles.getOrElse(i) { "" }}", cell), box.x, box.y + thumbHeight + LABEL_GAP + LABEL_HEIGHT * 0.8)
                }
            }

            // The pick, large, above the grid: where in the evening it stands, its number and its title —
            // drawn after the grid, which is cut off below it, so no row scrolled up can cover it.
            drawer.drawStyle.clip = Rectangle(shown.corner.x + pane.x * k, shown.corner.y + pane.y * k, pane.width * k, pane.height * k)
            drawer.stroke = null
            header?.let { f ->
                small?.let { s ->
                    drawer.fontMap = s
                    drawer.fill = ColorRGBa.WHITE.opacify(0.55 * alpha)
                    drawer.text(groups.getOrElse(pick) { "" }.uppercase(), pane.x + MARGIN, 70.0)
                    val hint = "vooruit / terug: kiezen      play: starten      scherm: sluiten      voorbij de laatste dia: alle schetsen"
                    drawer.text(hint, pane.x + pane.width - MARGIN - width(s, hint), 70.0)
                }
                drawer.fontMap = f
                drawer.fill = ColorRGBa.WHITE.opacify(alpha)
                val title = "${pick + 1}  ${titles.getOrElse(pick) { "" }}"
                drawer.text(fit(f, title, inner), pane.x + MARGIN, 140.0)
            }
        }
    }

    /** The width of a line as the drawer will set it: the glyphs' advances, not their boxes. */
    private fun width(f: FontImageMap, s: String) = s.sumOf { f.glyphMetrics[it]?.advanceWidth ?: 0.0 }

    /** [s] cut to [room], with an ellipsis where the face has one. */
    private fun fit(f: FontImageMap, s: String, room: Double): String {
        if (width(f, s) <= room) return s
        val dots = if (f.glyphMetrics.containsKey('…')) "…" else "..."
        var cut = s
        while (cut.isNotEmpty() && width(f, cut + dots) > room) cut = cut.dropLast(1)
        return cut.trimEnd() + dots
    }

    companion object {
        /** How many thumbnails a chapter's row holds; a moment's line holds one fewer, after its name. */
        const val COLUMNS = 5
        const val MARGIN = 60.0
        const val GUTTER = 24.0
        const val HEADER = 200.0
        /** How far above the first row the grid is cut off: room for the pick's red frame. */
        const val GRID_CLEAR = 14.0
        const val LABEL_GAP = 8.0
        const val LABEL_HEIGHT = 26.0
        const val ROW_GAP = 22.0
        /** A chapter's heading, over its block; its panel reaches this far round its slides; and the gap between sections. */
        const val CHAPTER_HEAD = 52.0
        const val BOX_PAD = 16.0
        const val SECTION_GAP = 26.0
        /** A preview is the whole wall, 3840 by 1080. */
        const val THUMB_ASPECT = 1080.0 / 3840.0
        /** The heading of the sketches at the foot, which stand outside the running order. */
        const val SHELF = "Schetsen en wanden · niet in de presentatie"
        const val HEADER_SIZE = 52.0
        const val SMALL_SIZE = 22.0
    }
}
