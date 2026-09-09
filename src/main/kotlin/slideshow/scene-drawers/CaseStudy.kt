// ============================================================================ //
//  No `package` declaration, for the reason the yard has none: this stands on
//  loadObjMesh and IsoPieces, which live in the default package. The file still
//  belongs in scene-drawers/.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.loadImage
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import slideshow.Cut
import slideshow.Scene
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.frames
import slideshow.smoothstep
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * One case: what it is about, and the piece that stands for it.
 *
 * The title is the subject — a sector — and [piece] is only the mark it is drawn as, with
 * [note] naming the element underneath and [image] a photograph of it, blended in as the
 * camera closes on the piece. That way round on purpose: the wall is about what is
 * built, and the catalogue is how it is said. Swapping which element stands for a sector is a
 * word here and changes nothing else.
 */
class Case(val piece: String, val title: String, val note: String = "", val image: String = "")

/**
 * A case study: every piece laid out at once, then each one taken full frame in turn.
 *
 * The scene opens on the whole set — all the cases pasted up together on the white ground, so
 * the field is seen before any one of it is — and then a click takes the first piece and
 * fills the wall with it, the next click the second, and so on to the end. The set is never
 * rebuilt and nothing is hidden: what moves is the camera.
 *
 * **The overview is a collage rather than a grid.** A grid underlies it, and a piece that its
 * scatter would push through a neighbour is walked back toward its own cell until it clears —
 * solids seen in the round passing through one another read as a fault, not as a collage. Every
 * piece is shifted off its cell, sized up or down, and given its own colour out of a playful
 * palette — so the set reads as things pasted up,
 * overlapping, rather than as specimens in a case. Each is scattered from a **seeded** random,
 * so the same frame always draws the same collage and the wall can still be scrubbed.
 *
 * **Every piece keeps its own little ground.** The shear casts each shadow from the piece's own
 * bottom, so shifting a piece up or down the wall takes its shadow with it — which is what a
 * collage wants, each cutting pasted at its own height with its own shadow under it, rather
 * than one horizon everything stands on.
 *
 * **Zooming is a scale and a shift, because the projection is parallel.** There is no
 * frustum to move and no second camera: putting a piece full frame is scaling every piece's
 * position and size about that piece's centre, which lands it at the middle of the wall at
 * whatever size is asked for. The same fact the Objects grid rests on — under an orthographic
 * camera, drawing a thing elsewhere on the paper at another size *is* the whole of the
 * transform. It also means the ground stays one plane through the zoom, so the shadows keep
 * landing on it: a piece's foot is `(g - focus.y) · z` whatever piece it is.
 *
 * **The zoom is the same number for every case**, which is not a coincidence but the fit
 * doing its job: [IsoPieces.fit] normalises each piece so its any-angle box is exactly one
 * unit tall on screen, so the factor that takes one unit to [fills] of the wall is the factor
 * that takes any of them.
 *
 * **Between two cases the camera pulls back before it goes in**, and that costs no click. The
 * zoom is interpolated in log space — the only way a scale reads as even — with a dip toward
 * the overview at the half way point, so the move is out, across, and in again. Spending a
 * click on the way out would make the set twice as long to walk and say nothing.
 *
 * **The photograph is seen *through* the piece, and only there.** Each case may name an image,
 * and as the camera closes the piece stops being flat colour and becomes a window onto it —
 * the wall around it stays paper. The picture is sampled in **wall pixels** rather than in the
 * piece's own space, so it is nailed to the wall and the piece is a window onto it rather than
 * a thing wrapped in it; that is the opening wall's trick for cutting its pieces out of
 * concrete, and it is what makes this possible at all, since these meshes carry no texture
 * coordinates to unwrap.
 *
 * What comes through is a **duotone in the piece's own colour** — the picture's luminance
 * drives the value and the tint keeps the hue — so a piece reads as itself with the project
 * inside it rather than as a photograph in the shape of a piece. It blends on the same number
 * the caption fades on, so one value drives both.
 *
 * **One piece at a time, and the picture is sized to that piece.** Only the case the camera has
 * centred is a window; its neighbours stay flat colour, because a picture showing through every
 * piece on the wall is a texture where showing through one is that piece being *about*
 * something. And the picture is laid over the focused piece's own box rather than the wall, so
 * what fills it is the whole photograph rather than the crop of it that happened to fall where
 * the piece stood.
 *
 * A whole-wall version was built first, the photograph laid on the ground between the shadows
 * and the pieces. It worked and was the wrong picture: the wall became a photograph with a
 * shape standing on it, where the point is the shape.
 *
 * **The caption belongs to the zoom, not to the click.** It fades with how far in the camera
 * is, so it goes as the wall pulls back between two cases and returns as the next one lands —
 * one number driving both, rather than a second schedule to keep in step.
 *
 * Everything is a function of `stage.position`, which the deck has already eased: the focus,
 * the zoom and the caption are read straight off it and nothing is eased twice — the trap
 * `CityMapSlide` records, where a second ease put the whole move in the middle of the click.
 */
