// ============================================================================ //
//  No `package` declaration, for the reason ConveyorScene has none: it stands on
//  loadObjectSheet, SheetObject and loadPieceDetails, which are in the default
//  package. The file belongs to backdrop-drawers/, a folder rather than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.Rectangle
import org.openrndr.shape.triangulate
import slideshow.Arrival
import slideshow.Backdrop
import slideshow.CubicBezier
import slideshow.Cut
import slideshow.Scale
import slideshow.Stage
import slideshow.Transition
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.readSilhouettes
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The catalogue to scale: the flat elevations added one at a time, small to large, each measured
 * as it arrives, **every one of them staying on the wall** — so the wall fills up while the camera
 * pulls back to make room.
 *
 * **Every piece keeps its place, and only the camera moves.** A new piece is packed beside the
 * ones already down, in real millimetres, and never moves again; the camera frames everything
 * added so far. So the wall is one growing collection at one true scale, and a piece arriving that
 * is larger than the cluster so far pulls the whole view back around it. Nothing ever leaves the
 * frame. A single row cannot do this: laid end to end the catalogue is 311 m long, which at the
 * wall's width is a strip 170 px high — so the pieces are packed in two dimensions.
 *
 * **The packing is chosen to keep the view as close as it can.** Each piece is tried against the
 * corners of every piece already down and of the whole cluster, and goes where the cluster's box
 * still fits the frame at the largest scale — so a piece that fits in a hole takes the hole and the
 * camera does not move at all. Ties go to the place nearest the middle, so the cluster grows
 * outward. Laid out once per wall size.
 *
 * **Small to large is the longest side**, because that is the number on the wall that visibly
 * grows: the larger of each piece's two dimensions is never smaller than the one before it.
 *
 * **The silhouette is fitted to the register's box, not the other way round.** A drawing's own
 * proportion agrees with its row to within 4% across the front sheet (see [alignedTo]); the drawing
 * is stretched the last few percent so the dimension lines measure exactly what is drawn. Which
 * register column is horizontal is read off the drawing — its width, or its depth where the sheet
 * draws a side elevation — whichever proportion it matches.
 *
 * **The true scale has a floor, and it is a speck.** The register runs from a 2 mm shim to a 23.8 m
 * beam, so by the end the smallest pieces are well under a pixel. Drawn to scale they would simply
 * disappear, and the count on the wall would stop being the count added; so a piece is never drawn
 * smaller than [LEAST] pixels across — a speck in its place, still there.
 *
 * **The camera zooms about a point.** Between two framings there is exactly one point that stands
 * still — solve the two for it — and scaling about it in log space moves every piece one way.
 * Interpolating the scale and the position separately makes the pieces overshoot and come back
 * whenever the scale drops by more than e in a step, and the first steps drop it thirty times.
 *
 * **A measurement is taken, not printed.** Each dimension line grows from one extension line to the
 * other and its figure counts the millimetres covered so far — the height up the left side, then the
 * width underneath. Every piece is packed with room for exactly those two figures at the scale it
 * arrives at; as the camera pulls back that room shrinks with it, and a figure fades over its last
 * [FADE] pixels once it no longer fits. So the newest piece is always measured, and the older
 * measurements fold away of their own accord as their pieces get small.
 *
 * **People stand among the pieces for scale**, the Crowd slide's figures at their real height and in
 * its blue, packed and kept like the pieces and never measured — they are what the pieces are
 * measured against. There is always one: the first comes with the first piece, and stands alone among
 * the fittings for the first half of the run, which is exactly how small those are. After that the
 * crowd grows with the material — a person joins each time the pieces' area grows by a person over
 * [peopleShare] — so the walls bring people with them and the two stay in balance. A person every few
 * pieces was tried first and put eleven figures beside a handful of fittings under half a metre.
 *
 * **One red on the wall at a time**: the newest piece is red until the next starts to rise, and the
 * red jumps to it in one frame. Mixed across the rise, the old piece went through pink.
 *
 * **Nothing read stands on the seam.** The wall is two projectors meeting at 1920, and a figure that
 * would straddle it slides along to whichever side it can, or is not set.
 *
 * **The scale bar tells the truth about the room**: a round length and the ratio the wall is showing
 * the pieces at, worked from [Scale.METRES_PER_PIXEL] — thousands of times life size on the first
 * shim and about a twentieth by the end. True where the canvas is the projectors' own pixels, as on
 * the committed 3840 wall.
 *
 * **Everything is a function of the frame**: the reveal is a schedule over the sorted pieces, the
 * camera an interpolation between framings, and the loop a remainder. At the end the pieces sink
 * into their places right to left, the wall stands bare for a moment, and the first piece rises again.
 */
