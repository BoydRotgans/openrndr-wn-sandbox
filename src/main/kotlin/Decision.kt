import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.composition.ShapeNode
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.shape.LineSegment
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * decision — scattered choices resolving into one plan.
 *
 * The piece has two keyframes and they are both drawings, not code: `non-decided.svg` is
 * where the dots and lines start and `decided.svg` is where they end up. The sketch reads
 * the pair, works out which dot in the one is which dot in the other, and moves them there
 * a single dot at a time.
 *
 * What actually stands at each dot is one of demo02's precast elements, off `DECISION_SHEET`
 * and drawn in the dot's own colour. So the svgs place and colour the decisions and the sheet
 * says what they are; the circles are only what the drawing uses to mark a spot, and
 * `DECISION_SHEET=none` draws them.
 *
 * `DECISION_INK` and `DECISION_PAPER` re-colour what is drawn without touching the scene:
 * `FFFFFF` is the black and white version, `FFFFFF,7A7A7A` a mono one that still tells the
 * two groups apart. The colours in the svgs stay as they are because they are load-bearing —
 * they pair the shapes across the drawings and say which dots move which line.
 *
 *     ./gradlew run -Popenrndr.application=DecisionKt
 *
 *     →  /  space   let the next dot decide
 *     ←              take it back
 *     r              back to undecided
 *
 * **The lines are not animated; they are read off the dots.** Each line belongs to the dots
 * that share its colour, and it stands wherever that group has got to: with neither red dot
 * settled the red line lies at the angle it starts at, with one settled it is half way, with
 * both it is exactly the axis it was going to be. So the line is the state of the argument,
 * the dots are the people in it, and the cross at the end is not a thing that gets drawn —
 * it is what is left once everyone agrees.
 *
 * Dots decide nearest-first, so the piece opens with the small agreements and closes on the
 * one that was furthest out. That last step is also the one that squares the last line, which
 * is the beat the whole thing is built around. `DECISION_ORDER` takes `far`, `file` or
 * `random` instead.
 *
 * To change the composition, move the circles in Figma and re-export both svgs. Nothing here
 * knows how many dots there are, what colour they are, or where the centre is.
 */