class CaseStudy(
    override val name: String = "Case study",
    /** The catalogue as meshes; each case's [Case.piece] is a file in it. */
    private val objects: File = File("data/objects"),
    /** The cases, in the order they are taken. */
    private val cases: List<Case> = emptyList(),
    private val fontPath: String = "data/fonts/default.otf",
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    /**
     * The colours the pieces are pasted in, taken in turn. Empty falls back to [ink] for all
     * of them, which is the one-colour wall the yard and the gallery run.
     */
    private val palette: List<ColorRGBa> = emptyList(),
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    private val shadow: ColorRGBa = ColorRGBa.fromHex("C4C4C4"),
    private val shadowDeep: ColorRGBa = ColorRGBa.BLACK,
    /** The sun's height and where it stands — see [IsoPieces]. */
    private val slant: Double = 2.2,
    private val light: Double = 135.0,
    /** The margin round the whole set, as a share of the wall's height. */
    private val margin: Double = 0.09,
    /**
     * The overview's grid. Stated rather than derived, and it has to be: the rule that reads
     * a grid off the count picks the cell nearest square, which on a 3.56:1 wall put all six
     * cases in **one row** of 886-pixel cells — so a piece was already 704 px tall in the
     * overview and "full frame" was 662, a zoom *out*. More rows is what makes the overview
     * small enough for the zoom to be a move. Null falls back to the derived grid.
     */
    private val columns: Int? = null,
    private val rows: Int? = null,
    /** How much of a cell's height a piece's any-angle box is fitted to, in the overview. */
    private val fill: Double = 0.80,
    /** How much of the wall's height a piece fills once it is taken full frame. */
    private val fills: Double = 0.78,
    /**
     * Where a focused piece comes to rest, as a share of the wall's width.
     *
     * **0.75 rather than the middle, and that is about the room rather than the picture.** The
     * wall is two 1920x1080 projectors side by side, so its centre is the *seam* between them:
     * a piece parked there is cut in half by the join and lands half on each machine. Three
     * quarters across is the middle of the right-hand projector, which leaves the left one
     * carrying the caption — the object on one screen and what it is on the other. The
     * overview is unaffected: it is the whole wall and belongs across both.
     */
    private val centres: Double = 0.75,
    /**
     * How far the camera pulls back on the way from one case to the next, as a share of the
     * whole zoom: 0 slides across at full size, 1 comes all the way out to the overview.
     */
    private val pull: Double = 0.72,
    /**
     * How far a piece may be shifted off its cell and sized away from its neighbours: 0 is the
     * neat grid, 1 is a full cell of scatter. What makes it a collage rather than a case.
     */
    private val collage: Double = 0.55,
    /** The seed the scatter is drawn from. Another seed is another arrangement. */
    private val seed: Int = 5,
    /** Seconds a piece takes to turn once about its own vertical axis. */
    private val spin: Double = 90.0,
    override val stepFrames: Int = frames(1.6),
    override val transition: Transition = Cut,
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Scene() {

    override val background: ColorRGBa get() = paper

    /**
     * The overview, then one click a case.
     *
     * Counted off [cases] rather than off what loaded, because **`steps` is read before
     * `load` runs**: `present` prints the running order first, so a count taken from the
     * meshes reported this as a two-click scene when it is thirteen. Everything that indexes
     * by step clamps, so a piece that fails to load costs a click that repeats the one before
     * rather than an exception.
     */
    override val steps get() = 1 + cases.size.coerceAtLeast(1)

    override fun stepName(step: Int) =
        if (step == 0) "the whole set" else cases.getOrNull(step - 1)?.title

    private val iso = IsoPieces(slant, light)
    private var fitted: List<IsoFitted> = emptyList()
    private var shown: List<Case> = emptyList()
    private var across = 1
    private var down = 1

    /** How one piece is pasted up: off its cell across and down, and its size against the rest. */
    private class Scatter(val x: Double, val y: Double, val size: Double)

    private var scatter: List<Scatter> = emptyList()
    private lateinit var face: FontImageMap
    private lateinit var small: FontImageMap

    /** A photograph a case, loaded once. Null where a case names none or the file is missing. */
    private var photos: List<ColorBuffer?> = emptyList()

    override fun load(program: Program) {
        iso.load()
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        small = face

        val found = cases.mapNotNull { case ->
            val mesh = loadObjMesh(File(objects, "${case.piece}.obj"))
            if (mesh == null) println("case study: no piece called ${case.piece} in ${objects.path}")
            mesh?.let { case to it }
        }
        if (found.isEmpty()) {
            println("case study: nothing to show — the wall stands empty")
            return
        }
        shown = found.map { it.first }

        // The grid the overview lays the set out on: the rows that leave the fewest cells
        // empty and, among those, the cell nearest square — the gallery's rule.
        val wallAspect = 16.0 / 9.0 * 2.0
        val n = found.size
        if (columns != null && rows != null) { across = columns; down = rows } else {
            val best = (1..4).map { r -> Triple(r, (n + r - 1) / r, (n + r - 1) / r * r - n) }
                .minWith(compareBy({ it.third }, { kotlin.math.abs((wallAspect / it.second) / (1.0 / it.first) - 1.0) }))
            down = best.first; across = best.second
        }

        val cellAspect = ((wallAspect - 2.0 * margin) / across) / ((1.0 - 2.0 * margin) / down)
        // The width cap allows for the **largest** a piece may be scaled to, not the nominal
        // size: the scatter sizes a piece up to `1 + collage/2`, and capped without that a wide
        // beam scaled up comes out broader than its own cell and crosses into its neighbours
        // wherever it stands, which no amount of walking back can clear.
        fitted = found.map { iso.fit(it.second, 0.86 * cellAspect / (fill * (1.0 + collage / 2.0))) }

        photos = shown.map { case ->
            if (case.image.isEmpty()) null else runCatching { loadImage(File(case.image)) }
                .onFailure { println("case study: no image at ${case.image} — ${case.title} stays on paper") }
                .getOrNull()
        }

        // The scatter, drawn once from the seed: how far off its cell each piece is pasted,
        // how much bigger or smaller than its neighbours, and which colour it takes.
        val random = Random(seed)
        scatter = List(found.size) {
            Scatter(
                (random.nextDouble() - 0.5) * collage,
                (random.nextDouble() - 0.5) * collage,
                1.0 + (random.nextDouble() - 0.5) * collage
            )
        }
        println("case study: ${found.size} cases on a ${across}x${down} overview, %.2fx zoom to %.2f of the wall".format(fills / (fill * (1.0 - 2.0 * margin) / down), fills))
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (fitted.isEmpty()) return

        val w = stage.width
        val h = stage.height
        val inset = h * margin
        val cellW = (w - 2.0 * inset) / across
        val cellH = (h - 2.0 * inset) / down
        val unit = cellH * fill
        val turn = 2.0 * Math.PI * stage.frame / frames(spin)

        // The set as it stands in the overview: nothing here knows about the zoom.
        //
        // **A piece is pasted where it was scattered, unless that crowds one already down**,
        // and then it is walked back toward its own cell until it clears. Scatter alone put
        // pieces through one another — an overlap that reads as a mistake rather than as a
        // collage, because these are solids seen in the round and one passing through another
        // has nowhere it could be standing. The city packs its plans the same way: the test is
        // between boxes rather than contours, which is deliberately strict, since a contour
        // always lies inside its box and boxes held apart guarantee the drawn edges are too.
        val boxes = mutableListOf<DoubleArray>()      // cx, bottom, width, height
        val clear = w * 0.004
        val laid = fitted.mapIndexed { i, f ->
            val c = i % across
            val r = i / across
            val paste = scatter[i]
            val size = unit * paste.size
            val scale = f.scale * size
            // A turning piece's near corner swings toward the viewer and so below the plane
            // it stands on; the plane sits that far up from the box's foot — see GalleryMode,
            // where the bottom row fell off the wall before it was moved.
            val below = f.mesh.spinRadius * sin(iso.pitch) * scale
            val homeX = -w / 2.0 + inset + (c + 0.5) * cellW
            val homeFoot = h / 2.0 - inset - (r + 1) * cellH + cellH * (1.0 - fill) / 2.0 + below
            val width = f.sweep * size

            // Walk back toward the cell in steps; the last step is the cell itself, which by
            // construction is as far apart as this grid can put them.
            var x = homeX
            var foot = homeFoot
            for (step in 0..STEPS) {
                val back = 1.0 - step.toDouble() / STEPS
                x = homeX + paste.x * cellW * back
                foot = homeFoot + paste.y * cellH * back
                val bottom = foot - below
                val clashes = boxes.any { o ->
                    kotlin.math.abs(o[0] - x) < (o[2] + width) / 2.0 + clear &&
                            kotlin.math.abs((o[1] + o[3] / 2.0) - (bottom + size / 2.0)) < (o[3] + size) / 2.0 + clear
                }
                if (!clashes) break
            }
            boxes += doubleArrayOf(x, foot - below, width, size)

            val placed = iso.standing(f, x, foot, size, turn + 2.0 * Math.PI * ((i * 0.6180339887498949) % 1.0))
            IsoPlaced(placed.mesh, placed.centre, placed.scale, placed.angle,
                palette.getOrNull(i % palette.size.coerceAtLeast(1)))
        }

        // Where the camera is: between the two clicks it stands between, with the zoom taken
        // in log space and dipped toward the overview at the half way point.
        // Per case now, not one number for all: the collage sizes the pieces differently, so
        // the factor that takes one to full frame is not the factor that takes the next.
        fun zoomFor(i: Int) = fills * h / (unit * scatter[i].size)
        val at = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val from = at.toInt().coerceIn(0, steps - 1)
        val to = min(from + 1, steps - 1)
        val t = if (to == from) 0.0 else at - from

        fun focusOf(step: Int) = if (step == 0) Vector3.ZERO else laid[(step - 1).coerceIn(laid.indices)].centre
        fun zoomOf(step: Int) = if (step == 0) 1.0 else zoomFor((step - 1).coerceIn(scatter.indices))

        val focus = focusOf(from) * (1.0 - t) + focusOf(to) * t
        // Only between two cases: coming out of the overview is already a pull.
        val dip = if (from >= 1 && to >= 1 && to != from) pull * ln(zoomOf(from)) * sin(Math.PI * t) else 0.0
        val zoom = exp(ln(zoomOf(from)) * (1.0 - t) + ln(zoomOf(to)) * t - dip)

        // A parallel projection, so the zoom is a scale about the focus and nothing else.
        // How far in the camera is, 0 at the overview and 1 full frame: what the photograph
        // blends on and the caption fades on, so one number drives both.
        val depth = smoothstep((ln(zoom) / ln(zoomOf(max(from, 1)))).coerceIn(0.0, 1.0))
        val which = (if (t > 0.5) to else from) - 1
        val case = shown.getOrNull(which)

        // The picture is laid over **the focused piece's own box**, not over the wall: the
        // piece is centred by the zoom, so the box is the middle of the frame at the height
        // the zoom gives it and its own width beside that. Fitted to cover, so the picture
        // crops rather than squashes and the piece is filled to its edges.
        // The whole scene slides off the seam as the camera closes, so a focused piece comes
        // to rest in the middle of one projector rather than across the join. At the overview
        // it is nought and the collage has both screens.
        val park = w * (centres - 0.5) * depth

        val photo = photos.getOrNull(which)?.takeIf { depth > 0.001 }
        if (photo != null) {
            val boxH = unit * scatter[which].size * zoom
            val boxW = fitted[which].sweep * boxH
            val cover = max(boxW / photo.width, boxH / photo.height)
            val span = Vector2(photo.width * cover, photo.height * cover)
            iso.window(photo, Vector2((w - span.x) / 2.0 + park, (h - span.y) / 2.0), span, depth)
        } else {
            iso.window(null, Vector2.ZERO, Vector2(w, h), 0.0)
        }

        // **The set fades out around the case being looked at**, so what is left is one piece,
        // its photograph, its shadow and the caption. Every other piece is taken toward the
        // paper as the camera closes and **stops casting as it goes** — a piece faded to
        // nothing but still throwing a shadow gives the whole thing away.
        //
        // Fading the shadow *colours* instead was tried and is wrong: there is one stencil
        // count for the wall, so it takes the focused piece's shadow with it and the case ends
        // up floating. Dropping a faded piece from the shadow pass keeps the one that matters.
        //
        // The fade runs ahead of the zoom, so the neighbours are gone by the time the piece is
        // large rather than still dissolving under it.
        val fade = min(1.0, depth * 1.8)
        iso.draw(drawer, w, h, laid.mapIndexed { i, it ->
            val held = i == which
            IsoPlaced(it.mesh, (it.centre - focus) * zoom + iso.right * park, it.scale * zoom, it.angle,
                if (held) it.tint else toward(it.tint ?: ink, paper, fade),
                window = held && photo != null,
                casts = held || fade < 0.999)
        }, ink, shadow, shadowDeep)

        if (case == null) return
        if (depth <= 0.01) return

        // The caption stands in the projector the piece has left: ranged left in that screen
        // and set on its middle, so the two read as one composition across the pair rather
        // than as a picture with a label under it.
        drawer.stroke = null
        // In screen coordinates, which run 0..w from the left — not the centred space the
        // pieces are placed in. Mixing the two put this at x = -787, off the wall entirely.
        val left = if (centres > 0.5) w * 0.045 else w * 0.545
        val y = h * 0.50
        line(drawer, "%02d / %02d".format(shown.indexOf(case) + 1, shown.size), face, left, y - h * 0.085, h * 0.024, ColorRGBa.BLACK.opacify(depth * 0.45))
        line(drawer, case.title, face, left, y, h * 0.055, ColorRGBa.BLACK.opacify(depth))
        if (case.note.isNotEmpty()) {
            line(drawer, case.note, small, left, y + h * 0.048, h * 0.026, ColorRGBa.BLACK.opacify(depth * 0.6))
        }
    }

    /**
     * [from] taken [t] of the way to [to]. Fading to the paper rather than to nothing, so a
     * piece dissolves into the wall instead of being blended against whatever is behind it.
     *
     * **The linearity has to be carried across.** `ColorRGBa.fromHex` hands back an sRGB
     * colour and the plain constructor does not, so a mix built without it uploads as linear
     * and every colour comes out lighter — navy `1E3A72` arrived as `6083B2`. Pure red and
     * white survive it, which is what made it look like a fading bug rather than a colour
     * space one: sRGB maps 0 and 1 to themselves.
     */
    private fun toward(from: ColorRGBa, to: ColorRGBa, t: Double) = ColorRGBa(
        from.r + (to.r - from.r) * t,
        from.g + (to.g - from.g) * t,
        from.b + (to.b - from.b) * t,
        from.alpha,
        from.linearity
    )

    private fun line(drawer: Drawer, text: String, font: FontImageMap, x: Double, y: Double, size: Double, colour: ColorRGBa) {
        if (text.isEmpty()) return
        drawer.fill = colour
        drawer.fontMap = font
        drawer.isolated {
            drawer.translate(x, y)
            drawer.scale(size / SIZE)
            drawer.text(text, 0.0, 0.0)
        }
    }

    private companion object {
        const val SIZE = 150.0

        /** Steps a crowded piece walks back toward its own cell before it gives up on the scatter. */
        const val STEPS = 8
    }
}
