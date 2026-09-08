import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorBufferShadow
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.triangulate
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import java.io.File
import kotlin.random.Random

/**
 * An image read back as a field of catalogue objects: whatever was drawn into a colour
 * buffer, rebuilt out of Willy Naessens elements.
 *
 * **The elements are not on a grid — they are packed.** A square starts at [coarse] and asks
 * how much ink is under it: full, it stands one element at that size; empty, nothing; part
 * full, it splits into four and each quarter asks again, down to [finest]. So a stroke is
 * filled by a few big elements with a run of small ones tracing its edge, and the size of a
 * mark tells you where in the letter it is.
 *
 * A single cell size cannot do that. Small enough for the edges to read, every element is that
 * small and the inside of a stroke is a field of identical dots; large enough for the inside
 * to have weight, the edges come out as a stair. The recursion is the same picture at both
 * scales at once, and it is one test — *is this square wholly inside the ink* — rather than
 * anything tuned per letter.
 *
 * Two decisions worth keeping:
 *
 * - **Coverage is sampled, not integrated.** A square asks a grid of points across itself
 *   rather than every pixel under it, more of them the bigger it is. On a 1920x1080 plate that
 *   is a fraction of a second against most of a minute, and no different to look at.
 * - **The ground is packed as well as the ink**, and it costs nothing to do it: a cell wholly
 *   outside the ink is as settled as one wholly inside, so it stands an element at that size
 *   rather than being dropped. What comes out is the whole plate in elements — big ones over
 *   the ground, a range of them in the letters, the small ones along the edge between.
 *   [MosaicCell.filled] says which side a cell is on; whether the ground is drawn at all, and
 *   in what, is the caller's business.
 * - **The cell is the shape of the catalogue, not a square.** Only four of the fifteen objects
 *   on `subset.svg` are anywhere near square; the median is 1.85 wide to high. Fitted into a
 *   square, that median object is a bar half the cell tall and the picture comes out striped —
 *   every mark a sliver with air above and below it. On a cell of the catalogue's own
 *   proportion the same object fills it, and the marks read as blocks. [shape] is that
 *   proportion, and quartering a rectangle keeps it, so every cell at every depth has it.
 * - **An element is still fitted, not stretched.** These are real components and their
 *   proportions are the whole of what they are, so a 3.29 beam is a fine bar even here and a
 *   0.85 corner is narrow. That range is the texture.
 */
class MosaicCell(
    val template: Int,
    val centre: Vector2,
    val height: Double,
    /** True where the cell sits in the ink, false where it sits in the ground around it. */
    val filled: Boolean
)

/**
 * [image] as a list of cells to stand an element in — see the note above.
 *
 * [solid] is how much ink a square needs to count as *inside* and stop splitting; [threshold]
 * how much a square at [finest] needs to be kept at all. [seed] fixes which element lands
 * where, so a card is the same picture every run.
 */
fun objectMosaic(
    image: ColorBuffer,
    templates: List<ObjectTemplate>,
    coarse: Double = 64.0,
    finest: Double = 16.0,
    shape: Double = 1.9,
    fill: Double = 0.9,
    solid: Double = 0.88,
    threshold: Double = 0.32,
    seed: Int = 0
): List<MosaicCell> {
    if (templates.isEmpty() || coarse < 1.0 || shape <= 0.0) return emptyList()

    val shadow = image.shadow
    shadow.download()

    val random = Random(seed)
    val cells = ArrayList<MosaicCell>()

    fun stand(x: Double, y: Double, w: Double, h: Double, filled: Boolean) {
        val pick = random.nextInt(templates.size)
        val height = minOf(h, w / templates[pick].aspect) * fill
        cells += MosaicCell(pick, Vector2(x + w / 2.0, y + h / 2.0), height, filled)
    }

    /**
     * One cell: stand an element in it, or quarter it and ask again.
     *
     * **Both sides are kept.** A cell wholly in the ink stands one, a cell wholly outside it
     * stands one too, and only a cell that straddles the edge is worth splitting.
     */
    fun consider(x: Double, y: Double, w: Double) {
        val h = w / shape
        val ink = coverage(shadow, image.width, image.height, x, y, w, h)

        if (ink >= solid) { stand(x, y, w, h, true); return }
        if (ink <= EMPTY) { stand(x, y, w, h, false); return }

        if (w > finest) {
            val hw = w / 2.0
            val hh = h / 2.0
            consider(x, y, hw)
            consider(x + hw, y, hw)
            consider(x, y + hh, hw)
            consider(x + hw, y + hh, hw)
            return
        }
        stand(x, y, w, h, ink >= threshold)
    }

    val columns = Math.ceil(image.width / coarse).toInt()
    val rows = Math.ceil(image.height / (coarse / shape)).toInt()
    for (row in 0 until rows) for (column in 0 until columns) {
        consider(column * coarse, row * coarse / shape, coarse)
    }

    shadow.destroy()
    return cells
}

