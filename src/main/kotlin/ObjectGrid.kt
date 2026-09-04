import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * The catalogue beat: the elements the camera has come to rest on leaving the plan they
 * were packed into and standing in a grid.
 *
 * The city is a bin-packing — an element sits where its building had room for it, at
 * whatever angle and spacing the plan happened to allow. This is the same set of elements
 * read the other way round: off the map and into rows, which is what a catalogue is. The
 * shot ends on the components rather than on the town, and nothing is added or taken away
 * to get there — every element in the grid was already standing on the ground it came from.
 *
 * Like `packBoxes` in demo01 and `stateAt` in Decision, the layout is a pure function of
 * one number: [gridSlots] maps a frame and a count to every position, with no time and no
 * state in it. The sketch only interpolates from where an element is to where this says it
 * belongs, so a change to the arrangement keeps animating for free.
 */

/** One element on its way from the plan to the grid. */
class GridMove(
    /** Which building it was packed onto, so it can still follow that building's reveal. */
    val building: Int,
    val placement: ObjectPlacement,
    val home: Vector2,
    val slot: Vector2,
    /**
     * How much of its packed size it keeps once it is in the grid.
     *
     * Never above 1: an element that already fits its cell is left at the size the plan
     * gave it, so the grid keeps the range of sizes that says these are real components
     * rather than one part repeated. Only the ones that would overrun are brought down,
     * and they have to be — the catalogue elements run from a 5 cm plate to a 24 m beam,
     * and at one common cell the beams otherwise run straight into their neighbours and a
     * corner of the grid reads as a single white mass.
     */
    val scale: Double
)

/** The arrangement itself: where the cells are and how big one is. */
class Grid(val slots: List<Vector2>, val cellWidth: Double, val cellHeight: Double)

/**
 * [count] positions filling [frame], row-major, as near square a cell as the frame allows.
 *
 * The column count is chosen from the frame's own proportion rather than fixed, so the
 * cells stay square-ish whatever shape the view is: on a 16:9 frame `sqrt(count * 16/9)`
 * columns is the arrangement whose cells are closest to square, and a square cell is what
 * makes a field of elements of very different proportions read as one grid.
 */
fun gridSlots(frame: Rectangle, count: Int): Grid {
    if (count <= 0) return Grid(emptyList(), frame.width, frame.height)
    val columns = Math.round(Math.sqrt(count * frame.width / frame.height))
        .toInt().coerceIn(1, count)
    val rows = (count + columns - 1) / columns
    val cellWidth = frame.width / columns
    val cellHeight = frame.height / rows

    // The last row is usually short, so it is centred rather than left hanging off the end.
    val slots = (0 until count).map { i ->
        val row = i / columns
        val column = i % columns
        val inRow = minOf(columns, count - row * columns)
        val indent = (columns - inRow) * cellWidth / 2.0
        Vector2(
            frame.corner.x + indent + (column + 0.5) * cellWidth,
            // rows fill from the top of the frame down, and y grows north on the map
            frame.corner.y + frame.height - (row + 0.5) * cellHeight
        )
    }
    return Grid(slots, cellWidth, cellHeight)
}

/**
 * Which element goes to which slot, so that **the total distance travelled is as small as
 * it can be** — the same rule Decision uses to pair its two keyframes, and for the same
 * reason: it is what keeps the arrangement recognisable across the move. An element ends
 * up in the part of the grid it was already standing in rather than crossing the frame,
 * so the field reads as the plan tidying itself up instead of as everything scattering
 * and re-forming.
 *
 * Repeatedly taking the shortest remaining pair, and then **swapping any two whose ends are
 * better off exchanged** until no swap improves the total. The second half is not a polish:
 * taking the shortest pair each time is greedy, so it spends the good slots early and leaves
 * the last few elements to match up with whatever is left over, wherever that is. Measured
 * on the closing frame, greedy alone moved one element 270 m — 95% of the frame's width,
 * against a 43 m average — which is exactly the element that reads as flying across the
 * picture while everything else settles.
 *
 * Returns, for each home, the index of its slot.
 */
