// ============================================================================ //
//  No `package` declaration, for the reason ObjectScene has none: it stands on
//  loadObjectSheet and SheetObject, which are in the default package. The file
//  belongs to backdrop-drawers/, which is a folder rather than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Cut
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.ramp
import slideshow.seconds
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wall the room arrives to: the draaiboek's 18:30, Aanvang.
 *
 * A black wall on which the catalogue draws itself. A piece is **drawn before it is filled**:
 * a single white line travels the whole edge of it, and the moment the outline closes the
 * shape floods to solid white. It holds, slides up out of the frame, and the next piece is
 * drawn in its place — object after object, through the whole of `objects-front.svg`, so
 * every variant in the catalogue has its turn while the room comes in.
 *
 * **The two halves are not a pair; they are two clocks.** Each 1920x1080 half runs its own
 * turn — draw, fill, hold, leave — and the right one runs half a turn behind the left, so
 * the wall alternates: left, right, left, right, with a new piece beginning as the one
 * across from it stands finished. Nothing on the wall is ever synchronised, which is what
 * keeps it from reading as a slideshow of pairs; there is always one piece being drawn and
 * one standing to look at. [phase] is the whole of that — 0.5 is the alternation, 0 puts the
 * two back in step, and anything between gives an overlap.
 *
 * **This is one scene rather than a kind of scene**, which is why it is not [ObjectScene].
 * That is the plain backdrop — a row of elements, standing still — and the closing wall is
 * one of those. This wall is up for the better part of an hour while the room fills, and it
 * is the first thing anyone sees of the evening, so it earns an arrangement of its own. The
 * two share no code but the sheet loader, so work here cannot land on the closing wall.
 *
 * **Everything is a pure function of [Stage.frame].** A half's turn number is a division and
 * its phase a remainder, so which piece is up and how far it has been drawn follow from the
 * frame count alone — nothing is accumulated and nothing is kept. That is what lets the wall
 * be scrubbed, jumped into, paused or filmed and show the same picture at the same frame, and
 * it is the rule every drawer in the deck is written to (the `ScreenRecorder` note under
 * demo01).
 *
 * **The pen moves at a constant speed, which the contour's own parameter cannot give it.**
 * `ShapeContour.sub(0, t)` walks the *segments* evenly, so on a piece whose corners are a
 * cluster of short edges the line crawls through the detail and races down the long sides.
 * Each outline is therefore resampled at load into equally spaced points ([Drawn.strokes]),
 * and the trace is the first `t` of them — so the line travels at one speed whatever the
 * shape, and a piece with 40 segments draws no faster than a rectangle with 4.
 *
 * **A piece with a hole is drawn as one pen, not as two at once.** The outlines of an
 * element are laid end to end and the travel runs through them in turn, so a doorway's
 * opening is drawn *after* the panel around it closes rather than growing alongside it.
 *
 * The front sheet is the whole catalogue and it is mostly plain silhouettes — a slab really
 * is a rectangle and `DRST_M24_1500` really is a hairline — so some turns draw very simple
 * shapes. That is the asset rather than a fault in the drawing, and it is the point of using
 * the full sheet: every variant gets its turn. `subset.svg` is the hand-picked alternative.
 */
