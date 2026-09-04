import kotlinx.serialization.json.JsonObject
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.triangulate
import java.io.File
import kotlin.random.Random

/**
 * The city rebuilt out of the catalogue.
 *
 * Every building on the map is replaced by Willy Naessens objects packed into the ground
 * it actually stands on: the footprint is used as the bin, not as the drawing. A house
 * takes one or two elements, a warehouse takes dozens, and because the objects are packed
 * at a real size in metres the count is not decoration — it is roughly how many precast
 * pieces that building's plan would take. The town then reads as a field of components
 * whose density is the density of what is really built there.
 *
 * The objects come from `data/svg/objects-iso.svg` through demo02's `loadObjectSheet`,
 * which recovers them from a flat Figma export — see the demo02 notes in CLAUDE.md.
 */

/**
 * One catalogue object, triangulated once and normalised: height 1, width [aspect],
 * centred on the origin, y pointing up. Placing it anywhere is then a scale and an offset,
 * so 115 objects are triangulated at load and never again however many times they appear.
 */
class ObjectTemplate(val triangles: List<Vector2>, val aspect: Double)

/**
 * One catalogue element standing on the map: which template it is, where its centre
 * landed, and how tall it was drawn.
 *
 * The packing works this out for every element anyway and used to throw it away on the way
 * to triangles. Keeping it is what lets an element be treated as a thing in its own right
 * rather than as anonymous geometry — moved on its own, counted, or laid out somewhere
 * else — without packing the city twice.
 */
class ObjectPlacement(val template: Int, val centre: Vector2, val height: Double)

/** The world-space triangles this placement draws as. */
fun ObjectPlacement.triangles(templates: List<ObjectTemplate>): List<Vector2> =
    templates[template].triangles.map { Vector2(centre.x + it.x * height, centre.y + it.y * height) }

fun loadObjectTemplates(file: File): List<ObjectTemplate> =
    loadObjectSheet(file).mapNotNull { sheetObject ->
        val bounds = sheetObject.bounds
        if (bounds.width <= 0.0 || bounds.height <= 0.0) return@mapNotNull null

        val scale = 1.0 / bounds.height
        val centre = bounds.center
        val triangles = sheetObject.shapes.flatMap { triangulate(it) }.map { point ->
            // svg y grows downward and the map's does not, so the object is flipped here
            // once rather than at every one of its placements
            Vector2((point.x - centre.x) * scale, -(point.y - centre.y) * scale)
        }
        if (triangles.isEmpty()) null else ObjectTemplate(triangles, bounds.width / bounds.height)
    }

/**
 * Objects packed onto every building, as one list of world-space triangles per building so
 * the reveal can still bring them in a building at a time.
 *
 * [objectSize] is the height of an object on the ground, in metres. It is the one control
 * that matters: it decides both how many objects the map holds and how coarse the grain is.
 */
fun objectPlacements(
    buildings: List<JsonObject>,
    templates: List<ObjectTemplate>,
    objectSize: Double = 8.0,
    gap: Double = 1.0,
    maxPerBuilding: Int = 48,
    /**
     * A floor on how small an element may be drawn, in metres. Zero leaves elements at
     * true size. Above zero they stop being ground objects and become glyphs: sized so
     * they still read at the end of a pull-back, which over twenty kilometres means
     * hundreds of metres across. Proportion between them is kept — a building four times
     * the size still gets an element up to four times larger — but the map is then a
     * field of marks whose density follows the buildings, not one element per plan.
     */
    minSize: Double = 0.0,
    /**
     * Snap every element to a square lattice of this many metres, one element to a cell.
     * Zero packs them freely against their plans instead.
     *
     * On a grid the plan stops deciding *where* an element goes and decides only whether a
     * cell is built on at all, so the map becomes a regular field whose occupancy is the
     * city. Nothing can touch, because a cell holds one element and every element is held
     * inside its cell.
     */
    gridSize: Double = 0.0,
    /**
     * The point the lattice is aligned on — a cell centre falls exactly here. Aligning it
     * to where the camera is held means the building nearest the centre lands dead centre
     * rather than up to half a cell off it.
     */
    gridOrigin: Vector2 = Vector2.ZERO
): List<List<ObjectPlacement>> {
    // Shared across every building, because two neighbours pack their own plans knowing
    // nothing of each other: their bounding boxes overlap wherever a building sits at an
    // angle, and without this their elements run into one another.
    // When elements are lifted off true scale the margin has to be lifted with them, or
    // the gap that read clearly at 8 m is a fraction of a pixel at 400 m.
    val lift = if (minSize > 0.0) minSize / objectSize else 1.0
    val margin = gap * lift
    val occupancy = Occupancy(cell = maxOf(objectSize, minSize) * 2.0)
    val takenCells = HashSet<Long>()

    return buildings.mapIndexed { index, feature ->
    val rings = feature.rings()
    val outer = rings.maxByOrNull { it.size } ?: return@mapIndexed emptyList()
    if (outer.size < 3) return@mapIndexed emptyList()

    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (point in outer) {
        if (point[0] < minX) minX = point[0]; if (point[0] > maxX) maxX = point[0]
        if (point[1] < minY) minY = point[1]; if (point[1] > maxY) maxY = point[1]
    }
    val box = Rectangle(minX, minY, maxX - minX, maxY - minY)
    if (box.width <= 0.0 || box.height <= 0.0) return@mapIndexed emptyList()

    // seeded per building, so a clip renders the same objects in the same places every time
    val random = Random(index * 2654435761L.toInt())

    val placements = if (gridSize > 0.0)
        packOnGrid(box, outer, templates, objectSize, margin, maxPerBuilding, random, takenCells, minSize, gridSize, gridOrigin)
    else
        packInto(box, outer, templates, objectSize, margin, maxPerBuilding, random, occupancy, minSize)
    placements.map { (template, centre, height) -> ObjectPlacement(template, centre, height) }
    }
}

