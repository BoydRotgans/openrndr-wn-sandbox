// No `package` declaration: it stands on `ObjectTemplate` and `loadMarkTemplates`, which are in
// the default package with the rest of the catalogue packing.

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.WrapMode
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadImage
import org.openrndr.draw.renderTarget
import org.openrndr.math.Matrix44
import java.io.File
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.shape.Rectangle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A picture for a [PhotoMosaic]: the image, where its crop centres, whether it is fitted whole, and
 * whether its marks are **concrete** rather than windows onto it. A blueprint is white lines on
 * black, so seen through the marks it would be a field of black; its marks are cast in concrete
 * instead — each a texture of its own — and the drawing arrives only as they dissolve into it.
 */
class MosaicPicture(
    val image: ColorBuffer,
    val focus: Vector2 = Vector2(0.5, 0.5),
    val whole: Boolean = false,
    val concrete: Boolean = false
)

/** Every jpg in [folder] (or [folder] itself if it is a file), repeating and mipmapped, for concrete marks. */
fun loadConcretes(folder: File): List<ColorBuffer> =
    (if (folder.isDirectory) folder.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sortedBy { it.name }
     else listOf(folder).filter { it.isFile })
        .map {
            loadImage(it).apply {
                wrapU = WrapMode.REPEAT; wrapV = WrapMode.REPEAT
                generateMipmaps(); filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }

/**
 * [file] loaded and brought down to [longest] pixels on its longer side, mipmapped. The case
 * photographs run to 6811 wide — 124 MB a picture on the GPU before its mips — and a pane is 1920
 * wide, so they are drawn once into a buffer of a sensible size through the mip chain and the
 * original let go. Smaller ones are kept as they are.
 */
fun loadPhoto(drawer: Drawer, file: File, longest: Int = 2560): ColorBuffer {
    val img = loadImage(file)
    val scale = longest.toDouble() / maxOf(img.width, img.height)
    if (scale >= 1.0) return img.apply { generateMipmaps(); filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR }
    img.generateMipmaps(); img.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
    val w = (img.width * scale).roundToInt()
    val h = (img.height * scale).roundToInt()
    val target = renderTarget(w, h) { colorBuffer() }
    drawer.isolatedWithTarget(target) {
        ortho(target)
        view = Matrix44.IDENTITY
        model = Matrix44.IDENTITY
        clear(ColorRGBa.BLACK)
        image(img, 0.0, 0.0, w.toDouble(), h.toDouble())
    }
    img.destroy()
    val out = target.colorBuffer(0)
    target.detachColorAttachments()
    target.destroy()
    return out.apply { generateMipmaps(); filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR }
}

/**
 * A picture seen through the catalogue, and handed over to the next one through it: the effect
 * the project highlights and the case studies share, in one place so the two cannot drift.
 *
 * **The packing leaves no black but the joints.** [area] is tiled exactly by a grid of [columns]
 * coarse cells on the marks' own proportion, less [hole], and each cell is split at random — more
 * often the coarser it is — into quarters, or into two halves side by side or one over the other,
 * down to cells no thinner than [THINNEST]. Halving a 1.8:1 cell gives a 0.9:1 or a 3.6:1 one,
 * which is the range the catalogue runs, so every leaf can stand a mark of nearly its own shape:
 * one of the [NEAREST] nearest in proportion, **stretched to fill the cell less [GAP]**. Fitting
 * each mark by its proportion was tried first and left the pane mostly black.
 *
 * **The picture is nailed to the area**, sampled in its pixels, so each mark is a window onto the
 * part of the picture behind it and a drift moves the picture behind marks that stand still.
 *
 * **The sequence**, all of it a function of two numbers — the seconds into an opening, or into a
 * click — so it can be scrubbed and filmed:
 * - opening: the picture builds up over [BUILD] on a left-to-right front, holds [HOLD], and its
 *   marks give way to their whole cells over [DISSOLVE], until the picture stands entire;
 * - a click: the whole picture breaks back into its marks over [REFORM], the front hands them over
 *   to the next picture's own packing over [HANDOVER] — at each spot the old mark goes before the
 *   new one grows — and those hold and dissolve in turn. [CLICK] in all.
 *
 * The hole is where a label stands: the marks, the dissolved picture and the joints all stop at
 * its edge, so the label is set in black the grid left rather than on a box laid over it.
 *
 * Every picture has a packing of its own, built the first time it is asked for and seeded by its
 * key, so two pictures handing over are two different deals of the same marks. A packing is two
 * vertex buffers — the marks, and the whole cells for the dissolve — and each sizes or fades itself
 * in the shader from its place in the order, so nothing is rebuilt per frame.
 */
class PhotoMosaic(
    /** Where it stands, in the layout units the caller draws in. */
    val area: Rectangle,
    private val templates: List<ObjectTemplate>,
    private val columns: Int = 6,
    /** How many coarse cells the label's hole takes along the foot, from the left; 0 for none. */
    holeCells: Int = 0,
    /**
     * Where this area's front runs in the whole sweep: 0..1 for a mosaic of its own, 0..0.5 and
     * 0.5..1 for two panes that build and hand over as one wall.
     */
    private val sweep: ClosedFloatingPointRange<Double> = 0.0..1.0,
    /**
     * How a mark gives way to its whole cell in the dissolve: true fills the cell in one frame on
     * its turn, one at a time; false crossfades each in over its own share of the dissolve.
     */
    private val instant: Boolean = true,
    private val seed: Int = 7,
    /** The textures a concrete picture's marks are cast in, one picked a mark. */
    private val concretes: List<ColorBuffer> = emptyList()
) {
    /** The coarse cell, for callers laying a label out on the grid. */
    val cellWidth = area.width / columns
    val rows = (area.height / (cellWidth / SHAPE)).roundToInt().coerceAtLeast(2)
    val cellHeight = area.height / rows

    /** The label's hole: whole coarse cells in the bottom left corner, or null. */
    val hole: Rectangle? = if (holeCells <= 0) null
        else Rectangle(area.x, area.y + area.height - cellHeight, cellWidth * holeCells, cellHeight)

    private class Packing(val elements: VertexBuffer, val cells: VertexBuffer)
    private val packings = HashMap<Int, Packing>()

    /**
     * The mosaic at one moment. [picture] gives a key's image, where in it the crop centres, and
     * whether it is fitted whole rather than cropped to fill;
     * [px] turns layout units into target pixels, which is what the shader samples against.
     *
     * [from] is the picture at rest. With [to] null or equal to it, the mosaic stands: building,
     * holding and dissolving over [opening] seconds if that is given, whole otherwise. With [to]
     * another key it is [click] seconds into handing [from] over to [to].
     */
    fun draw(
        drawer: Drawer, px: Vector2, zoom: Double,
        picture: (Int) -> MosaicPicture,
        from: Int, to: Int? = null, click: Double = 0.0, opening: Double? = null
    ) {
        if (templates.isEmpty()) return
        if (to == null || to == from) {
            val clock = opening ?: Double.MAX_VALUE
            val build = (clock / BUILD).coerceIn(0.0, 1.0)
            val dissolve = ((clock - BUILD - HOLD) / DISSOLVE).coerceIn(0.0, 1.0)
            if (dissolve < 1.0) layer(drawer, px, zoom, picture, from, ELEMENTS, if (build < 1.0) BUILD_UP else STAND, build)
            if (dissolve > 0.0) layer(drawer, px, zoom, picture, from, CELLS, STAND, dissolve)
            return
        }
        val breakUp = (click / REFORM).coerceIn(0.0, 1.0)
        val handover = ((click - REFORM) / HANDOVER).coerceIn(0.0, 1.0)
        val dissolve = ((click - REFORM - HANDOVER - HOLD) / DISSOLVE).coerceIn(0.0, 1.0)
        if (handover <= 0.0) {
            layer(drawer, px, zoom, picture, from, ELEMENTS, STAND, 1.0)
            layer(drawer, px, zoom, picture, from, CELLS, STAND, 1.0 - breakUp)
        } else {
            if (handover < 1.0) layer(drawer, px, zoom, picture, from, ELEMENTS, LEAVE, handover)
            if (dissolve < 1.0) layer(drawer, px, zoom, picture, to, ELEMENTS, ARRIVE, handover)
            if (dissolve > 0.0) layer(drawer, px, zoom, picture, to, CELLS, STAND, dissolve)
        }
    }

    private fun layer(
        drawer: Drawer, px: Vector2, zoom: Double, picture: (Int) -> MosaicPicture,
        key: Int, which: Int, mode: Int, phase: Double
    ) {
        val pic = picture(key)
        val img = pic.image
        val box = Rectangle(area.x * px.x, area.y * px.y, area.width * px.x, area.height * px.y)
        val c = if (pic.whole) fit(img, box.width / box.height, zoom) else crop(img, box.width / box.height, zoom, pic.focus)
        drawer.shadeStyle = shadeStyle {
            vertexTransform = ELEMENT_SIZE
            fragmentTransform = if (which == CELLS) CELL_PHOTO else ELEMENT_PHOTO
            parameter("photo", img)
            parameter("crop", Vector4(c.x / img.width, c.y / img.height, c.width / img.width, c.height / img.height))
            parameter("area", Vector4(box.x, box.y, box.width, box.height))
            parameter("mode", mode)
            parameter("phase", phase)
            parameter("window", if (which == CELLS) DISSOLVE_WINDOW else WINDOW)
            parameter("instant", if (instant) 1.0 else 0.0)
            val stone = pic.concrete && concretes.isNotEmpty() && which == ELEMENTS
            parameter("concrete", if (stone) 1.0 else 0.0)
            val c0 = concretes.getOrElse(0) { img }
            parameter("c0", c0)
            parameter("c1", concretes.getOrElse(1) { c0 })
            parameter("c2", concretes.getOrElse(2) { c0 })
            parameter("tile", CONCRETE_TILE * px.x)
        }
        drawer.fill = ColorRGBa.WHITE
        drawer.stroke = null
        val p = packings.getOrPut(key) { packed(seed + key) }
        drawer.vertexBuffer(if (which == CELLS) p.cells else p.elements, DrawPrimitive.TRIANGLES)
        drawer.shadeStyle = null
    }

    /** Build the packings for [keys] now, so no click waits on one. */
    fun prepare(keys: Iterable<Int>) {
        if (templates.isEmpty()) return
        keys.forEach { packings.getOrPut(it) { packed(seed + it) } }
    }

    private fun packed(seed: Int): Packing {
        val random = Random(seed)
        // `stone` is a concrete mark's texture, its tone and where in the texture it is cut from, so
        // no two neighbours are the same slab.
        class Leaf(val template: Int, val cell: Rectangle, val order: Double, val fade: Double, val stone: Vector4)
        val leaves = ArrayList<Leaf>()
        fun cell(r: Rectangle, depth: Int) {
            val chance = SPLIT[depth.coerceAtMost(SPLIT.size - 1)]
            if (random.nextDouble() < chance) {
                val halfW = r.width / 2.0
                val halfH = r.height / 2.0
                val kind = random.nextDouble()
                val parts = when {
                    kind < QUARTERS && minOf(halfW, halfH) >= THINNEST -> listOf(
                        Rectangle(r.x, r.y, halfW, halfH), Rectangle(r.x + halfW, r.y, halfW, halfH),
                        Rectangle(r.x, r.y + halfH, halfW, halfH), Rectangle(r.x + halfW, r.y + halfH, halfW, halfH)
                    )
                    kind < QUARTERS + (1.0 - QUARTERS) / 2.0 && minOf(halfW, r.height) >= THINNEST -> listOf(
                        Rectangle(r.x, r.y, halfW, r.height), Rectangle(r.x + halfW, r.y, halfW, r.height)
                    )
                    minOf(r.width, halfH) >= THINNEST -> listOf(
                        Rectangle(r.x, r.y, r.width, halfH), Rectangle(r.x, r.y + halfH, r.width, halfH)
                    )
                    else -> emptyList()
                }
                if (parts.isNotEmpty()) { parts.forEach { cell(it, depth + 1) }; return }
            }
            val aspect = r.width / r.height
            val nearest = templates.indices.sortedBy { abs(ln(templates[it].aspect / aspect)) }.take(NEAREST)
            val pick = nearest[random.nextInt(nearest.size)]
            val across = sweep.start + (sweep.endInclusive - sweep.start) *
                    ((r.center.x - area.x) / area.width).coerceIn(0.0, 1.0)
            val order = (across * (1.0 - JITTER) + random.nextDouble() * JITTER).coerceIn(0.0, 1.0)
            val stone = Vector4(
                random.nextInt(3).toDouble(),
                TONE.start + random.nextDouble() * (TONE.endInclusive - TONE.start),
                random.nextDouble(), random.nextDouble()
            )
            leaves += Leaf(pick, r, order, random.nextDouble(), stone)
        }
        for (row in 0 until rows) for (column in 0 until columns) {
            val r = Rectangle(area.x + column * cellWidth, area.y + row * cellHeight, cellWidth, cellHeight)
            if (hole?.contains(r.center) == true) continue          // the label's hole
            cell(r, 0)
        }

        val format = vertexFormat {
            position(3)
            attribute("origin", VertexElementType.VECTOR2_FLOAT32)
            attribute("order", VertexElementType.FLOAT32)
            attribute("stone", VertexElementType.VECTOR4_FLOAT32)
        }
        val count = leaves.sumOf { templates[it.template].triangles.size }.coerceAtLeast(3)
        val elements = vertexBuffer(format, count)
        elements.put {
            var written = 0
            leaves.forEach { leaf ->
                // A template is height 1 and `aspect` wide about its centre; stretched to the cell
                // inset by half the gap on every side.
                val t = templates[leaf.template]
                val sx = (leaf.cell.width - GAP).coerceAtLeast(0.0) / t.aspect
                val sy = (leaf.cell.height - GAP).coerceAtLeast(0.0)
                val centre = leaf.cell.center
                t.triangles.forEach {
                    val p = Vector2(centre.x + it.x * sx, centre.y - it.y * sy)
                    write(Vector3(p.x, p.y, 0.0)); write(centre); write(leaf.order.toFloat()); write(leaf.stone)
                    written++
                }
            }
            while (written < 3) { write(Vector3.ZERO); write(Vector2.ZERO); write(0.0f); write(Vector4.ZERO); written++ }
        }
        // The cells whole, edge to edge: filled, they close every joint and every notch. Their
        // order is a random of their own, so the marks go one here and one there, not as a sweep.
        val cells = vertexBuffer(format, (leaves.size * 6).coerceAtLeast(3))
        cells.put {
            leaves.forEach { leaf ->
                val r = leaf.cell
                val a = Vector2(r.x, r.y); val b = Vector2(r.x + r.width, r.y)
                val c = Vector2(r.x + r.width, r.y + r.height); val d = Vector2(r.x, r.y + r.height)
                for (p in listOf(a, b, c, a, c, d)) {
                    write(Vector3(p.x, p.y, 0.0)); write(r.center); write(leaf.fade.toFloat()); write(leaf.stone)
                }
            }
            if (leaves.isEmpty()) repeat(3) { write(Vector3.ZERO); write(Vector2.ZERO); write(0.0f); write(Vector4.ZERO) }
        }
        return Packing(elements, cells)
    }

    companion object {
        /** Seconds: the opening's build, hold and dissolve, and a click's break-up and handover. */
        const val BUILD = 1.0
        const val REFORM = 0.3
        const val HANDOVER = 1.4
        const val HOLD = 0.3
        const val DISSOLVE = 1.5
        const val CLICK = REFORM + HANDOVER + HOLD + DISSOLVE
        const val OPENING = BUILD + HOLD + DISSOLVE

        /**
         * The packing, in layout units at 1920x1080 a pane: the cells' shape (`subset_svg`'s median
         * proportion), the thinnest side a split may leave, the joint between cells, the chance a
         * cell splits at each depth, the share of splits that quarter rather than halve, and how
         * many marks nearest a cell's shape it may draw from.
         */
        const val SHAPE = 1.85
        const val THINNEST = 22.0
        const val GAP = 3.0
        val SPLIT = doubleArrayOf(0.8, 0.6, 0.45, 0.3, 0.15)
        const val QUARTERS = 0.5
        const val NEAREST = 3

        /**
         * A concrete mark: one repeat of its texture across this many layout units, and the range
         * its tone is drawn from, so the slabs differ in shade as well as in grain.
         */
        const val CONCRETE_TILE = 900.0
        val TONE = 0.78..1.05

        /** How much of a mark's place in the order is its own random rather than the front. */
        const val JITTER = 0.35

        /** The share of the build or handover one mark's move takes, and of the dissolve one cell's fade. */
        const val WINDOW = 0.18
        const val DISSOLVE_WINDOW = 0.3

        private const val ELEMENTS = 0
        private const val CELLS = 1
        private const val STAND = 0
        private const val BUILD_UP = 1
        private const val LEAVE = 2
        private const val ARRIVE = 3

        /**
         * The part of [img] that covers a box of [aspect], [zoom] times in, centred as near [focus]
         * as the picture allows — so the box is always full and a drift never shows an edge.
         */
        /**
         * The whole of [img] inside a box of [aspect], [zoom] times in, centred: the crop runs past
         * the image on the long sides and the shader draws black there, which is the ground a
         * white-on-black drawing is already on — so a plan is never cut to fill the box.
         */
        fun fit(img: ColorBuffer, aspect: Double, zoom: Double): Rectangle {
            val iw = img.width.toDouble()
            val ih = img.height.toDouble()
            val (cw0, ch0) = if (iw / ih > aspect) Pair(iw, iw / aspect) else Pair(ih * aspect, ih)
            val cw = cw0 * FIT_MARGIN / zoom
            val ch = ch0 * FIT_MARGIN / zoom
            return Rectangle((iw - cw) / 2.0, (ih - ch) / 2.0, cw, ch)
        }

        /** How much air a fitted drawing keeps round it, as a factor on the box. */
        const val FIT_MARGIN = 1.06

        fun crop(img: ColorBuffer, aspect: Double, zoom: Double, focus: Vector2): Rectangle {
            val iw = img.width.toDouble()
            val ih = img.height.toDouble()
            val (cw0, ch0) = if (iw / ih > aspect) Pair(ih * aspect, ih) else Pair(iw, iw / aspect)
            val cw = cw0 / zoom
            val ch = ch0 / zoom
            val x = (focus.x * iw - cw / 2.0).coerceIn(0.0, iw - cw)
            val y = (focus.y * ih - ch / 2.0).coerceIn(0.0, ih - ch)
            return Rectangle(x, y, cw, ch)
        }
    }
}

/**
 * A mark's size, per vertex: 0 before its turn, 1 after, grown from its own centre. In a handover
 * the leaving marks go over the first half of their own window and the arriving ones come over the
 * second, so at any spot the old mark is gone before the new one grows in.
 */
private const val ELEMENT_SIZE = """
    float local = clamp((p_phase - va_order * (1.0 - p_window)) / p_window, 0.0, 1.0);
    float k = 1.0;
    if (p_mode == 1) k = local;
    else if (p_mode == 2) k = 1.0 - clamp(local * 2.0, 0.0, 1.0);
    else if (p_mode == 3) k = clamp(local * 2.0 - 1.0, 0.0, 1.0);
    k = k * k * (3.0 - 2.0 * k);
    x_position.xy = va_origin + (x_position.xy - va_origin) * k;
"""

/** The picture seen through the mark: the fragment's place in the area mapped into the crop. */
private const val ELEMENT_PHOTO = """
    vec2 inArea = clamp((v_worldPosition.xy - p_area.xy) / p_area.zw, 0.0, 1.0);
    vec2 uv = p_crop.xy + inArea * p_crop.zw;
    x_fill = texture(p_photo, vec2(uv.x, 1.0 - uv.y));
    // past the picture's edge, which only a fitted drawing reaches, is the drawing's own black
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) x_fill.rgb = vec3(0.0);
    if (p_concrete > 0.5) {
        // cast in concrete: the mark's own texture, cut from its own place in it, at its own tone
        vec2 cuv = v_worldPosition.xy / p_tile + va_stone.zw;
        vec3 c = va_stone.x < 0.5 ? texture(p_c0, cuv).rgb
               : va_stone.x < 1.5 ? texture(p_c1, cuv).rgb : texture(p_c2, cuv).rgb;
        x_fill.rgb = c * va_stone.y;
    }
    x_fill.a = 1.0;
"""

/**
 * A whole cell of the picture, its opacity its own turn in the dissolve: laid over its mark it
 * fills the joints and the notches. Instant, a cell is filled in the frame the dissolve reaches its
 * place in the order; otherwise it crossfades over its own window.
 */
private const val CELL_PHOTO = """
    vec2 inArea = clamp((v_worldPosition.xy - p_area.xy) / p_area.zw, 0.0, 1.0);
    vec2 uv = p_crop.xy + inArea * p_crop.zw;
    float local = clamp((p_phase - va_order * (1.0 - p_window)) / p_window, 0.0, 1.0);
    x_fill = texture(p_photo, vec2(uv.x, 1.0 - uv.y));
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) x_fill.rgb = vec3(0.0);
    float faded = local * local * (3.0 - 2.0 * local);
    x_fill.a = mix(faded, step(va_order, p_phase), p_instant);
"""