/** How much of the cell at [x], [y] is inked, from a grid of samples across it. */
private fun coverage(
    shadow: ColorBufferShadow, width: Int, height: Int,
    x: Double, y: Double, w: Double, h: Double
): Double {
    val n = (w / 3.0).toInt().coerceIn(3, 9)
    var ink = 0.0
    for (sy in 0 until n) for (sx in 0 until n) {
        val px = (x + (sx + 0.5) * w / n).toInt().coerceIn(0, width - 1)
        val py = (y + (sy + 0.5) * h / n).toInt().coerceIn(0, height - 1)
        val c = shadow[px, py]
        ink += (c.r + c.g + c.b) / 3.0 * c.alpha
    }
    return ink / (n * n)
}

/**
 * The cells as one vertex buffer, in the pane's own coordinates — y down, so the elements are
 * flipped back out of the world-space orientation [loadObjectTemplates] leaves them in.
 *
 * **Every vertex carries its own element's centre and its place in the order**, which is what
 * lets a whole card arrive an element at a time in *one draw call*. Each element grows from
 * nothing about its own middle as its number comes up, and a shade style works that out per
 * vertex from `va_origin` and `va_order` — see [MOSAIC_ARRIVAL]. The alternative is a draw
 * call per element still on its way in, which on a card of three thousand with a fifth of them
 * moving at any moment is a few hundred a frame.
 *
 * An element that has not started is drawn at zero size: its triangles collapse to a point and
 * nothing reaches the screen, so there is nothing to skip and no state to keep.
 */
fun mosaicBuffer(cells: List<MosaicCell>, templates: List<ObjectTemplate>): VertexBuffer {
    val format = vertexFormat {
        position(3)
        attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        attribute("order", VertexElementType.FLOAT32)
    }
    val vertices = cells.sumOf { templates[it.template].triangles.size }
    val buffer = vertexBuffer(format, vertices.coerceAtLeast(1))

    buffer.put {
        cells.forEachIndexed { index, cell ->
            templates[cell.template].triangles.forEach {
                val at = cell.centre + Vector2(it.x, -it.y) * cell.height
                write(Vector3(at.x, at.y, 0.0))
                write(cell.centre)
                write(index.toFloat())
            }
        }
        // a buffer must be filled: an empty card would otherwise be one unwritten vertex
        if (vertices == 0) { write(Vector3.ZERO); write(Vector2.ZERO); write(0.0f) }
    }
    return buffer
}

/**
 * The vertex transform that arrives a mosaic: element `va_order` grows from its own centre
 * once `p_arrived` reaches it, over `p_ramp` elements' worth of the count.
 *
 * Smoothstepped rather than linear — an element appearing at full speed and stopping dead is
 * the hard cut it replaced, only slower.
 */
const val MOSAIC_ARRIVAL = """
    float t = clamp((p_arrived - va_order) / p_ramp, 0.0, 1.0);
    float k = t * t * (3.0 - 2.0 * t);
    x_position.xy = va_origin + (x_position.xy - va_origin) * k;
"""

/** Below this a cell holds nothing worth splitting for. */
const val EMPTY = 0.04


// ------------------------------------------------------------------------------------ //
//  The same idea, live: the cells are baked once and the *picture* is what moves.
// ------------------------------------------------------------------------------------ //

/**
 * A card that stands a [mosaicField], whatever it reads to decide where the marks go.
 *
 * `ObjectChapterPanel` reads type it sets itself; `ObjectImageChapterPanel` reads a picture off
 * disk. What they have in common is the field and the three things `CardStudio` does to one, so
 * the studio holds this rather than a particular class — otherwise every new kind of card comes
 * up in the studio with `b`, `j` and `k` silently dead and no readout under it.
 */