fun main() = application {
    // Made for a 1920x1080 canvas; the window shows it at DECISION_WINDOW_SCALE. The
    // composition lives in the svg's own coordinates and is fitted to the canvas, so the
    // window is a preview of the piece and never a different one.
    val canvasWidth = Env["DECISION_WIDTH"]?.toDoubleOrNull() ?: 1920.0
    val canvasHeight = Env["DECISION_HEIGHT"]?.toDoubleOrNull() ?: 1080.0
    val windowScale = Env["DECISION_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.7

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
    }

    program {
        // What stands at each decision. The svgs say where the decisions are and how they
        // are coloured; the sheet says what they look like. DECISION_SHEET=none leaves the
        // dots as the circles they are in the file, which is the piece as drawn — the
        // sentinel is a word rather than an empty value because Env reads blank as unset
        // and an unset key would fall back to the sheet again.
        val sheet = Env["DECISION_SHEET"] ?: DEFAULT_SHEET
        val sheetObjects = if (sheet.equals(NO_SHEET, true)) emptyList() else loadObjectSheet(File(sheet))

        // A dot is 70 units across and the objects gather 116 apart, so an object drawn at
        // the dot's own size is a smudge. The scale therefore grows the object *and* the
        // arrangement it gathers into by the same factor: the decided positions are pushed
        // out from the point they gather on, so the composition is the one that was drawn,
        // enlarged, rather than a tighter one with the objects piled up in it.
        val objectScale = when {
            sheetObjects.isEmpty() -> 1.0
            else -> Env["DECISION_OBJECT_SCALE"]?.toDoubleOrNull() ?: DEFAULT_OBJECT_SCALE
        }

        val scene = loadDecisionScene(
            File(Env["DECISION_UNDECIDED"] ?: DEFAULT_UNDECIDED),
            File(Env["DECISION_DECIDED"] ?: DEFAULT_DECIDED),
            Env["DECISION_ORDER"] ?: "near",
            objectScale
        )
        println("${scene.dots.size} dots, ${scene.guides.size} lines, deciding in order ${scene.order}")

        // Which drawings, counted in the sheet's reading order. Left unset they come in that
        // order, which on these sheets is a run of plain slabs.
        val objects = if (sheetObjects.isEmpty()) emptyList() else {
            val picked = Env["DECISION_OBJECTS"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            val chosen = picked?.map { sheetObjects[it.mod(sheetObjects.size)] } ?: sheetObjects
            println("${sheetObjects.size} objects in $sheet, standing in: ${picked ?: "sheet order"}")
            List(scene.dots.size) { chosen[it % chosen.size] }
        }

        // The drawing's own colours are what pair the shapes up and what tie a line to the
        // dots that move it, so they are never overwritten. DECISION_INK changes only what
        // reaches the paper: one value paints everything alike, several are handed out to
        // the colours in the order the drawing introduces them — which is how a black and
        // white version can still keep its two groups apart, in tone instead of in hue.
        val inks = Env["DECISION_INK"]?.split(",")
            ?.mapNotNull { it.trim().removePrefix("#").takeIf(String::isNotEmpty) }
            ?.map { ColorRGBa.fromHex(it) }
            ?.takeIf { it.isNotEmpty() }

        val groups = (scene.dots.map { it.fill } + scene.guides.map { it.stroke })
            .map { ColourKey(it) }
            .distinct()

        val palette = inks?.let { list ->
            groups.withIndex().associate { (index, key) -> key to list[index % list.size] }
        } ?: emptyMap()

        fun ink(colour: ColorRGBa) = palette[ColourKey(colour)] ?: colour

        val paper = Env["DECISION_PAPER"]
            ?.let { ColorRGBa.fromHex(it.trim().removePrefix("#")) }
            ?: scene.background

        var settled = 0
        var applied = 0

        // Backdated so the opening frame reads as a finished transition into the
        // undecided state rather than as one still running.
        var startedAt = -1000.0

        val dotFrom = scene.dots.map { it.undecided }.toMutableList()
        val dotTo = dotFrom.toMutableList()
        val guideFrom = scene.guides.map { it.undecided }.toMutableList()
        val guideTo = guideFrom.toMutableList()

        fun eased(now: Double, delay: Double, span: Double) =
            easeInOutCubic(((now - startedAt - delay) / span).coerceIn(0.0, 1.0))

        fun dotAt(now: Double, index: Int) =
            lerp(dotFrom[index], dotTo[index], eased(now, 0.0, DOT_DURATION))

        /** The line follows the dot that moved it, a beat behind and over a little longer. */
        fun guideAt(now: Double, index: Int) =
            lerp(guideFrom[index], guideTo[index], eased(now, GUIDE_LAG, GUIDE_DURATION))

        /**
         * Asks [stateAt] where everything belongs once [target] dots have decided and starts
         * a move towards it. Everything leaves from where it is at [now], so stepping again
         * part way through picks up from the current frame instead of snapping.
         */
        fun retarget(target: Int, now: Double) {
            val state = scene.stateAt(target)
            val dotsNow = scene.dots.indices.map { dotAt(now, it) }
            val guidesNow = scene.guides.indices.map { guideAt(now, it) }

            for (i in scene.dots.indices) {
                dotFrom[i] = dotsNow[i]
                dotTo[i] = state.dots[i]
            }
            for (i in scene.guides.indices) {
                guideFrom[i] = guidesNow[i]
                guideTo[i] = state.guides[i]
            }
            startedAt = now
        }

        // Only the count is set here. The retarget happens in the draw loop, because that is
        // the one place `seconds` is the clock the animation is read back with — see the
        // note on ScreenRecorder in demo01.
        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT || event.key == KEY_SPACEBAR ->
                    settled = (settled + 1).coerceAtMost(scene.dots.size)

                event.key == KEY_ARROW_LEFT -> settled = (settled - 1).coerceAtLeast(0)
                event.name == "r" -> settled = 0
            }
        }

        if (Env.boolean("DECISION_RECORD")) {
            extend(ScreenRecorder().apply {
                frameRate = Env["DECISION_FPS"]?.toIntOrNull() ?: 60
                // so the clip comes out at the canvas size, not the window's
                contentScale = 1.0 / windowScale
                Env["DECISION_DURATION"]?.toDoubleOrNull()?.let { maximumDuration = it }
            })
        }

        // Seconds between automatic steps, for recording a clip hands-off. The first step
        // falls one interval in, so the clip opens on the undecided drawing.
        val autoStep = Env["DECISION_AUTOSTEP"]?.toDoubleOrNull() ?: 0.0

        // One png per resting state, then quit: the keyframes the piece steps through,
        // which is how to judge a change to the drawings without filming it.
        val stills = Env.boolean("DECISION_STILLS")
        val settleTime = maxOf(DOT_DURATION, GUIDE_LAG + GUIDE_DURATION)

        // Everything is composed at the canvas size and the window only shows that image,
        // so the preview, a still and a recorded frame are the same pixels at different
        // sizes. Mipmaps keep the preview from crawling when the window is smaller, and the
        // stencil is what lets the objects be drawn as shapes rather than as outlines.
        val canvas = renderTarget(canvasWidth.toInt(), canvasHeight.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        // The svg's coordinates fitted into the canvas, centred. One scale for everything,
        // so radii and stroke weights stay in proportion to the drawing whatever the canvas
        // is — the piece is the drawing, not a pixel size.
        val scale = min(canvasWidth / scene.frame.width, canvasHeight / scene.frame.height)
        val origin = Vector2(
            (canvasWidth - scene.frame.width * scale) / 2.0,
            (canvasHeight - scene.frame.height * scale) / 2.0
        ) - scene.frame.corner * scale

        fun place(point: Vector2) = origin + point * scale

        extend {
            val now = seconds

            if (autoStep > 0.0 && !stills) {
                settled = (now / autoStep).toInt().coerceIn(0, scene.dots.size)
            }

            if (settled != applied) {
                applied = settled
                retarget(settled, now)
            }

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(paper)

                // Each object keeps the colour of the dot it stands for, because that is
                // what says which line it moves. It is fitted into the square the dot
                // occupied rather than given a size of its own, so the sheet can be swapped
                // for one drawn at any scale and the composition does not shift.
                drawer.stroke = null
                scene.dots.forEachIndexed { index, dot ->
                    drawer.fill = ink(dot.fill)
                    val centre = place(dotAt(now, index))
                    val object_ = objects.getOrNull(index)
                    if (object_ == null) {
                        drawer.circle(centre, dot.radius * scale)
                    } else {
                        val extent = dot.radius * 2.0 * scale * objectScale
                        drawer.isolated { object_.drawFitted(drawer, Rectangle.fromCenter(centre, extent)) }
                    }
                }

                // Lines over objects, the order they are in the file. In the decided state
                // the cross threads the gap between the four of them, and that only reads if
                // the lines are the thing on top.
                drawer.fill = null
                scene.guides.forEachIndexed { index, guide ->
                    drawer.stroke = ink(guide.stroke)
                    drawer.strokeWeight = guide.weight * scale
                    val segment = guideAt(now, index)
                    drawer.lineSegment(place(segment.start), place(segment.end))
                }
            }

            canvas.colorBuffer(0).generateMipmaps()
            drawer.image(canvas.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())

            if (stills && now - startedAt > settleTime) {
                val target = File("screenshots/decision-%02d.png".format(settled))
                target.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(target)
                println("saved ${target.path}")
                if (settled >= scene.dots.size) application.exit() else settled++
            }
        }
    }
}

