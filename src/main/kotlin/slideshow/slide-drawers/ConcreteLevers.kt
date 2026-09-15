package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A population of the mix: how many dots, in what, and where they settle relative to the panel's middle. */
class Population(val count: Int, val colour: ColorRGBa, val at: Vector2, val spread: Double)

/** One station of the cycle: what stands there, its formula under it in the accent, and the process that leads on from it. */
class Station(val name: String, val formula: String, val process: String)

/** Something entering or leaving the cycle: what, at which station's process (0 based), and whether it comes in. */
class Exchange(val what: String, val at: Int, val entering: Boolean)

/** A caption under a panel: a numbered heading and a line or two. */
class Lever(val heading: String, val text: String)

/**
 * Three ways to make concrete cleaner, three panels, a click each: the mix as a scatter of dots
 * separating out of one cloud; production as the lime cycle, a ring the arrows keep travelling
 * round; measurement as a row of bars descending. One caption row under all three.
 *
 * - **The scatter separates on the slide's own clock.** Every dot starts in one cloud in the
 *   middle of its panel and travels to its population's place, drawn from a seed so the same
 *   frame always draws the same cloud — a mix being re-composed rather than three groups
 *   appearing. Only while the slide is on its first state, so stepping back into it finds the
 *   mix separated.
 * - **The cycle's arrowheads travel.** The ring is four arcs between four stations, each with an
 *   arrowhead that runs its arc over [travel] frames and comes round again, off `stage.frame`.
 *   The ring draws itself around on the click and the lettering fades up on the same number.
 * - **The bars grow from their baseline**, staggered along the click, values as given.
 *
 * Everything is a function of `stage.position` and `stage.frame`; nothing is kept.
 */