class ScaleScene(
    override val name: String = "To scale",
    /** The sheet, read by [loadObjectSheet]: `objects-front.svg`, the elevations. */
    private val sheet: File,
    /**
     * The register, lined up with the sheet by [alignedTo]. **Without it the wall stands empty**:
     * a scale needs real sizes, and a drawing alone has only a proportion.
     */
    private val details: File?,
    private val paper: ColorRGBa = ColorRGBa.fromHex("000000"),
    /** A piece once the next has landed. */
    private val piece: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    /** The piece just arrived — what is being said right now. */
    private val accent: ColorRGBa = slideshow.Palette.RED,
    /** The newest piece's measurements. */
    private val ink: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    /** Every older measurement and the scale bar. */
    private val quiet: ColorRGBa = ColorRGBa.fromHex("8A8D93"),
    /**
     * Seconds from one piece arriving to the next. Under [BEAT] the steps inside it close up; at
     * [BEAT] the finished measurement stands a second and a half before the camera moves on.
     */
    private val beat: Double = BEAT,
    /** Seconds the whole collection stands after the last piece, before it sinks and starts again. */
    private val hold: Double = 8.0,
    private val fontPath: String = "data/fonts/default.otf",
    /** The figures on the dimension lines, in canvas pixels. */
    private val figureSize: Double = 28.0,
    /** The scale bar, and the names when they are on. */
    private val captionSize: Double = 22.0,
    private val scaleBar: Boolean = true,
    /**
     * Each piece's name under its width, where there is room. **Off**, because the room is not
     * packed for it: packing every piece with a name's worth of space under it took the finished
     * wall from 40% covered to 27%, and the wall is about the pieces filling it.
     */
    private val names: Boolean = false,
    /**
     * The wall it composes for, in canvas pixels. **Stated rather than read off the stage**, because
     * the packing is worked out in [load], which is not told the wall — and packing on the first
     * frame instead put a hitch on the cut into it. `draw` fits this into whatever it is given.
     */
    private val wallWidth: Double = 3840.0,
    private val wallHeight: Double = 1080.0,
    /**
     * The people obj the Crowd slide reads, for scale figures standing among the pieces. Null, or a
     * file that is not there, leaves the pieces on their own.
     */
    private val people: File? = null,
    /**
     * How much of the pieces' area the people stand for. The first person comes with the first piece,
     * so there are always people on the wall; another joins each time the pieces' total area has grown
     * by a person's area over this share. **Balanced by material, not by count**: a person every few
     * pieces put eleven figures beside a handful of fittings under half a metre, and the people were
     * most of the wall. By area, the first person stands alone among the fittings — which is the scale
     * of them — and the crowd grows with the walls. 0 keeps the one person.
     */
    private val peopleShare: Double = 0.08,
    /** The people: the Crowd slide's blue, so they read as the reference rather than the catalogue. */
    private val crowd: ColorRGBa = slideshow.Palette.BLUE,
    override val transition: Transition = Cut,
    override val sound: slideshow.Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    /** One drawing and its real size, in the millimetres the register gives. */
    private class Item(
        val element: SheetObject,
        val detail: PieceDetail,
        /** Millimetres across as drawn: the register's width, or its depth for a side elevation. */
        val across: Double,
        val up: Double,
        /** How far the drawing's own proportion is off the register's, as a fraction. */
        val mismatch: Double
    ) {
        val longest = max(across, up)
        val label = detail.name.uppercase()
    }

    private var items: List<Item> = emptyList()
    /**
     * Each piece's triangles, once, as fractions of its own box — so placing it is a scale and an
     * offset, however it is sized, and a frame never tessellates. `drawer.shapes` did, every frame,
     * and on this machine it also waits for the GPU after every shape with a hole: the whole
     * collection cost 29 ms a frame that way.
     */
    private var triangles: List<DoubleArray> = emptyList()
    private var buffer: VertexBuffer? = null

    /**
     * A figure standing among the pieces: its triangles as fractions of its own box, its real size,
     * and the beat it arrives on. People carry no measurements — they are the thing measured against.
     */
    private class Person(val triangles: DoubleArray, val across: Double, val up: Double, val beat: Int, val order: Int)
    private var persons: List<Person> = emptyList()
    private var figures: FontImageMap? = null
    private var captions: FontImageMap? = null

    override fun load(program: Program) {
        if (!sheet.isFile) {
            println("no sheet at ${sheet.path} — \"$name\" stands empty")
            return
        }
        val drawings = loadObjectSheet(sheet)
        val register = details?.let { loadPieceDetails(it) }?.alignedTo(drawings.size)
        if (register == null) {
            println("\"$name\": no register lines up with ${sheet.name}, so nothing has a real size " +
                    "— the wall stands empty")
            return
        }
        val all = drawings.mapIndexedNotNull { i, element -> itemOf(element, register[i]) }
        // Stable on the register's own order, so two pieces of one length come in catalogue order.
        items = all.sortedWith(compareBy<Item>({ it.longest }, { it.detail.no }))
        triangles = items.map { item ->
            val b = item.element.bounds
            val points = item.element.shapes.flatMap { triangulate(it) }
            DoubleArray(points.size * 2) { j ->
                val q = points[j / 2]
                if (j % 2 == 0) (q.x - b.x) / b.width else (q.y - b.y) / b.height
            }
        }
        persons = peopleOf(people)
        // Room for every piece's and person's triangles, or two for a speck, whichever it is drawn as.
        val capacity = triangles.sumOf { max(it.size / 2, 6) } + persons.sumOf { max(it.triangles.size / 2, 6) }
        buffer = vertexBuffer(vertexFormat { position(3) }, capacity.coerceAtLeast(6))

        figures = runCatching {
            program.loadFont(fontPath, figureSize, characterSet = TYPE_CHARACTERS, contentScale = 1.0)
        }.getOrNull()
        captions = runCatching {
            program.loadFont(fontPath, captionSize, characterSet = TYPE_CHARACTERS, contentScale = 1.0)
        }.getOrNull()

        val began = System.nanoTime()
        plan = Plan(Rectangle(0.0, 0.0, wallWidth, wallHeight))
        val packing = (System.nanoTime() - began) / 1e6

        val skipped = drawings.size - items.size
        val sideOn = items.count { it.across != it.detail.widthMm }
        val worst = items.maxOfOrNull { it.mismatch } ?: 0.0
        println(("\"$name\": ${items.size} pieces to scale, %s to %s mm (%.0f times), %d drawn side on, " +
                "drawings within %.0f%% of the register%s, %d people, packed in %.0f ms, a loop of %.1fs").format(
            fmt(items.first().longest), fmt(items.last().longest),
            items.last().longest / items.first().longest, sideOn, worst * 100.0,
            if (skipped > 0) ", $skipped without a size left off" else "", persons.size, packing, periodSeconds
        ))
    }

    /**
     * The people who stand among the pieces: one on the first beat, and another each time the pieces'
     * area has grown by a person over [peopleShare] — each an adult off the obj at their real height,
     * dealt from a fixed shuffle so no figure repeats until every one has stood, and half of them
     * mirrored. A big piece can bring more than one.
     *
     * Adults only — the same three quarters of the tallest the Crowd slide keeps — because a child or
     * a seated figure stands for a different size, and a scale figure has to be a grown-up standing.
     */
    private fun peopleOf(file: File?): List<Person> {
        if (file == null || items.isEmpty()) return emptyList()
        // Millimetres: the obj is in centimetres.
        val all = readSilhouettes(file, 0.1)
        if (all.isEmpty()) {
            println("\"$name\": no people at ${file.path} — the pieces stand on their own")
            return emptyList()
        }
        val tallest = all.maxOf { it.height }
        val adults = all.filter { it.height >= ADULTS * tallest }.shuffled(kotlin.random.Random(SEED))
        // The beat each person joins on: the first with the first piece, then by the pieces' area.
        val person = adults.sortedBy { it.height }[adults.size / 2].let { median ->
            median.height * (median.triangles.maxOf { it.x } - median.triangles.minOf { it.x })
        }
        val beats = mutableListOf(0)
        var area = 0.0
        for ((i, item) in items.withIndex()) {
            area += item.across * item.up
            while (peopleShare > 0.0 && area * peopleShare >= beats.size * person) beats += i
        }
        return beats.indices.map { j ->
            val figure = adults[j % adults.size]
            val lo = figure.triangles.minOf { it.x }
            val wide = figure.triangles.maxOf { it.x } - lo
            val mirrored = (j / adults.size + j) % 2 == 1
            val tri = DoubleArray(figure.triangles.size * 2) { k ->
                val q = figure.triangles[k / 2]
                if (k % 2 == 0) (q.x - lo) / wide else 1.0 - q.y / figure.height
            }
            if (mirrored) for (k in tri.indices step 2) tri[k] = 1.0 - tri[k]
            Person(tri, wide, figure.height, beats[j], j - beats.indexOf(beats[j]))
        }
    }

    /**
     * The piece with its real box, or null where the register gives it no height or no length.
     * Across is whichever of width and depth the drawing's own proportion is nearer, in log terms.
     */
    private fun itemOf(element: SheetObject, detail: PieceDetail): Item? {
        val up = detail.heightMm.takeIf { it > 0.0 } ?: return null
        val drawn = ln(element.aspect.coerceIn(1e-3, 1e3))
        val across = listOf(detail.widthMm, detail.depthMm).filter { it > 0.0 }
            .minByOrNull { abs(ln(it / up) - drawn) } ?: return null
        return Item(element, detail, across, up, kotlin.math.exp(abs(ln(across / up) - drawn)) - 1.0)
    }

    // --- the schedule, in seconds ------------------------------------------------------- //

    /** How much the steps inside a beat close up when the beat is shorter than they are. */
    private val pace get() = min(1.0, beat / BEAT)

    private fun start(i: Int) = LEAD + i * beat
    private val end get() = start(items.size) + hold
    private val periodSeconds get() = end + SINK_SPREAD + SINK + REST
    private val periodFrames get() = frames(periodSeconds).coerceAtLeast(1)

    override val loop: Int get() = if (items.isEmpty()) 0 else periodFrames

    /** The piece arriving or last arrived at [t]. */
    private fun current(t: Double) = floor((t - LEAD) / beat).toInt().coerceIn(0, max(0, items.size - 1))

    /** How far a step has got at [t] for piece [i]: 0 before [at] into its beat, 1 after [length]. */
    private fun progress(t: Double, i: Int, at: Double, length: Double) =
        calm(((t - start(i) - at * pace) / (length * pace)).coerceIn(0.0, 1.0))

    // --- the packing and the framings --------------------------------------------------- //

    /** Where the camera stands: `screen = offset + scale * mm`, the offset one per axis. */
    private data class View(val scale: Double, val x: Double, val y: Double)

    /**
     * Every piece's place on a wall of [bounds], in millimetres (y down, as on the screen), and the
     * framing after each piece is added.
     *
     * A piece is packed with room around it — for its height figure on the left and its width
     * figure underneath — sized in pixels at the scale it arrives at, so the newest piece always has
     * room to be measured. Worked out once, in [load], so `draw` only interpolates.
     */
    private inner class Plan(val bounds: Rectangle) {
        val h = bounds.height
        val left = bounds.x
        val right = bounds.x + bounds.width
        val projectors = max(1, (bounds.width / (h * 16.0 / 9.0)).roundToInt())
        val seams = (1 until projectors).map { bounds.x + it * bounds.width / projectors }
        /** What the collection is framed into: the wall less its margins and the scale bar's band. */
        val area = Rectangle(
            bounds.x + h * SIDE, bounds.y + h * BAND,
            bounds.width - 2.0 * h * SIDE, bounds.height - h * BAND - h * FOOT
        )

        val x = DoubleArray(items.size)
        val y = DoubleArray(items.size)
        /** The room packed round each piece, in millimetres: left, underneath, and the other two. */
        val roomLeft = DoubleArray(items.size)
        val roomBottom = DoubleArray(items.size)
        val roomEdge = DoubleArray(items.size)
        /** Where each person stands, the same way. */
        val px = DoubleArray(persons.size)
        val py = DoubleArray(persons.size)
        val views = ArrayList<View>(items.size)

        private fun fit(b: DoubleArray) = min(area.width / (b[2] - b[0]), area.height / (b[3] - b[1]))
        private fun union(a: DoubleArray?, b: DoubleArray) = if (a == null) b else doubleArrayOf(
            min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])
        )
        private fun overlaps(a: DoubleArray, b: DoubleArray) =
            a[0] < b[2] - 1e-9 && b[0] < a[2] - 1e-9 && a[1] < b[3] - 1e-9 && b[1] < a[3] - 1e-9

        init {
            val placed = ArrayList<DoubleArray>()
            var box: DoubleArray? = null

            /**
             * Packs a [w] by [hh] body with [left], [bottom] and [edge] pixels of room round it, and
             * says where its footprint went and the scale that room was sized at. The room is in pixels
             * at the scale the body arrives at, which depends on where it goes: estimate, place, and
             * settle in a few rounds.
             */
            fun pack(w: Double, hh: Double, left: Double, bottom: Double, edge: Double): Pair<DoubleArray, Double> {
                var at = box?.let { fit(it) } ?: min(area.width / w, area.height / hh)
                var chosen = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                for (round in 0 until 6) {
                    val fw = w + (left + edge) / at
                    val fh = hh + (edge + bottom) / at
                    chosen = box?.let { best(fw, fh, it, placed) } ?: doubleArrayOf(0.0, 0.0, fw, fh)
                    val next = fit(union(box, chosen))
                    if (abs(next - at) <= next * 0.03) break
                    at = next
                }
                placed += chosen
                box = union(box, chosen)
                return chosen to at
            }

            var next = 0
            for (i in items.indices) {
                val (chosen, at) = pack(items[i].across, items[i].up, ROOM_LEFT, ROOM_BOTTOM, ROOM_EDGE)
                roomLeft[i] = ROOM_LEFT / at
                roomBottom[i] = ROOM_BOTTOM / at
                roomEdge[i] = ROOM_EDGE / at
                x[i] = chosen[0] + roomLeft[i]
                y[i] = chosen[1] + roomEdge[i]
                // The people due on this beat go in after its piece, with room only to stand apart.
                while (next < persons.size && persons[next].beat == i) {
                    val person = persons[next]
                    val (spot, where) = pack(person.across, person.up, PERSON_ROOM, PERSON_ROOM, PERSON_ROOM)
                    px[next] = spot[0] + PERSON_ROOM / where
                    py[next] = spot[1] + PERSON_ROOM / where
                    next++
                }
                val box = box!!
                val s = fit(box)
                views += View(
                    s,
                    area.center.x - s * (box[0] + box[2]) / 2.0,
                    area.center.y - s * (box[1] + box[3]) / 2.0
                )
            }
        }

        /**
         * Where a footprint [fw] by [fh] goes: against a corner of a piece already down or of the
         * whole [box], clear of everything, where the collection still fits the frame largest —
         * and of those, the one nearest the middle.
         */
        private fun best(fw: Double, fh: Double, box: DoubleArray, placed: List<DoubleArray>): DoubleArray {
            var found = doubleArrayOf(box[2], box[1], box[2] + fw, box[1] + fh)
            var score = -1.0
            var near = Double.MAX_VALUE
            for (f in placed) {
                for (cx in doubleArrayOf(f[2], f[0] - fw, f[0], f[2] - fw, box[0], box[2] - fw)) {
                    for (cy in doubleArrayOf(f[1], f[3] - fh, f[3], f[1] - fh, box[1], box[3] - fh)) {
                        val c = doubleArrayOf(cx, cy, cx + fw, cy + fh)
                        if (placed.any { overlaps(c, it) }) continue
                        val grown = union(box, c)
                        val s = fit(grown)
                        val dx = (cx + fw / 2.0 - (grown[0] + grown[2]) / 2.0) * s / area.width
                        val dy = (cy + fh / 2.0 - (grown[1] + grown[3]) / 2.0) * s / area.height
                        val d = dx * dx + dy * dy
                        if (s > score * (1.0 + 1e-9) || (s >= score * (1.0 - 1e-9) && d < near)) {
                            found = c
                            score = s
                            near = d
                        }
                    }
                }
            }
            return found
        }

        /**
         * The camera at [t]: from the last framing to this piece's over the first [MOVE] of its
         * beat, as a zoom about the one point the two framings share.
         */
        fun camera(t: Double): View {
            val i = current(t)
            if (i == 0) return views[0]
            val m = calm(((t - start(i)) / (MOVE * pace)).coerceIn(0.0, 1.0))
            val a = views[i - 1]
            val b = views[i]
            if (abs(b.scale - a.scale) <= 1e-9 * a.scale) {
                return View(a.scale, a.x + (b.x - a.x) * m, a.y + (b.y - a.y) * m)
            }
            val s = a.scale * (b.scale / a.scale).pow(m)
            // The point both framings put in the same place, and the zoom about it.
            val stillX = (b.x - a.x) / (a.scale - b.scale)
            val stillY = (b.y - a.y) / (a.scale - b.scale)
            return View(s, a.x + (a.scale - s) * stillX, a.y + (a.scale - s) * stillY)
        }
    }

    private var plan: Plan? = null

    // --- drawing ----------------------------------------------------------------------- //

    override fun draw(drawer: Drawer, stage: Stage) {
        val plan = plan ?: return
        if (items.isEmpty()) return
        // The wall as composed, fitted into the one given — the identity on the committed wall.
        val fit = min(stage.width / wallWidth, stage.height / wallHeight)
        drawer.isolated {
            translate(
                stage.bounds.x + (stage.width - wallWidth * fit) / 2.0,
                stage.bounds.y + (stage.height - wallHeight * fit) / 2.0
            )
            scale(fit)
            compose(drawer, stage, plan)
        }
    }

    private fun compose(drawer: Drawer, stage: Stage, plan: Plan) {
        val t = seconds(stage.frame % periodFrames)
        val cur = current(t)
        val view = plan.camera(t)

        // The collection sinks into its places right to left, off where each stands at the end.
        val last = plan.views.last()
        fun sunkAt(middle: Double): Double {
            if (t < end) return 0.0
            val x = last.x + last.scale * middle
            val order = ((plan.right - x) / wallWidth).coerceIn(0.0, 1.0)
            return calm(((t - end - order * SINK_SPREAD) / SINK).coerceIn(0.0, 1.0))
        }
        fun sunk(k: Int) = sunkAt(plan.x[k] + items[k].across / 2.0)

        // How far the camera has come on this beat: the last piece hands over its standing as the
        // newest over the same move, so its measurements do not pop at the beat.
        val move = if (cur == 0) 1.0 else calm(((t - start(cur)) / (MOVE * pace)).coerceIn(0.0, 1.0))
        val arrived = progress(t, cur, RISE_AT, RISE)

        // The pieces, into one buffer: the settled ones first and the red one after, so the whole
        // collection is two draw calls however many are down.
        val buf = buffer ?: return
        var settled = 0
        var reds = 0
        var total = 0

        /** A body [across] by [up] mm at [x], [y], risen [rise], into the buffer: its triangles or a speck. */
        fun org.openrndr.draw.BufferWriter.body(tri: DoubleArray, across: Double, up: Double, x: Double, y: Double, rise: Double) {
            val s = view.scale
            var wide = s * across
            var tall = s * up
            var xl = view.x + s * x
            val bottom = view.y + s * (y + up)
            if (max(wide, tall) < LEAST) {
                // Too small for the scale: a speck in its place, so it is still on the wall.
                val ratio = min(wide, tall) / max(wide, tall).coerceAtLeast(1e-12)
                val centre = xl + wide / 2.0
                if (wide >= tall) { wide = LEAST; tall = max(1.0, LEAST * ratio) }
                else { tall = LEAST; wide = max(1.0, LEAST * ratio) }
                xl = centre - wide / 2.0
                val y0 = bottom - tall * rise
                for ((u, v) in SQUARE) write(Vector3(xl + u * wide, y0 + v * tall * rise, 0.0))
                total += 6
            } else {
                val top = bottom - tall * rise
                var j = 0
                while (j < tri.size) {
                    write(Vector3(xl + tri[j] * wide, top + tri[j + 1] * tall * rise, 0.0))
                    j += 2
                }
                total += tri.size / 2
            }
        }

        buf.put {
            for (pass in 0..1) {
                for (k in 0..cur) {
                    // One red on the wall at a time: it passes to the new piece the frame that
                    // piece starts to rise. Mixed across the rise instead, it went through pink.
                    val red = k == cur || (k == cur - 1 && arrived <= 0.0)
                    if (red != (pass == 1)) continue
                    val item = items[k]
                    val rise = progress(t, k, RISE_AT, RISE) * (1.0 - sunk(k))
                    if (rise <= 0.0) continue
                    body(triangles[k], item.across, item.up, plan.x[k], plan.y[k], rise)
                }
                if (pass == 0) settled = total
            }
            reds = total
            // The people last, a beat's person rising just after its piece.
            for (j in persons.indices) {
                val person = persons[j]
                if (person.beat > cur) break
                // Several joining on one beat stand up one after another.
                val rise = progress(t, person.beat, PERSON_AT + person.order * PERSON_STAGGER, RISE) *
                        (1.0 - sunkAt(plan.px[j] + person.across / 2.0))
                if (rise <= 0.0) continue
                body(person.triangles, person.across, person.up, plan.px[j], plan.py[j], rise)
            }
        }
        drawer.isolated {
            stroke = null
            fill = piece
            if (settled > 0) vertexBuffer(buf, DrawPrimitive.TRIANGLES, 0, settled)
            fill = accent
            if (reds > settled) vertexBuffer(buf, DrawPrimitive.TRIANGLES, settled, reds - settled)
            fill = crowd
            if (total > reds) vertexBuffer(buf, DrawPrimitive.TRIANGLES, reds, total - reds)
        }

        for (k in 0..cur) {
            val newness = when (k) {
                cur -> 1.0
                cur - 1 -> 1.0 - move
                else -> 0.0
            }
            val colour = when (k) {
                cur -> ink
                cur - 1 -> ink.mix(quiet, arrived)
                else -> quiet
            }
            measure(drawer, plan, t, k, view, newness, colour, 1.0 - sunk(k))
        }

        if (scaleBar) scaleBar(drawer, plan, view.scale)
        drawer.stroke = null
    }

    /**
     * Piece [k]'s height and width, each faded by the room the camera leaves it. [newness] is 1 for
     * the piece just arrived, whose measurements stand whatever the room.
     */
    private fun measure(
        drawer: Drawer, plan: Plan, t: Double, k: Int, view: View,
        newness: Double, colour: ColorRGBa, fade: Double
    ) {
        if (fade <= 0.0) return
        val item = items[k]
        val s = view.scale
        val xl = view.x + s * plan.x[k]
        val top = view.y + s * plan.y[k]
        val wide = s * item.across
        val tall = s * item.up
        val xr = xl + wide
        val bottom = top + tall
        // The piece's own room on the wall now: its footprint, which nothing else stands in.
        val roomLeft = s * plan.roomLeft[k]
        val roomBottom = s * plan.roomBottom[k]
        val footLeft = xl - roomLeft
        val footRight = xr + s * plan.roomEdge[k]
        val newest = newness >= 1.0
        val figures = figures
        val cap = figureSize * CAP
        val reach = OFFSET + FIGURE_GAP + cap

        // --- the height, up the left side ---
        val ph = progress(t, k, HEIGHT_AT, DIM)
        if (ph > 0.0) {
            val text = fmt(item.up)
            val adv = figures?.advanceOf(text) ?: 0.0
            val room = clear(roomLeft - reach) * clear(tall - adv - 8.0)
            val alpha = max(room, newness) * fade
            if (alpha > 0.0) {
                val x = xl - OFFSET
                val paint = colour.opacify(alpha)
                val lines = ArrayList<Vector2>(14)
                lines.segment(xl - EXT_GAP, bottom, x - EXT_OVER, bottom)
                lines.segment(xl - EXT_GAP, top, x - EXT_OVER, top)
                lines.segment(x, bottom, x, bottom - tall * ph)
                lines.slash(x, bottom)
                if (ph >= 1.0) lines.slash(x, top)
                strokes(drawer, paint, lines)
                // Read upward, its baseline beside the line — clear of the seam or not set.
                val baseline = x - FIGURE_GAP
                if (figures != null && plan.seams.none { it > baseline - cap - SEAM_CLEAR && it < baseline + SEAM_CLEAR }) {
                    val count = fmt(item.up * ph)
                    drawer.isolated {
                        fontMap = figures
                        fill = paint
                        translate(baseline, (top + bottom) / 2.0)
                        rotate(-90.0)
                        // odometer: the digits fill in from the end of the finished figure
                        text(count, adv / 2.0 - figures.advanceOf(count), 0.0)
                    }
                }
            }
        }

        // --- the width, underneath ---
        val pw = progress(t, k, WIDTH_AT, DIM)
        val y = bottom + OFFSET
        val figureBase = y + FIGURE_GAP + cap
        val lo = if (newest) plan.left + EDGE else footLeft
        val hi = if (newest) plan.right - EDGE else footRight
        if (pw > 0.0) {
            val text = fmt(item.across)
            val adv = figures?.advanceOf(text) ?: 0.0
            val room = clear(roomBottom - reach) * clear(footRight - footLeft - adv - 8.0)
            val alpha = max(room, newness) * fade
            if (alpha > 0.0) {
                val paint = colour.opacify(alpha)
                val lines = ArrayList<Vector2>(14)
                lines.segment(xl, bottom + EXT_GAP, xl, y + EXT_OVER)
                lines.segment(xr, bottom + EXT_GAP, xr, y + EXT_OVER)
                lines.segment(xl, y, xl + wide * pw, y)
                lines.slash(xl, y)
                if (pw >= 1.0) lines.slash(xr, y)
                strokes(drawer, paint, lines)
                if (figures != null) {
                    placed((xl + xr) / 2.0, adv, lo, hi, plan.seams)?.let { at ->
                        val count = fmt(item.across * pw)
                        drawer.isolated {
                            fontMap = figures
                            fill = paint
                            text(count, at + adv - figures.advanceOf(count), figureBase)
                        }
                    }
                }
            }
        }

        // --- the name, under its width, only where the room allows ---
        val captions = captions
        if (names && captions != null) {
            val pn = progress(t, k, NAME_AT, NAME)
            if (pn > 0.0) {
                val tracking = captionSize * TRACKING
                val adv = tracked(captions, item.label, tracking)
                val base = figureBase + NAME_GAP + captionSize * CAP
                val alpha = clear(roomBottom - (base - bottom)) * clear(footRight - footLeft - adv - 8.0) * pn * fade
                if (alpha > 0.0) {
                    placed((xl + xr) / 2.0, adv, footLeft, footRight, plan.seams)?.let { at ->
                        drawer.isolated {
                            fontMap = captions
                            fill = colour.mix(quiet, 0.35).opacify(alpha)
                            var pen = at
                            item.label.forEachIndexed { i, ch ->
                                text(ch.toString(), pen, base)
                                pen += (captions.glyphMetrics[ch]?.advanceWidth ?: 0.0) + tracking +
                                        (if (i + 1 < item.label.length) captions.kerning(ch, item.label[i + 1]) else 0.0)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The ratio the wall is showing the pieces at, and a round length under it, top left of the
     * first projector in the band the collection is never framed into.
     */
    private fun scaleBar(drawer: Drawer, plan: Plan, s: Double) {
        val captions = captions ?: return
        val h = plan.h
        val x0 = plan.left + h * SIDE
        val y0 = plan.bounds.y + h * 0.04
        val length = niceLength(s, h * 0.1)
        val span = length * s
        val cap = captionSize * CAP

        // mm on the wall for a mm of piece: the projection, stated in Scale.
        val onWall = s * Scale.METRES_PER_PIXEL * 1000.0
        val ratio = if (onWall >= 1.0) "${ratioOf(onWall)} : 1" else "1 : ${ratioOf(1.0 / onWall)}"

        drawer.isolated {
            fontMap = captions
            fill = ink
            text("SCHAAL  $ratio", x0, y0 + cap)
        }
        val bar = y0 + cap + 24.0
        line(drawer, quiet, x0, bar, x0 + span, bar)
        for (f in listOf(0.0, 0.5, 1.0)) {
            line(drawer, quiet, x0 + span * f, bar, x0 + span * f, bar - (if (f == 0.5) 5.0 else 10.0))
        }
        drawer.isolated {
            fontMap = captions
            fill = quiet
            text(lengthOf(length), x0 + span + 14.0, bar + cap / 2.0 - 2.0)
        }
    }

    // --- small things ------------------------------------------------------------------- //

    private fun line(drawer: Drawer, paint: ColorRGBa, x0: Double, y0: Double, x1: Double, y1: Double) {
        drawer.stroke = paint
        drawer.strokeWeight = WEIGHT
        drawer.lineSegment(x0, y0, x1, y1)
        drawer.stroke = null
    }

    private fun MutableList<Vector2>.segment(x0: Double, y0: Double, x1: Double, y1: Double) {
        add(Vector2(x0, y0))
        add(Vector2(x1, y1))
    }

    /** The architect's slash at the end of a dimension line. */
    private fun MutableList<Vector2>.slash(x: Double, y: Double) = segment(x - TICK, y + TICK, x + TICK, y - TICK)

    /** A dimension's lines in one call: they share a colour, so there is no reason for more. */
    private fun strokes(drawer: Drawer, paint: ColorRGBa, lines: List<Vector2>) {
        drawer.stroke = paint
        drawer.strokeWeight = WEIGHT
        drawer.lineSegments(lines)
        drawer.stroke = null
    }

    /**
     * Where a label [width] wide centred on [centre] can stand inside [lo]..[hi] without lying
     * across a seam, as its left edge — or null where it cannot.
     */
    private fun placed(centre: Double, width: Double, lo: Double, hi: Double, seams: List<Double>): Double? {
        if (width > hi - lo) return null
        var l = (centre - width / 2.0).coerceIn(lo, hi - width)
        for (seam in seams) {
            if (l < seam + SEAM_CLEAR && l + width > seam - SEAM_CLEAR) {
                l = listOf(seam - SEAM_CLEAR - width, seam + SEAM_CLEAR)
                    .filter { it >= lo && it + width <= hi }
                    .minByOrNull { abs(it - l) } ?: return null
            }
        }
        return l
    }

    /** How wide [text] sets with [tracking] between its letters. */
    private fun tracked(map: FontImageMap, text: String, tracking: Double) =
        map.advanceOf(text) + tracking * (text.length - 1).coerceAtLeast(0)

    /** 0 with no room, 1 with [FADE] pixels of it to spare. */
    private fun clear(room: Double) = (room / FADE).coerceIn(0.0, 1.0)

    /** Millimetres as the register writes them, whole. */
    private fun fmt(mm: Double) = mm.roundToInt().toString()

    /** The smallest 1, 2 or 5 times a power of ten that spans at least [pixels] at [s]. */
    private fun niceLength(s: Double, pixels: Double): Double {
        var decade = 1e-2
        while (decade < 1e7) {
            for (m in listOf(1.0, 2.0, 5.0)) if (m * decade * s >= pixels) return m * decade
            decade *= 10.0
        }
        return 1e7
    }

    private fun lengthOf(mm: Double) = when {
        mm < 1.0 -> "%.1f MM".format(java.util.Locale.ROOT, mm).replace('.', ',')
        mm < 1000.0 -> "${mm.roundToInt()} MM"
        else -> "${(mm / 1000.0).roundToInt()} M"
    }

    private fun ratioOf(v: Double) =
        if (v >= 10.0) v.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, v).replace('.', ',')

    private fun calm(t: Double) = CALM(t)

    // --- the wall as timing ------------------------------------------------------------- //

    override val lanes: List<String> get() = listOf("pieces", "heights", "widths", "people")

    /** A note as each piece stands up, as each of its dimensions is drawn, and as each person joins. */
    override fun arrivals(clicks: List<Int>): List<Arrival> {
        if (items.isEmpty()) return super.arrivals(clicks)
        return items.indices.flatMap { i ->
            listOf(
                Arrival(0, i, frames(start(i) + RISE_AT * pace), frames(RISE * pace).coerceAtLeast(1)),
                Arrival(1, i, frames(start(i) + HEIGHT_AT * pace), frames(DIM * pace).coerceAtLeast(1)),
                Arrival(2, i, frames(start(i) + WIDTH_AT * pace), frames(DIM * pace).coerceAtLeast(1))
            )
        } + persons.mapIndexed { j, person ->
            Arrival(3, j, frames(start(person.beat) + (PERSON_AT + person.order * PERSON_STAGGER) * pace),
                frames(RISE * pace).coerceAtLeast(1))
        }
    }

    override val midiFrames: Int? get() = if (items.isEmpty()) null else periodFrames

    private companion object {
        /** The calm move, for a wall that stands for minutes — style-guide/motion.md. */
        val CALM = CubicBezier(0.4, 0.0, 0.2, 1.0)

        /** The beat the steps inside it are timed against. */
        const val BEAT = 5.0
        /** Seconds of empty wall before the first piece. */
        const val LEAD = 0.8
        /** The camera's move to the next framing. */
        const val MOVE = 1.8
        /**
         * The piece standing up from its own foot, from [RISE_AT] into its beat — once the camera
         * has all but landed. Rising while the camera still zoomed, it came in oversized and shrank
         * into place, which read as the piece settling rather than as the piece arriving.
         */
        const val RISE_AT = 1.4
        const val RISE = 0.8
        /** A person due on a beat rises a moment after its piece, so the piece is seen arriving first. */
        const val PERSON_AT = 1.7
        const val PERSON_STAGGER = 0.2
        /** Its height drawn, then its width, each over [DIM]; then its name, when names are on. */
        const val HEIGHT_AT = 2.0
        const val WIDTH_AT = 2.6
        const val DIM = 0.8
        const val NAME_AT = 3.2
        const val NAME = 0.3
        /** The collection sinking at the end: spread across the wall, each over [SINK], then bare. */
        const val SINK_SPREAD = 1.6
        const val SINK = 0.8
        const val REST = 1.0

        /** Of the wall's height: the side margins, the scale bar's band at the top, the foot. */
        const val SIDE = 0.05
        const val BAND = 0.09
        const val FOOT = 0.04

        /**
         * The room packed round a piece, in pixels at the scale it arrives at: on its left for the
         * height figure, underneath for the width figure, and a sliver on the other two sides.
         */
        const val ROOM_LEFT = 62.0
        const val ROOM_BOTTOM = 64.0
        const val ROOM_EDGE = 6.0
        /** The room round a person, on every side: enough to stand apart, no figures to hold. */
        const val PERSON_ROOM = 10.0
        /** People as tall as this share of the tallest: the adults, the Crowd slide's cut. */
        const val ADULTS = 0.75
        const val SEED = 7

        /** A dimension line's distance off the piece, in pixels. */
        const val OFFSET = 18.0
        const val WEIGHT = 2.0
        const val TICK = 5.0
        const val EXT_GAP = 5.0
        const val EXT_OVER = 7.0
        const val FIGURE_GAP = 8.0
        const val NAME_GAP = 12.0
        const val EDGE = 24.0
        const val SEAM_CLEAR = 10.0
        /** Pixels over which a measurement fades as its room runs out. */
        const val FADE = 10.0
        /** The smallest a piece is drawn, in pixels across: below it, a speck. */
        const val LEAST = 3.0
        const val CAP = 0.7
        const val TRACKING = 0.12
        /** A speck's two triangles, as fractions of its box. */
        val SQUARE = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
    }
}