// ------------------------------------------------------------------------------ //

/** One dot, and the two places it can be. */
class Dot(
    val fill: ColorRGBa,
    val radius: Double,
    val undecided: Vector2,
    val decided: Vector2
) {
    /** How far this dot has to move. What the deciding order is read from. */
    val travel = undecided.distanceTo(decided)
}

/** One line, and the two places it can be. Its colour says which dots move it. */
class Guide(
    val stroke: ColorRGBa,
    val weight: Double,
    val undecided: LineSegment,
    val decided: LineSegment
)

/**
 * The two drawings, paired up: every dot and line knows where it starts and where it ends,
 * and [order] is the sequence the dots decide in.
 */
class DecisionScene(
    val frame: Rectangle,
    val background: ColorRGBa,
    val dots: List<Dot>,
    val guides: List<Guide>,
    val order: List<Int>
)

/** Where everything is once [settled] dots have decided. */
class DecisionState(val dots: List<Vector2>, val guides: List<LineSegment>)

/**
 * The whole piece as a pure function of one number: how many dots have decided. No time and
 * no state, so the sketch only ever has to interpolate towards what this says.
 *
 * A dot is either where it started or where it ends up. A line is somewhere in between, at
 * the fraction of *its own colour's* dots that have settled — a line with no dots of its
 * colour falls back to the whole room.
 */
