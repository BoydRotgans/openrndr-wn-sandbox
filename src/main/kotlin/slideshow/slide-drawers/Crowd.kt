package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The social and governance pillars told as a crowd: one person, then the group around them,
 * then the lines between them, then the group as an arrow with one out in front, then the
 * whole as a disc with a share of it marked out. Five states, a click apart, read off
 * `data/ref/social.pdf`.
 *
 * **Every figure is a slot, and a slot keeps its figure across the whole slide.** Each of the
 * five states is a *formation* — a list of places, one a slot — and the deck's `position`
 * says which two the slide is between. A slot in both moves from the one place to the other;
 * a slot only in the later one stands up at its place, the nearest to the middle first, so a
 * formation grows out of the figures already there rather than being dealt over them. That
 * is what makes it one crowd being re-arranged rather than five pictures of crowds, and it is
 * `packBoxes` and `stateAt` again: one number in, every figure's place out.
 *
 * Slot 0 is the individual the slide opens on. It stays exactly where it is while the group
 * forms around it, is one of the white kin in that group, turns blue with the rest when the
 * lines come, is the one out ahead of the arrow, and comes back to the middle of the whole.
 * The slide is that person's story as much as the group's.
 *
 * **Which figure goes where between two formations is matched, not ranked.** Both formations
 * order their places nearest-the-middle first, but handing rank *n* of the one to rank *n* of
 * the other sends a figure from the left of the crowd to the right of the arrow, and the
 * paths cross. So each figure already standing takes the free place nearest to where it is,
 * measured in figure heights from the formation's own middle — Decision's rule for pairing
 * its two drawings, greedy because the counts run to seven hundred.
 *
 * **The silhouettes are the 3D people flattened, not drawings of people.** The obj carries
 * 114 low-poly figures; each is projected orthographically along the direction that shows
 * it *widest* — the principal axis of its footprint — so a standing figure is seen from the
 * front and a striding one from the side, which is the mix the reference shows. All 114 go
 * into one vertex buffer at load and every figure on the wall is an instance of one of them,
 * so a disc of seven hundred is a hundred draw calls, not seven hundred. They are drawn into
 * a multisampled buffer first: at 26 pixels tall a silhouette is all edge, and the deck's
 * own buffers are not multisampled.
 *
 * **The lines are drawn under the figures.** A line runs from one figure's chest to
 * another's, and where it crosses either body it is covered — so what shows is the stretch
 * between them, starting and ending at the silhouettes' edges, with nothing measured. The
 * belt wall's shadow trick, inverted.
 */
