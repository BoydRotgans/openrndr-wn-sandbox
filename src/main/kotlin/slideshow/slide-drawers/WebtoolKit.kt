// ============================================================================ //
//  No `package` declaration: it stands on KitView, which lives in the default
//  package with the course walls.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.LineJoin
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import slideshow.Arrival
import slideshow.CubicBezier
import slideshow.FPS
import slideshow.Frame
import slideshow.Palette
import slideshow.SNAP_SECONDS
import slideshow.Scale
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.Want
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import slideshow.frames
import slideshow.linear
import slideshow.mix
import slideshow.snap
import slideshow.voiced

/**
 * The building out of the webtool, **as the webtool**: an interface with a mouse cursor in it. It opens on an
 * empty canvas and one large round button in its middle, "Start a new circle". The first click of the talk is
 * the cursor clicking it, **as an interface does**: the pointer turns into a hand as it comes over the button, the
 * button swells and takes a ring, it goes down under the press and springs back — and **on the release it bursts**:
 * the disc opens into a red ring running outward, and the kit course's pieces fly out of its middle to the field,
 * nearest first, each overshooting its place a little and settling, in the kit's red or blue with a white line on
 * every real edge — and the exploded view stands, with no label yet. The next click the cursor takes the first
 * piece — a selection box round it, as in a drawing tool — and **drags it** to a free place, its label appearing
 * as it is taken and travelling with it. Every click after that drags the next. The last click **combines them into one building, in one movement**: the cursor moves aside, the labels
 * close, and every piece goes home at once — the dragged ones letting go of their drags — while the camera comes
 * in on the cube and turns a quarter round it, all on the one ease ([GATHER]). The cursor never leaves the frame.
 * A label a click, so the cue sheet, the subtitles and the voice keep the states they were made for.
 *
 * **Where each piece is dragged to is worked out once, at load**, since the camera never moves: the shortest
 * drag, at least [MIN_DRAG] pixels so it reads as one (less only where nothing that long will do), that lands
 * the piece in the frame clear of every other piece and label with room for its own label beside it — on the
 * side away from the middle of the field if it fits there, the other side if not. A
 * piece held is lifted toward the camera and a little larger, set down on release where it was dropped. The
 * label being said is full size on a white plate; the ones before it smaller, on the ground's own.
 *
 * **The pieces are chosen at load**, from the corner the camera holds: left and right by turns, top to bottom,
 * the biggest block in each band of the field on its side that stands wholly in the frame and is least hidden
 * behind blocks nearer the camera; one with no free place to be dragged to gives way to the next.
 *
 * **It is the kit's own drawing**, `KitView`: the pieces at the kit's own exploded places, lifted, moved and
 * scaled through a `KitPick`, and the combine the kit's own move home — run with no stagger and an even move
 * ([webtoolKitKey]), so the slide's one ease is the only curve on it, and the zoom even in world units
 * (`pullLinear`), so pieces gathering in straight lines stay in frame. `SLIDES_WEBTOOL_*` steers it by the kit's
 * own names, each falling back to the `KIT_` key of the same name. **A pure function of `position`**, the deck's rule: every move is timed in the
 * seconds a click has run (`linear` of it), so clicking back plays it undone, the cursor included. It states
 * the pane it composes for, since the picks and the drags are settled in [load], and draws the pieces into a
 * multisampled target of its own.
 *
 * **With a [catalogue] it is the last of the catalogue's three slides** and opens on the grid's last frame, the
 * catalogue to scale: every piece takes the kit's red or blue one after another, outward from the scale figure, then
 * the whole sheet and the figure go into the button's middle in one movement, the button growing out of that point,
 * and the cursor comes in to rest — then the slide is as it was, from the button on. The view is the catalogue's, so the grid and
 * the kit are drawn by one renderer. The arrival runs on the slide's own frame count, and only when it opened on
 * its first state.
 */