fun DecisionScene.stateAt(settled: Int): DecisionState {
    val decided = order.take(settled.coerceIn(0, dots.size)).toSet()
    val positions = dots.mapIndexed { index, dot ->
        if (index in decided) dot.decided else dot.undecided
    }

    val segments = guides.map { guide ->
        val party = dots.indices.filter { sameColour(dots[it].fill, guide.stroke) }
        val agreement = when {
            party.isEmpty() -> decided.size.toDouble() / dots.size
            else -> party.count { it in decided }.toDouble() / party.size
        }
        LineSegment(
            lerp(guide.undecided.start, guide.decided.start, agreement),
            lerp(guide.undecided.end, guide.decided.end, agreement)
        )
    }
    return DecisionState(positions, segments)
}

// ------------------------------------------------------------------------------ //

/**
 * Reads the two keyframes and works out which shape in the one is which shape in the other.
 *
 * The svgs carry no ids — Figma's export is a flat list of `<circle>` and `<path>` — so the
 * pairing has to be inferred. Colour splits them into groups first, then within a group the
 * dots are matched so that the total distance travelled is as small as it can be. That is
 * what keeps the arrangement recognisable across the move: the top-right dot goes to the
 * top-right slot rather than crossing the frame to swap with its twin.
 *
 * [order] is `near` (default, smallest move first and the outlier last), `far`, `file` or
 * `random`.
 *
 * [gather] pushes the decided positions out from the point they gather on. It is how a dot
 * gets replaced by something larger than a dot without the result being a pile: the decided
 * arrangement keeps its proportions and simply takes more room, so the cross still threads
 * between the objects the way it threads between the circles.
 */
fun loadDecisionScene(
    undecided: File,
    decided: File,
    order: String = "near",
    gather: Double = 1.0
): DecisionScene {
    val start = readKeyframe(undecided)
    val end = readKeyframe(decided)

    val paired = pairByColour(start.spots, end.spots, { it.fill }, { a, b -> a.centre.distanceTo(b.centre) })
        .map { (from, to) -> Dot(from.fill, from.radius, from.centre, to.centre) }

    val guides = pairByColour(start.rules, end.rules, { it.stroke }, { a, b ->
        a.segment.position(0.5).distanceTo(b.segment.position(0.5))
    }).map { (from, to) -> Guide(from.stroke, from.weight, from.segment, orient(from.segment, to.segment)) }

    check(paired.isNotEmpty()) { "${undecided.path} holds no dots — expected filled circles" }

    // The point they gather on. Where the lines cross would do as well and is the same place
    // to within a few units, but the mean of the decided positions needs no lines to exist.
    val focus = paired.map { it.decided }.reduce(Vector2::plus) / paired.size.toDouble()
    val dots = when (gather) {
        1.0 -> paired
        else -> paired.map { Dot(it.fill, it.radius, it.undecided, focus + (it.decided - focus) * gather) }
    }

    val sequence = when (order.lowercase()) {
        "far" -> dots.indices.sortedByDescending { dots[it].travel }
        "file" -> dots.indices.toList()
        "random" -> dots.indices.shuffled(Random(ORDER_SEED))
        else -> dots.indices.sortedBy { dots[it].travel }
    }

    return DecisionScene(start.frame, start.background, dots, guides, sequence)
}