class Crowd(
    /**
     * The title over each state, in order — so a run of the same title holds and a change
     * crossfades on the click. Also the click count.
     */
    private val titles: List<String> = listOf(
        "ESG Social", "ESG Social", "ESG Governance", "ESG Governance", "ESG Governance"
    ),
    private val file: File = File("data/ref/silhouette_people_lowpoly_obj.obj"),
    private val fontPath: String = "data/fonts/default.otf",
    /** The one, the kin, the leader, the whole: what stands apart from the crowd. */
    private val one: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    /** The crowd. */
    private val many: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    /** The share of the whole marked out on the last click. */
    private val share: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /** The crowd's grid: columns across the pane, rows about the middle, and the row pitch and figure height as shares of the pane's height. */
    private val columns: Int = 11,
    private val rows: Int = 5,
    private val pitch: Double = 0.27,
    private val figure: Double = 0.24,
    /** How many of the crowd, nearest the one, stand in its colour. */
    private val kin: Int = 9,
    /** How many lines the network draws. */
    private val links: Int = 16,
    /** Figures shorter than this share of the tallest are left out: the seated and the children. */
    private val shortest: Double = 0.75,
    private val seed: Int = 7,
    override val stepFrames: Int = frames(1.5),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Crowd"
    override val steps get() = titles.size

    override fun stepName(step: Int): String? = when (step) {
        1 -> "the group gathers"
        2 -> "the network"
        3 -> "the arrow"
        4 -> "the whole"
        else -> null
    }

    /** One silhouette: its run in the vertex buffer and its height, in adult heights. */
    private class Variant(val start: Int, val count: Int, val height: Double)

    /** A place in a formation: where the feet stand, how tall an adult is there, and the colour. */
    private class Spot(val foot: Vector2, val size: Double, val tint: ColorRGBa) {
        val centre: Vector2 get() = foot - Vector2(0.0, size / 2.0)
    }

    /** One state of the crowd: a spot a slot, and the middle and unit its spots are measured from. */
    private class Formation(val spots: List<Spot>, val centre: Vector2, val unit: Double)

    private class Link(val a: Int, val b: Int)

    private lateinit var face: FontImageMap
    private var mesh: VertexBuffer? = null
    private var variants: List<Variant> = emptyList()
    private lateinit var style: ShadeStyle

    // Laid out against the pane on first sight of it, and again only if the pane changes.
    private var laidFor: Pair<Int, Int>? = null
    private var formations: List<Formation> = emptyList()
    private var network: List<Link> = emptyList()
    private var travels: BooleanArray = BooleanArray(0)    // pair k -> anything already standing moves
    private var order: IntArray = IntArray(0)          // slot -> variant
    private var flipped: BooleanArray = BooleanArray(0) // slot -> mirrored
    private var batches: List<VertexBuffer> = emptyList()
    private var wall: RenderTarget? = null
    private var flat: ColorBuffer? = null

    override fun load(program: Program) {
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        style = shadeStyle {
            vertexTransform = "x_position.xy = i_foot + vec2(x_position.x * i_size.x, -x_position.y * i_size.y);"
            fragmentTransform = "x_fill = vi_tint;"
        }
        val read = read(file) ?: run { println("crowd: no figures at ${file.path} — the title stands alone"); return }
        mesh = read.first
        variants = read.second
        println("crowd: ${variants.size} figures off ${file.name}, ${mesh?.vertexCount?.div(3)} triangles")
    }

    // ------------------------------------------------------------------------------ //
    //  The figures
    // ------------------------------------------------------------------------------ //

    /**
     * Every group in the obj as a flat silhouette in one vertex buffer.
     *
     * The file is Y up with the feet on y = 0 and real-ish centimetres. Each figure is seen
     * along the direction that shows it widest: the principal axis of its footprint in the
     * ground plane is the direction it is *broadest across*, so projecting onto that axis and
     * looking along its perpendicular gives the fullest silhouette the model has. Normalised
     * to adult heights — [ADULT] units is 1 — rather than to each figure's own height, so a
     * child stays a child beside a grown-up.
     */
    private fun read(file: File): Pair<VertexBuffer, List<Variant>>? {
        if (!file.isFile) return null
        val verts = ArrayList<Vector3>()
        val groups = LinkedHashMap<String, MutableList<IntArray>>()
        var current = groups.getOrPut("") { mutableListOf() }
        file.forEachLine { line ->
            when {
                line.startsWith("v ") -> {
                    val t = line.trim().split(Regex("\\s+"))
                    verts += Vector3(t[1].toDouble(), t[2].toDouble(), t[3].toDouble())
                }
                line.startsWith("g ") -> current = groups.getOrPut(line.substring(2).trim()) { mutableListOf() }
                line.startsWith("f ") -> {
                    val ids = line.substring(2).trim().split(Regex("\\s+")).map { it.substringBefore('/').toInt() - 1 }
                    for (k in 1 until ids.size - 1) current += intArrayOf(ids[0], ids[k], ids[k + 1])
                }
            }
        }

        class Flat(val triangles: List<Vector2>, val height: Double)

        val flats = groups.values.filter { it.isNotEmpty() }.map { faces ->
            val used = faces.flatMap { it.toList() }.distinct().map { verts[it] }
            val floor = used.minOf { it.y }
            val height = (used.maxOf { it.y } - floor) / ADULT

            // the broadest direction across the footprint
            val mx = used.sumOf { it.x } / used.size
            val mz = used.sumOf { it.z } / used.size
            var sxx = 0.0; var szz = 0.0; var sxz = 0.0
            for (v in used) { val dx = v.x - mx; val dz = v.z - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz }
            val theta = 0.5 * atan2(2.0 * sxz, sxx - szz)
            val ax = cos(theta); val az = sin(theta)

            fun across(v: Vector3) = (v.x - mx) * ax + (v.z - mz) * az
            val left = used.minOf { across(it) }
            val right = used.maxOf { across(it) }
            val middle = (left + right) / 2.0

            val triangles = faces.flatMap { f ->
                f.map { i -> val v = verts[i]; Vector2((across(v) - middle) / ADULT, (v.y - floor) / ADULT) }
            }
            Flat(triangles, height)
        }
        if (flats.isEmpty()) return null

        val tallest = flats.maxOf { it.height }
        val kept = flats.filter { it.height >= shortest * tallest }
        val total = kept.sumOf { it.triangles.size }
        val buffer = vertexBuffer(vertexFormat { position(3) }, total.coerceAtLeast(1))
        val variants = ArrayList<Variant>()
        buffer.put {
            var at = 0
            for (f in kept) {
                for (p in f.triangles) write(Vector3(p.x, p.y, 0.0))
                variants += Variant(at, f.triangles.size, f.height)
                at += f.triangles.size
            }
            if (total == 0) write(Vector3.ZERO)
        }
        return buffer to variants
    }

    // ------------------------------------------------------------------------------ //
    //  The formations
    // ------------------------------------------------------------------------------ //

    /** The five states, laid out against a pane of [w] by [h]. */
    private fun formations(w: Double, h: Double): List<Formation> {
        val mid = Vector2(w / 2.0, h / 2.0)
        val f = h * figure

        // 0: the one, standing in the middle at the crowd's own size, so nothing about it
        // moves when the crowd arrives.
        val single = Formation(listOf(Spot(mid + Vector2(0.0, f / 2.0), f, one)), mid, f)

        // 1: the crowd. A grid with a row through the middle, the outer rows bleeding off the
        // pane top and bottom, so it reads as a crowd the pane is looking into rather than a
        // set of rows on it. Ordered nearest the one first, in cells, so the kin are the block
        // around it.
        val cw = w / columns
        val rh = h * pitch
        val cells = buildList {
            for (r in -(rows / 2)..rows / 2) for (c in 0 until columns) {
                val centre = Vector2((c + 0.5) * cw, h / 2.0 + r * rh)
                add(Spot(centre + Vector2(0.0, f / 2.0), f, many))
            }
        }.sortedBy { val d = it.centre - mid; (d.x / cw) * (d.x / cw) + (d.y / rh) * (d.y / rh) }
        val crowd = assemble(single, cells, mid, f).let { fm ->
            Formation(fm.spots.mapIndexed { i, s -> Spot(s.foot, s.size, if (i < kin) one else many) }, fm.centre, fm.unit)
        }

        // 2: the same crowd, one colour, with the lines between (built separately).
        val network = Formation(crowd.spots.map { Spot(it.foot, it.size, many) }, mid, f)

        // 3: the arrow. A shaft four rows deep and a head eight, on a grid of small figures;
        // a cell is in the arrow if its centre is inside the polygon, so the head's rows
        // shorten toward the tip by themselves. The one stands a step beyond the tip.
        val a = h * ARROW_FIGURE
        val ap = h * ARROW_ROW
        val ac = w * ARROW_COL
        val tail = w * ARROW_TAIL
        val neck = w * ARROW_NECK
        val tip = w * ARROW_TIP
        val shaft = 2.0 * ap
        val head = 4.0 * ap
        val arrowCells = buildList {
            for (r in -4 until 4) {
                val cy = h / 2.0 + (r + 0.5) * ap
                val dy = abs(cy - h / 2.0)
                var c = 0
                while (true) {
                    val cx = tail + (c + 0.5) * ac
                    if (cx > tip) break
                    val inside = if (cx < neck) dy < shaft else dy < head * (1.0 - (cx - neck) / (tip - neck))
                    if (inside) add(Spot(Vector2(cx, cy + a / 2.0), a, many))
                    c++
                }
            }
        }
        val arrowMid = arrowCells.fold(Vector2.ZERO) { acc, s -> acc + s.centre } / arrowCells.size.toDouble()
        val leader = Spot(Vector2(tip + w * ARROW_LEAD, h / 2.0 + a / 2.0), a, one)
        val arrow = assemble(network, arrowCells.sortedBy { (it.centre - arrowMid).squaredLength }, arrowMid, a, lead = leader)

        // 4: the whole. Tiny figures on a grid, kept to a disc, with the share marked out as
        // the part of the disc that lies outside a second circle standing left of it — a
        // crescent along the right edge, widest at the middle and tapering to the top and
        // bottom. The one comes back to the centre.
        val t = h * DISC_FIGURE
        val dp = h * DISC_ROW
        val dc = w * DISC_COL
        val radius = h * DISC_RADIUS
        val discMid = Vector2(w / 2.0, h / 2.0 + h * DISC_DROP)
        val bite = discMid - Vector2(BITE * radius, 0.0)
        val discCells = buildList {
            val down = (radius / dp).toInt() + 1
            val across = (radius / dc).toInt() + 1
            for (r in -down until down) for (c in -across until across) {
                val centre = discMid + Vector2((c + 0.5) * dc, (r + 0.5) * dp)
                if ((centre - discMid).length > radius - t / 2.0) continue
                val marked = (centre - bite).length > BITE_RADIUS * radius
                add(Spot(centre + Vector2(0.0, t / 2.0), t, if (marked) share else one))
            }
        }.sortedBy { (it.centre - discMid).squaredLength }
        val whole = assemble(arrow, discCells.drop(1), discMid, t, lead = discCells.first())

        return listOf(single, crowd, network, arrow, whole)
    }

    /**
     * Hands [cells] to slots. A slot already standing in [prev] takes the free cell nearest to
     * where it is, in figure heights from each formation's own middle — nearest the middle
     * first, so the centre keeps the centre — and what is left is dealt to new slots in the
     * order given, which is nearest-the-middle first as well. [lead] is slot 0, stated.
     */
    private fun assemble(prev: Formation?, cells: List<Spot>, centre: Vector2, unit: Double, lead: Spot? = null): Formation {
        val spots = ArrayList<Spot>()
        val free = cells.toMutableList()
        lead?.let { spots += it }
        if (prev != null) {
            for (i in spots.size until prev.spots.size) {
                if (free.isEmpty()) break
                val from = (prev.spots[i].centre - prev.centre) / prev.unit
                val pick = free.minByOrNull { ((it.centre - centre) / unit - from).squaredLength }!!
                free.remove(pick)
                spots += pick
            }
        }
        spots += free
        return Formation(spots, centre, unit)
    }

    /**
     * The lines of the network: pairs of figures in adjacent rows within two columns of each
     * other, no figure carrying more than two, drawn from a seed so the same wall comes up
     * every run. Only figures on the pane are linked; a line to one off it points at nothing.
     */
    private fun links(crowd: Formation, w: Double, h: Double): List<Link> {
        val cw = w / columns
        val rh = h * pitch
        val visible = crowd.spots.indices.filter { crowd.spots[it].centre.y in 0.0..h }
        if (visible.size < 2) return emptyList()
        val random = Random(seed)
        val degree = IntArray(crowd.spots.size)
        val taken = HashSet<Pair<Int, Int>>()
        val out = ArrayList<Link>()
        var tries = 0
        while (out.size < links && tries++ < 4000) {
            val a = visible.random(random)
            val b = visible.random(random)
            if (a == b || degree[a] >= 2 || degree[b] >= 2) continue
            val da = crowd.spots[a].centre
            val db = crowd.spots[b].centre
            val dr = abs(da.y - db.y) / rh
            val dc = abs(da.x - db.x) / cw
            if (dr < 0.5 || dr > 1.5 || dc > 2.5) continue
            if (!taken.add(min(a, b) to max(a, b))) continue
            degree[a]++; degree[b]++
            out += Link(a, b)
        }
        return out
    }

    /** Everything that depends on the pane's size, done once for it. */
    private fun ensure(w: Int, h: Int) {
        if (laidFor == (w to h)) return
        laidFor = w to h

        formations = formations(w.toDouble(), h.toDouble())
        network = links(formations[2], w.toDouble(), h.toDouble())
        travels = BooleanArray(formations.size - 1) { k ->
            val a = formations[k]; val b = formations[k + 1]
            (0 until min(a.spots.size, b.spots.size)).any { a.spots[it].foot != b.spots[it].foot || a.spots[it].size != b.spots[it].size }
        }
        val most = formations.maxOf { it.spots.size }

        // Slot i is always the same figure, mirrored or not: a shuffle of the variants dealt
        // round, so neighbours differ and the same slot looks the same in every state.
        val deal = IntArray(variants.size) { it }.also { it.shuffle(Random(seed)) }
        order = IntArray(most) { deal[it % variants.size] }
        val coin = Random(seed + 1)
        flipped = BooleanArray(most) { coin.nextBoolean() }

        // One instance buffer a variant, sized to the most slots that can ever be its own.
        batches.forEach { it.destroy() }
        val each = (most + variants.size - 1) / variants.size
        batches = variants.map { vertexBuffer(INSTANCE, each.coerceAtLeast(1)) }

        wall?.let { it.colorBuffer(0).destroy(); it.destroy() }
        flat?.destroy()
        wall = renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) { colorBuffer() }
        flat = colorBuffer(w, h)

        println("crowd: ${formations.map { it.spots.size }} figures a state, ${network.size} lines, ${variants.size} variants")
    }

    // ------------------------------------------------------------------------------ //
    //  Drawing
    // ------------------------------------------------------------------------------ //

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        val mesh = mesh

        if (mesh != null && variants.isNotEmpty()) {
            ensure(w.toInt(), h.toInt())
            figures(drawer, stage, mesh)
        }

        // The titles: a run of one title holds, and a change crossfades over the click. Each
        // state's weight is how near `position` is to it, and a title's alpha is the sum of
        // the weights of the states that carry it.
        val weights = LinkedHashMap<String, Double>()
        titles.forEachIndexed { k, title ->
            val weight = (1.0 - abs(stage.position - k)).coerceIn(0.0, 1.0)
            if (weight > 0.0) weights[title] = min(1.0, (weights[title] ?: 0.0) + weight)
        }
        drawer.stroke = null
        drawer.fontMap = face
        for ((title, alpha) in weights) {
            drawer.fill = ColorRGBa.WHITE.opacify(alpha)
            set(drawer, title, Vector2(stage.center.x, h * HEAD), h * TITLE / SIZE)
        }
    }

    private fun figures(drawer: Drawer, stage: Stage, mesh: VertexBuffer) {
        val wall = wall ?: return
        val flat = flat ?: return

        // Between which two formations, and how far across. A click has two phases where
        // the figures already standing have somewhere to go: they travel first, and the new
        // ones stand up around them once that is well along — the arrow's core is the crowd
        // shrunk into the middle, and then the arrow grows out of it. Both phases are keyed
        // off an interval of the click's *linear* time, each with a smoothstep of its own,
        // rather than off the deck's eased number: the deck eases the whole click, and a
        // phase that is a part of it has to ease itself or it starts and stops with an
        // edge. Where nothing travels — the one standing still while the crowd forms — the
        // arrivals take the whole click.
        val k = stage.position.toInt().coerceIn(0, formations.size - 1)
        val from = formations[k]
        val to = formations.getOrNull(k + 1)
        val lin = if (to == null) 0.0 else linear(stage.on(k + 1))
        val travelling = travels.getOrElse(k) { false }
        val t = if (travelling) smoothstep(lin / TRAVEL) else lin
        val arriving = if (travelling) ((lin - ARRIVE_AT) / (1.0 - ARRIVE_AT)).coerceIn(0.0, 1.0) else lin

        // The lines, under the figures, growing out on their click and gone in the first
        // moments of the next, before the crowd has visibly left for the arrow.
        val lines = stage.on(2) * (1.0 - linear(stage.on(3)) * 5.0).coerceIn(0.0, 1.0)
        if (lines > 0.0 && network.isNotEmpty()) {
            val crowd = formations[2]
            val grown = linear(stage.on(2))
            drawer.stroke = ColorRGBa.WHITE.opacify(lines)
            drawer.strokeWeight = LINE
            drawer.fill = null
            network.forEachIndexed { i, link ->
                val reach = smoothstep((grown - (i.toDouble() / network.size) * STAGGER) / (1.0 - STAGGER))
                if (reach <= 0.0) return@forEachIndexed
                val a = chest(crowd.spots[link.a])
                val b = chest(crowd.spots[link.b])
                drawer.lineSegment(a, a + (b - a) * reach)
            }
            drawer.stroke = null
        }

        // Every slot's place this frame: eight floats — foot, size (x mirrored), tint.
        val nFrom = from.spots.size
        val nTo = to?.spots?.size ?: nFrom
        val n = max(nFrom, nTo)
        val place = FloatArray(n * 8)
        val shown = BooleanArray(n)
        for (i in 0 until n) {
            val a = from.spots.getOrNull(i)
            val b = to?.spots?.getOrNull(i)
            val foot: Vector2
            val size: Double
            val tint: ColorRGBa
            val grow: Double
            when {
                a != null && b != null -> {
                    foot = a.foot + (b.foot - a.foot) * t
                    size = a.size + (b.size - a.size) * t
                    tint = toward(a.tint, b.tint, t)
                    grow = 1.0
                }
                b != null -> {   // arriving: stands up where it will stand, the middle first
                    foot = b.foot; size = b.size; tint = b.tint
                    grow = arrival(i - nFrom, nTo - nFrom, arriving)
                }
                a != null -> {   // leaving
                    foot = a.foot; size = a.size; tint = a.tint
                    grow = 1.0 - arrival(i - nTo, nFrom - nTo, arriving)
                }
                else -> continue
            }
            if (grow <= 0.0) continue
            shown[i] = true
            val o = i * 8
            place[o] = foot.x.toFloat(); place[o + 1] = foot.y.toFloat()
            place[o + 2] = (size * grow * if (flipped[i]) -1.0 else 1.0).toFloat()
            place[o + 3] = (size * grow).toFloat()
            place[o + 4] = tint.r.toFloat(); place[o + 5] = tint.g.toFloat(); place[o + 6] = tint.b.toFloat(); place[o + 7] = 1f
        }

        // Into the multisampled wall, a variant at a time: its slots are every `variants.size`th
        // from where the deal put it first, so each batch is a stride through the slots.
        drawer.isolatedWithTarget(wall) {
            drawer.ortho(wall)
            drawer.clear(ColorRGBa.TRANSPARENT)
            drawer.shadeStyle = style
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            for (v in variants.indices) {
                val first = order.indexOf(v)
                if (first < 0 || first >= n) continue
                var count = 0
                batches[v].put {
                    var i = first
                    while (i < n) {
                        if (shown[i]) {
                            val o = i * 8
                            write(Vector2(place[o].toDouble(), place[o + 1].toDouble()))
                            write(Vector2(place[o + 2].toDouble(), place[o + 3].toDouble()))
                            write(Vector4(place[o + 4].toDouble(), place[o + 5].toDouble(), place[o + 6].toDouble(), 1.0))
                            count++
                        }
                        i += variants.size
                    }
                }
                if (count == 0) continue
                val variant = variants[v]
                drawer.vertexBufferInstances(
                    listOf(mesh), listOf(batches[v]), DrawPrimitive.TRIANGLES, count,
                    offset = variant.start, vertexCount = variant.count
                )
            }
            drawer.shadeStyle = null
        }
        wall.colorBuffer(0).copyTo(flat)
        drawer.image(flat, stage.bounds.corner.x, stage.bounds.corner.y)
    }

    /** Where a line meets a figure: chest height, which the body then covers. */
    private fun chest(spot: Spot) = spot.foot - Vector2(0.0, spot.size * CHEST)

    /**
     * How far the [j]th of [count] arrivals has stood up at [time] through the arrivals'
     * phase: each starts a little after the one before, over the first [STAGGER] of it, and
     * takes the rest.
     */
    private fun arrival(j: Int, count: Int, time: Double): Double {
        val start = if (count <= 1) 0.0 else (j.toDouble() / count) * STAGGER
        return smoothstep((time - start) / (1.0 - STAGGER))
    }

    /** A mix that keeps the colours' linearity — the plain constructor drops it and every mixed value comes out lighter. */
    private fun toward(from: ColorRGBa, to: ColorRGBa, t: Double) = ColorRGBa(
        from.r + (to.r - from.r) * t,
        from.g + (to.g - from.g) * t,
        from.b + (to.b - from.b) * t,
        from.alpha,
        from.linearity
    )

    /** One line centred on [at]. */
    private fun set(drawer: Drawer, text: String, at: Vector2, scale: Double) {
        if (text.isEmpty()) return
        drawer.isolated {
            drawer.translate(at)
            drawer.scale(scale)
            drawer.text(text, -face.advanceOf(text) / 2.0, 0.0)
        }
    }

    private companion object {
        const val SIZE = 200.0
        const val TITLE = 0.036
        const val HEAD = 0.075

        /** The obj's units for a grown-up, so the figures keep their sizes against one another. */
        const val ADULT = 175.0

        /** The arrow, as shares of the pane: figure height and grid on the height, the run on the width. */
        const val ARROW_FIGURE = 0.075
        const val ARROW_ROW = 0.087
        const val ARROW_COL = 0.022
        const val ARROW_TAIL = 0.09
        const val ARROW_NECK = 0.615
        const val ARROW_TIP = 0.875
        const val ARROW_LEAD = 0.03

        /** The disc: figure height and grid on the height; its radius, and how far below the middle it sits. */
        const val DISC_FIGURE = 0.024
        const val DISC_ROW = 0.03
        const val DISC_COL = 0.0146
        const val DISC_RADIUS = 0.44
        const val DISC_DROP = 0.02

        /** The share: the circle whose outside marks it stands this far left of the disc's middle, this much bigger. */
        const val BITE = 0.49
        const val BITE_RADIUS = 1.11

        /** The two phases of a click with travel in it: the travel over this much of it, the arrivals from here. */
        const val TRAVEL = 0.55
        const val ARRIVE_AT = 0.4

        /** How much of the arrivals' phase they are spread over; the rest is the last one standing up. */
        const val STAGGER = 0.6
        const val CHEST = 0.72
        const val LINE = 2.0

        val INSTANCE = vertexFormat {
            attribute("foot", VertexElementType.VECTOR2_FLOAT32)
            attribute("size", VertexElementType.VECTOR2_FLOAT32)
            attribute("tint", VertexElementType.VECTOR4_FLOAT32)
        }
    }
}