/**
 * The same packing as world-space triangles, one list per building, which is what the mesh
 * wants when nothing has to move on its own.
 */
fun objectFabric(
    buildings: List<JsonObject>,
    templates: List<ObjectTemplate>,
    objectSize: Double = 8.0,
    gap: Double = 1.0,
    maxPerBuilding: Int = 48,
    minSize: Double = 0.0,
    gridSize: Double = 0.0,
    gridOrigin: Vector2 = Vector2.ZERO
): List<List<Vector2>> =
    objectPlacements(buildings, templates, objectSize, gap, maxPerBuilding, minSize, gridSize, gridOrigin)
        .map { building -> building.flatMap { it.triangles(templates) } }

/**
 * Shelf packing: objects are laid left to right along a row, and the row wraps when the
 * next one will not fit. It is demo02's idea at building scale, with one difference —
 * demo02 lets an object run past the end of a line and cuts it, because there the strip is
 * the subject. Here the bin is a real building, so nothing is placed that would hang off
 * its plan: a candidate is kept only if it fits the row *and* its centre lands inside the
 * footprint, which is what stops an L-shaped building being filled across its notch.
 */
private fun packInto(
    box: Rectangle,
    outline: List<DoubleArray>,
    templates: List<ObjectTemplate>,
    objectSize: Double,
    gap: Double,
    maxPerBuilding: Int,
    random: Random,
    occupancy: Occupancy,
    minSize: Double
): List<Triple<Int, Vector2, Double>> {
    val placed = mutableListOf<Triple<Int, Vector2, Double>>()

    // Elements take the size the plan allows, up to [objectSize]. A narrow terrace gets
    // small ones and a shed gets a single tiny one, which is what gives the map its grain:
    // the size carries the scale of the building as well as the count.
    //
    // With a [minSize] floor the same proportion is kept but lifted: a plan of [objectSize]
    // lands exactly on the floor, smaller plans sit on it, and larger ones scale up to four
    // times it. So the grain survives while nothing is drawn too small to see.
    val plan = minOf(box.height, box.width)
    val height =
        if (minSize > 0.0) (plan * (minSize / objectSize)).coerceIn(minSize, minSize * 4.0)
        else minOf(objectSize, plan)
    if (height <= 0.0) return placed

    var y = box.y
    while (y + height <= box.y + box.height + 1e-6 && placed.size < maxPerBuilding) {
        var x = box.x
        var placedInRow = false

        while (placed.size < maxPerBuilding) {
            val remaining = box.x + box.width - x
            // pick something that fits what is left of the row; a handful of tries is
            // enough to find one, and giving up ends the row rather than forcing a bad fit
            var chosen = -1
            repeat(8) {
                if (chosen < 0) {
                    val candidate = random.nextInt(templates.size)
                    if (templates[candidate].aspect * height <= remaining) chosen = candidate
                }
            }
            if (chosen < 0) break

            val width = templates[chosen].aspect * height
            val centre = Vector2(x + width / 2, y + height / 2)
            val rect = boxAround(centre, width, height)
            if (contains(outline, centre) && occupancy.free(rect, gap)) {
                occupancy.add(rect)
                placed += Triple(chosen, centre, height)
                placedInRow = true
            }
            x += width + gap
        }

        if (!placedInRow && placed.isEmpty() && height >= box.height - 1e-6) break
        y += height + gap
    }

    // A building too small for even one element at that size still has to appear, so it
    // gets one shrunk to its plan — but only where there is room for it.
    if (placed.isEmpty()) {
        val chosen = random.nextInt(templates.size)
        val fitted = if (minSize > 0.0) height
                     else minOf(box.height, box.width / templates[chosen].aspect)
        if (fitted > 0.0) {
            val rect = boxAround(box.center, fitted * templates[chosen].aspect, fitted)
            if (occupancy.free(rect, gap)) {
                occupancy.add(rect)
                placed += Triple(chosen, box.center, fitted)
            }
        }
    }
    return placed
}

/** Even-odd point in polygon, on the footprint's outer ring. */
private fun contains(ring: List<DoubleArray>, point: Vector2): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i][0]; val yi = ring[i][1]
        val xj = ring[j][0]; val yj = ring[j][1]
        if ((yi > point.y) != (yj > point.y) &&
            point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi
        ) inside = !inside
        j = i
    }
    return inside
}


