package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.extra.svg.loadSVG
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import slideshow.Backdrop
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames
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

    override fun load(program: Program) {
        pieces = folder.listFiles()
            ?.filter { it.isFile && it.extension.equals("svg", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { read(it) }
            .orEmpty()
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

        drawer.fill = ink
        for ((c, count) in values.withIndex()) {
            val piece = pieces[c % pieces.size]
            val cellWidth = pitch * (1.0 - GUTTER)
            val cellHeight = unit * (1.0 - GAP)
            val fit = min(cellWidth / piece.bounds.width, cellHeight / piece.bounds.height)

            for (k in 0 until count) {
                // Every column rises at once, each a little behind the one to its left.
                val start = frames((k + c * STAGGER) * BEAT)
                val arrived = easeInOutCubic(((stage.frame - start).toDouble() / frames(ARRIVE)).coerceIn(0.0, 1.0))
                if (arrived <= 0.0) continue

                val x = area.x + (c + 0.5) * pitch
                val y = baseline - (k + 0.5) * unit
                drawer.isolated {
                    translate(x, y)
                    scale(fit * arrived)
                    translate(-piece.bounds.center)
                    shapes(piece.shapes)
                }
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