/** A filled circle read off a keyframe. */
private class Spot(val fill: ColorRGBa, val centre: Vector2, val radius: Double)

/** A stroked line read off a keyframe. */
private class Rule(val stroke: ColorRGBa, val weight: Double, val segment: LineSegment)

private class Keyframe(
    val frame: Rectangle,
    val background: ColorRGBa,
    val spots: List<Spot>,
    val rules: List<Rule>
)

private fun readKeyframe(file: File): Keyframe {
    require(file.isFile) { "no keyframe at ${file.path} — set DECISION_UNDECIDED / DECISION_DECIDED in .env" }

    val nodes = loadSVG(file).findShapes().filter { it.effectiveShape.contours.isNotEmpty() }
    check(nodes.isNotEmpty()) { "${file.path} holds no shapes" }

    // Figma exports the artboard as a background rect the size of the frame, so the largest
    // closed shape gives both the coordinate space everything else is measured in and the
    // colour to clear to. Figma also writes a clip rect in <defs> at exactly that size, and
    // the loader hands it over too — the artboard is saved only by coming first in the
    // document, maxBy keeping the first of equal areas. If a re-export ever comes back with
    // a white background, that tie is what went the other way.
    val closed = nodes.filter { node -> node.effectiveShape.contours.all { it.closed } }
    val board = closed.maxByOrNull { it.effectiveShape.bounds.area }
        ?: error("${file.path} holds no background rect")
    val frame = board.effectiveShape.bounds

    // A dot is a small closed shape as wide as it is high. That takes the circles and leaves
    // the artboard and the clip rect, without either being named.
    val spots = closed.filter { it !== board && it.isDot(frame) }.map { node ->
        val bounds = node.effectiveShape.bounds
        Spot(
            node.effectiveFill ?: ColorRGBa.WHITE,
            bounds.center,
            (bounds.width + bounds.height) / 4.0
        )
    }

    // A line is an open contour; only its ends matter, the rest of it is straight.
    val rules = nodes.filter { node -> node.effectiveShape.contours.any { !it.closed } }.map { node ->
        val contour = node.effectiveShape.contours.first { !it.closed }
        Rule(
            node.effectiveStroke ?: ColorRGBa.WHITE,
            node.effectiveStrokeWeight,
            LineSegment(contour.segments.first().start, contour.segments.last().end)
        )
    }

    return Keyframe(frame, board.effectiveFill ?: ColorRGBa.BLACK, spots, rules)
}

private fun ShapeNode.isDot(frame: Rectangle): Boolean {
    val bounds = effectiveShape.bounds
    return bounds.width > 0.0 &&
            abs(bounds.width - bounds.height) < bounds.width * DOT_ROUNDNESS &&
            bounds.width < frame.width * DOT_MAX_WIDTH
}

/**
 * Pairs the shapes of one keyframe with those of the other: same colour first, then whichever
 * one-to-one matching within that colour moves the least in total.
 *
 * The matching is exhaustive up to [BRUTE_FORCE_LIMIT] shapes of a colour and greedy above it,
 * which is the point where the factorial stops being free and a composition that dense has no
 * obvious pairing to get wrong anyway.
 *
 * Pairs come back in the order [from] listed them, not grouped by colour: that is the order
 * the svg was drawn in, and it is what an object index in `DECISION_OBJECTS` counts along.
 */