/**
 * The marks a field is packed out of, from **either a sheet or a folder**.
 *
 * A sheet is the catalogue: one flat svg whose objects have to be told apart by reading the
 * grid and the captions off the file, which is [loadObjectSheet]'s whole job. A folder is the
 * simpler thing — one svg to a mark, whole file as it stands, nothing inferred — which is what
 * a set of drawn marks actually looks like on disk when it was not exported as a catalogue
 * page. `data/svg/3-shapes` is one of those.
 *
 * Marks come back in filename order, so a folder's order is stated rather than incidental.
 */
fun loadMarkTemplates(file: File): List<ObjectTemplate> = when {
    file.isDirectory -> file.listFiles()
        ?.filter { it.isFile && it.extension.equals("svg", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?.mapNotNull { markTemplate(it) }
        .orEmpty()

    file.isFile -> loadObjectTemplates(file)
    else -> emptyList()
}

/**
 * One svg as one mark: every shape in it, normalised to height 1 and centred, exactly as
 * [loadObjectTemplates] normalises an object off a sheet — so the two are interchangeable
 * downstream and a folder costs the packing nothing.
 */
private fun markTemplate(file: File): ObjectTemplate? {
    val shapes = loadSVG(file).findShapes().map { it.effectiveShape }.filter { !it.empty }
    if (shapes.isEmpty()) return null

    val boxes = shapes.map { it.bounds }
    val left = boxes.minOf { it.corner.x }
    val top = boxes.minOf { it.corner.y }
    val right = boxes.maxOf { it.corner.x + it.width }
    val bottom = boxes.maxOf { it.corner.y + it.height }
    val width = right - left
    val height = bottom - top
    if (width <= 0.0 || height <= 0.0) return null

    val scale = 1.0 / height
    val cx = (left + right) / 2.0
    val cy = (top + bottom) / 2.0
    // svg y grows downward and the field's does not, so the mark is flipped here once rather
    // than at every one of its placements.
    val triangles = shapes.flatMap { triangulate(it) }
        .map { Vector2((it.x - cx) * scale, -(it.y - cy) * scale) }

    return if (triangles.isEmpty()) null else ObjectTemplate(triangles, width / height)
}

interface MosaicCard {
    /** Draw the plate itself rather than the field that reads it — the studio's `b`. */
    var plainly: Boolean

    /** The marks a step bigger or smaller, the field rebuilt around them — the studio's `j`/`k`. */
    fun rescale(by: Double)

    /** What this card is made of, in one line, for the readout. */
    val recipe: String
}

/**
 * One cell of the standing field — every cell of [mosaicField]'s quadtree, at every level,
 * whether or not it is the one that ends up drawn.
 *
 * Which of them stand is not decided here and cannot be: it depends on the picture, and the
 * picture changes every frame. Each cell carries what the rule needs to work itself out on the
 * GPU — where it is, how big it is, and how many levels are above it — and [MOSAIC_FIELD] then
 * applies exactly [objectMosaic]'s rule per frame against a mask texture.
 */
class FieldCell(
    val template: Int,
    val centre: Vector2,
    /** The element's own height, fitted into the cell as ever. */
    val height: Double,
    /** The cell's width. Its height is this over the field's shape. */
    val cell: Double,
    /** How many cells stand above this one — 0 at the coarsest. */
    val depth: Int,
    /** Where this cell comes in the arrival, 0..1. A value rather than an index — see below. */
    val order: Double
)

/**
 * The whole quadtree of cells over a [width] x [height] frame, coarsest to finest.
 *
 * [objectMosaic] walks the same tree and keeps the cells that settle against one image; this
 * keeps **all** of them, because the image is going to move. A cell knows its own size and its
 * depth, which is everything the rule needs, and the decision — *is this square wholly inside
 * the ink, and did every square above it straddle* — is made per frame in the vertex shader.
 *
 * The order is a value in 0..1 per cell rather than the cell's index in this list, and it has
 * to be: only a fraction of these cells stand at any moment, so an arrival counted in indices
 * would spend most of its length on cells nobody can see. A value spreads whatever is standing
 * evenly across the reveal.
 */
fun mosaicField(
    width: Int,
    height: Int,
    templates: List<ObjectTemplate>,
    coarse: Double = 64.0,
    finest: Double = 16.0,
    shape: Double = 1.9,
    fill: Double = 0.9,
    /**
     * A clearance held between neighbouring marks, in frame pixels, whatever size they are.
     *
     * [fill] cannot do this: it is a *fraction* of the cell, so it opens a wide gap around a
     * coarse mark and a hairline one around a fine mark — and on a quadtree the fine marks are
     * exactly where two neighbours are closest. Subtracting a fixed clearance from the cell
     * before the mark is fitted holds the same air at every level: a mark is inset half of it
     * on each side, so any two neighbours stand this far apart no matter which levels they are
     * drawn at. 0 is the old behaviour, where only [fill] separates them.
     */
    gap: Double = 0.0,
    seed: Int = 0,
    /**
     * Every element at one size, rather than each fitted into the cell by its own proportion.
     *
     * Fitted is the honest thing when a mark stands for a component — a 3.3:1 beam is a fine
     * bar and a square panel is a block — but it means a cell of beams carries a third of the
     * ink a cell of panels does, and on a field of one cell size that reads as holes rather
     * than as variety. At one height they all mark their cell equally and the wide ones run
     * over their neighbours, which is what a field of one size costs.
     */
    uniform: Boolean = false
): List<FieldCell> {
    if (templates.isEmpty() || coarse < 1.0 || shape <= 0.0) return emptyList()

    val random = Random(seed)
    val cells = ArrayList<FieldCell>()

    fun stand(x: Double, y: Double, w: Double, depth: Int) {
        val h = w / shape
        val pick = random.nextInt(templates.size)
        // The room left once the clearance is taken off both sides of the cell. Fitted, the
        // mark's *width* has to clear it too, which is why the aspect divides the width term.
        val room = if (uniform) h - gap
        else minOf(h - gap, (w - gap) / templates[pick].aspect)
        val size = (room * fill).coerceAtLeast(0.0)
        cells += FieldCell(
            pick, Vector2(x + w / 2.0, y + h / 2.0),
            size, w, depth, random.nextDouble()
        )
        if (w > finest) {
            val hw = w / 2.0
            val hh = h / 2.0
            stand(x, y, hw, depth + 1)
            stand(x + hw, y, hw, depth + 1)
            stand(x, y + hh, hw, depth + 1)
            stand(x + hw, y + hh, hw, depth + 1)
        }
    }

    val columns = Math.ceil(width / coarse).toInt()
    val rows = Math.ceil(height / (coarse / shape)).toInt()
    for (row in 0 until rows) for (column in 0 until columns) {
        stand(column * coarse, row * coarse / shape, coarse, 0)
    }
    return cells
}

/**
 * The field as one vertex buffer — the whole card, at every level, in one draw call.
 *
 * Every vertex carries its cell's centre, its size, its depth and its place in the arrival, so
 * the vertex shader can decide for itself whether this cell is the one that stands here and at
 * what size. A cell that is not collapses to a point and reaches nothing.
 */
fun fieldBuffer(cells: List<FieldCell>, templates: List<ObjectTemplate>): VertexBuffer {
    val format = vertexFormat {
        position(3)
        attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        attribute("cell", VertexElementType.FLOAT32)
        attribute("depth", VertexElementType.FLOAT32)
        attribute("order", VertexElementType.FLOAT32)
    }
    val vertices = cells.sumOf { templates[it.template].triangles.size }
    val buffer = vertexBuffer(format, vertices.coerceAtLeast(1))

    buffer.put {
        cells.forEach { cell ->
            templates[cell.template].triangles.forEach {
                val at = cell.centre + Vector2(it.x, -it.y) * cell.height
                write(Vector3(at.x, at.y, 0.0))
                write(cell.centre)
                write(cell.cell.toFloat())
                write(cell.depth.toFloat())
                write(cell.order.toFloat())
            }
        }
        if (vertices == 0) {
            write(Vector3.ZERO); write(Vector2.ZERO); write(0.0f); write(0.0f); write(0.0f)
        }
    }
    return buffer
}

/**
 * [objectMosaic]'s rule, per frame, against a mask texture: **the cells stand still and the
 * picture moves through them.**
 *
 * A cell stands here if it has settled — wholly inside the ink or wholly outside it — *and*
 * every cell above it straddled the edge. That is the recursion the CPU version walks, written
 * as a test a vertex can make on its own, so a whole card of tens of thousands of cells at
 * every level is still one draw call and the picture behind it can be anything.
 *
 * **Coverage comes from the mip chain.** A cell of `w` pane pixels is `w / p_texel` texels of
 * the mask, so `textureLod` at `log2` of that is the average of exactly that square — the
 * sampled coverage [objectMosaic] computes by hand, for nothing. It is why the mask is
 * rendered on the *cell's* aspect rather than the pane's: a cell is then square in texels and
 * a square mip average is the cell's own box.
 *
 * The arrival rides on top: an element grows from its own centre when the count reaches it.
 */
const val MOSAIC_FIELD = """
    vec2 pane = p_pane;
    vec2 cellSize = vec2(va_cell, va_cell / p_shape);
    float lod = max(log2(va_cell / p_texel), 0.0);
    // The plate is drawn y-down, the way everything else here is laid out, and a texture is
    // read y-up — so v is flipped on the way in. Taken raw the whole card comes out upside
    // down and every letter with it, which renders perfectly and reads as nothing.
    float self = textureLod(p_mask, vec2(va_origin.x, pane.y - va_origin.y) / pane, lod).r;

    // every cell above this one has to have straddled, or that one is standing instead
    float live = 1.0;
    for (int j = 1; j <= int(va_depth); j++) {
        vec2 up = cellSize * exp2(float(j));
        vec2 at = (floor(va_origin / up) + 0.5) * up;
        float a = textureLod(p_mask, vec2(at.x, pane.y - at.y) / pane,
                             max(log2(up.x / p_texel), 0.0)).r;
        if (a >= p_solid || a <= p_empty) live = 0.0;
    }

    // the finest cells always stand — there is nothing under them to hand the edge to
    float finest = step(va_cell, p_finest + 0.5);
    float settled = max(finest, max(step(p_solid, self), 1.0 - step(p_empty, self)));

    // **The size is the coverage.** A cell wholly in the ink stands its element full size, one
    // wholly outside it stands the same element at p_shrink, and everything between is in
    // between — so a word passing over the field swells the elements it crosses and lets them
    // back down behind it, and the letters are carried by the size of the marks rather than by
    // their colour alone. Smoothstepped, so nothing steps between two sizes.
    float grow = mix(p_shrink, 1.0, self * self * (3.0 - 2.0 * self));

    // **Where this cell comes in the arrival.** `p_sweep` 0 is the baked random order — every
    // cell independent of its neighbours, the field simply filling in. 1 stages it instead, in
    // two passes that cannot be baked because which pass a cell belongs to is what the mask
    // says, and the mask is read per frame: the ground sweeps **up** from the foot over the
    // first `p_stage` of the reveal, then after `p_delay` the ink sweeps **down** from the
    // head over what is left. So the wall is built first and the words land onto it.
    //
    // The jitter is a little of the cell's own random order mixed back in, so the leading edge
    // of a sweep is a ragged front of marks rather than a ruled line crossing the card.
    float lit = step(p_threshold, self);
    float y01 = clamp(va_origin.y / pane.y, 0.0, 1.0);
    float staged = mix(
        (1.0 - y01) * p_stage,
        p_stage + p_delay + y01 * max(1.0 - p_stage - p_delay, 0.0),
        lit
    );
    float order = clamp(mix(va_order, staged + (va_order - 0.5) * p_jitter, p_sweep), 0.0, 1.0);
    float a = clamp((p_arrived - order * (1.0 - p_ramp)) / p_ramp, 0.0, 1.0);
    float k = live * settled * grow * a * a * (3.0 - 2.0 * a);
    x_position.xy = va_origin + (x_position.xy - va_origin) * k;
"""

/**
 * The other half of [MOSAIC_FIELD]: a cell takes the ink or the ground from the same mask it
 * decided to stand by.
 *
 * In the fragment rather than the vertex shader because a shade style has no varying of its own
 * to carry the answer across — and it costs nothing to ask again, the sample being the same one
 * for every pixel of an element.
 */
const val MOSAIC_FIELD_COLOUR = """
    float lod = max(log2(va_cell / p_texel), 0.0);
    float tone = textureLod(p_mask,
        vec2(va_origin.x, p_pane.y - va_origin.y) / p_pane, lod).r;
    x_fill.rgb = mix(p_ground.rgb, p_ink.rgb, step(p_threshold, tone));
    x_fill.a = 1.0;
"""