class ConcreteLevers(
    private val title: String = "Verduurzamen van beton",
    private val populations: List<Population>,
    private val stations: List<Station>,
    private val exchanges: List<Exchange> = emptyList(),
    private val bars: List<Double>,
    private val levers: List<Lever>,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val blue: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    private val seed: Int = 1,
    /** Frames an arrowhead takes to run its arc. */
    private val travel: Int = frames(3.0),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Levers"
    override val steps get() = 3
    override val settle get() = frames(SEPARATE) + frames(SEPARATE_AT)

    override fun stepName(step: Int): String? = when (step) {
        1 -> "door productie"
        2 -> "door meting"
        else -> null
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    /** A dot: where it starts in the cloud and where it settles, both relative to the panel's middle in pane heights. */
    private class Dot(val from: Vector2, val to: Vector2, val colour: ColorRGBa)
    private var dots: List<Dot> = emptyList()

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        val random = Random(seed)
        fun gauss(): Double {
            // Box–Muller, from the seeded stream, so the cloud is the same every run.
            val u = random.nextDouble().coerceAtLeast(1e-9)
            val v = random.nextDouble()
            return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
        }
        dots = populations.flatMap { pop ->
            List(pop.count) {
                Dot(
                    Vector2(gauss(), gauss()) * CLOUD,
                    pop.at + Vector2(gauss(), gauss()) * pop.spread,
                    pop.colour
                )
            }
        }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null

        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val panel = w / 3.0
        fun panelBox(i: Int) = Rectangle(i * panel + w * MARGIN, h * PANEL_TOP, panel - 2 * w * MARGIN, h * (PANEL_BOTTOM - PANEL_TOP))

        // 1. The mix: one cloud separating into its populations.
        val opening = stage.step == 0 && stage.position < 1e-6
        val separated = if (opening) smoothstep(stage.since(frames(SEPARATE_AT), frames(SEPARATE))) else 1.0
        val first = panelBox(0)
        dots.forEach { d ->
            val at = first.center + (d.from + (d.to - d.from) * separated) * h
            drawer.fill = d.colour
            drawer.circle(at, DOT)
        }
        caption(drawer, 0, panelBox(0), 1.0, h)

        // 2. The cycle.
        val cycled = stage.on(1)
        if (cycled > 0.0) {
            cycle(drawer, panelBox(1), cycled, stage.frame, h, w)
            caption(drawer, 1, panelBox(1), cycled, h)
        }

        // 3. The bars.
        val measured = stage.on(2)
        if (measured > 0.0 && bars.isNotEmpty()) {
            val box = panelBox(2)
            val max = bars.maxOrNull() ?: 1.0
            val slot = box.width / bars.size
            val width = slot * BAR
            bars.forEachIndexed { i, v ->
                val rect = Rectangle(box.x + i * slot + (slot - width) / 2.0, box.y + box.height * (1.0 - v / max), width, box.height * v / max)
                drawer.fill = blue
                drawer.rectangle(grownFromBase(rect, staggered(measured, i, bars.size, LAG)))
            }
            caption(drawer, 2, box, measured, h)
        }
    }

    /** The caption under panel [i]: heading bold, the text under it in the regular, at [alpha]. */
    private fun caption(drawer: Drawer, i: Int, box: Rectangle, alpha: Double, h: Double) {
        val lever = levers.getOrNull(i) ?: return
        if (alpha <= 0.0) return
        drawer.fill = ink.opacify(alpha)
        drawer.setLine(lever.heading, bold, Vector2(box.x, h * CAPTION_Y), h * HEADING, SIZE)
        text.wrapped(lever.text, box.width * SIZE / (h * TEXT)).forEachIndexed { j, line ->
            drawer.setLine(line, text, Vector2(box.x, h * CAPTION_TEXT_Y + j * h * LEAD), h * TEXT, SIZE)
        }
    }

    /**
     * The lime cycle in [box]: four stations round a ring, an arc between each pair with an
     * arrowhead running along it, the process named on the arc and what enters and leaves in
     * the accent. The ring draws around on [drawn] and the lettering fades up with it.
     */
    private fun cycle(drawer: Drawer, box: Rectangle, drawn: Double, frame: Int, h: Double, w: Double) {
        val n = stations.size
        if (n < 2) return
        val c = box.center
        val r = minOf(box.width, box.height) * RING
        val lettering = drawn

        // Station k stands on a diagonal — a half step round from the top, going clockwise on
        // screen — so the processes fall on the cardinals and the stations' lettering ranges
        // away from the ring at its corners rather than out to the side, where the panel next
        // door is: set at the cardinals, "Gebrande kalk" ran into the bars.
        fun angle(k: Double) = -PI / 2.0 + (k + 0.5) * 2.0 * PI / n
        fun on(k: Double, radius: Double = r) = c + Vector2(cos(angle(k)), sin(angle(k))) * radius

        // The arcs: from just after one station to just before the next, drawn around in order.
        val gapK = GAP_DEG / 360.0 * n
        drawer.stroke = ink
        drawer.strokeWeight = RING_LINE
        for (k in 0 until n) {
            val share = (drawn * n - k).coerceIn(0.0, 1.0)
            if (share <= 0.0) break
            val from = k + gapK
            val to = k + 1 - gapK
            val steps = 40
            val pts = (0..steps).map { i -> on(from + (to - from) * share * i / steps) }
            drawer.lineStrip(pts)
            // The arrowhead, travelling the arc and coming round again — only once the arc is drawn.
            if (share >= 1.0) {
                val u = ((frame + k * travel / n) % travel).toDouble() / travel
                val kk = from + (to - from) * u
                val tip = on(kk)
                val tangent = Vector2(-sin(angle(kk)), cos(angle(kk)))    // clockwise on screen
                val normal = Vector2(cos(angle(kk)), sin(angle(kk)))
                drawer.stroke = null
                drawer.fill = ink
                drawer.contour(ShapeContour.fromPoints(listOf(
                    tip + tangent * HEAD, tip - normal * HEAD * 0.6, tip + normal * HEAD * 0.6
                ), closed = true))
                drawer.stroke = ink
            }
        }
        drawer.stroke = null

        // The stations: name outside the ring, formula under it in the accent.
        val size = h * STATION
        stations.forEachIndexed { k, s ->
            val p = on(k.toDouble(), r + h * STATION_OUT)
            val dir = Vector2(cos(angle(k.toDouble())), sin(angle(k.toDouble())))
            // Ranged away from the ring, the block's nearest corner at the station's point.
            val align = if (dir.x < 0.0) 1.0 else 0.0
            val y = if (dir.y < 0.0) p.y - h * LEAD else p.y + size
            drawer.fill = ink.opacify(lettering)
            drawer.setLine(s.name, text, Vector2(p.x, y), size, SIZE, align)
            drawer.fill = accent.opacify(lettering)
            drawer.setLine(s.formula, bold, Vector2(p.x, y + h * LEAD), size, SIZE, align)

            // The process, on the arc inside the ring, half way to the next station.
            val q = on(k + 0.5, r * PROCESS_IN)
            val lines = text.wrapped(s.process, (r * PROCESS_MEASURE) * SIZE / (h * PROCESS))
            val top = q.y - (lines.size - 1) * h * PROCESS_LEAD / 2.0 + h * PROCESS * 0.34
            drawer.fill = ink.opacify(lettering)
            lines.forEachIndexed { j, line -> drawer.setLine(line, text, Vector2(q.x, top + j * h * PROCESS_LEAD), h * PROCESS, SIZE, 0.5) }
        }

        // What enters and leaves, as a short accent arrow outside the arc it belongs to.
        exchanges.forEachIndexed { i, e ->
            val siblings = exchanges.filter { it.at == e.at }
            val slotShare = siblings.indexOf(e) - (siblings.size - 1) / 2.0
            val kk = e.at + 0.5 + slotShare * 0.18
            val outer = on(kk, r + h * EXCHANGE_OUT)
            val inner = on(kk, r + h * EXCHANGE_IN)
            val (tail, head) = if (e.entering) outer to inner else inner to outer
            drawer.stroke = accent.opacify(lettering)
            drawer.strokeWeight = LINE
            drawer.lineSegment(tail, head)
            drawer.stroke = null
            val d = (head - tail).normalized
            val nrm = Vector2(-d.y, d.x)
            drawer.fill = accent.opacify(lettering)
            drawer.contour(ShapeContour.fromPoints(listOf(head, head - d * HEAD * 0.8 + nrm * HEAD * 0.45, head - d * HEAD * 0.8 - nrm * HEAD * 0.45), closed = true))
            val label = on(kk, r + h * EXCHANGE_LABEL)
            val dir = Vector2(cos(angle(kk)), sin(angle(kk)))
            drawer.setLine(e.what, bold, Vector2(label.x, label.y + h * PROCESS * 0.34), h * PROCESS, SIZE, if (dir.x < 0) 1.0 else 0.0)
        }
    }

    private companion object {
        const val SIZE = 200.0

        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val MARGIN = 0.02
        const val PANEL_TOP = 0.30
        const val PANEL_BOTTOM = 0.70
        const val CAPTION_Y = 0.815
        const val CAPTION_TEXT_Y = 0.87
        const val HEADING = 0.036
        const val TEXT = 0.0287
        const val LEAD = 0.035

        /** The cloud every dot starts in and the dot itself, in pane heights and pixels. */
        const val CLOUD = 0.05
        const val DOT = 5.0
        const val SEPARATE_AT = 0.4
        const val SEPARATE = 1.6

        const val RING = 0.38
        const val RING_LINE = 4.0
        const val GAP_DEG = 9.0
        const val HEAD = 11.0
        const val STATION = 0.022
        const val STATION_OUT = 0.022
        const val PROCESS = 0.018
        const val PROCESS_LEAD = 0.023
        const val PROCESS_IN = 0.56
        const val PROCESS_MEASURE = 0.8
        const val EXCHANGE_IN = 0.035
        const val EXCHANGE_OUT = 0.075
        const val EXCHANGE_LABEL = 0.095
        const val LINE = 2.0

        const val BAR = 0.78
        const val LAG = 0.1
    }
}