private fun <T> pairByColour(
    from: List<T>,
    to: List<T>,
    colour: (T) -> ColorRGBa,
    cost: (T, T) -> Double
): List<Pair<T, T>> {
    val left = from.groupBy { ColourKey(colour(it)) }
    val right = to.groupBy { ColourKey(colour(it)) }

    require(left.keys == right.keys) {
        "the two keyframes do not hold the same colours: ${left.keys} against ${right.keys}"
    }

    val pairs = left.keys.flatMap { key ->
        val a = left.getValue(key)
        val b = right.getValue(key)
        require(a.size == b.size) {
            "$key appears ${a.size} times in the undecided drawing and ${b.size} times in the decided one"
        }
        match(a, b, cost)
    }
    return pairs.sortedBy { pair -> from.indexOfFirst { it === pair.first } }
}

private fun <T> match(from: List<T>, to: List<T>, cost: (T, T) -> Double): List<Pair<T, T>> {
    if (from.size == 1) return listOf(from[0] to to[0])

    if (from.size <= BRUTE_FORCE_LIMIT) {
        val best = permutations(from.size).minByOrNull { order ->
            order.withIndex().sumOf { (i, j) -> cost(from[i], to[j]) }
        }!!
        return best.mapIndexed { i, j -> from[i] to to[j] }
    }

    val taken = BooleanArray(to.size)
    return from.map { item ->
        val pick = to.indices.filter { !taken[it] }.minByOrNull { cost(item, to[it]) }!!
        taken[pick] = true
        item to to[pick]
    }
}

/** Every one-to-one assignment of [n] items, by slotting item n-1 into each gap in turn. */
private fun permutations(n: Int): List<List<Int>> {
    if (n == 0) return listOf(emptyList())
    return permutations(n - 1).flatMap { rest ->
        (0..rest.size).map { at -> rest.toMutableList().apply { add(at, n - 1) } }
    }
}

/** [target] with its ends put the way round that moves [source] the least. */
private fun orient(source: LineSegment, target: LineSegment): LineSegment {
    val straight = source.start.distanceTo(target.start) + source.end.distanceTo(target.end)
    val swapped = source.start.distanceTo(target.end) + source.end.distanceTo(target.start)
    return if (swapped < straight) LineSegment(target.end, target.start) else target
}

/**
 * Colours as they group. Two shapes exported from the same Figma fill parse to the same
 * doubles, but rounding first means a colour that is a shade off in one file still groups
 * with its twin instead of failing the pairing.
 */
private class ColourKey(colour: ColorRGBa) {
    private val key = Triple(round(colour.r), round(colour.g), round(colour.b))
    private fun round(v: Double) = Math.round(v * 255.0).toInt()
    override fun equals(other: Any?) = other is ColourKey && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "#%02X%02X%02X".format(key.first, key.second, key.third)
}

private fun sameColour(a: ColorRGBa, b: ColorRGBa) = ColourKey(a) == ColourKey(b)

// ------------------------------------------------------------------------------ //

private const val DEFAULT_UNDECIDED = "data/svg/non-decided.svg"
private const val DEFAULT_DECIDED = "data/svg/decided.svg"

/** The sheet the dots are drawn from — demo02's hand-picked selection. */
private const val DEFAULT_SHEET = "data/svg/subset.svg"

/** DECISION_SHEET set to this draws the dots themselves instead of anything off a sheet. */
private const val NO_SHEET = "none"
private const val DEFAULT_OBJECT_SCALE = 3.0

/** Seconds a dot takes to decide, and the beat the line waits before following it. */
private const val DOT_DURATION = 0.9
private const val GUIDE_LAG = 0.12
private const val GUIDE_DURATION = 1.0

/** How far from square a closed shape may be and still count as a dot, and how large. */
private const val DOT_ROUNDNESS = 0.05
private const val DOT_MAX_WIDTH = 0.25

private const val BRUTE_FORCE_LIMIT = 8
private const val ORDER_SEED = 4

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

private fun lerp(a: Vector2, b: Vector2, t: Double) = Vector2(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun lerp(a: LineSegment, b: LineSegment, t: Double) =
    LineSegment(lerp(a.start, b.start, t), lerp(a.end, b.end, t))

private fun easeInOutCubic(t: Double) =
    if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3.0) / 2.0