/**
 * One element per lattice cell, centred in it.
 *
 * The building's plan is walked cell by cell rather than packed: a cell is taken when its
 * centre falls inside the footprint and nothing has claimed it yet. Two things follow for
 * free. Elements cannot touch, because each is held inside its own cell with the margin
 * kept clear at the edges. And the lattice is global rather than per building, so a terrace
 * of houses lands on the same rows as the block behind it instead of each plot starting its
 * own grid — which is what makes the field read as one weave rather than many.
 *
 * Sizes still vary: an element takes the size its plan allows, then is trimmed to whatever
 * fits the cell, so small plots hold small pieces and large ones fill their cell.
 */
private fun packOnGrid(
    box: Rectangle,
    outline: List<DoubleArray>,
    templates: List<ObjectTemplate>,
    objectSize: Double,
    margin: Double,
    maxPerBuilding: Int,
    random: Random,
    taken: HashSet<Long>,
    minSize: Double,
    gridSize: Double,
    gridOrigin: Vector2
): List<Triple<Int, Vector2, Double>> {
    val placed = mutableListOf<Triple<Int, Vector2, Double>>()

    val plan = minOf(box.height, box.width)
    val wanted =
        if (minSize > 0.0) (plan * (minSize / objectSize)).coerceIn(minSize, minSize * 4.0)
        else minOf(objectSize, plan)
    if (wanted <= 0.0) return placed

    fun cellX(v: Double) = Math.floor((v - gridOrigin.x) / gridSize + 0.5).toInt()
    fun cellY(v: Double) = Math.floor((v - gridOrigin.y) / gridSize + 0.5).toInt()

    val firstX = cellX(box.x)
    val lastX = cellX(box.x + box.width)
    val firstY = cellY(box.y)
    val lastY = cellY(box.y + box.height)

    fun claim(cx: Int, cy: Int) {
        val key = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        if (!taken.add(key)) return
        val chosen = random.nextInt(templates.size)
        // trimmed so the element and its margin stay inside its cell
        val room = (gridSize - margin) / maxOf(1.0, templates[chosen].aspect)
        val height = minOf(wanted, room)
        if (height > 0.0) {
            placed += Triple(
                chosen,
                Vector2(gridOrigin.x + cx * gridSize, gridOrigin.y + cy * gridSize),
                height
            )
        }
    }

    // Cells the plan actually covers: a warehouse spanning several of them gets several.
    for (cy in firstY..lastY) {
        for (cx in firstX..lastX) {
            if (placed.size >= maxPerBuilding) return placed
            val centre = Vector2(gridOrigin.x + cx * gridSize, gridOrigin.y + cy * gridSize)
            if (contains(outline, centre)) claim(cx, cy)
        }
    }

    // Most buildings are far smaller than a cell and will contain no cell centre at all —
    // at an 88 m lattice a 12 m house almost never does. Rather than drop them, each takes
    // the cell it sits in. Dense streets then fill their cells and thin ones leave gaps,
    // which is what carries the shape of the town onto the grid.
    if (placed.isEmpty()) {
        claim(cellX(box.center.x), cellY(box.center.y))
    }
    return placed
}

/**
 * Where elements already are, so a new one can be refused if it would touch one.
 *
 * The test is between bounding boxes rather than contours, which is deliberately strict:
 * a contour always lies inside its box, so boxes kept [margin] apart guarantee the drawn
 * edges are at least that far apart. It costs some density on the diagonal isometric
 * shapes, whose boxes are loose, but it cannot let two elements touch.
 *
 * A uniform grid keeps it cheap — only elements in the cells the candidate covers are
 * looked at, so placing a couple of hundred thousand of them stays linear.
 */
private class Occupancy(private val cell: Double) {
    private val grid = HashMap<Long, MutableList<DoubleArray>>()

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private inline fun overCells(rect: DoubleArray, margin: Double, action: (Long) -> Unit) {
        val minX = ((rect[0] - margin) / cell).toInt()
        val minY = ((rect[1] - margin) / cell).toInt()
        val maxX = ((rect[2] + margin) / cell).toInt()
        val maxY = ((rect[3] + margin) / cell).toInt()
        for (cx in minX..maxX) for (cy in minY..maxY) action(key(cx, cy))
    }

    fun free(rect: DoubleArray, margin: Double): Boolean {
        var clear = true
        overCells(rect, margin) { key ->
            if (clear) {
                val here = grid[key]
                if (here != null) {
                    for (other in here) {
                        if (rect[0] - margin < other[2] && rect[2] + margin > other[0] &&
                            rect[1] - margin < other[3] && rect[3] + margin > other[1]
                        ) { clear = false; break }
                    }
                }
            }
        }
        return clear
    }

    fun add(rect: DoubleArray) {
        overCells(rect, 0.0) { key -> grid.getOrPut(key) { mutableListOf() } += rect }
    }
}

private fun boxAround(centre: Vector2, width: Double, height: Double) =
    doubleArrayOf(centre.x - width / 2, centre.y - height / 2, centre.x + width / 2, centre.y + height / 2)