class OpeningScene(
    override val name: String = "Opening",
    /** The sheet, read by [loadObjectSheet]. The front sheet is all 112 pieces. */
    private val sheet: File,
    /** What the wall says along the top — the maker, not the name of the export. */
    private val title: String = "Willy Naessens",
    /**
     * The catalogue's own detail file, paired with the sheet by index. Null, missing, or a
     * different length to the sheet and the pieces carry their proportion instead.
     */
    private val details: File? = null,
    /**
     * A concrete texture the filled pieces are cut out of, tiled to cover the wall. Null
     * fills them flat in [ink].
     */
    private val concrete: File? = null,
    /** How big one tile of that texture is drawn, against its own pixels. */
    private val concreteScale: Double = 1.0,
    /** How much of the texture reaches the fill: 1 is all of it, 0 is flat [ink]. */
    private val concreteMix: Double = 1.0,
    /** The wall. */
    private val paper: ColorRGBa = ColorRGBa.BLACK,
    /** The line, and the fill it becomes. */
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    /** How much of a half-frame an element takes, at its largest dimension. */
    private val fit: Double = 0.58,
    /** The drawn line's weight, in canvas pixels — held constant however the piece is scaled. */
    private val weight: Double = 5.0,
    /**
     * Seconds the line takes to travel the whole edge of a piece.
     *
     * Long, and deliberately: this is the wall's one moving part, and it is watched by a room
     * that is arriving rather than attending. Drawing a piece slowly enough to follow is the
     * whole of what makes it read as a thing being made rather than a thing appearing.
     */
    private val trace: Double = 18.0,
    /** Seconds the fill takes to land once the outline closes. 0 snaps. */
    private val flood: Double = 0.3,
    /**
     * Seconds a finished piece stands before it leaves.
     *
     * **This is the reading time, and with the type settling exactly as the outline closes it is
     * the only part that is.** The finished piece stands here — flooded, still, its entry settled
     * beside it — so it has to be long enough to take in four short lines from across a room.
     * Six seconds is what it stands for, the flood included.
     *
     * It is paid for in stillness, which is worth knowing rather than discovering: the pen waits
     * throughout, and most of the catalogue is small parts it draws in a second or two, so a long
     * wait dominates the wall quickly. The share of frames with the pen standing still is the
     * thing to measure if this is pushed further — it was 58% at a five second wait when the
     * pen was quicker, and is worth re-reading whenever either number moves.
     */
    private val hold: Double = 5.7,
    /**
     * How long the pen's tail is, measured in **outlines of the piece being drawn**.
     *
     * 1 is the rule this wall is built on: *the line never fades out from under an object
     * before that object is finished.* The tail is exactly as long as the outline the pen is
     * on, so a piece is whole at the instant it closes — graded from just-gone at its start to
     * full brightness at the head — and only then begins to go.
     *
     * It has to be measured against **this** piece rather than an average one, which is what a
     * fixed length got wrong: the catalogue runs from a 5cm anchor to a 24m beam, so an average
     * tail eats the start of every long piece well before the pen gets round to closing it.
     */
    private val tail: Double = 1.0,
    /** Seconds the concrete takes to fade once it has stood its hold. */
    private val fade: Double = 1.4,
    /**
     * How far the right half runs behind the left, as a fraction of a whole turn. 0.5 is the
     * alternation this wall is built on — one side draws while the other stands; 0 puts the
     * two back in step and draws them as a pair.
     */
    private val phase: Double = 0.5,
    /**
     * The annotation's colour. Null draws the pieces bare.
     *
     * White, so the wall is one ink: the lettering is the same white as the piece it stands
     * under, and the only colour on the wall is the black it is all drawn on. The house red
     * is the alternative and is one argument — it reads as a drawing marked *up*, where the
     * white reads as a drawing captioned.
     *
     * It arrives *after* the outline closes and the piece has flooded — the drawing first,
     * then what the drawing is of — and leaves with the piece.
     */
    private val label: ColorRGBa? = ColorRGBa.WHITE,
    /**
     * The face the annotation is set in, and it is stated rather than taken from the deck's
     * furniture (`Type`). It was reaching Rockwell either way, but only because
     * `Slideshow.kt` happens to point `Type.file` there — repoint the talk's family and this
     * wall's lettering would follow it somewhere it was never designed for.
     */
    private val fontPath: String = "data/fonts/default.otf",
    /** How big that face is set, in canvas pixels. Small: this is a drawing, not a caption. */
    private val fontSize: Double = 13.0,
    /**
     * The colour the piece's entry flashes through when its outline closes.
     *
     * The house red, and the one place it appears on this wall: the rest is one white ink on
     * black, so a single beat of colour is enough to say *found* without the wall becoming a
     * two-colour drawing. The maker's name is deliberately left out of it — it is not a fact
     * about this piece and has nothing to announce.
     */
    private val highlight: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    /** Seconds that beat takes: out to black, up to [highlight], back to [ink]. */
    private val pulse: Double = 1.4,
    /** How the scene arrives. A cut; a fade here is composed on the whole wall. */
    override val transition: Transition = Cut,
    /**
     * The bed under the wall — the one cue here that loops, because this scene is up for the
     * better part of an hour. Its fade is the sound's own, not the transition's: the picture
     * cuts and the ambience comes up under it.
     */
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    /** One element, with its outline resampled into equally spaced points and what it is. */
    private class Drawn(
        val element: SheetObject,
        val strokes: List<List<Vector2>>,
        val meta: PieceMeta
    ) {
        /** Points over the whole edge, so a travel can be measured against it. */
        val points = strokes.sumOf { it.size }
    }

    private var pieces: List<Drawn> = emptyList()
    private var blueprint: BlueprintLabel? = null
    private var stoneStyle: ShadeStyle? = null

    // --- the path, and one speed along it ------------------------------------------ //
    //
    // **The pen has a speed, not a schedule.** Every stroke on the wall — each contour of each
    // piece, and each crossing between them — is one length of a single path, and the pen walks
    // it at a constant number of pane pixels a second. Timing a *piece* instead, as this did
    // first, is what made the drawing lurch: a small component and a 24m beam were each given
    // the same seconds, so the pen crawled around one and raced around the other. Nothing here
    // is measured in turns any more; everything is measured in distance.

    /** One stretch of the path: a contour, or a crossing between two of them. */
    private class Run(
        val points: List<Vector2>,
        /** Distance along this run at each point, so a length can be turned into a position. */
        val along: DoubleArray,
        /** Which piece this belongs to, or -1 for a crossing. */
        val piece: Int
    ) {
        val length get() = along.last()
    }

    /** The whole path, built once the pane's size is known. */
    private class Path(
        val runs: List<Run>,
        /** Where each run begins, measured from the start of the path. */
        val starts: DoubleArray,
        val total: Double,
        /** Where each piece's outline begins and ends along the path. */
        val pieceFrom: DoubleArray,
        val pieceTo: DoubleArray,
        /** Pane pixels a frame, set so an average piece takes [trace] seconds. */
        val speed: Double,
        /** Frames the pen waits at each piece's last point. */
        val pause: Double,
        /** The frame each piece closes on, and so the frame its wait begins. */
        val closedAt: DoubleArray,
        /** Frames for the whole path, waits included. */
        val totalTime: Double
    )

    private var path: Path? = null
    private var builtFor: Pair<Int, Int>? = null

    private val traceFrames get() = max(1, frames(trace))

    /**
     * A full pass: the whole path walked once. Long, because the path is the entire catalogue
     * laid end to end rather than a turn that repeats.
     */
    override val loop: Int
        get() = path?.totalTime?.toInt() ?: 0

    override fun load(program: Program) {
        if (!sheet.isFile) {
            println("no sheet at ${sheet.path} — \"$name\" stands empty; set SLIDES_OPENING_SHEET in .env")
            return
        }

        val all = loadObjectSheet(sheet)

        // The register, paired with the sheet **by index and only when the counts agree**.
        //
        // That check is the whole safety of this: the detail file is the catalogue in
        // `obj_no` order and the iso sheet is the same catalogue in the same order, but the
        // *front* sheet recovers 112 rather than 115 — so pointing the wall at that sheet
        // with these details still attached would shift every name one place from the first
        // gap onwards and never say so. A wall of mislabelled components is worse than a
        // wall of unlabelled ones, so a mismatch drops the register entirely.
        val register = details?.let { loadPieceDetails(it) }.orEmpty()
        val paired = register.size == all.size
        when {
            register.isEmpty() && details != null ->
                println("no usable detail file at ${details.path} — pieces carry their proportion")
            register.isNotEmpty() && !paired ->
                println("${details?.name}: ${register.size} rows against ${all.size} on " +
                        "${sheet.name} — not the same catalogue, so the details are left off")
        }
        pieces = all.mapIndexedNotNull { index, element ->
            // Every outline of the piece, resampled to one spacing. The budget is shared out
            // by length rather than per contour, so a small hole gets proportionally fewer
            // points than the panel around it and the pen crosses both at the same speed.
            val contours = element.shapes.flatMap { it.contours }.filter { it.length > 1e-6 }
            val total = contours.sumOf { it.length }
            if (total <= 0.0) return@mapIndexedNotNull null

            val strokes = contours.map { contour ->
                val n = (POINTS * contour.length / total).roundToInt().coerceIn(2, POINTS)
                contour.equidistantPositions(n)
            }
            Drawn(element, strokes, PieceMeta(
                title = title,
                number = index + 1,
                of = all.size,
                ratio = element.aspect,
                detail = if (paired) register.getOrNull(index) else null
            ))
        }

        // The annotation's own face, small: the reference is a technical drawing, where the
        // type is a fraction of the size of the thing it describes.
        label?.let {
            // The character set is named, and has to be: the default atlas has no `×`, so
            // the size line came out as "170  10  60 MM" — the glyph missing and its advance
            // zero, which reads as a spacing bug rather than an absent character. TYPE_CHARACTERS
            // is the deck's own set and carries the figures this wall sets.
            blueprint = BlueprintLabel(it, program.loadFont(
                fontPath, fontSize, characterSet = TYPE_CHARACTERS, contentScale = 1.0))
        }

        // The wall everything here is projected onto — see [concreteWall], which both this
        // and the conveyor stand on so the idea lives in one place.
        stoneStyle = concreteWall(concrete, concreteScale, concreteMix, name)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (pieces.isEmpty()) return
        val paneWidth = stage.width / HALVES
        val paneHeight = stage.height
        val road = pathFor(paneWidth, paneHeight) ?: return

        // The wall, and everything drawn from here is projected onto it: the fill, the line
        // that draws the edge, and the lettering. Set once for the whole frame rather than
        // around the fill alone, which is the difference between a concrete *object* and a
        // concrete *wall with a projector on it* — the second is what this is.
        drawer.shadeStyle = stoneStyle

        val floodBy = frames(flood).toDouble()
        val holdBy = frames(hold).toDouble()
        val fadeBy = frames(fade).toDouble()

        for (half in 0 until HALVES) {
            val pane = Rectangle(
                stage.bounds.corner.x + half * paneWidth, stage.bounds.corner.y,
                paneWidth, paneHeight
            )

            // Where the pen is. The two halves walk the *same* path on the same schedule, a
            // [phase] of its whole length apart — which is why they never show the same piece
            // and never fall into step, and why there is only one path to build.
            val clock = stage.frame + half * phase * road.totalTime
            val pen = penAt(road, clock)

            // The tail is the length of the outline the pen is on, so the line cannot fade
            // out from under a piece before that piece has closed.
            val on = pieceAt(road, pen)
            val tailLength = (if (on >= 0)
                wrap(road.pieceTo[on] - road.pieceFrom[on], road.total) else road.total
            ).coerceAtLeast(1.0) * tail

            // The concrete of whatever the pen has recently finished, poured in behind it.
            // Measured in **frames since the piece closed** rather than in distance, because
            // the pen is standing still for part of it: the flood and the hold happen during
            // the wait, and the fade begins on the frame the pen moves off.
            for (i in pieces.indices) {
                if (road.pieceTo[i] < 0.0) continue
                val since = wrap(clock - road.closedAt[i], road.totalTime)
                val alpha = when {
                    since < floodBy -> since / floodBy
                    since < floodBy + holdBy -> 1.0
                    since < floodBy + holdBy + fadeBy -> 1.0 - (since - floodBy - holdBy) / fadeBy
                    else -> 0.0
                }
                if (alpha <= 0.0) continue
                drawer.isolated {
                    place(this, pane, pieces[i])
                    // Multiplied into the fill rather than replacing it, so the flood's own
                    // fade still rides on the alpha and the ink still tints the stone.
                    this.fill = ink.opacify(alpha)
                    this.stroke = null
                    shapes(pieces[i].element.shapes)
                }
            }

            trail(drawer, pane, road, pen, tailLength)
            annotate(drawer, pane, road, pen, stage.frame, on, clock)
        }

        drawer.shadeStyle = null
    }

    /**
     * The tail: the last [tail] pixels of path behind the pen, dimming with distance.
     *
     * Walked backwards run by run rather than piece by piece, so it crosses contour joins and
     * piece boundaries without knowing they are there — which is the whole reason the path is
     * one list. The gradient is banded, a band every [BAND] pixels: a line strip takes one
     * colour, and a band per pixel would be thousands of calls for a difference nobody can see.
     */
    private fun trail(drawer: Drawer, pane: Rectangle, road: Path, pen: Double, tail: Double) {
        var run = runAt(road, pen)
        var to = pen - road.starts[run]
        var ago = 0.0
        var guard = GUARD

        drawer.isolated {
            translate(pane.corner)
            fill = null
            strokeWeight = weight
            while (ago < tail && guard-- > 0) {
                val here = road.runs[run]
                val from = max(0.0, to - (tail - ago))
                var at = from
                while (at < to) {
                    val next = min(to, at + BAND)
                    val alpha = 1.0 - (ago + (to - (at + next) / 2.0)) / tail
                    if (alpha > 0.0) {
                        stroke = ink.opacify(alpha.coerceAtMost(1.0))
                        val piece = slice(here, at, next)
                        if (piece.size >= 2) lineStrip(piece)
                    }
                    at = next
                }
                ago += to - from
                run = (run - 1 + road.runs.size) % road.runs.size
                to = road.runs[run].length
            }
        }
    }

    /**
     * The annotation for the piece the pen is on.
     *
     * **The type searches for exactly as long as the pen is drawing.** It starts churning on the
     * frame the pen leaves the last piece and settles on the frame the new outline closes, so
     * the lettering and the drawing are the same gesture — you can tell how far through a piece
     * the wall is from either one.
     *
     * The reading time is the other half of that: through the wait and the crossing the pen is
     * not on a new piece yet, so [pieceAt] still answers with the one just finished and the
     * entry stands settled, beside its concrete, until the next outline actually begins. Holding
     * it settled *into* the next piece was tried and is worse — the type then sat still while
     * the pen was visibly drawing, and the two read as unrelated.
     */
    private fun annotate(
        drawer: Drawer, pane: Rectangle, road: Path, pen: Double, frame: Int, current: Int,
        clock: Double
    ) {
        val label = blueprint ?: return
        if (current < 0) return

        val from = road.pieceFrom[current]
        val span = wrap(road.pieceTo[current] - from, road.total).coerceAtLeast(1.0)
        val resolve = (wrap(pen - from, road.total) / span).coerceIn(0.0, 1.0)

        val piece = pieces[current]
        val scale = fitOf(pane.width, pane.height, piece)
        val box = piece.element.bounds
        label.draw(
            drawer, pane,
            Rectangle.fromCenter(pane.center, box.width * scale, box.height * scale),
            piece.meta, resolve, frame, accent = announce(road, clock, current)
        )
    }

    /**
     * The beat the entry runs through when its piece closes: out to black, up into [highlight],
     * and back to the wall's own white.
     *
     * Null once it is over, which is most of the time — the wall is white ink on black and this
     * is the only colour on it.
     *
     * **It is measured from the frame the piece closed**, not from anything the pen is doing, so
     * it fires on the same event as the wait and the flood: the outline meets itself, the
     * concrete goes in, and the entry announces what has been found. While a piece is still
     * being drawn its closing frame is a whole path ahead, so the beat is simply not running.
     */
    private fun announce(road: Path, clock: Double, piece: Int): ColorRGBa? {
        val over = frames(pulse).toDouble()
        if (over <= 0.0) return null
        val since = wrap(clock - road.closedAt[piece], road.totalTime)
        if (since >= over) return null

        val t = since / over
        return when {
            t < DARK -> ColorRGBa.BLACK
            t < COLOUR -> ColorRGBa.BLACK.mix(highlight, (t - DARK) / (COLOUR - DARK))
            else -> highlight.mix(ink, (t - COLOUR) / (1.0 - COLOUR))
        }
    }

    // --- the path ------------------------------------------------------------------- //

    /** The path for a pane of this size, built once and kept. */
    private fun pathFor(paneWidth: Double, paneHeight: Double): Path? {
        val key = paneWidth.toInt() to paneHeight.toInt()
        if (builtFor == key) return path

        val runs = ArrayList<Run>()
        val starts = ArrayList<Double>()
        val pieceFrom = DoubleArray(pieces.size) { -1.0 }
        val pieceTo = DoubleArray(pieces.size) { -1.0 }
        var at = 0.0
        var last: Vector2? = null

        fun add(points: List<Vector2>, piece: Int) {
            if (points.size < 2) return
            val along = DoubleArray(points.size)
            for (i in 1 until points.size) {
                along[i] = along[i - 1] + points[i].distanceTo(points[i - 1])
            }
            starts.add(at)
            runs.add(Run(points, along, piece))
            at += along.last()
        }

        val centre = Vector2(paneWidth / 2.0, paneHeight / 2.0)
        pieces.forEachIndexed { index, piece ->
            val scale = fitOf(paneWidth, paneHeight, piece)
            val middle = piece.element.bounds.center
            piece.strokes.forEach { stroke ->
                val points = stroke.map { centre + (it - middle) * scale }
                // A crossing before every contour, the ones *inside* a piece included: a panel
                // with a doorway is two rings, and without this the pen jumped between them.
                last?.let { add(listOf(it, points.first()), -1) }
                if (pieceFrom[index] < 0.0) pieceFrom[index] = at
                add(points, index)
                last = points.last()
                pieceTo[index] = at
            }
        }
        if (runs.isEmpty()) return null

        // Close the loop, so the pen comes round to where it started and the wall repeats.
        last?.let { add(listOf(it, runs.first().points.first()), -1) }

        // One speed for everything, set so an *average* piece takes `trace` seconds. So `trace`
        // still means what it says, while a long outline honestly takes longer than a short one.
        val average = pieces.indices
            .filter { pieceTo[it] >= 0.0 }
            .map { wrap(pieceTo[it] - pieceFrom[it], at) }
            .average()

        val speed = (average / traceFrames).coerceAtLeast(1e-6)

        // **The pen waits where each piece closes.** The wait is the flood and the hold — the
        // concrete is poured and stands while the drawing holds still — and the pen leaves at
        // the very moment the concrete begins to fade. So the stop is a beat rather than a
        // stall: it lands on the same event every time, which is what separates it from the
        // lurching this replaced, where the pace changed for no reason the eye could find.
        val pause = (frames(flood) + frames(hold)).toDouble()
        val closedAt = DoubleArray(pieces.size)
        for (i in pieces.indices) closedAt[i] = pieceTo[i] / speed + pause * i
        val totalTime = at / speed + pause * pieces.size

        val built = Path(runs, starts.toDoubleArray(), at, pieceFrom, pieceTo,
            speed, pause, closedAt, totalTime)
        path = built
        builtFor = key
        println(("\"$name\": ${sheet.name}, ${pieces.size} pieces, %.0f px a second, " +
                "an average piece in %.1fs, %.1fs held at each, %.0f min a pass").format(
            built.speed * slideshow.FPS, trace, seconds(built.pause.toInt()),
            seconds(built.totalTime.toInt()) / 60.0))
        return built
    }

    /**
     * Where the pen is at [frame], waits included.
     *
     * Distance is no longer just `speed * frame`: the pen stands still at every piece's last
     * point for [Path.pause] frames, so the schedule has to be inverted — find the last piece
     * closed, and either the pen is still waiting on it or it has been moving since.
     */
    private fun penAt(road: Path, frame: Double): Double {
        val t = wrap(frame, road.totalTime)
        var low = -1
        var high = road.closedAt.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (road.closedAt[mid] <= t) low = mid else high = mid - 1
        }
        if (low < 0) return t * road.speed
        val since = t - road.closedAt[low]
        return if (since < road.pause) road.pieceTo[low]
        else road.pieceTo[low] + (since - road.pause) * road.speed
    }

    /** Which run the pen is in. */
    private fun runAt(road: Path, pen: Double): Int {
        var low = 0
        var high = road.runs.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (road.starts[mid] <= pen) low = mid else high = mid - 1
        }
        return low
    }

    /** Which piece the pen is on, or the one it has just left while it is crossing. */
    private fun pieceAt(road: Path, pen: Double): Int {
        var run = runAt(road, pen)
        var guard = GUARD
        while (road.runs[run].piece < 0 && guard-- > 0) {
            run = (run - 1 + road.runs.size) % road.runs.size
        }
        return road.runs[run].piece
    }

    /**
     * The polyline of [run] between two distances along it, with the ends interpolated.
     *
     * Interpolated rather than snapped to the nearest point, because a crossing is two points
     * and snapping would make it appear whole or not at all — the pen would jump the gap.
     */
    private fun slice(run: Run, from: Double, to: Double): List<Vector2> {
        if (to <= from) return emptyList()
        val out = ArrayList<Vector2>()
        out.add(pointAt(run, from))
        for (i in run.points.indices) {
            if (run.along[i] > from && run.along[i] < to) out.add(run.points[i])
        }
        out.add(pointAt(run, to))
        return out
    }

    /** Where [distance] along [run] falls. */
    private fun pointAt(run: Run, distance: Double): Vector2 {
        val d = distance.coerceIn(0.0, run.length)
        var i = run.along.binarySearch(d)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, run.points.size - 2)
        val span = run.along[i + 1] - run.along[i]
        val t = if (span > 1e-9) (d - run.along[i]) / span else 0.0
        return run.points[i] + (run.points[i + 1] - run.points[i]) * t
    }

    /** How much a piece is scaled to sit in a pane of this size. */
    private fun fitOf(paneWidth: Double, paneHeight: Double, piece: Drawn): Double {
        val b = piece.element.bounds
        return min(paneWidth / b.width, paneHeight / b.height) * fit
    }

    /** Puts the drawer in the piece's own space, centred in its half. */
    private fun place(drawer: Drawer, pane: Rectangle, piece: Drawn) {
        val scale = fitOf(pane.width, pane.height, piece)
        drawer.translate(pane.center)
        drawer.scale(scale, scale)
        drawer.translate(-piece.element.bounds.center)
    }

    /** [value] brought into 0 until [span], for a path that loops. */
    private fun wrap(value: Double, span: Double): Double {
        if (span <= 0.0) return 0.0
        val m = value % span
        return if (m < 0.0) m + span else m
    }

    private companion object {
        /** Left and right: the two 1920x1080 halves of the wall. */
        const val HALVES = 2

        /**
         * Points an element's whole edge is resampled to. It sets how smooth a curve looks
         * and how finely the travel can stop, and 900 is well past both at this size — the
         * pieces are precast components, so they are mostly straight lines anyway.
         */
        const val POINTS = 900

        /** Pane pixels between steps of the fading gradient along the tail. */
        const val BAND = 24.0

        /** A ceiling on the walk back down the path, so a short path cannot spin. */
        const val GUARD = 400

        /** How much of the announcing beat is spent out at black, and reaching the colour. */
        const val DARK = 0.16
        const val COLOUR = 0.42
    }
}
