package slideshow

import org.openrndr.math.Vector2

/**
 * Principle 8 — re-form: the slots persist.
 *
 * When one picture becomes another, the things in it keep their identity. A slot that is in both
 * formations travels from its old place to its new one over the first [travel] of the change; a
 * slot only in the old one leaves over that same share; a slot only in the new one stands up
 * once the travelling is mostly done. So a formation grows out of the figures already there
 * rather than being dealt over them — the crowd's rule, and the ladder's, written once.
 *
 * [where] answers for one change: the old formation, the new one, and how far between them,
 * 0..1 on the click's eased number. Places are matched by index, so a caller that wants the
 * nearest place for each thing orders the new formation with [nearest] first.
 */
object Slots {
    /** One slot as it stands: where, how present (0 gone, 1 standing), and whether it is new. */
    class Slot(val at: Vector2, val presence: Double, val arriving: Boolean, val leaving: Boolean)

    fun where(from: List<Vector2>, to: List<Vector2>, t: Double, travel: Double = TRAVEL): List<Slot> {
        val moved = smoothstep((t / travel).coerceIn(0.0, 1.0))
        val stood = smoothstep(((t - travel) / (1.0 - travel)).coerceIn(0.0, 1.0))
        val n = maxOf(from.size, to.size)
        return (0 until n).map { i ->
            val a = from.getOrNull(i)
            val b = to.getOrNull(i)
            when {
                a != null && b != null -> Slot(a + (b - a) * moved, 1.0, arriving = false, leaving = false)
                a != null -> Slot(a, 1.0 - moved, arriving = false, leaving = true)
                else -> Slot(b!!, stood, arriving = true, leaving = false)
            }
        }
    }

    /**
     * The new places reordered so the i-th old place goes to the free new place nearest it,
     * greedy in the order given; new places left over follow, nearest the [centre] first, so
     * newcomers stand up in the middle and the formation grows outwards.
     */
    fun nearest(from: List<Vector2>, to: List<Vector2>, centre: Vector2 = Vector2.ZERO): List<Vector2> {
        val free = to.toMutableList()
        val ordered = mutableListOf<Vector2>()
        for (a in from) {
            val pick = free.minByOrNull { (it - a).squaredLength } ?: break
            free.remove(pick)
            ordered += pick
        }
        ordered += free.sortedBy { (it - centre).squaredLength }
        return ordered
    }

    /** The share of a change spent travelling before newcomers stand up: the guide's ≈ 60%. */
    const val TRAVEL = 0.6
}
