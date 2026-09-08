// ============================================================================ //
//  No `package` declaration, for the reason OpeningScene has none: this wall stands
//  on loadObjMeshes, which lives in the default package and cannot be imported
//  into a named one. The file still belongs in backdrop-drawers/.
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
import kotlin.math.floor

/**
 * The yard: the catalogue's pieces in the round, side by side at one height on a white
 * ground, each turning slowly on the spot under a low sun, the row drifting across the wall.
 *
 * The 115 pieces of `data/objects` are the same catalogue the svg sheets hold flat, but as
 * the real geometry — so this wall shows them as *things*, in one colour with a shadow. They
 * are laid in a single row, end to end a fixed gap apart, standing on one ground plane; the
 * row drifts left, and every piece rotates about its own vertical axis as it goes, each a
 * little out of phase with the next so the row does not turn as one. Everything below the
 * row — the camera, the fit, the shear, the shadow count — is [IsoPieces], shared with the
 * gallery.
 *
 * **The row is one strip, not a screen of separate objects.** Every piece is laid after the
 * one before it at its own sweep plus the gap, so the offsets are cumulative and worked out
 * once at load. The strip wraps, and where a piece is on any frame is one multiplication and
 * a remainder; its angle is another. Nothing is carried between frames, so the wall can be
 * scrubbed, filmed or jumped into. The belt wall's arrangement, in three dimensions.
 *
 * **A piece is drawn a whole wall's width before it reaches the wall.** Its shadow, thrown
 * left, lies on the wall long before the piece does; culled at the edge the shadow *popped*
 * in as the piece arrived. The body is clipped by the window and only the shadow shows.
 */
class YardScene(
    override val name: String = "Yard",
    /** The catalogue as meshes, read by [loadObjMeshes]. */
    private val objects: File = File("data/objects"),
    /** The one colour everything is drawn in. */
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    /** The wall. White, so the shadows have something to fall on. */
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    /** What one shadow is drawn in, sharp-edged. */
    private val shadow: ColorRGBa = ColorRGBa.fromHex("C4C4C4"),
    /** What two or more shadows lying over one another are drawn in. */
    private val shadowDeep: ColorRGBa = ColorRGBa.BLACK,
    /** The sun's height and where it stands — see [IsoPieces]. */
    private val slant: Double = 5.0,
    private val light: Double = 135.0,
    /** The height every piece is fitted to, as a share of the wall's height. */
    private val piece: Double = 0.50,
    /** No piece wider than this share of the wall, whatever its proportion. */
    private val widest: Double = 0.45,
    /** Space between one piece's sweep and the next, as a share of the wall's width. */
    private val gap: Double = 0.035,
    /** Seconds the row takes to move one wall's width. Slow: this is a wall, not a belt. */
    private val crossing: Double = 60.0,
    /** Seconds a piece takes to turn once about its own vertical axis. */
    private val spin: Double = 45.0,
    /**
     * Where the ground plane crosses the screen, as a share of the wall's height from the top.
     * Higher than the belt's line, because a turning piece's near corner swings toward the
     * viewer and so *down* the screen — at 0.76 a deep piece reached y = 1052 of 1080.
     */
    private val ground: Double = 0.68,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    private val iso = IsoPieces(slant, light)
    private var fitted: List<IsoFitted> = emptyList()

    /** Where each piece's slot starts along the strip, in wall widths; and the strip's length. */
    private var offsets: DoubleArray = DoubleArray(0)
    private var strip = 1.0

    override fun load(program: Program) {
        iso.load()
        val meshes = loadObjMeshes(objects)
        if (meshes.isEmpty()) {
            println("yard: no meshes in ${objects.path} — the wall stands empty")
            return
        }
        val aspect = 16.0 / 9.0 * 2.0                    // the wall, so `widest` is in wall widths
        fitted = meshes.map { iso.fit(it, widest * aspect / piece) }

        // The strip: each piece's sweep after the one before, and the gap.
        var at = 0.0
        offsets = DoubleArray(fitted.size) { i ->
            val here = at
            at += fitted[i].sweep * piece / aspect + gap
            here
        }
        strip = at
        println("yard: ${fitted.size} pieces on a strip %.1f walls long, crossing in ${crossing}s, a turn in ${spin}s".format(strip))
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (fitted.isEmpty()) return

        val w = stage.width
        val h = stage.height
        val unit = h * piece                    // one piece height, in wall pixels
        val speed = w / frames(crossing)        // pixels a frame
        val travel = stage.frame * speed
        val floor = -h * (ground - 0.5)         // screen y of the ground line, wall centre at 0
        val turn = 2.0 * Math.PI * stage.frame / frames(spin)

        val placed = mutableListOf<IsoPlaced>()
        val length = strip * w
        val pass = floor(travel / length).toInt()
        for (m in pass - 1..pass + 1) {
            if (m < 0) continue
            for ((i, f) in fitted.withIndex()) {
                val left = w / 2.0 + offsets[i] * w + m * length - travel   // its slot's left edge
                val width = f.sweep * unit
                if (left > w / 2.0 + w * 1.0 || left + width < -w / 2.0 - w * 0.25) continue
                // Each a little out of phase with the next, a golden turn apart, so the row
                // does not rotate as one thing.
                val angle = turn + 2.0 * Math.PI * ((i * 0.6180339887498949) % 1.0)
                placed += iso.standing(f, left + width / 2.0, floor, unit, angle)
            }
        }
        iso.draw(drawer, w, h, placed, ink, shadow, shadowDeep)
    }
}