class WebtoolKit(
    private val title: String,
    /** A label a click, in the order said, each on a piece of its own. */
    private val labels: List<String>,
    private val boldPath: String = "data/fonts/default.otf",
    private val palette: Palette = Palette.onBlack,
    private val pane: Vector2 = Vector2(1920.0, 1080.0),
    /** The corner the camera holds, in degrees round. */
    private val yaw: Double = 45.0,
    /** The button the interface opens on, and its colour. */
    private val button: String = "Start a new circle",
    private val buttonColour: ColorRGBa = Palette.RED,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList(),
    /** The catalogue this slide carries on from, the ring's and the grid's scene; null opens on the button alone. */
    private val catalogue: CircleCatalogue? = null
) : Slide() {

    override val name = "Webtool kit"

    /** The button; the pieces standing apart; a drag a label; one building. */
    override val steps get() = labels.size + 3

    override val stepFrames: Int get() = frames(DRAG_CLICK)

    /** Opened on the catalogue, the first state holds until the kit's pieces are in the button and the cursor is in. */
    override val settle: Int get() = catalogue?.let { maxOf(stepFrames, frames(it.arrivalSeconds)) } ?: stepFrames

    /** The click the deck opened this slide on: the catalogue's arrival plays only from the first. */
    private var openedOn = 0

    override fun cameFrom(previous: Slide, stood: Int, leftOn: Int, opensOn: Int) {
        openedOn = opensOn
        if (catalogue != null && previous is CircleGrid && previous.scene === catalogue) catalogue.gridLeftOn = leftOn
    }

    /**
     * The first click presses the button and bursts it into the pieces, and lands once they have; a drag is
     * [DRAG_CLICK]; the combine is [COMBINE] and a rest.
     */
    override fun stepLength(step: Int): Int = frames(seconds(step))
    private fun seconds(step: Int): Double = when (step) {
        1 -> CUT + BURST_SPREAD + BURST + LOOK
        combineStep -> COMBINE + SETTLE
        else -> DRAG_CLICK
    }

    /** The click that drags pick [i], and the one that combines them all. */
    private fun dragStep(i: Int) = i + 2
    private val combineStep get() = labels.size + 2

    override fun stepName(step: Int): String? = when (step) {
        0 -> "start a new circle"
        1 -> "the pieces"
        combineStep -> "one building"
        else -> labels.getOrNull(step - 2)
    }

    private var view: KitView? = null
    private lateinit var bold: FontImageMap
    /** The block each label is on, by its seed. */
    private var picks: List<Int> = emptyList()
    /** For each pick: where the cursor takes it, how far it is dragged, and its label's place once dropped. */
    private var grabs: List<Vector2> = emptyList()
    private var drags: List<Vector2> = emptyList()
    private var places: List<Place> = emptyList()
    /** Every block's middle on the frame standing apart, and when it sets off in the burst, after the release. */
    private var homes: Map<Int, Vector2> = emptyMap()
    private var delays: Map<Int, Double> = emptyMap()
    private var target: RenderTarget? = null
    private var resolved: ColorBuffer? = null

    /** A label's place once its piece is dropped: the middle of its plate, and whether the piece is to its right. */
    private class Place(val middle: Vector2, val pieceRight: Boolean)

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        val started = System.currentTimeMillis()
        // The kit's own keys, but every piece setting off at once and moving evenly in kit time, so the combine
        // can put one ease over the lot.
        // With a catalogue the view is its own, so the grid before this slide and the kit are one renderer.
        val kit = catalogue?.let { it.load(program); it.view } ?: KitView(program, ::webtoolKitKey, pane.x.toInt(), pane.y.toInt())
        layOut(kit)
        view = kit
        println("webtool kit: built in ${System.currentTimeMillis() - started} ms, dragging blocks " +
            picks.indices.joinToString { "${picks[it]} ${drags[it].length.toInt()} px" })
    }

    private fun plateWidth(text: String, size: Double) = bold.advanceWithSubscripts(text) * size / SIZE + 2.0 * size * PAD

    /**
     * The pieces the cursor drags, where to, and where their labels stand — off the field as the camera sees it
     * standing apart. The picks: left and right by turns, top to bottom, the biggest block in each band of the
     * field on its side that stands wholly in the frame and is least hidden behind blocks nearer the camera —
     * passed over for the next when it cannot be dragged clear. The drags: for each in turn, the shortest drag of
     * at least [MIN_DRAG] that lands the piece clear of every other piece and label, with its own label beside it
     * on the side away from the middle, all inside the frame.
     */
    private fun layOut(kit: KitView) {
        val w = pane.x; val h = pane.y
        val boxes = kit.blocksAt(kit.cycle, yaw, w.toInt(), h.toInt())
        val near = kit.nearnessAt(kit.cycle, yaw)
        if (boxes.isEmpty()) return
        val margin = w * Frame.MARGIN
        fun area(b: Rectangle) = b.width * b.height
        fun overlap(a: Rectangle, b: Rectangle) =
            maxOf(0.0, minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)) * maxOf(0.0, minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y))
        fun seen(seed: Int): Double {
            val b = boxes.getValue(seed)
            val hidden = boxes.entries.filter { (near[it.key] ?: 0.0) > (near[seed] ?: 0.0) }.sumOf { overlap(b, it.value) }
            return area(b) * (1.0 - hidden / area(b)).coerceAtLeast(0.05)
        }
        fun inFrame(b: Rectangle) = b.x >= margin && b.x + b.width <= w - margin && b.y >= h * TOP && b.y + b.height <= h - margin
        fun inflated(b: Rectangle, by: Double) = Rectangle(b.x - by, b.y - by, b.width + 2.0 * by, b.height + 2.0 * by)
        fun moved(b: Rectangle, by: Vector2) = Rectangle(b.x + by.x, b.y + by.y, b.width, b.height)
        val x0 = boxes.values.minOf { it.x }; val y0 = boxes.values.minOf { it.y }
        val field = Rectangle(x0, y0, boxes.values.maxOf { it.x + it.width } - x0, boxes.values.maxOf { it.y + it.height } - y0)

        val bands = (labels.size + 1) / 2
        val size = Scale.label(h)
        val ph = size * (ABOVE + BELOW)
        val now = boxes.toMutableMap()
        val placed = ArrayList<Rectangle>()
        val chosen = ArrayList<Int>()
        val ds = ArrayList<Vector2>(); val ps = ArrayList<Place>()

        /** The shortest drag for [seed] with [text] beside it that clears everything already down, or null. */
        fun dragFor(seed: Int, text: String): Pair<Vector2, Pair<Rectangle, Boolean>>? {
            val b = boxes.getValue(seed)
            val pw = plateWidth(text, size)
            // A label beside the piece, the side away from the middle first, then the other.
            fun labelsBeside(m: Rectangle): List<Pair<Rectangle, Boolean>> {
                val right = Rectangle(m.x + m.width + GAP, m.center.y - ph / 2.0, pw, ph) to false
                val left = Rectangle(m.x - GAP - pw, m.center.y - ph / 2.0, pw, ph) to true
                return if (m.center.x >= field.center.x) listOf(right, left) else listOf(left, right)
            }
            val others = now.filterKeys { it != seed }.values.map { inflated(it, AIR) }
            fun fits(m: Rectangle, l: Rectangle) = inFrame(m) && inFrame(l) &&
                others.none { it.intersects(m) || it.intersects(l) } &&
                placed.none { inflated(it, CLEAR).intersects(m) || inflated(it, CLEAR).intersects(l) }
            for (least in listOf(MIN_DRAG, MIN_DRAG * 0.6, MIN_DRAG * 0.3)) {
                var best: Pair<Vector2, Pair<Rectangle, Boolean>>? = null
                var y = -REACH
                while (y <= REACH) {
                    var x = -REACH
                    while (x <= REACH) {
                        val d = Vector2(x, y)
                        x += SCAN
                        if (d.length < least || d.length > REACH || (best != null && d.length >= best.first.length)) continue
                        val m = moved(b, d)
                        val label = labelsBeside(m).firstOrNull { fits(m, it.first) } ?: continue
                        best = d to label
                    }
                    y += SCAN
                }
                if (best != null) return best
            }
            return null
        }

        // A label a piece, in the order said: left and right by turns, top to bottom, the most seen block in its
        // band on its side first; a block that cannot be dragged clear gives way to the next one that can.
        labels.forEachIndexed { i, text ->
            val right = i % 2 == 1
            val band = i / 2
            val top = field.y + field.height * band / bands
            val bottom = field.y + field.height * (band + 1) / bands
            val open = boxes.keys.filter { it !in chosen }.sortedByDescending { seen(it) }
            val onSide = open.filter { (boxes.getValue(it).center.x >= field.center.x) == right && inFrame(boxes.getValue(it)) }
            val candidates = (onSide.filter { boxes.getValue(it).center.y in top..bottom } + onSide + open).distinct()
            val seed = candidates.firstOrNull { dragFor(it, text) != null } ?: candidates.firstOrNull() ?: return@forEachIndexed
            val found = dragFor(seed, text)
            val d = found?.first ?: Vector2.ZERO
            val m = moved(boxes.getValue(seed), d)
            val pw = plateWidth(text, size)
            val (l, pieceRight) = found?.second ?: (Rectangle(m.x + m.width + GAP, m.center.y - ph / 2.0, pw, ph) to false)
            chosen += seed
            now[seed] = m
            placed += l
            ds += d
            ps += Place(l.center, pieceRight)
        }
        picks = chosen
        // The burst: every block from the button's middle, the nearest to it first.
        val centre = Vector2(w / 2.0, h / 2.0)
        val order = boxes.keys.sortedBy { boxes.getValue(it).center.distanceTo(centre) }
        homes = boxes.mapValues { it.value.center }
        delays = order.withIndex().associate { (rank, seed) -> seed to BURST_SPREAD * rank / maxOf(1, order.size - 1) }
        grabs = picks.map { boxes.getValue(it).center }
        drags = ds
        places = ps
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val kit = view ?: return
        val w = stage.width.toInt()
        val h = stage.height.toInt()
        val n = picks.size
        /** Seconds click [k] has run: 0 before it starts, its whole length once it has landed. */
        fun into(k: Int) = linear(stage.on(k)) * seconds(k)
        val first = into(1)
        val combining = into(combineStep)

        // When each drag's moments fall, in seconds into its own click: the cursor comes to the piece, takes it,
        // drags it, lets go.
        fun since(i: Int) = into(dragStep(i)) - MOVE
        fun dragged(i: Int) = smoother((since(i) - PRESS) / DRAG)
        fun held(i: Int) = snap(since(i) / SNAP_SECONDS) * (1.0 - snap((since(i) - PRESS - DRAG) / SNAP_SECONDS))

        // Kit time: standing apart, and through the combine on the last click; the drags let go of their pieces
        // over the first part of it, so each flies home from where it was dropped. Until the button is released
        // there are no pieces at all; on the release they burst out of its middle.
        // The combine is one movement on one ease: every piece home at once, each dragged piece letting go of its
        // drag, the camera coming in on the cube and turning a quarter round it.
        val released = first >= CUT
        val gathered = GATHER(combining / COMBINE)
        val t = if (combining > 0.0) kit.cycle + kit.holdApart + gathered * kit.lift else kit.cycle
        val pull = if (combining > 0.0) kit.pullLinear(gathered, w, h) else 1.0
        val seen = yaw + TURN * gathered
        val home = 1.0 - gathered
        val closing = 1.0 - snap(combining / SNAP_SECONDS)

        val button = buttonOf(stage.height)
        val buttonCentre = Vector2(stage.width / 2.0, stage.height / 2.0)
        // Opened on the catalogue: seconds into its arrival, the sheet's pieces as they stand then — the kit's lit
        // and going into the button, the rest going — and how far the button has come up. Past the arrival, or
        // opened on a later click, the catalogue is gone and the button stands.
        val arriving = catalogue?.takeIf { openedOn == 0 || (stage.step == 0 && stage.position < 1e-6) }
        val intro = if (arriving != null) stage.frame / FPS.toDouble() else Double.MAX_VALUE
        // The button grows out of the point the sheet goes into, on the same curve.
        val appear = catalogue?.gathered(intro) ?: 1.0
        val (sheet, sheetColours) = if (arriving != null && intro < arriving.arrivalSeconds)
            arriving.rowPieces(List(arriving.count) { arriving.pourPose(it, intro, arriving.gridLeftOn, buttonCentre) }, w, h,
                arriving.personLeaving(intro, arriving.gridLeftOn, buttonCentre))
        else emptyList<AssembleScene.RowPiece>() to emptyMap()
        val lifted = HashMap<Int, Double>()
        val shifts = HashMap<Int, org.openrndr.math.Vector3>()
        val scales = HashMap<Int, Double>()
        // The burst: each block from the button's middle to its place, overshooting a little and settling, and
        // growing from nothing on the way.
        if (released) for ((seed, at) in homes) {
            val p = ((first - CUT - (delays[seed] ?: 0.0)) / BURST).coerceIn(0.0, 1.0)
            if (p >= 1.0) continue
            scales[seed] = 1.0 - (1.0 - p).let { it * it * it }
            val out = backOut(p)
            val a = Math.toRadians(SWIRL * (1.0 - minOf(out, 1.0)))
            val r = (at - buttonCentre) * out
            val on = buttonCentre + Vector2(r.x * cos(a) - r.y * sin(a), r.x * sin(a) + r.y * cos(a))
            shifts[seed] = kit.offsetFor(on - at, kit.cycle, yaw, w, h)
        }
        picks.forEachIndexed { i, seed ->
            if (since(i) <= 0.0) return@forEachIndexed
            if (held(i) > 0.0) lifted[seed] = held(i)
            val px = drags[i] * (dragged(i) * home)
            if (px.length > 0.0) shifts[seed] = kit.offsetFor(px, kit.cycle, yaw, w, h)
        }

        val (msaa, image) = targetFor(w, h)
        var boxes: Map<Int, Rectangle> = emptyMap()
        drawer.isolatedWithTarget(msaa) {
            ortho(msaa)
            clear(kit.paper)
            // The button's ring, under the pieces that come out of it.
            if (released) drawRing(this, buttonCentre, button.radius * (1.0 + POP), (first - CUT) / RING)
            // The kit without its wire boxes: the pieces alone on the ground.
            // With the catalogue's pieces still on the sheet, in the same pass: one renderer, one depth.
            boxes = kit.drawPieces(this, (if (released) kit.kit.catalogue(t).first else emptyList()) + sheet, emptyList(), seen, pull, 0.0,
                KitPick(lifted = lifted, shifts = shifts, scales = scales, colours = sheetColours), clear = false)
        }
        msaa.colorBuffer(0).copyTo(image)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.image(image, stage.bounds.x, stage.bounds.y, stage.width, stage.height)
        }

        val frame = Frame(stage.bounds)
        // Opened on the catalogue, the grid's title stands until the button comes up, and this one pops over it.
        val titled = if (catalogue != null && intro < catalogue.buttonAt) catalogue.gridTitle else title
        plate(drawer, titled, frame.titleLeft, Scale.title(stage.height), 1.0, palette.paper, palette.ink)

        // The button, until it is released: hovered, pressed, sprung back.
        // Opened on the catalogue, the cursor comes in from off the frame to its rest as the last piece goes in.
        val cursor = cursorAt(stage, ::into, buttonCentre, button.radius, combining).let { c ->
            val cat = arriving ?: return@let c
            if (first > 0.0 || intro >= cat.arrivalSeconds) c
            else Cursor(c.at + OFF_FRAME * stage.height * (1.0 - smoother((intro - cat.cursorAt) / (cat.arrivalSeconds - cat.cursorAt))), c.press, c.hand)
        }
        if (!released) drawButton(drawer, buttonCentre, button, hoverOf(first, stage, buttonCentre, button.radius),
            pressOf(first), springOf(first), appear)

        // The piece in hand, selected as a drawing tool selects: from just before the cursor takes it to the drop.
        picks.forEachIndexed { i, seed ->
            val selected = smoother((since(i) + SELECT_LEAD) / SELECT_IN) *
                (1.0 - smoother((since(i) - PRESS - DRAG - SELECT_OUT) / SELECT_IN)) * closing
            val box = boxes[seed]
            if (selected > 0.0 && box != null) drawSelection(drawer, box.movedBy(stage.bounds.corner), selected)
        }

        // The labels: each appears as its piece is taken and travels with it, and stays where it is dropped — the
        // one being said full size on a white plate, the ones before it smaller on the ground's own.
        val size = Scale.label(stage.height)
        for (i in 0 until n) {
            val place = places.getOrNull(i) ?: continue
            val open = snap((since(i) - LABEL_DELAY) / SNAP_SECONDS) * closing
            if (open <= 0.0) continue
            val next = if (i < n - 1) since(i + 1) else combining
            val saying = 1.0 - snap(next / SNAP_SECONDS)
            val sz = size * mix(SMALL, 1.0, saying)
            val middle = place.middle - drags[i] * (1.0 - dragged(i))
            val rect = Rectangle.fromCenter(middle, plateWidth(labels[i], sz), sz * (ABOVE + BELOW))
            val bright = saying >= 0.5
            plate(drawer, labels[i], Vector2(rect.x + sz * PAD, rect.y + sz * ABOVE), sz, open,
                if (bright) palette.ink else palette.paper, if (bright) palette.paper else palette.ink, fromRight = place.pieceRight)
        }

        // The cursor, over everything, always.
        drawCursor(drawer, cursor, stage.height)
    }

    // --- the slide as timing ------------------------------------------------------------ //
    //
    //  A lane a kind of gesture, every note read off the constants `draw` steps through, so the file
    //  and the picture cannot disagree: the pointer's moves; the button's hover, press and the ring it
    //  bursts into; a note a block flying out of it; a note a drag, from the grab to the let-go; a note
    //  a label while it is the one being said; a note a block going home in the combine; and the
    //  states, the floor every slide has. Pitch is height on the pane, top highest, wherever a thing
    //  has a place; the button's three moments are a chord's three notes and the labels rise a
    //  semitone each in the order said. The combine is every block at once, so it lands as a chord.

    override val lanes: List<String>
        get() = listOf("pointer", "button", "burst", "drags", "labels", "gather", "states") +
            if (catalogue != null) listOf("catalogue") else emptyList()

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val states = super.arrivals(clicks).map { it.copy(lane = STATES_LANE) }
        val intro = catalogueWants()
        // Until `load` there are no picks or blocks to score.
        if (picks.isEmpty() || clicks.size < combineStep || !::bold.isInitialized) return voiced(intro) + states
        val n = picks.size
        /** The frame [s] seconds into click [k] falls on: `into`, read backwards. */
        fun at(k: Int, s: Double) = clicks[k - 1] + (s / seconds(k) * stepLength(k)).roundToInt()
        fun height(p: Vector2) = ((1.0 - p.y / pane.y) * (SCORE_NOTES - 1)).roundToInt().coerceIn(0, SCORE_NOTES - 1)
        fun want(lane: Int, pitch: Int, from: Int, to: Int) = Want(lane, pitch, from, (to - from).coerceAtLeast(1), 0, SCORE_NOTES - 1)
        fun drop(i: Int) = grabs[i] + drags[i]
        val centre = pane / 2.0
        val button = buttonOf(pane.y)
        val rest = Vector2(pane.x * REST.x, pane.y * REST.y)
        val wants = ArrayList<Want>()

        // The pointer: to the button, to each piece, and aside as the kit combines.
        wants += want(POINTER, height(aimOf(centre, button.radius)), at(1, 0.0), at(1, TO_BUTTON))
        for (i in 0 until n) wants += want(POINTER, height(grabs[i]), at(dragStep(i), 0.0), at(dragStep(i), MOVE))
        wants += want(POINTER, height(rest), at(combineStep, 0.0), at(combineStep, AWAY))

        // The button: hovered from the moment the pointer is over it until it bursts, held down, and its ring.
        wants += want(BUTTON, CHORD[0], at(1, overButton(rest, centre, button.radius)), at(1, CUT))
        wants += want(BUTTON, CHORD[1], at(1, PRESS_AT), at(1, PRESS_AT + DOWN + HOLD_DOWN))
        wants += want(BUTTON, CHORD[2], at(1, CUT), at(1, CUT + RING))

        // The burst: every block from the button's middle to its place, nearest first.
        for ((seed, home) in homes) {
            val from = CUT + (delays[seed] ?: 0.0)
            wants += want(BURST_LANE, height(home), at(1, from), at(1, from + BURST))
        }

        // The drags, grab to let-go, at the height they are dropped; and each label from when it opens until
        // the next is being said, or the combine closes them all.
        for (i in 0 until n) {
            val k = dragStep(i)
            wants += want(DRAGS, height(drop(i)), at(k, MOVE), at(k, MOVE + PRESS + DRAG))
            val said = if (i < n - 1) at(dragStep(i + 1), MOVE) else at(combineStep, 0.0)
            wants += want(LABELS, LABEL_NOTE + i, at(k, MOVE + LABEL_DELAY), said)
        }

        // The combine: every block home at once on the one ease, from where it stood apart — the dragged ones
        // from where they were dropped.
        val dropped = picks.withIndex().associate { (i, seed) -> seed to drags[i] }
        for ((seed, home) in homes)
            wants += want(GATHER_LANE, height(home + (dropped[seed] ?: Vector2.ZERO)), at(combineStep, 0.0), at(combineStep, COMBINE))

        return voiced(wants + intro) + states
    }

    /**
     * Opened on the catalogue, its arrival: a note as each piece takes its colour, at the height it stands on the
     * sheet, and one for the whole sheet going into the button, which is one movement — the schedule `draw` steps
     * through, read off the catalogue. Empty for a slide with none.
     */
    private fun catalogueWants(): List<Want> {
        val cat = catalogue ?: return emptyList()
        if (cat.count == 0) return emptyList()
        val left = cat.gridLeftOn
        fun height(p: Vector2) = ((1.0 - p.y / pane.y) * (SCORE_NOTES - 1)).roundToInt().coerceIn(0, SCORE_NOTES - 1)
        fun want(p: Vector2, from: Double, length: Int) = Want(CATALOGUE_LANE, height(p), frames(from), length, 0, SCORE_NOTES - 1)
        return (0 until cat.count).flatMap { i ->
            val at = cat.sheetAt(i, left) ?: return@flatMap emptyList()
            val coloured = cat.colourStartOf(i, left) ?: return@flatMap emptyList()
            listOf(want(at, coloured, frames(LIT_NOTE)))
        } + want(pane / 2.0, cat.buttonAt, frames(cat.intoButtonSeconds))
    }

    /** The button laid out: its words over two lines, their size, and the disc's radius. */
    private class Button(val lines: List<String>, val size: Double, val radius: Double)

    private fun buttonOf(paneHeight: Double): Button {
        val size = Scale.title(paneHeight) * BUTTON_TYPE
        fun width(line: String) = bold.advanceWithSubscripts(line) * size / SIZE
        val words = button.split(" ")
        val lines = (1 until words.size)
            .map { listOf(words.take(it).joinToString(" "), words.drop(it).joinToString(" ")) }
            .minByOrNull { l -> l.maxOf { width(it) } } ?: listOf(button)
        val reach = Vector2(lines.maxOf { width(it) } / 2.0, size * LEADING * lines.size / 2.0).length
        return Button(lines, size, reach + size * BUTTON_PAD)
    }

    /** Where the pointer's tip rests on the button: low in it, clear of the words. */
    private fun aimOf(centre: Vector2, radius: Double) = centre + AIM * radius
    private fun restOf(stage: Stage) = Vector2(stage.width * REST.x, stage.height * REST.y)

    /** How far the button is hovered at [s] seconds into the first click: from the moment the pointer is over it. */
    private fun hoverOf(s: Double, stage: Stage, centre: Vector2, radius: Double): Double =
        smoother((s - overButton(restOf(stage), centre, radius)) / HOVER_IN)

    /** Seconds into the first click the pointer comes over the button: where its path from [rest] crosses the edge. */
    private fun overButton(rest: Vector2, centre: Vector2, radius: Double): Double {
        val aim = aimOf(centre, radius)
        var lo = 0.0; var hi = 1.0
        repeat(24) {
            val f = (lo + hi) / 2.0
            if ((rest + (aim - rest) * smoother(f)).distanceTo(centre) > radius) lo = f else hi = f
        }
        return hi * TO_BUTTON
    }

    /** How far down the button is at [s] seconds into the first click: pressed, held, let go. */
    private fun pressOf(s: Double) =
        smoother((s - PRESS_AT) / DOWN) * (1.0 - smoother((s - PRESS_AT - DOWN - HOLD_DOWN) / UP))

    /** How far the button has sprung back past its size on the let-go, just before it bursts. */
    private fun springOf(s: Double) = smoother((s - PRESS_AT - DOWN - HOLD_DOWN) / UP)

    /** The pointer: where its tip is, how far it is pressed, and whether it is the hand, over the button. */
    private class Cursor(val at: Vector2, val press: Double, val hand: Boolean)

    /**
     * Where the cursor is and how far it is pressed: resting low on the right; to the button, over it and clicking
     * it; to each piece in turn, taking it and dragging it and letting go; and aside as the kit combines.
     */
    private fun cursorAt(stage: Stage, into: (Int) -> Double, centre: Vector2, radius: Double, combining: Double): Cursor {
        val n = picks.size
        val rest = restOf(stage)
        val aim = aimOf(centre, radius)
        fun leg(a: Vector2, b: Vector2, f: Double) = a + (b - a) * smoother(f)
        fun drop(i: Int) = grabs[i] + drags[i]
        if (n == 0) return Cursor(rest, 0.0, false)
        if (combining > 0.0) return Cursor(leg(drop(n - 1), rest, combining / AWAY), 0.0, false)
        // The latest click begun: the button's, where the pointer stays where it clicked, or a drag's.
        val k = (1..dragStep(n - 1)).lastOrNull { into(it) > 0.0 } ?: return Cursor(rest, 0.0, false)
        val s = into(k)
        if (k == 1)
            return Cursor(leg(rest, aim, s / TO_BUTTON), pressOf(s), s < CUT && hoverOf(s, stage, centre, radius) >= 0.5)
        val i = k - 2
        val from = if (i == 0) aim else drop(i - 1)
        val pressed = if (s >= MOVE) 1.0 - snap((s - MOVE - PRESS - DRAG) / SNAP_SECONDS) else 0.0
        val at = if (s < MOVE) leg(from, grabs[i], s / MOVE) else grabs[i] + drags[i] * smoother((s - MOVE - PRESS) / DRAG)
        return Cursor(at, pressed * snap((s - MOVE) / (PRESS * 0.5)), false)
    }

    /**
     * The button: a disc in the accent with the words in white across its middle. Hovered it swells a little and
     * a white ring stands round it; pressed it goes down and darkens; let go it springs back past its size.
     */
    private fun drawButton(drawer: Drawer, centre: Vector2, button: Button, hover: Double, pressed: Double, spring: Double,
                           appear: Double = 1.0) {
        if (appear <= 0.0) return
        val k = ((1.0 + HOVER_GROW * hover) * (1.0 - SQUASH * pressed) + POP * spring) * appear
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.translate(centre)
            drawer.scale(k)
            if (hover > 0.0) {
                drawer.fill = null
                drawer.stroke = palette.ink.opacify(hover)
                drawer.strokeWeight = HOVER_RING / k
                drawer.circle(0.0, 0.0, button.radius + HOVER_GAP * hover / k)
            }
            drawer.stroke = null
            drawer.fill = buttonColour.mix(ColorRGBa.BLACK, 0.3 * pressed)
            drawer.circle(0.0, 0.0, button.radius)
            drawer.fill = palette.ink
            val pitch = button.size * LEADING
            button.lines.forEachIndexed { i, line ->
                val baseline = (i - (button.lines.size - 1) / 2.0) * pitch + button.size * 0.36
                drawer.setLine(line, bold, Vector2(0.0, baseline), button.size, SIZE, align = 0.5)
            }
        }
    }

    /**
     * The burst's ring at [q] of its run: at 0 the button's disc whole, then a hole opening in its middle as its
     * edge runs outward, the ring thinning fast to nothing — the button's own red, never faded.
     */
    private fun drawRing(drawer: Drawer, centre: Vector2, radius: Double, q: Double) {
        if (q >= 1.0) return
        val f = q.coerceIn(0.0, 1.0)
        val outer = radius * (1.0 + (RING_REACH - 1.0) * (1.0 - Math.pow(2.0, -10.0 * f)) / (1.0 - Math.pow(2.0, -10.0)))
        val inner = maxOf(0.0, outer - radius * Math.pow(1.0 - f, 3.0))
        if (outer - inner < 0.5) return
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.fill = null
            drawer.stroke = buttonColour
            drawer.strokeWeight = outer - inner
            drawer.circle(centre, (outer + inner) / 2.0)
        }
    }

    /** A drawing tool's selection round [box]: a hairline and a square handle at each corner. */
    private fun drawSelection(drawer: Drawer, box: Rectangle, shown: Double) {
        val r = Rectangle(box.x - SELECT_PAD, box.y - SELECT_PAD, box.width + 2.0 * SELECT_PAD, box.height + 2.0 * SELECT_PAD)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.fill = null
            drawer.stroke = palette.ink.opacify(shown)
            drawer.strokeWeight = 1.5
            drawer.rectangle(r)
            drawer.fill = palette.ink.opacify(shown)
            drawer.stroke = palette.paper.opacify(shown)
            for (c in listOf(r.corner, Vector2(r.x + r.width, r.y), Vector2(r.x, r.y + r.height), Vector2(r.x + r.width, r.y + r.height)))
                drawer.rectangle(Rectangle.fromCenter(c, HANDLE, HANDLE))
        }
    }

    /** The mouse pointer: a white arrow, or over the button a pointing hand, with a black edge, smaller pressed. */
    private fun drawCursor(drawer: Drawer, cursor: Cursor, paneHeight: Double) {
        val k = paneHeight * CURSOR / 20.0 * (1.0 - 0.12 * cursor.press)
        val outline = ShapeContour.fromPoints((if (cursor.hand) HAND else ARROW).map { cursor.at + it * k }, closed = true)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.lineJoin = LineJoin.MITER
            drawer.fill = palette.ink
            drawer.stroke = palette.paper
            drawer.strokeWeight = k * 1.1
            drawer.contour(outline)
            if (cursor.hand) {
                drawer.strokeWeight = k * 0.8
                drawer.lineSegments(CREASES.map { cursor.at + it * k })
            }
        }
    }

    /**
     * [text] at [size], ranged left on [at] (its baseline), on a plate of [fill] that opens from its side —
     * the left, or the right when [fromRight] — as [open] runs 0 to 1; the words stand once it is open, in [ink].
     */
    private fun plate(drawer: Drawer, text: String, at: Vector2, size: Double, open: Double, fill: ColorRGBa, ink: ColorRGBa,
                      fromRight: Boolean = false) {
        if (open <= 0.0) return
        val pad = size * PAD
        val width = bold.advanceWithSubscripts(text) * size / SIZE + 2.0 * pad
        val shown = width * open.coerceIn(0.0, 1.0)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.stroke = null
            drawer.fill = fill
            drawer.rectangle(if (fromRight) at.x - pad + width - shown else at.x - pad, at.y - size * ABOVE, shown, size * (ABOVE + BELOW))
            if (open >= 1.0) {
                drawer.fill = ink
                drawer.setLine(text, bold, at, size, SIZE)
            }
        }
    }

    private fun targetFor(w: Int, h: Int): Pair<RenderTarget, ColorBuffer> {
        val t = target?.takeIf { it.width == w && it.height == h }
            ?: renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) { colorBuffer(); depthBuffer() }.also {
                target?.destroy(); target = it
                resolved?.destroy(); resolved = colorBuffer(w, h)
            }
        return t to resolved!!
    }

    private fun smoother(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }

    /** Out past 1 and back, by [OVERSHOOT]: a piece landing a little beyond its place and settling. */
    private fun backOut(x: Double) = (x.coerceIn(0.0, 1.0) - 1.0).let { 1.0 + (OVERSHOOT + 1.0) * it * it * it + OVERSHOOT * it * it }

    private companion object {
        const val SIZE = 200.0
        /** The classic pointer, tip at the origin, 20 units tall. */
        val ARROW = listOf(
            Vector2(0.0, 0.0), Vector2(0.0, 17.0), Vector2(4.0, 13.2), Vector2(7.0, 20.0),
            Vector2(9.6, 18.9), Vector2(6.7, 12.2), Vector2(12.0, 12.2)
        )
        /** The pointing hand, its fingertip at the origin, on the arrow's scale; and the creases between its fingers. */
        val HAND = listOf(
            Vector2(-2.0, 1.8), Vector2(-1.5, 0.5), Vector2(0.0, 0.0), Vector2(1.5, 0.5), Vector2(2.0, 1.8),
            Vector2(2.0, 8.0), Vector2(2.4, 7.3), Vector2(3.7, 6.9), Vector2(5.0, 7.3), Vector2(5.4, 8.1),
            Vector2(5.8, 7.9), Vector2(7.1, 7.7), Vector2(8.4, 8.1), Vector2(8.8, 9.0),
            Vector2(9.2, 8.9), Vector2(10.4, 8.8), Vector2(11.5, 9.4), Vector2(11.8, 10.4),
            Vector2(11.8, 15.0), Vector2(11.2, 17.2), Vector2(10.2, 18.6), Vector2(10.2, 20.0),
            Vector2(2.2, 20.0), Vector2(2.2, 18.8), Vector2(0.2, 17.0),
            Vector2(-3.6, 13.2), Vector2(-4.9, 11.4), Vector2(-4.4, 10.3), Vector2(-3.1, 10.2), Vector2(-2.0, 11.2)
        )
        val CREASES = listOf(
            Vector2(2.0, 8.0), Vector2(2.0, 12.0), Vector2(5.4, 8.1), Vector2(5.4, 12.0), Vector2(8.8, 9.0), Vector2(8.8, 12.2)
        )
        /** The pointer's height as a share of the pane's, and where it rests, as shares of the pane; where its tip
         *  comes to on the button, in the button's radii from its middle. */
        const val CURSOR = 0.045
        val REST = Vector2(0.78, 0.84)
        /** Where the pointer comes in from, opened on the catalogue: off the frame below right of its rest, in pane heights. */
        val OFF_FRAME = Vector2(0.4, 0.3)
        val AIM = Vector2(0.12, 0.45)

        /** Seconds: the cursor's move to a piece, a press, a drag, and the cursor going aside. */
        const val MOVE = 0.6
        const val PRESS = 0.15
        const val DRAG = 0.8
        const val AWAY = 0.8
        /** The first click: the cursor to the button, and a moment over it; the press down, held, and let go, on
         *  which it bursts; each piece's flight out, the spread of their starts, the ring's run; and a moment of
         *  the field standing before the click lands. */
        const val TO_BUTTON = 0.7
        const val DWELL = 0.35
        const val PRESS_AT = TO_BUTTON + DWELL
        const val DOWN = 0.08
        const val HOLD_DOWN = 0.07
        const val UP = 0.07
        const val CUT = PRESS_AT + DOWN + HOLD_DOWN + UP
        const val BURST = 0.8
        const val BURST_SPREAD = 0.35
        const val RING = 0.65
        const val LOOK = 0.3
        /** The button: seconds to take the hover; how much it swells hovered, gives pressed and springs past on the
         *  let-go; the hover ring's weight and its gap, in pixels; how far the burst's ring runs, in radii; and how
         *  far a piece overshoots its place. */
        const val HOVER_IN = 0.15
        const val HOVER_GROW = 0.06
        const val SQUASH = 0.12
        const val POP = 0.08
        const val HOVER_RING = 3.0
        const val HOVER_GAP = 14.0
        const val RING_REACH = 3.2
        const val OVERSHOOT = 1.2
        /** Degrees a piece swings round the button's middle on its way out, settling to none as it lands. */
        const val SWIRL = 35.0
        /** The combine: its length, its one ease, and how far round the camera turns. */
        const val COMBINE = 2.6
        val GATHER = CubicBezier(0.6, 0.0, 0.2, 1.0)
        const val TURN = 90.0
        /** The selection round the piece in hand: seconds before the grab it appears, to show and to go and how long
         *  after the drop it stays; its air round the piece and its handles, in pixels. */
        const val SELECT_LEAD = 0.12
        const val SELECT_IN = 0.1
        const val SELECT_OUT = 0.15
        const val SELECT_PAD = 8.0
        const val HANDLE = 9.0
        /** The rest after a let-go. */
        const val SETTLE = 0.35
        const val DRAG_CLICK = MOVE + PRESS + DRAG + SETTLE
        /** Seconds into a piece being taken that its label opens. */
        const val LABEL_DELAY = 0.1

        /** The button's type, as a share of the title size, its line pitch, and the disc's reach past the words, in
         *  type sizes; it stands in the middle of the pane. */
        const val BUTTON_TYPE = 1.1
        const val LEADING = 1.15
        const val BUTTON_PAD = 0.9

        /** A plate's reach above the baseline and below it, and either side of the words, in type sizes. */
        const val ABOVE = 0.95
        const val BELOW = 0.45
        const val PAD = 0.4
        /** Pixels: the air round a dropped piece and its label, between two labels, beside a piece, the grid a drop
         *  is sought on, the least drag that reads as one and the most. */
        const val AIR = 14.0
        const val CLEAR = 12.0
        const val GAP = 18.0
        const val SCAN = 12.0
        const val MIN_DRAG = 180.0
        const val REACH = 520.0
        /** The highest a piece or a label is dropped, below the title, as a share of the pane's height. */
        const val TOP = 0.13
        /** A label said already, as a share of the size of the one being said. */
        const val SMALL = 0.78

        /** The score: the lanes in [lanes]' order, the pitches the pane's height is spread over, the button's
         *  chord and where the labels' run starts, both in the middle of that range. */
        const val POINTER = 0
        const val BUTTON = 1
        const val BURST_LANE = 2
        const val DRAGS = 3
        const val LABELS = 4
        const val GATHER_LANE = 5
        const val STATES_LANE = 6
        const val CATALOGUE_LANE = 7
        /** Seconds a piece's note lasts as it takes its colour: a pop, so a short one. */
        const val LIT_NOTE = 0.1
        const val SCORE_NOTES = 48
        val CHORD = listOf(24, 28, 31)
        const val LABEL_NOTE = 24
    }
}

/** The kit's keys the webtool sets for itself unless `SLIDES_WEBTOOL_` does: no stagger and an even move. */
private val WEBTOOL_OWN_KEYS = mapOf("STAGGER" to "0", "EASE" to "0,0,1,1")

/**
 * A kit key as the webtool reads it: `SLIDES_WEBTOOL_` first, then its own, then the kit's `KIT_` of the same name.
 * The catalogue builds its view with the same, so the grid and the webtool are drawn by one `KitView`.
 */
fun webtoolKitKey(key: String): String? = Env["SLIDES_WEBTOOL_$key"] ?: WEBTOOL_OWN_KEYS[key] ?: Env["KIT_$key"]