fun assignByTravel(homes: List<Vector2>, slots: List<Vector2>): IntArray {
    val assignment = IntArray(homes.size) { -1 }
    if (homes.isEmpty() || slots.isEmpty()) return assignment

    val pairs = ArrayList<Triple<Double, Int, Int>>(homes.size * slots.size)
    homes.forEachIndexed { h, home ->
        slots.forEachIndexed { s, slot -> pairs += Triple(home.squaredDistanceTo(slot), h, s) }
    }
    pairs.sortBy { it.first }

    val homeTaken = BooleanArray(homes.size)
    val slotTaken = BooleanArray(slots.size)
    var placed = 0
    for ((_, h, s) in pairs) {
        if (homeTaken[h] || slotTaken[s]) continue
        assignment[h] = s
        homeTaken[h] = true; slotTaken[s] = true
        if (++placed == minOf(homes.size, slots.size)) break
    }

    // Exchange any two whose ends are better off swapped, until nothing improves. Each pass
    // is n^2 on a list of the elements in one frame, and it converges in a handful of them.
    val assigned = homes.indices.filter { assignment[it] >= 0 }
    var improved = true
    var passes = 0
    while (improved && passes++ < 20) {
        improved = false
        for (a in assigned.indices) for (b in a + 1 until assigned.size) {
            val i = assigned[a]; val j = assigned[b]
            val si = assignment[i]; val sj = assignment[j]
            val now = homes[i].squaredDistanceTo(slots[si]) + homes[j].squaredDistanceTo(slots[sj])
            val swapped = homes[i].squaredDistanceTo(slots[sj]) + homes[j].squaredDistanceTo(slots[si])
            if (swapped < now) {
                assignment[i] = sj; assignment[j] = si
                improved = true
            }
        }
    }
    return assignment
}

/**
 * The elements standing inside [frame], turned into the moves that carry them into a grid
 * filling that same frame.
 *
 * [frame] is the view the camera comes to rest on, so the elements that take part are
 * exactly the ones on screen at the end of the push — the grid forms out of what is
 * already there, and nothing arrives from off frame to join it.
 *
 * [limit] caps how many take part. A wide final view can hold thousands of elements, and a
 * grid of thousands is a texture rather than a catalogue; past the cap the ones nearest the
 * middle of the frame are kept, so the grid forms around what the camera is looking at.
 */
fun gridMoves(
    placements: List<List<ObjectPlacement>>,
    templates: List<ObjectTemplate>,
    frame: Rectangle,
    limit: Int,
    /** How much of a cell an element may fill, leaving the rest as the gap around it. */
    fill: Double = 0.8
): List<GridMove> {
    // Everything the frame *touches*, not everything centred in it. An element whose centre
    // falls just outside still has its body inside, and testing the centre leaves those
    // behind on the plan: they stay standing where the grid has cleared, as slivers along
    // all four edges. Taking whatever overlaps means that by construction nothing of the
    // city is left inside the frame once these have moved.
    val inside = ArrayList<Pair<Int, ObjectPlacement>>()
    val right = frame.corner.x + frame.width
    val top = frame.corner.y + frame.height
    placements.forEachIndexed { building, elements ->
        elements.forEach {
            val halfHeight = it.height / 2
            val halfWidth = halfHeight * templates[it.template].aspect
            if (it.centre.x + halfWidth > frame.corner.x && it.centre.x - halfWidth < right &&
                it.centre.y + halfHeight > frame.corner.y && it.centre.y - halfHeight < top)
                inside += building to it
        }
    }
    if (inside.isEmpty()) return emptyList()

    val centre = frame.center
    val taking = if (inside.size <= limit) inside
                 else inside.sortedBy { it.second.centre.squaredDistanceTo(centre) }.take(limit)

    val homes = taking.map { it.second.centre }
    val grid = gridSlots(frame, homes.size)
    val assignment = assignByTravel(homes, grid.slots)

    return taking.mapIndexedNotNull { i, (building, placement) ->
        val slot = assignment[i]
        if (slot < 0) return@mapIndexedNotNull null
        val height = placement.height
        val width = height * templates[placement.template].aspect
        val scale = minOf(1.0, grid.cellWidth * fill / width, grid.cellHeight * fill / height)
        GridMove(building, placement, homes[i], grid.slots[slot], scale)
    }
}

/**
 * The order the grid is taken apart in at the end, closing on one element.
 *
 * The survivor is whichever slot sits nearest the middle of the frame, and it is chosen by
 * *slot* rather than by where the element came from: by this point the grid is what is on
 * screen, and the one in the middle of it is the one the eye is already on.
 *
 * Returns a removal rank per move — the survivor gets -1 and never goes, the rest get
 * 0, 1, 2 ... in the order they leave.
 *
 * `far` empties the frame from the outside in, so what is left contracts onto the survivor
 * and the last thing to happen is the closest thing to it going. `near` clears its
 * neighbours first, which leaves a ring standing and reads as a hole opening rather than as
 * a closing in. `file` and `random` are also there.
 */
fun cullOrder(moves: List<GridMove>, frame: Rectangle, order: String = "far"): IntArray {
    val ranks = IntArray(moves.size) { -1 }
    if (moves.size <= 1) return ranks

    val centre = frame.center
    val survivor = moves.indices.minByOrNull { moves[it].slot.squaredDistanceTo(centre) }!!
    val others = moves.indices.filter { it != survivor }

    val leaving = when (order.lowercase()) {
        "near" -> others.sortedBy { moves[it].slot.squaredDistanceTo(centre) }
        "random" -> others.shuffled(kotlin.random.Random(4021))
        "file" -> others
        else -> others.sortedByDescending { moves[it].slot.squaredDistanceTo(centre) }
    }
    leaving.forEachIndexed { rank, move -> ranks[move] = rank }
    return ranks
}
