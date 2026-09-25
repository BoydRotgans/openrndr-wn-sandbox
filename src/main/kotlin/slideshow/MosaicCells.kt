package slideshow

import org.openrndr.shape.Rectangle
import kotlin.random.Random

/**
 * The project highlight's grid, as one thing both it and the chapter card deal from: a coarse
 * cell splits at random into quarters, or into two halves side by side or one over the other,
 * down to [THINNEST], each leaf handed to [leaf] as it is reached.
 *
 * It is in `slideshow` rather than beside `PhotoMosaic` because the chapter card is in
 * `slideshow.drawers` and cannot import from the default package; the default package can import
 * from here. The leaves are handed over *during* the walk rather than returned as a list, so a
 * caller that draws its own randoms per leaf (the highlight picks a mark, an order and a stone)
 * consumes the one stream in exactly the order it always did, and its packings stay the same deal.
 */
object MosaicCells {
    /** The coarse cell's shape: `subset_svg`'s median proportion. */
    const val SHAPE = 1.85
    /** The thinnest side a split may leave, in layout units at 1920x1080 a pane. */
    const val THINNEST = 22.0
    /** The joint between cells. */
    const val GAP = 3.0
    /** The chance a cell splits at each depth. */
    val SPLIT = doubleArrayOf(0.8, 0.6, 0.45, 0.3, 0.15)
    /** The share of splits that quarter rather than halve. */
    const val QUARTERS = 0.5

    /** Rows of coarse cells in an area [columns] across, keeping them near [SHAPE]. */
    fun rows(area: Rectangle, columns: Int): Int =
        Math.round(area.height / (area.width / columns / SHAPE)).toInt().coerceAtLeast(2)

    /**
     * [must] forces a split wherever it answers true, down to [THINNEST] — how a picture's edges are
     * traced on the grid, the cells that straddle one going on dividing while the rest stop where
     * chance leaves them; a forced split may go on down to [finest] rather than [THINNEST]. Left
     * out, the stream is drawn exactly as it always was.
     */
    fun split(r: Rectangle, random: Random, depth: Int = 0, must: (Rectangle) -> Boolean = { false },
              finest: Double = THINNEST, leaf: (Rectangle) -> Unit) {
        val chance = SPLIT[depth.coerceAtMost(SPLIT.size - 1)]
        val forced = must(r)
        if (forced || random.nextDouble() < chance) {
            val floor = if (forced) finest else THINNEST
            val halfW = r.width / 2.0
            val halfH = r.height / 2.0
            val kind = random.nextDouble()
            val quarters = minOf(halfW, halfH) >= floor
            val beside = minOf(halfW, r.height) >= floor
            val stacked = minOf(r.width, halfH) >= floor
            fun quartered() = listOf(
                Rectangle(r.x, r.y, halfW, halfH), Rectangle(r.x + halfW, r.y, halfW, halfH),
                Rectangle(r.x, r.y + halfH, halfW, halfH), Rectangle(r.x + halfW, r.y + halfH, halfW, halfH)
            )
            fun besides() = listOf(Rectangle(r.x, r.y, halfW, r.height), Rectangle(r.x + halfW, r.y, halfW, r.height))
            val parts = when {
                kind < QUARTERS && quarters -> quartered()
                kind < QUARTERS + (1.0 - QUARTERS) / 2.0 && beside -> besides()
                stacked -> listOf(Rectangle(r.x, r.y, r.width, halfH), Rectangle(r.x, r.y + halfH, r.width, halfH))
                // A forced split takes whatever split is left, where a chance one gives up: drawn
                // "stacked" on a cell too thin for it, an 80 by 11 cell straddling a stem stopped
                // there and the stem went missing from the D.
                forced && beside -> besides()
                forced && quarters -> quartered()
                else -> emptyList()
            }
            if (parts.isNotEmpty()) { parts.forEach { split(it, random, depth + 1, must, finest, leaf) }; return }
        }
        leaf(r)
    }
}
