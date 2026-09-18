package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.extra.svg.loadSVG
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import slideshow.Arrival
import slideshow.Backdrop
import slideshow.MidiTimed
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.ramp
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A pictogram chart on a wall that goes from [from] to [to] and back over [period] seconds:
 * a column per value, each one a stack of a single precast piece repeated, so a bar is read by
 * counting the pieces in it.
 *
 * **The pieces are a folder, one svg to a piece** — `data/svg/subset_svg`, which
 * `tools/split_sheet.py` cuts out of `subset.svg`. They are read whole, as they stand, in
 * filename order, so column n stands piece n and nothing about the sheet has to be inferred.
 * This file is in a package, and `loadMarkTemplates` is in the default package where it cannot
 * be imported from, so it reads the folder itself; it keeps the shapes rather than triangulating
 * them, since a few hundred placements a frame is nothing to draw.
 *
 * **One value is one piece, and every piece in a stack takes the same unit of height**, fitted
 * into it by its own proportion. Stacked at their own heights a bar of slabs and a bar of tall
 * panels with the same count would stand at different heights, and the chart would be wrong.
 *
 * The bars build on [Stage.frame] and the colour runs on [Stage.loop], so both are functions of
 * the frame and it scrubs, replays and films like every other wall.
 *
 * **When each element arrives is a list, not an expression buried in the draw loop.** [reveals]
 * is the whole build — every element, the column and row it lands in, and the frame it starts on
 * — as a pure function of [values], and `draw` reads it rather than working the timing out again
 * as it goes. That is what lets the same schedule be exported without a second copy of the
 * arithmetic to keep in step. That schedule is what [MidiTimed] asks for, so the wall can be
 * written down as timing — a note per element, on the frame it starts to arrive — by the
 * organizer or by `PlainMidi.kt`, with no second copy of the arithmetic anywhere.
 */
class PlainScene(
    override val name: String = "Plain",
    /** The folder of svgs, one piece a file. */
    private val folder: File,
    /** How many pieces each column stacks. Columns take the pieces in turn and repeat. */
    private val values: List<Int> = listOf(3, 5, 8, 4, 9, 6, 2, 7, 10, 5, 3, 8, 6, 4, 9),
    private val from: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val to: ColorRGBa = ColorRGBa.fromHex("1E3A72"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    /** Seconds for red to blue and back. */
    period: Double = 8.0,
    override val sound: slideshow.Sound? = null
) : Backdrop() {

    override val loop = frames(period)

    private class Piece(val shapes: List<Shape>, val bounds: Rectangle)

    private var pieces = emptyList<Piece>()

    /** One element arriving: where it lands, the frame it starts on and how long it takes. */
    data class Reveal(val column: Int, val row: Int, val start: Int, val length: Int)

    /**
     * Every element the wall stands, in the order they land — the whole build, from the counts
     * alone. It needs no pieces loaded, so it can be read before there is a window.
     */
    val reveals: List<Reveal> = values.flatMapIndexed { c, count ->
        (0 until count).map { k ->
            Reveal(column = c, row = k, start = frames((k + c * STAGGER) * BEAT), length = frames(ARRIVE))
        }
    }.sortedBy { it.start }

    /** The svgs the columns take in turn, in the order they take them. */
    fun files(): List<File> = folder.listFiles()
        ?.filter { it.isFile && it.extension.equals("svg", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()

    // --- the wall as timing ------------------------------------------------------------ //
    //
    // A lane is a column, named after the piece it stands, and an element's place up its stack
    // is its index — so a bar rises as a run and the file reads as the chart on its side. Both
    // come off `reveals` and the folder, so nothing here is a second copy of the schedule.

    override val lanes: List<String>
        get() = files().let { files ->
            values.indices.map { c ->
                if (files.isEmpty()) "col %02d".format(c + 1)
                else "col %02d - %s".format(c + 1, files[c % files.size].nameWithoutExtension)
            }
        }

    // The wall builds on its own clock from the frame it comes up, so the clicks say nothing
    // about it — there is only one, and it is the wall arriving.
    override fun arrivals(clicks: List<Int>): List<Arrival> =
        reveals.map { Arrival(lane = it.column, index = it.row, start = it.start, length = it.length) }

    override fun load(program: Program) {
        pieces = files().mapNotNull { read(it) }
        // A missing folder leaves the wall bare rather than stopping the show.
        println("$name: ${pieces.size} pieces from $folder")
    }

    private fun read(file: File): Piece? {
        val shapes = loadSVG(file).findShapes().map { it.effectiveShape }.filter { !it.empty }
        if (shapes.isEmpty()) return null
        val boxes = shapes.map { it.bounds }
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.x + it.width }
        val bottom = boxes.maxOf { it.y + it.height }
        return Piece(shapes, Rectangle(left, top, right - left, bottom - top))
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        drawer.stroke = null
        drawer.fill = from.mix(to, 0.5 - 0.5 * cos(2.0 * PI * stage.loop))
        drawer.rectangle(stage.bounds)

        if (pieces.isEmpty() || values.isEmpty()) return

        // Sizes off the height and positions off the width, the rule the deck lays out by.
        val margin = stage.height * MARGIN
        val area = Rectangle(margin, margin, stage.width - 2.0 * margin, stage.height - 2.0 * margin)
        val pitch = area.width / values.size
        val unit = area.height / max(1, values.max())
        val baseline = area.y + area.height

        // One fit a column: every piece in a stack is the same drawing at the same size.
        val cellWidth = pitch * (1.0 - GUTTER)
        val cellHeight = unit * (1.0 - GAP)
        val fits = DoubleArray(values.size) { c ->
            val piece = pieces[c % pieces.size]
            min(cellWidth / piece.bounds.width, cellHeight / piece.bounds.height)
        }

        drawer.fill = ink
        // Every column rises at once, each a little behind the one to its left — which is
        // `reveals` and not anything worked out here.
        for (r in reveals) {
            val arrived = easeInOutCubic(ramp(stage.frame - r.start, r.length))
            if (arrived <= 0.0) continue

            val piece = pieces[r.column % pieces.size]
            val x = area.x + (r.column + 0.5) * pitch
            val y = baseline - (r.row + 0.5) * unit
            drawer.isolated {
                translate(x, y)
                scale(fits[r.column] * arrived)
                translate(-piece.bounds.center)
                shapes(piece.shapes)
            }
        }
    }

    private companion object {
        /** Of the wall's height, on every side. */
        const val MARGIN = 0.08
        /** Of a column's width, left clear between columns. */
        const val GUTTER = 0.22
        /** Of a unit's height, left clear between stacked pieces. */
        const val GAP = 0.18
        /** Seconds between one piece in a stack and the next landing on it. */
        const val BEAT = 0.12
        /** Beats each column starts behind the one to its left. */
        const val STAGGER = 0.5
        /** Seconds a piece takes to grow to its size. */
        const val ARRIVE = 0.35
    }
}
