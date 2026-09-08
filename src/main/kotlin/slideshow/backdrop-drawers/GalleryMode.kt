// ============================================================================ //
//  No `package` declaration, for the reason YardScene has none: it stands on
//  loadObjMesh and IsoPieces in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Backdrop
import slideshow.Cut
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import slideshow.frames
import java.io.File
import kotlin.random.Random

/**
 * The gallery: the whole catalogue on a dense grid across the white ground, every piece
 * turning slowly on its own axis, the turns rippling across the field.
 *
 * The yard's picture without the yard's drift. Every piece stands where it is and rotates
 * about its own vertical axis, all at the same slow rate — but **each is offset in phase by a
 * sine of where it stands**, so the field is never in step with itself: a wave of "facing"
 * runs across the wall, a piece and its neighbour a little apart, the far end of a row half a
 * turn from the near end. Calm, because nothing moves quickly and nothing starts or stops;
 * alive, because no two pieces show the same face at once.
 *
 * **Everything is a function of the frame.** A piece's angle is the frame times a rate plus
 * its phase, and its phase is a sine of its column and row — nothing scheduled, nothing
 * carried between frames, so the wall can be scrubbed, filmed or jumped into. It began as a
 * grid where one piece at a time turned a quarter and came to rest, in a shuffled order; that
 * was replaced by this, which the same three dials describe with less machinery.
 *
 * **The grid is read off the count, not stated**, and the pieces are shuffled onto its cells
 * from a seed, because the catalogue is alphabetical and thirty-odd `WAND_n` would otherwise
 * stand in a block. Everything below the grid — the camera, the fit, the shear, the two-tone
 * shadow count — is [IsoPieces], shared with the yard.
 */
class GalleryMode(
    override val name: String = "Gallery",
    /** The catalogue as meshes; the [picks] are files in it. */
    private val objects: File = File("data/objects"),
    /**
     * Which pieces tile the grid, by file name without `.obj`. **Empty is every piece in the
     * folder** — the whole catalogue, each once — which is what the wall runs; a short list
     * repeats to fill the grid.
     */
    private val picks: List<String> = emptyList(),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    private val shadow: ColorRGBa = ColorRGBa.fromHex("C4C4C4"),
    private val shadowDeep: ColorRGBa = ColorRGBa.BLACK,
    /**
     * The sun's height and where it stands — see [IsoPieces]. Lower here than in the yard: a
     * collection wants each piece's shadow mostly within its own cell, where the yard's runs
     * under half a dozen neighbours.
     */
    private val slant: Double = 2.2,
    private val light: Double = 135.0,
    /**
     * The grid. Dense on purpose: the wall is a *field* of pieces rather than a row of
     * exhibits, so the cells are small and the pieces fill most of them, and the shadows —
     * five times a piece's height, thrown left — run under several neighbours and the
     * two-tone count has plenty to show.
     */
    private val columns: Int? = null,
    private val rows: Int? = null,
    /**
     * The margin round the whole grid, as a share of the wall's height, the same on all four
     * sides. The gutters between cells are the cells' own; this is the collection's frame.
     */
    private val margin: Double = 0.07,
    /**
     * How much of a cell's height a piece's any-angle box is fitted to; the rest is gutter,
     * split evenly above and below. The box is a bound over every angle, so a piece rarely
     * reaches it and the gutter reads wider than it is set.
     */
    private val fill: Double = 0.84,
    /** Seconds one piece takes to turn once. Slow: a turn should be noticed, not watched. */
    private val spin: Double = 75.0,
    /**
     * How many waves of phase run across the wall's width. The offset between neighbours is
     * a sine of the column, so with 1.5 waves the field shows every face somewhere along a
     * row; each row is shifted a share of a turn from the one above it so the columns do not
     * line up either.
     */
    private val ripple: Double = 1.5,
    /** The seed the pieces are shuffled onto the cells from. */
    private val seed: Int = 11,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    private val iso = IsoPieces(slant, light)
    private var fitted: List<IsoFitted> = emptyList()

    private var across = 1
    private var down = 1

    /** The piece in each cell: the catalogue shuffled onto the grid, so families do not sit in a block. */
    private var inCell: IntArray = IntArray(0)

    override fun load(program: Program) {
        iso.load()
        val meshes = if (picks.isEmpty()) loadObjMeshes(objects) else picks.mapNotNull { pick ->
            loadObjMesh(File(objects, "$pick.obj")).also { if (it == null) println("gallery: no piece called $pick in ${objects.path}") }
        }
        if (meshes.isEmpty()) {
            println("gallery: nothing to show — the wall stands empty")
            return
        }

        // The grid is read off the count when it is not stated: the rows that leave the
        // fewest cells empty, and among those the cell nearest square. 115 pieces on this
        // wall come out 23 by 5, exactly, every piece once.
        val wallAspect = 16.0 / 9.0 * 2.0
        val n = meshes.size
        if (columns != null && rows != null) { across = columns; down = rows } else {
            val best = (2..12).map { r -> Triple(r, (n + r - 1) / r, (n + r - 1) / r * r - n) }
                .minWith(compareBy({ it.third }, { kotlin.math.abs((wallAspect / it.second) / (1.0 / it.first) - 1.0) }))
            down = best.first; across = best.second
        }

        // A cell is the wall inside the margin, over the grid; a piece may sweep most of it.
        val cellAspect = ((wallAspect - 2.0 * margin) / across) / ((1.0 - 2.0 * margin) / down)
        val widest = 0.86 * cellAspect / fill                    // in piece heights: the same gutter across
        fitted = meshes.map { iso.fit(it, widest) }

        val cells = across * down
        inCell = IntArray(cells) { it % fitted.size }.also { it.shuffle(Random(seed)) }
        println("gallery: ${meshes.size} pieces on a ${across}x${down} grid, a turn in ${spin}s, ${ripple} waves across")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (fitted.isEmpty()) return

        val w = stage.width
        val h = stage.height
        val inset = h * margin
        val cellW = (w - 2.0 * inset) / across
        val cellH = (h - 2.0 * inset) / down
        val unit = cellH * fill                          // one piece height, in wall pixels
        val cells = across * down

        // One slow rate for every piece, and a phase that is a sine of where the piece stands:
        // a wave across the columns, each row shifted a share of a turn from the last.
        val turn = 2.0 * Math.PI * stage.frame / frames(spin)

        val placed = mutableListOf<IsoPlaced>()
        for (cell in 0 until cells) {
            val c = cell % across
            val r = cell / across
            val f = fitted[inCell[cell]]

            val wave = Math.sin(2.0 * Math.PI * ripple * (c + 0.5) / across + r * 0.9)
            val angle = turn + Math.PI * wave

            // Each cell is its own patch of ground, and the ground plane is *not* at the foot of
            // the box: a turning piece's near corner swings toward the viewer and so below the
            // plane it stands on, by up to its sweep radius times sin(pitch). Stood on the
            // cell's foot the bottom row fell off the wall. So the plane sits that far up
            // from the box's foot, and the whole any-angle box — `below` under the plane and
            // the rest above — is the middle `fill` of the cell.
            val scale = f.scale * unit
            val below = f.mesh.spinRadius * Math.sin(iso.pitch) * scale
            val x = -w / 2.0 + inset + (c + 0.5) * cellW
            val foot = h / 2.0 - inset - (r + 1) * cellH + cellH * (1.0 - fill) / 2.0 + below
            placed += iso.standing(f, x, foot, unit, angle)
        }
        iso.draw(drawer, w, h, placed, ink, shadow, shadowDeep)
    }
}
