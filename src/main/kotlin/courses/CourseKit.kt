import org.openrndr.color.ColorRGBa
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.buildTransform
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

// sketch-default: cube, in boxes
// sketch-variant: draft 2 | CourseKit_draft2Kt | | a copy of the kit to take further, on KIT_DRAFT2_* keys over the kit's own
// sketch-variant: draft 2, photograph | CourseKit_draft2Kt | KIT_DRAFT2_STYLE=photo | draft 2 with a photograph over the pieces: intact on the whole cube from its opening corner and again after the full circle, each piece keeping its part when exploded
// sketch-variant: draft 2, photograph 2 | CourseKit_draft2Kt | KIT_DRAFT2_STYLE=photo KIT_DRAFT2_PHOTO_PICK=2 | draft 2's photograph version on the second photograph, a concrete facade of balconies behind bare branches and yellow flowers
// sketch-variant: draft 2, photograph 3 | CourseKit_draft2Kt | KIT_DRAFT2_STYLE=photo KIT_DRAFT2_PHOTO_PICK=3 | draft 2's photograph version on the third photograph, a mountain ridge of grey rock over green slopes under a heavy sky
// sketch-variant: draft 2, photograph 4 | CourseKit_draft2Kt | KIT_DRAFT2_STYLE=photo KIT_DRAFT2_PHOTO_PICK=4 | draft 2's photograph version on the fourth photograph, strawberry-tree fruit in orange and yellow against green leaves
// sketch-variant: two screens | | KIT_SCREENS=2 | a kit to each projector, each composed for its own screen, taking turns: one comes apart, stands exploded and goes back together while the other stands whole
// sketch-variant: two screens, quiet | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false | the two screens with the camera standing still and no boxes: the pieces float apart on the bare ground and come together again
// sketch-variant: two screens, W and N | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_FORM_LEFT=letter-W KIT_FORM_RIGHT=letter-N KIT_YAW=20 KIT_CLOSE=1.0 KIT_RADIUS=3 | the quiet two screens with a large W on the left and a large N on the right in place of the cubes, for Willy Naessens: each letter comes apart and is put together again, by turns
// sketch-variant: two screens, WN from the pictures | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png | the W and N drawn after data/WN/W.png and N.png, seen square on while whole so each reads as the picture, the camera turning and rising into a rotated exploded view as it comes apart
// sketch-variant: two screens, WN slow and in step | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_STEP=7 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0.015 | the W and N from the pictures coming apart and together at once, slowly, a letter to exploded and back in 25 seconds and round again; exploded close together, the camera fitted to fill each pane
// sketch-variant: two screens, WN close-up | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_STACK_OPEN=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=1.7,1.7,1 KIT_EXPLODE_DEPTH=10 KIT_APART_FILL=1.15 KIT_APART_QUANTILE=0.8 | the slow WN in step, exploded as a drawing of one — every piece carried out from the letter's middle, so no two ever meet — and seen in close-up while apart, the outermost running off the pane
// sketch-variant: two screens, WN close-up, settling | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_STACK_OPEN=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=1.7,1.7,1 KIT_EXPLODE_DEPTH=10 KIT_APART_FILL=1.15 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 | the WN close-up with the letters settling into place while whole — landing a little spread and closing in over the hold — and every piece standing still in its spot while apart
// sketch-variant: two screens, WN landscape | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 | the settling WN with its stacks opened wide when exploded, the copies spread into a landscape of elements the close-up stands among, none ever meeting
// sketch-variant: two screens, WN landscape, going round | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 KIT_SPIN=1 | the WN landscape with the camera never still but on the letters: it sets off round them as they open, goes on round the exploded landscape at one speed, and lands square in front as they close, a whole turn a loop
// sketch-variant: two screens, WN landscape, slow round | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=62.5 KIT_LIFT=10 KIT_GRID_HOLD=6.1 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 KIT_SPIN=1 | the WN landscape going round a quarter as fast, 7 degrees a second: the letters open and close at the same pace and stand apart long enough for the whole turn, a loop of 62.5 seconds
// sketch-variant: two screens, WN landscape, cutting in | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=118.9 KIT_LIFT=10 KIT_GRID_HOLD=14.02 KIT_BUILD_HOLD=0.9 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 KIT_SPIN=1 KIT_SPIN_DELAY=5 KIT_CLOSEUPS_LEFT=22:5,27:4,48:5,70:5,75:4,92:5 KIT_CLOSEUPS_RIGHT=25:5,30:4,50:5,58:4,73:6,94:4 KIT_CLOSEUP_FILL=0.7 | the slow round at half the speed again, 3.6 degrees a second, setting off 5 s after the letters start to open, and each side cutting hard now and then to a close-up of one element of its own, sometimes both at once, turning in place with the same rotation before cutting back to the overview; the W and N stand in front 6.25 s after each landing, a loop of 118.9 s
// sketch-variant: two screens, WN landscape, jump cuts | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=71.5 KIT_LIFT=10 KIT_GRID_HOLD=7.2 KIT_BUILD_HOLD=0.9 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 KIT_SPIN=1 KIT_SPIN_RAMP=0.5 KIT_SPIN_SPEED=3.6 KIT_CLOSEUPS_LEFT=17:4,21:4,31:5,42:4,46:4,54:4 KIT_CLOSEUPS_RIGHT=19:5,28:4,32:4,44:5,52:4 KIT_CLOSEUP_FILL=0.7 | the cutting-in wall turning from the first frame the letters open and at the same 3.6 degrees a second, but coming round in a loop of 71.5 s rather than 118.9: the angle jumps ahead a little at every hard cut into or out of a close-up, where it cannot be seen
// sketch-variant: two screens, WN landscape, swinging back | | KIT_SCREENS=2 KIT_ORBIT=false KIT_BOX_LINES=false KIT_CUBE=20 KIT_FRONT=true KIT_CLOSE=1.8 KIT_RADIUS=3 KIT_YAW=30 KIT_FORM_LEFT=image:data/WN/W.png KIT_FORM_RIGHT=image:data/WN/N.png KIT_SYNC=true KIT_PERIOD=25 KIT_LIFT=10 KIT_GRID_HOLD=0.7 KIT_BUILD_HOLD=0.7 KIT_STAGGER=0 KIT_BLOCKS=1 KIT_EXPLODE_SCALE=2.6,2.6,1 KIT_EXPLODE_DEPTH=14 KIT_STACK=1.0 KIT_STACK_OPEN=6 KIT_STACK_REACH=4 KIT_APART_FILL=1.5 KIT_APART_QUANTILE=0.8 KIT_SETTLE=0.04 KIT_DRIFT=0 KIT_SWING=90 | the WN landscape with the camera turning all the while the letters stand apart, to a quarter round, and then going straight back the way it came to land in front as they close
// sketch-variant: grey | | KIT_STYLE=grey | the same kit in greys on a light ground, before it took the climb's colours
// sketch-variant: on a flat grid, in colour | | KIT_STYLE=grey KIT_GRID=lattice KIT_STEP=8 KIT_SCALE=17 KIT_TOP=@red KIT_SIDE=0A0A0F KIT_SIDE_Z=1E3A72 KIT_INK=0A0A0F KIT_SHADOWS=true KIT_LIFT=5 | the pieces lying on a flat isometric grid round the cube, red, navy and black with their shadows
// sketch-variant: building | | KIT_STYLE=grey KIT_FORM=building KIT_GRID=lattice KIT_STEP=5.5 KIT_RADIUS=4 KIT_SCALE=17 KIT_TOP=@red KIT_SIDE=0A0A0F KIT_SIDE_Z=0A0A0F KIT_INK=0A0A0F KIT_SHADOWS=true KIT_EDGED=0 KIT_GRID_AT=23,-23 KIT_LIFT=3 | the precast building of bays and storeys instead of the cube
/**
 * v6 · **the kit: a cube, then a grid**. The catalogue's precast pieces stand fitted together as a cube in
 * the middle box of a hexagon of wire boxes, are taken apart into the boxes round it — each standing in a
 * box of its own as it stood in the cube — and are fitted together again; for ever. **It comes apart as an
 * exploded view**: every piece in one quick straight move to the box that lies outward from it, a soft
 * few hundredths of a second after the one before, the outermost first; and while it stands exploded the
 * camera goes a quarter of the way round it, softly, so each round is seen from the next corner.
 * The boxes stand in layers, as wide as the frame and running off its top and foot, a piece floating at the
 * middle of its box, and the boxes are only there while it stands apart. The camera stands close on the
 * cube while it is whole, pulls out to the field as it comes apart, and eases back in as it goes together. **It wears the climb's look** (`KIT_STYLE=climb`): black ground, every piece one flat
 * red or blue, dealt so blocks touching in the cube differ, and a white line on every real edge of it;
 * `grey` is the kit as it was, greys on a light ground. `KIT_GRID=lattice` lays the pieces flat on an
 * isometric grid instead, a piece at a time, red on top and navy and black at the sides with a flat navy
 * shadow. `KIT_FORM=building` stands the precast building instead.
 *
 *     ./gradlew run -Popenrndr.application=CourseKitKt
 *
 * The schedule and the kit are `AssembleScene`'s catalogue on a `lattice`; this file only draws. The
 * shadow is the piece flattened onto the ground along the sun, one matrix, since there is nothing but the
 * ground for it to fall on — and only a piece at rest throws one, since in the air its shadow lies far off
 * on the ground and reads as loose navy litter. `KIT_*` in `.env` steer it.
 */
fun main() = runCourse("course-v6-kit", preview = 4.0, canvasWidth = if (Env["KIT_SCREENS"] == "2") 3840 else 1920) { kitCourse() }

/**
 * A look for the kit, `KIT_STYLE`: the ground, the colours dealt to the pieces one each (null keeps the three
 * face tones), the tones, the pieces' edge line — its colour, and its reach either side of the edge in
 * pixels — and the boxes' lines. Every value is also a `KIT_*` key of its own, which wins over the style.
 */
private class KitStyle(val paper: String, val colours: String?, val top: String, val side: String, val sideZ: String,
                       val edge: String, val edgeWidth: Double, val ink: String)

private val kitStyles = mapOf(
    // CourseClimb's: flat red and blue, a white line on every real edge, on black.
    "climb" to KitStyle("000000", "${slideshow.Brand.redHex},${slideshow.Brand.blueHex}", slideshow.Brand.redHex, slideshow.Brand.redHex, slideshow.Brand.redHex, "FFFFFF", 0.5, "4A4D55"),
    "grey" to KitStyle("F4F4F4", null, "D5D6D8", "7A7D83", "A6A8AD", "F4F4F4", 0.75, "9EA0A6")
)

/** How far, in the kit's units, a piece wanders across and up while the kit stands apart: under half the least gap. */
private const val WANDER_ACROSS = 0.12

/** How much of a pane's half the pieces standing apart may reach, when the camera is fitted to them. */
private const val APART_MARGIN = 0.92

/** How much of the frame's half a piece may reach while the camera keeps it in shot. */
private const val FRAME_MARGIN = 0.97

/** How long, as a share of the move, the zoom may run on after the last piece lands, and settle. */
private const val ZOOM_SETTLE = 0.25

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.kitCourse(): (Drawer, Double) -> Unit {
    if (Env["KIT_SCREENS"] == "2") return kitTwoScreens()
    val view = KitView(this)
    return { drawer: Drawer, time: Double ->
        val t = time + view.opening
        view.draw(drawer, t, 45.0 + if (view.orbit) 90.0 * view.kit.turnsAt(t) else 0.0)
    }
}

/**
 * One multisampled target for every two-screen kit, the way the course walls share theirs: at 8x a
 * 1920 by 1080 target with its depth is 133 MB, the show plays two of these walls, and a kit only
 * holds it while it draws one side and resolves it into that side's pane — so however many kits
 * there are, and even two handing over in one frame, one target serves them all.
 */
private var kitShared: org.openrndr.draw.RenderTarget? = null

private fun kitTarget(w: Int, h: Int): org.openrndr.draw.RenderTarget =
    kitShared?.takeIf { it.width == w && it.height == h }
        ?: org.openrndr.draw.renderTarget(w, h, multisample = org.openrndr.draw.BufferMultisample.SampleCount(8)) {
            colorBuffer(); depthBuffer()
        }.also { kitShared = it }

/**
 * **The kit for two projectors** (`KIT_SCREENS=2`, the variant "two screens"): a kit to each, each composed for
 * its own 1920 by 1080, so the cube stands in the middle of a projector rather than on the seam between them —
 * drawn across the wall as one, it stood exactly there. The right one is another cube (`KIT_SEED_RIGHT`, dealt
 * from its own seed) in the same look.
 *
 * **They take turns, one at a time.** The left comes apart, stands exploded while its camera goes a quarter
 * round, and goes back together, while the right stands whole; then, a beat with both whole later
 * (`KIT_TURN_GAP`), the right takes its turn while the left stands whole — so whenever one is exploded the
 * other is assembled, and only one ever moves. Each turn is the kit's own schedule played through — out, apart,
 * together and the zoom's settle — and the wait is the kit held whole, so a side is the course's own kit with
 * its whole hold stretched to cover the other's turn. The wall opens on both standing whole.
 *
 * `KIT_FORM_LEFT` and `KIT_FORM_RIGHT` give each side a form of its own — `letter-W` and `letter-N` stand
 * Willy Naessens' initials in the cubes' places, `image:data/WN/W.png` a letter read off a picture — and
 * `KIT_YAW` the corner both cameras stand at, since a letter reads from nearer its face than the kit's 45.
 * `KIT_FRONT=true` stands the camera square in front of the form while it is whole, so a letter reads as drawn.
 */
private fun org.openrndr.Program.kitTwoScreens(): (Drawer, Double) -> Unit {
    val pw = CourseCanvas.width / 2
    val ph = CourseCanvas.height
    val left = KitView(this, { if (it == "FORM") Env["KIT_FORM_LEFT"] ?: Env["KIT_FORM"] else Env["KIT_$it"] }, pw, ph)
    val right = KitView(this, {
        when (it) {
            "SEED" -> Env["KIT_SEED_RIGHT"] ?: "11"
            "FORM" -> Env["KIT_FORM_RIGHT"] ?: Env["KIT_FORM"]
            else -> Env["KIT_$it"]
        }
    }, pw, ph)
    val gap = Env["KIT_TURN_GAP"]?.toDoubleOrNull() ?: 1.0
    // The corner the cameras stand at: 45 is the kit's own, square across a corner; a letter reads from nearer
    // its face.
    val yaw = Env["KIT_YAW"]?.toDoubleOrNull() ?: 45.0
    // Whole, the camera stands square in front of the form and level with it, so a letter reads as drawn; it
    // turns to the corner, rises to the isometric elevation and pulls out as the kit comes apart — all three on
    // how far apart the pieces are, so they move as one. The kit's own zoom is fitted to its corner views and
    // would snap out at once on a wide letter that fits from the front; and the boxes are not drawn.
    val front = Env["KIT_FRONT"] == "true"
    fun turnOf(v: KitView) = v.kit.phases[3] + v.holdApart + v.lift + v.settle
    // One turn each and a gap after each, the right starting where the left's turn and gap end.
    val turn = maxOf(turnOf(left), turnOf(right))
    val period = 2.0 * (turn + gap)
    /** Kit time for [v] at [time] on the wall, its turns starting [from] seconds in and one a [period]. */
    fun kitTime(v: KitView, time: Double, from: Double): Double {
        val u = time - from
        val round = kotlin.math.floor(u / period)
        val s = u - round * period
        val base = (round + 1.0) * v.cycle
        val out = v.kit.phases[3]
        return when {
            s < out -> base - out + s
            s < out + v.holdApart + v.lift + v.settle -> base + (s - out)
            else -> base + v.holdApart + v.lift + v.settle
        }
    }
    val msaa = kitTarget(pw, ph)
    // In step (`KIT_SYNC=true`): both go at once, round a loop of `KIT_PERIOD` seconds — whole, out, apart, back
    // — on the left kit's proportions, each side playing its own schedule through each part. The loop starts and
    // ends with both whole, so going round again is not seen.
    val sync = Env["KIT_SYNC"] == "true"
    val template = left.kit.phases.let { listOf(it[2], it[3], it[0], it[1]) }
    val loop = Env["KIT_PERIOD"]?.toDoubleOrNull() ?: template.sum()
    /** Kit time for [v] at [time] on the wall, and which part of the loop it is in — whole, out, apart, back — and how far. */
    fun stepOf(v: KitView, time: Double): Triple<Double, Int, Double> {
        var s = (time / loop - kotlin.math.floor(time / loop)) * template.sum()
        var part = 0
        while (part < 3 && s >= template[part]) { s -= template[part]; part++ }
        val p = (s / template[part]).coerceIn(0.0, 1.0)
        val (hA, l, hW, out) = v.kit.phases
        return Triple(when (part) {
            0 -> v.cycle + hA + l + p * hW
            1 -> 2.0 * v.cycle - out + p * out
            2 -> 2.0 * v.cycle + p * hA
            else -> 2.0 * v.cycle + hA + p * l
        }, part, p)
    }
    fun inStep(v: KitView, time: Double) = stepOf(v, time).first

    // Going round (`KIT_SPIN`, whole turns a loop, in step and in front only): the camera stands still only on the
    // letter whole, square in front of it. As the letter opens it sets off round it, gathering speed over the move;
    // it goes on round at one speed for as long as the pieces stand apart, and slows over the move back to land
    // square in front again as the letter closes — so the exploded view is never a still. A whole turn is the only
    // way to keep going one way and still come back to the front. Its speed follows: the turn over the two moves,
    // taken at half speed on average, and the hold between at full.
    //
    // Swinging back (`KIT_SWING`, degrees, in place of KIT_SPIN) is the other way to keep the exploded view moving:
    // the camera sets off the same way and goes on turning at one speed while the pieces stand apart, reaching
    // KIT_SWING degrees round as they begin to close, and then goes straight back the way it came to land in front
    // as they close. It carries on a moment past the swing as it turns about, so it never stops dead up there.
    //
    // `KIT_SPIN_DELAY` holds the going round back that many seconds after the letters start to open, so they come
    // apart seen from the front and the turn sets off out of that: the build-up. `KIT_SPIN_RAMP` is how many seconds
    // it takes to get up to speed, as long as the move out unless set — 0.5 has it visibly turning from the first
    // frame the letters open.
    //
    // `KIT_SPIN_SPEED` states the speed, in degrees a second, instead of deriving it from the loop — and then the turn
    // is shortened by the close-ups rather than sped up: whatever of the whole turn the camera does not cover at that
    // speed in the time it has, it jumps across at the hard cuts, the same share at every cut into or out of a
    // close-up on that side. A jump cannot be seen across a hard cut, so on screen the turn goes at one speed
    // throughout and still comes round to the front in a loop half as long.
    val spin = if (sync && front) Env["KIT_SPIN"]?.toDoubleOrNull() ?: 0.0 else 0.0
    val swing = if (sync && front) Env["KIT_SWING"]?.toDoubleOrNull() ?: 0.0 else 0.0
    val turning = spin > 0.0 || swing > 0.0
    val (tOut, tApart, tBack) = template.drop(1).map { it / template.sum() * loop }
    val tWhole = template[0] / template.sum() * loop
    val spinDelay = (Env["KIT_SPIN_DELAY"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, tApart)
    val spinRamp = (Env["KIT_SPIN_RAMP"]?.toDoubleOrNull() ?: tOut).coerceIn(0.01, tOut + tApart - spinDelay)
    val spinEnd = tOut + tApart + tBack
    // Degrees a second, times this, is how far the turn goes by its own motion from setting off to landing.
    val spinSpan = spinRamp / 2.0 + (spinEnd - spinDelay - spinRamp - tBack) + tBack / 2.0
    val swingSpeed = swing / (tOut / 2.0 + tApart)
    // The close-ups' times, "start:length" in seconds of the loop, read here since the turn jumps at them; a shot that
    // does not fall wholly while the pieces stand apart is left out, since a close-up of a piece on the move would lose it.
    val closeTimes = listOf(left, right).associateWith { v ->
        val side = if (v === left) "left" else "right"
        (if (sync && front) Env["KIT_CLOSEUPS_${side.uppercase()}"] ?: Env["KIT_CLOSEUPS"] else null).orEmpty().split(",")
            .mapNotNull { it.split(":").mapNotNull { n -> n.trim().toDoubleOrNull() }.takeIf { n -> n.size == 2 } }
            .filter { (start, length) ->
                val inside = listOf(start, start + length / 2.0, start + length).all { stepOf(v, it).second == 2 }
                if (!inside) println("kit: the close-up at ${start}s on the $side falls outside the exploded view, left out")
                inside
            }.map { (start, length) -> start to start + length }
    }
    /** Every hard cut on [v]'s side, in seconds since the letters started to open. */
    val cuts = closeTimes.mapValues { (_, times) -> times.flatMap { listOf(it.first, it.second) }.map { it - tWhole }.distinct().sorted() }
    val asked = Env["KIT_SPIN_SPEED"]?.toDoubleOrNull()
    /** The turn's own speed on [v]'s side, and the degrees it jumps at each of its cuts. */
    val spinSpeeds = listOf(left, right).associateWith { v ->
        val left360 = 360.0 * spin - (asked ?: 0.0) * spinSpan
        when {
            asked == null -> 360.0 * spin / spinSpan to 0.0
            left360 < 0.0 || (left360 > 1e-6 && cuts.getValue(v).isEmpty()) -> {
                println("kit: ${"%.1f".format(asked)} degrees a second does not come round in this loop with these cuts, going at the loop's own speed")
                360.0 * spin / spinSpan to 0.0
            }
            else -> asked to if (cuts.getValue(v).isEmpty()) 0.0 else left360 / cuts.getValue(v).size
        }
    }
    // How far a ramp of length 1 has gone at [x] when its speed rises from 0 to 1 on a smoothstep.
    fun ramp(x: Double) = x * x * x - x * x * x * x / 2.0
    /** Degrees round from the front on [v]'s side, in [part] of the loop — whole, out, apart, back — [p] of the way through it. */
    fun spinAt(v: KitView, part: Int, p: Double): Double {
        if (part == 0) return 0.0
        // Back from the swing to the front on a cubic that leaves at the speed it arrived with and lands at rest.
        if (swing > 0.0) return when (part) {
            1 -> swingSpeed * tOut * ramp(p)
            2 -> swingSpeed * (tOut / 2.0 + tApart * p)
            else -> swing * (2 * p * p * p - 3 * p * p + 1) + swingSpeed * tBack * (p * p * p - 2 * p * p + p)
        }
        val (speed, jump) = spinSpeeds.getValue(v)
        // Seconds since the letters started to open.
        val s = when (part) { 1 -> p * tOut; 2 -> tOut + p * tApart; else -> tOut + tApart + p * tBack }
        val own = speed * spinSpan
        val jumped = jump * cuts.getValue(v).count { it <= s }
        return jumped + when {
            s <= spinDelay -> 0.0
            s < spinDelay + spinRamp -> speed * spinRamp * ramp((s - spinDelay) / spinRamp)
            s < spinEnd - tBack -> speed * (spinRamp / 2.0 + s - spinDelay - spinRamp)
            else -> own - speed * tBack * ramp((spinEnd - s) / tBack)
        }
    }

    // Never quite still (`KIT_SETTLE`, `KIT_DRIFT`, `KIT_WANDER`). Whole, the pieces settle into place: they land
    // KIT_SETTLE spread out about the form's middle and close in over the hold until the letter is exact as it
    // begins to come apart; apart, the field goes on opening by KIT_DRIFT over the hold, and each piece wanders on
    // a slow path of its own — KIT_WANDER toward and away from the camera, WANDER_ACROSS across and up — by as
    // much as the kit stands apart. Going back, the spread passes from the one to the other with the move. **Neither can bring two pieces together.** The settling is the explode again, the same
    // scaling about one point by at least 1. The wander toward the camera cannot, since no block is cut through
    // the form's depth and any two stand apart across or up already; and across and up each piece keeps within
    // half of the least gap the explode has opened by then. Every path goes round a whole number of times a loop.
    val settle = Env["KIT_SETTLE"]?.toDoubleOrNull() ?: 0.0
    val drift = Env["KIT_DRIFT"]?.toDoubleOrNull() ?: settle
    val wander = Env["KIT_WANDER"]?.toDoubleOrNull() ?: 0.0
    val formMiddle = listOf(left, right).associateWith { v -> v.middleAt(v.cycle + v.holdApart + v.lift) }
    // Easing out of the landing in both holds, so the pieces carry on from the move rather than stopping first.
    fun spreadAt(part: Int, p: Double) = 1.0 + when (part) {
        0 -> settle * (1.0 - p) * (1.0 - p)
        1 -> 0.0
        2 -> drift * (1.0 - (1.0 - p) * (1.0 - p))
        else -> drift + (settle - drift) * p
    }
    fun lifeOf(v: KitView, t: Double, part: Int, p: Double, time: Double, apart: Double): Map<Int, Vector3> {
        if (settle <= 0.0 && drift <= 0.0 && wander <= 0.0) return emptyMap()
        val k = spreadAt(part, p)
        val u = time / loop
        val middle = formMiddle.getValue(v)
        return v.boxesAt(t).mapValues { (seed, box) ->
            val r = java.util.Random(seed * 7919L + if (v === left) 1 else 2)
            fun path(amplitude: Double) = amplitude * sin(2.0 * Math.PI * ((1 + r.nextInt(2)) * u + r.nextDouble()))
            val centre = (box.first + box.second) * 0.5
            val across = if (wander > 0.0) WANDER_ACROSS else 0.0
            (centre - middle) * (k - 1.0) + Vector3(path(across), path(across), path(wander)) * apart
        }
    }
    // Standing apart, the camera is fitted to where the pieces are, seen from the exploded corner: as near as
    // keeps every piece inside the pane's margins, so the field fills the pane without a piece off its edge.
    // The kit's own field is framed to run its boxes off every edge, and with a fine lattice more pieces go to
    // boxes it cannot show whole than to ones it can.
    //
    // The moves between are fitted the same way, once: every frame of the coming apart and the going back is
    // looked at from the camera it has then and how far out it must be to keep every piece inside is read off.
    // The zoom leads the pieces out and trails them home, 1 − (1 − apart)³ of the way to the apart framing, and
    // swells out by the least B · sin(π · apart) that keeps every one of those frames whole: half way round,
    // rising, the camera sees the pieces nearest it swing down the frame further than they stand when apart.
    // `KIT_APART_FILL` over 1 stands the camera that much nearer, a close-up: the outermost pieces run off the
    // pane rather than all standing whole in it.
    val fill = Env["KIT_APART_FILL"]?.toDoubleOrNull() ?: 1.0
    // And it is fitted to the pieces' reach at this quantile — below 1, the few furthest out may run off the
    // pane rather than draw everything else small. It looks, apart, at the middle of the pieces rather than of
    // the form, moving there on the zoom's curve, so an exploded letter whose pieces lean one way is centred.
    val quantile = Env["KIT_APART_QUANTILE"]?.toDoubleOrNull() ?: 1.0
    fun led(a: Double) = 1.0 - (1.0 - a).let { it * it * it }
    val apartAim = listOf(left, right).associateWith { v -> v.middleAt(v.cycle + v.holdApart / 2.0) }
    fun aimOf(v: KitView, out: Double) = v.lookPoint + (apartAim.getValue(v) - v.lookPoint) * led(out)
    fun needed(v: KitView, t: Double, out: Double, turn: Double = yaw * out): Double {
        val boxes = v.blocksAt(t, turn, pw, ph, pitch = v.isoPitch * out, pull = 1.0, aim = aimOf(v, out)).values
        val reach = boxes.map { maxOf(
            maxOf(kotlin.math.abs(it.x - pw / 2.0), kotlin.math.abs(it.x + it.width - pw / 2.0)) / (pw / 2.0),
            maxOf(kotlin.math.abs(it.y - ph / 2.0), kotlin.math.abs(it.y + it.height - ph / 2.0)) / (ph / 2.0)) }.sorted()
        val at = reach[((reach.size - 1) * quantile.coerceIn(0.0, 1.0)).toInt()]
        return v.pullScaled(fill * APART_MARGIN / at, pw, ph)
    }
    // Going round, the field is seen from every side, so it is fitted from every 5 degrees and the camera stands at
    // the mean of those, one distance all the way round: fitted from each side as it came, it went in and out by as
    // much as twice as the pieces lined up behind one another and spread apart again.
    // Swinging, it stands at the mean over the angles it passes while apart.
    val apartFits = listOf(left, right).associateWith { v ->
        if (!front || !turning) emptyList() else (0 until 72).map { needed(v, v.cycle + v.holdApart / 2.0, 1.0, it * 5.0) }
    }
    fun fitFrom(fits: List<Double>, turn: Double): Double {
        val x = (turn / 5.0).mod(72.0)
        val i = x.toInt()
        return fits[i % 72] + (fits[(i + 1) % 72] - fits[i % 72]) * (x - i)
    }
    val apartPull = listOf(left, right).associateWith { v ->
        when {
            !front -> 1.0
            swing > 0.0 -> (0..20).map { fitFrom(apartFits.getValue(v), spinAt(v, 2, it / 20.0)) }.average()
            spin > 0.0 -> apartFits.getValue(v).average()
            else -> needed(v, v.cycle + v.holdApart / 2.0, 1.0)
        }
    }
    /** The pull the field wants standing apart, seen from [turn] degrees round: the one corner's, or the fit from there. */
    fun apartFitAt(v: KitView, turn: Double): Double =
        apartFits.getValue(v).let { if (it.isEmpty()) apartPull.getValue(v) else fitFrom(it, turn) }
    // The swell is measured against the field as it will stand from the angle the camera has reached, not against the
    // one distance: going round, the camera is a quarter of the way round before the pieces land, and from there the
    // field may want more room than the mean — which, divided by the small sin(π · apart) at the end of a move, threw
    // the camera right out and left the letter a speck.
    val swell = listOf(left, right).associateWith { v ->
        if (!front) 0.0 else {
            val (hA, l, _, out) = v.kit.phases
            // Every frame of the two moves, and how far round the camera is on it.
            val times = (0..90).map { (2.0 * v.cycle - out + out * it / 90.0) to spinAt(v, 1, it / 90.0) } +
                (0..90).map { (2.0 * v.cycle + hA + l * it / 90.0) to spinAt(v, 3, it / 90.0) }
            times.map { (t, round) -> v.kit.apartAt(t).let { a ->
                val turn = if (turning) round else yaw * a
                Triple(a, needed(v, t, a, turn), apartFitAt(v, turn))
            } }
                .filter { (a, _, _) -> a in 0.02..0.98 }
                .maxOfOrNull { (a, n, apart) -> (n - apart * led(a)) / sin(Math.PI * a) }?.coerceAtLeast(0.0) ?: 0.0
        }
    }
    if (turning) listOf(left, right).forEach { v ->
        val (speed, jump) = if (swing > 0.0) swingSpeed to 0.0 else spinSpeeds.getValue(v)
        println("kit: ${if (swing > 0.0) "swinging" else "going round"} %s — %.1f degrees a second apart, %.1f jumped at each of %d cuts, the camera at %.2f, swelling %.2f"
            .format(if (v === left) "left" else "right", speed, jump, cuts.getValue(v).size, apartPull.getValue(v), swell.getValue(v)))
    }
    // Close-ups (`KIT_CLOSEUPS_LEFT` and `_RIGHT`, or `KIT_CLOSEUPS` for both): at each "start:length", in seconds of
    // the loop, that side cuts hard to one element of the exploded landscape and, at the end, hard back to the
    // overview. The camera keeps the overview's angle and goes on turning with it, only standing close and looking at
    // the element — so the element turns in place in the middle of its side, its neighbours swinging past. It stands
    // `KIT_CLOSEUP_FILL` of the side's height across at any angle. Each close-up is dealt from the least hidden
    // elements — every element is looked at from the close-up at the shot's start, middle and end, and scored by how
    // much of it the elements standing nearer the camera cover — and takes the catalogue piece shown least so far on
    // either side, never the one its side has just shown, nor the one the other side is showing at the same time — the
    // two sides' close-ups may overlap, and do. The letters are cut from only a handful of pieces (WAND_11,
    // _12, _27, _33), so a loop repeats some; dealt by block alone, the first loop showed WAND_33 four times. A shot must fall while the
    // pieces stand apart, since a close-up of a piece on the move would lose it; one that does not is left out.
    class Shot(val start: Double, val end: Double, val centre: Vector3, val pull: Double, var piece: String = "")
    val closeFill = Env["KIT_CLOSEUP_FILL"]?.toDoubleOrNull() ?: 0.7
    fun turnAt(v: KitView, time: Double) = stepOf(v, time).let { (t, part, p) -> if (turning) spinAt(v, part, p) else yaw * v.kit.apartAt(t) }
    val shown = HashMap<String, Int>()
    val dealt = ArrayList<Shot>()
    val shots = listOf(left, right).associateWith { v ->
        val side = if (v === left) "LEFT" else "RIGHT"
        val used = HashSet<Int>()
        var last: String? = null
        closeTimes.getValue(v)
            .mapIndexed { k, (start, end) ->
                val length = end - start
                val t = inStep(v, start + length / 2.0)
                val boxes = v.boxesAt(t)
                fun rectArea(r: Rectangle) = r.width * r.height
                val scored = boxes.keys.filter { it !in used }.map { seed ->
                    val (lo, hi) = boxes.getValue(seed)
                    val centre = (lo + hi) * 0.5
                    val pull = v.pullForScale(closeFill * ph / 2.0 / ((hi - lo).length / 2.0), pw, ph)
                    val hidden = listOf(start, start + length / 2.0, end).maxOf { at ->
                        val turn = turnAt(v, at)
                        val rects = v.blocksAt(t, turn, pw, ph, pitch = v.isoPitch, pull = pull, aim = centre)
                        val near = v.nearnessAt(t, turn)
                        val own = rects.getValue(seed)
                        rects.filter { (o, _) -> o != seed && near.getValue(o) > near.getValue(seed) }.values.sumOf { r ->
                            val w = minOf(own.x + own.width, r.x + r.width) - maxOf(own.x, r.x)
                            val h = minOf(own.y + own.height, r.y + r.height) - maxOf(own.y, r.y)
                            if (w > 0.0 && h > 0.0) w * h else 0.0
                        } / rectArea(own).coerceAtLeast(1e-9)
                    }
                    Triple(seed, Shot(start, end, centre, pull), hidden)
                }.sortedBy { it.third }
                val names = v.kit.catalogue(t).first.associate { it.seed to it.mesh.name }
                val clear = scored.filter { it.third <= scored.first().third + 0.1 }
                val alongside = dealt.filter { it.start < end && it.end > start }.map { it.piece }.toSet()
                val other = clear.filter { names[it.first] != last && names[it.first] !in alongside }
                    .ifEmpty { clear.filter { names[it.first] != last } }.ifEmpty { clear }
                val least = other.minOf { shown[names.getValue(it.first)] ?: 0 }
                val fresh = other.filter { (shown[names.getValue(it.first)] ?: 0) == least }
                val pick = fresh[java.util.Random(k * 7919L + if (v === left) 3 else 5).nextInt(fresh.size)]
                used += pick.first
                last = names.getValue(pick.first)
                shown[last!!] = (shown[last] ?: 0) + 1
                println("kit: close-up on the %s at %.1fs for %.1fs, %s, %.0f%% hidden"
                    .format(side.lowercase(), start, length, names.getValue(pick.first), 100.0 * pick.third))
                pick.second.also { it.piece = last!!; dealt += it }
            }
    }
    // `KIT_CHECK=true` proves it rather than argues it: 500 moments of the loop, every block's box in the world with
    // the settling and the wander on it, each pair tested for overlap, printed at load.
    if (Env["KIT_CHECK"] == "true" && sync) listOf(left, right).forEach { v ->
        var moments = 0; var worst = 0; var deepest = 0.0
        for (i in 0 until 500) {
            val time = loop * i / 500.0
            val (t, part, p) = stepOf(v, time)
            val shifts = lifeOf(v, t, part, p, time, v.kit.apartAt(t))
            val boxes = v.boxesAt(t).map { (seed, b) -> (shifts[seed] ?: Vector3.ZERO).let { d -> (b.first + d) to (b.second + d) } }
            var n = 0
            for (x in boxes.indices) for (y in x + 1 until boxes.size) {
                val (a0, a1) = boxes[x]; val (b0, b1) = boxes[y]
                val over = minOf(minOf(a1.x, b1.x) - maxOf(a0.x, b0.x), minOf(a1.y, b1.y) - maxOf(a0.y, b0.y), minOf(a1.z, b1.z) - maxOf(a0.z, b0.z))
                if (over > 1e-4) { n++; deepest = maxOf(deepest, over) }
            }
            if (n > 0) moments++
            worst = maxOf(worst, n)
        }
        println("kit check: ${if (v === left) "left" else "right"} — $moments of 500 moments with blocks overlapping, most $worst pairs, deepest %.3f".format(deepest))
    }
    val panes = List(2) { org.openrndr.draw.colorBuffer(pw, ph) }
    return { drawer: Drawer, time: Double ->
        listOf(left to gap, right to gap + turn + gap).forEachIndexed { i, (v, from) ->
            val step = if (sync) stepOf(v, time) else null
            val t = step?.first ?: kitTime(v, time, from)
            drawer.isolatedWithTarget(msaa) {
                ortho(msaa)
                if (front) {
                    // The zoom leads the pieces out and trails them home, since the first to go outrun an even pull.
                    val out = v.kit.apartAt(t)
                    val turn = if (turning && step != null) spinAt(v, step.second, step.third) else yaw * out
                    val shot = shots.getValue(v).firstOrNull { (time - kotlin.math.floor(time / loop) * loop).let { u -> u >= it.start && u < it.end } }
                    val pull = shot?.pull ?: (apartPull.getValue(v) * led(out) + swell.getValue(v) * sin(Math.PI * out))
                    val shifts = if (step != null) lifeOf(v, t, step.second, step.third, time, out) else emptyMap()
                    v.drawPieces(this, v.kit.catalogue(t).first, emptyList(), turn, pull, 0.0,
                        if (shifts.isEmpty()) null else KitPick(shifts = shifts), pitch = v.isoPitch * out, aim = shot?.centre ?: aimOf(v, out))
                } else v.draw(this, t, yaw + if (v.orbit) 90.0 * v.kit.turnsAt(t) else 0.0)
            }
            msaa.colorBuffer(0).copyTo(panes[i])
        }
        drawer.isolated {
            drawer.shadeStyle = null
            val w = drawer.width / 2.0
            panes.forEachIndexed { i, pane -> drawer.image(pane, i * w, 0.0, w, drawer.height.toDouble()) }
        }
    }
}

/**
 * The kit as a thing to draw at any moment of its cycle, from any corner: what [kitCourse] runs on its own
 * clock, and what the webtool slide (`WebtoolKit`) runs off its clicks — one implementation, two callers.
 * [key] reads a setting by the kit's own name for it (`STYLE`, `COLOURS` …), the course's `KIT_` keys unless a
 * caller hands in its own; [frameWidth] and [frameHeight] are the frame it composes for, which decides which
 * boxes keep a piece in shot and how the zoom is fitted.
 */
class KitView(
    program: org.openrndr.Program,
    private val key: (String) -> String? = { Env["KIT_$it"] },
    frameWidth: Int = CourseCanvas.width,
    frameHeight: Int = CourseCanvas.height
) {
    private fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    private fun colour(k: String, default: String) = ColorRGBa.fromHex(key(k) ?: default)
    private val style = kitStyles[key("STYLE") ?: "climb"] ?: kitStyles.getValue("climb")
    /** The ground the kit is drawn on. */
    val paper = colour("PAPER", style.paper)
    private val top = colour("TOP", style.top)
    private val side = colour("SIDE", style.side)
    private val sideZ = colour("SIDE_Z", style.sideZ)
    private val edge = colour("EDGE", style.edge)
    private val shadow = colour("SHADOW", slideshow.Brand.blueHex)
    private val ink = colour("INK", style.ink)
    private val colours = (key("COLOURS") ?: style.colours)?.takeIf { it != "none" }
        ?.split(",")?.map { ColorRGBa.fromHex(it.trim()) }.orEmpty()

    private val gridAt = (key("GRID_AT") ?: "0,0").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        .takeIf { it.size == 2 }?.let { Vector2(it[0], it[1]) } ?: Vector2.ZERO
    private fun names(k: String, default: String) = (Env[k] ?: default).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private val iso = atan(1.0 / sqrt(2.0))
    // The grid: wire boxes a piece stands in, each the cube's size, or a flat isometric lattice.
    private val boxes = (key("GRID") ?: "boxes") == "boxes"
    private val cubeSide = number("CUBE", 10.0)
    private val step = number("STEP", if (boxes) cubeSide else 8.0)
    // Pixels a cell: the boxes as wide as the frame, running off its top and foot.
    private val radius = number("RADIUS", if (boxes) 5.0 else 3.0)
    private val layers = if (boxes) number("LAYERS", 1.0) else 0.0
    // Seen from a corner the boxes are a diamond, (2r + 1) steps across on the diagonal, flattened by the
    // elevation and stood up by the layers; the frame is covered when its corners fall inside it.
    private fun scaleFor(width: Int, height: Int): Double {
        if (!boxes) return number("SCALE", 17.0)
        val across = (2.0 * radius + 1.0) * step / sqrt(2.0)
        val down = across * sin(iso) + (layers + 0.5) * step * cos(iso)
        return number("SCALE", (width / 2.0 / across + height / 2.0 / down) * number("FILL", 1.0))
    }
    // A box a piece may go to only if the piece stays whole in the frame from every angle the camera turns
    // through: a middle d from the axis and y from the cube's middle stands at most d across and
    // d · sin(elevation) + |y| · cos(elevation) up or down, and the piece reaches half its longest side
    // beyond.
    private val frameW = frameWidth.toDouble()
    private val frameH = frameHeight.toDouble()
    private val margin = number("MAX_SIDE", 6.0) * 0.75 + 1.0
    private fun fitsWhole(m: Vector3): Boolean {
        val k = scaleFor(frameW.toInt(), frameH.toInt())
        val d = sqrt(m.x * m.x + m.z * m.z)
        return d + margin <= frameW / 2.0 / k && d * sin(iso) + kotlin.math.abs(m.y) * cos(iso) + margin <= frameH / 2.0 / k
    }
    /** Seconds between one piece setting off and the next, seconds a piece takes to go, and the curve it goes on. */
    val stagger = number("STAGGER", 0.04)
    val move = number("MOVE", if (boxes) 1.1 else 0.8)
    val curve = (key("EASE") ?: if (boxes) "0.65,0,0.25,1" else "0.4,0,0.2,1").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        .takeIf { it.size == 4 }?.let { slideshow.CubicBezier(it[0], it[1], it[2], it[3]) } ?: slideshow.snap
    /** The kit and its schedule. */
    val kit = AssembleScene(
        name = "Kit", sheet = File("none"), details = File("none"),
        grammar = "catalogue", render = "solid", layout = if (boxes) "boxes" else "lattice",
        form = key("FORM") ?: "cube",
        cubeSize = cubeSide.toInt(), explode = boxes && (key("EXPLODE") ?: "true") != "false",
        stagger = stagger, blocks = number("BLOCKS", 24.0).toInt(), maxSide = number("MAX_SIDE", 6.0).toInt(),
        stack = number("STACK", 1.5), stackGap = number("STACK_GAP", 0.2), stackMax = number("STACK_MAX", 16.0).toInt(),
        stackOpen = number("STACK_OPEN", 0.8),
        objects = File(Env["SLIDES_YARD_OBJECTS"] ?: "data/objects"),
        walls = names("SLIDES_ASSEMBLE_WALLS", "WAND_27,WAND_33,WAND_17,WAND_23,WAND_6,WAND_8,WAND_11,WAND_28,WAND_10,WAND_22,WAND_12,WAND"),
        floors = names("SLIDES_ASSEMBLE_FLOORS", "VLOER,VLOER_3,PREDAL,VLOER_2"),
        front = names("SLIDES_ASSEMBLE_FRONT", "WAND_27,WAND_17,WAND_33,WAND_23"),
        catalogueAt = gridAt,
        latticeStep = step, latticeRadius = radius.toInt(),
        // In boxes only the middle box is the cube's; on the flat lattice the nodes by the cube are kept clear.
        latticeClear = number("CLEAR", if (boxes) step / 2.0 else cubeSide / 2.0 + number("MAX_SIDE", 6.0) / 2.0 + 1.0),
        latticeLayers = layers.toInt(),
        latticeSpread = number("SPREAD", 3.5),
        explodeScale = key("EXPLODE_SCALE")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let {
            when (it.size) { 1 -> Vector3(it[0], it[0], it[0]); 3 -> Vector3(it[0], it[1], it[2]); else -> null }
        },
        explodeDepth = number("EXPLODE_DEPTH", 0.0),
        stackReach = number("STACK_REACH", 1.0),
        latticeFits = { m -> fitsWhole(m) },
        move = move, gapStart = number("GAP_START", 0.45), gapEnd = number("GAP_END", 0.25),
        curve = curve,
        catalogueHold = number("GRID_HOLD", 3.0), buildHold = number("BUILD_HOLD", 3.0),
        guides = false,
        seed = key("SEED")?.toIntOrNull() ?: Env["SLIDES_ASSEMBLE_SEED"]?.toIntOrNull() ?: 5
    ).also { it.load(program) }

    // The climb's colours, one a piece and flat on every face, dealt so blocks touching in the cube differ
    // where they can: each in turn, the most touching first, takes the colour it shares least face with
    // among the pieces already dealt. Read off the cube standing whole, so a piece keeps its colour
    // wherever it goes.
    private val colourOf: Map<Int, ColorRGBa> = if (colours.isEmpty()) emptyMap() else {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        // A block by its seed: a stack's copies all carry it, so the block is the box round all of them.
        val built = kit.catalogue(kit.builtAt).first.groupBy { it.seed }.values.toList()
        val bounds = built.map { stackOfPieces ->
            val corners = stackOfPieces.flatMap { p ->
                val pts = p.mesh.points
                val lo = Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
                val hi = Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
                (0..7).map { i ->
                    (p.model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz
                }
            }
            Vector3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }) to
                Vector3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })
        }
        // Two blocks touch where they overlap on two axes and stand a joint apart on the third; the area is
        // the overlap.
        fun touching(a: Pair<Vector3, Vector3>, b: Pair<Vector3, Vector3>): Double {
            val gaps = (0..2).map { k -> maxOf(c(a.first, k), c(b.first, k)) - minOf(c(a.second, k), c(b.second, k)) }
            val apart = gaps.indices.filter { gaps[it] > -1e-3 }
            return if (apart.size == 1 && gaps[apart[0]] < 0.5) gaps.filterIndexed { k, _ -> k != apart[0] }.fold(1.0) { m, g -> m * -g } else 0.0
        }
        val n = built.size
        val touch = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else touching(bounds[i], bounds[j]) } }
        val dealt = IntArray(n) { -1 }
        for (i in (0 until n).sortedByDescending { touch[it].sum() }) {
            dealt[i] = colours.indices.minBy { k -> (0 until n).sumOf { j -> if (dealt[j] == k) touch[i][j] + 1e-6 else 0.0 } }
        }
        built.indices.associate { built[it].first().seed to colours[dealt[it]] }
    }

    // The true isometric, from a corner [yaw] degrees round; and the sun, down and to the right, low.
    private fun eyeAt(yaw: Double, pitch: Double = iso) = Math.toRadians(yaw).let { y -> Vector3(sin(y) * cos(pitch), sin(pitch), cos(y) * cos(pitch)) }
    private fun upAt(yaw: Double, pitch: Double = iso) = Math.toRadians(yaw).let { y -> Vector3(-sin(y) * sin(pitch), cos(pitch), -cos(y) * sin(pitch)) }
    /** The camera's elevation over the ground, the isometric one, in degrees. */
    val isoPitch get() = Math.toDegrees(iso)
    private val eye = eyeAt(45.0)
    // Seen exploded, the camera goes a quarter round the kit, softly — each round from the next corner.
    val orbit = boxes && (key("ORBIT") ?: "true") != "false"
    private val sunAngle = Math.toRadians(number("SUN_ANGLE", 15.0))
    private val sunElevation = Math.toRadians(number("SUN_ELEVATION", 30.0))
    private val toSun = Vector3(-cos(sunAngle) * cos(sunElevation), sin(sunElevation), -sin(sunAngle) * cos(sunElevation))
    // A piece laid flat on the ground along the sun: x and z carried by its height, y the ground's.
    private val g = kit.ground + 0.01
    private val sx = toSun.x / toSun.y
    private val sz = toSun.z / toSun.y
    private val flatten = Matrix44.fromColumnVectors(
        Vector4(1.0, 0.0, 0.0, 0.0), Vector4(-sx, 0.0, -sz, 0.0),
        Vector4(0.0, 0.0, 1.0, 0.0), Vector4(sx * g, g, sz * g, 1.0)
    )

    // Flat: one colour for the whole piece, or the top and a side by which way it faces; and the piece's own
    // creased edges in a line, so the blocks read on every face of the cube and not only on its top — the
    // climb's line, reaching EDGE_WIDTH pixels either side of an edge and fading over EDGE_SOFT.
    private val paint = shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;"
        fragmentTransform = """
            vec3 n = normalize(v_worldNormal);
            if (dot(n, p_eye) < 0.0) n = -n;
            vec4 c = p_flat == 1 ? p_colour : (n.y > 0.5 ? p_top : (abs(n.x) >= abs(n.z) ? p_side : p_sideZ));
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float line = 1.0 - smoothstep(p_width - p_soft, p_width + p_soft, d);
            // A caller picking pieces out takes the rest toward the ground; 0 leaves the colour as it was.
            x_fill = mix(mix(c, p_edge, p_edged * line), p_paper, p_dim);
        """
        parameter("eye", eye); parameter("top", top); parameter("side", side); parameter("sideZ", sideZ)
        parameter("flat", if (colours.isEmpty()) 0 else 1); parameter("colour", top)
        parameter("edge", edge); parameter("width", number("EDGE_WIDTH", style.edgeWidth))
        parameter("soft", number("EDGE_SOFT", 0.6)); parameter("edged", number("EDGED", 1.0))
        parameter("paper", paper); parameter("dim", 0.0)
    }
    private val flat = shadeStyle { fragmentTransform = "x_fill = p_shadow;"; parameter("shadow", shadow) }
    private val wire = WireCubes(emptyList(), top, 1.0, ink, paper, 1.0)
    private var lines: VertexBuffer? = null

    /** Where the camera looks: the cube's middle. A caller placing pieces of its own by the frame starts here. */
    val look = Vector3(gridAt.x, kit.ground + number("LIFT", if (boxes) step / 2.0 else 5.0), gridAt.y)

    /** The colour the kit dealt the block [seed], or null where it deals none. */
    fun blockColour(seed: Int): ColorRGBa? = colourOf[seed]
    private val shadows = (key("SHADOWS") ?: "false") == "true"
    private val boxesAlways = (key("BOXES_ALWAYS") ?: "false") == "true"
    // The boxes' lines at all: `BOX_LINES=false` leaves the pieces floating apart on the bare ground.
    private val boxLines = (key("BOX_LINES") ?: "true") != "false"
    // Whole, the camera stands close, the cube filling this share of the frame; it pulls out to the field as
    // the kit comes apart and comes back in as it goes together. 0 holds it out.
    private val close = if (boxes) number("CLOSE", 0.8) else 0.0
    /** Kit time the course opens on: a building standing whole. */
    val opening = kit.builtAt

    // The zoom. Whole, the cube fills [close] of the frame — from a corner it is sqrt 2 sides across and
    // cos(elevation) + sqrt 2 · sin(elevation) sides high — and exploded the field is framed as [scaleFor]
    // frames it; between the two in log space, so the pull reads as even, on a smootherstep.
    private fun nearFor(width: Int, height: Int) =
        close * minOf(width / (cubeSide * sqrt(2.0)), height / (cubeSide * (cos(iso) + sqrt(2.0) * sin(iso))))
    /** Seconds of kit time standing apart, and that all the pieces take to go from the one state to the other. */
    val holdApart = kit.phases[0]
    val lift = kit.phases[1]
    private val holdWhole = kit.phases[2]
    /** Seconds of kit time a whole cycle takes: apart, coming together, whole, coming apart. */
    val cycle = kit.phases.sum()
    private fun smoother(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
    // The outermost pieces fly out faster than an even pull and come home after it, so the zoom's timing is
    // fitted to them, once: every frame of both moves is looked at from all four corners the camera comes
    // round to, how far through the pull the camera must be to keep every piece whole in the frame is read
    // off, and each move takes the longest, gentlest ease that is always at least that far through. Coming
    // together, the zoom may run on a moment past the last piece landing, and settle.
    /** How long the zoom runs on after the last piece lands, coming together. */
    val settle = minOf(ZOOM_SETTLE * lift, holdWhole)
    private val zoom: Pair<Double, Double> = if (close <= 0.0) lift to lift else {
        val near = ln(nearFor(frameW.toInt(), frameH.toInt())); val far = ln(scaleFor(frameW.toInt(), frameH.toInt()))
        val bounds = HashMap<ObjMesh, Pair<Vector3, Vector3>>()
        fun needed(t: Double) = (0 until 4).maxOf { q ->
            val eye = eyeAt(45.0 + 90.0 * q); val up = upAt(45.0 + 90.0 * q); val right = up.cross(eye)
            var fits = Double.MAX_VALUE
            for (p in kit.catalogue(t).first) {
                val (lo, hi) = bounds.getOrPut(p.mesh) {
                    val pts = p.mesh.points
                    Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
                }
                for (i in 0..7) {
                    val v = (p.model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz - look
                    fits = minOf(fits, FRAME_MARGIN * frameW / 2.0 / kotlin.math.abs(v.dot(right)).coerceAtLeast(1e-9),
                                 FRAME_MARGIN * frameH / 2.0 / kotlin.math.abs(v.dot(up)).coerceAtLeast(1e-9))
                }
            }
            ((near - ln(fits)) / (near - far)).coerceIn(0.0, 1.0)
        }
        val frames = (lift * 60.0).toInt()
        val coming = (0..frames).map { k -> (cycle - lift + k / 60.0).let { it to needed(it) } }
        val going = (0..frames).map { k -> (holdApart + k / 60.0).let { it to needed(it) } }
        val durations = generateSequence(lift) { it - 0.01 }.takeWhile { it > 0.2 }
        val out = durations.firstOrNull { d -> coming.all { (t, n) -> smoother((t - (cycle - lift)) / d) >= n - 1e-6 } } ?: 0.2
        val end = holdApart + lift + settle
        val back = generateSequence(lift + settle) { it - 0.01 }.takeWhile { it > 0.2 }
            .firstOrNull { d -> going.all { (t, n) -> 1.0 - smoother((t - (end - d)) / d) >= n - 1e-6 } } ?: 0.2
        println("kit: the camera pulls out over %.2fs and comes back in over %.2fs".format(out, back))
        out to back
    }
    private val zoomOut get() = zoom.first
    private val zoomIn get() = zoom.second
    /** How far through the pull the camera is at kit time [t]: 0 standing close on the cube, 1 out on the field. */
    fun pulled(t: Double): Double {
        val u = t - kotlin.math.floor(t / cycle) * cycle
        return when {
            u < holdApart -> 1.0
            u < holdApart + lift + settle -> 1.0 - smoother((u - (holdApart + lift + settle - zoomIn)) / zoomIn)
            u < cycle - lift -> 0.0
            else -> smoother((u - (cycle - lift)) / zoomOut)
        }
    }

    /**
     * The pull whose view is [e] of the way from the field's to the close-up's, even in world units across the
     * frame rather than in log space: pieces gathering in straight lines on the same [e] then never leave it,
     * since each one's reach across the frame is the same blend of its reach apart and whole.
     */
    fun pullLinear(e: Double, width: Int, height: Int): Double {
        if (close <= 0.0) return 1.0
        val near = nearFor(width, height); val far = scaleFor(width, height)
        val s = 1.0 / ((1.0 - e) / far + e / near)
        return ((ln(s) - ln(near)) / (ln(far) - ln(near))).coerceIn(0.0, 1.0)
    }

    /** The pull at which a unit of the kit is [scale] pixels across a frame [width] by [height]. */
    fun pullForScale(scale: Double, width: Int, height: Int): Double {
        if (close <= 0.0) return 1.0
        val near = ln(nearFor(width, height)); val far = ln(scaleFor(width, height))
        return (ln(scale) - near) / (far - near)
    }

    /** The pull that stands the camera [factor] times as close as it stands out on the field: over 1 nearer. */
    fun pullScaled(factor: Double, width: Int, height: Int): Double {
        if (close <= 0.0) return 1.0
        val near = ln(nearFor(width, height)); val far = ln(scaleFor(width, height))
        return 1.0 + ln(factor) / (far - near)
    }

    /** Pixels a unit at kit time [t] in a frame [width] by [height]: close on the cube whole, out on the field apart. */
    private fun scaleAt(t: Double, width: Int, height: Int): Double = scaleForPull(pulled(t), width, height)

    /** Pixels a unit with the camera [pull] of the way out, 0 close on the cube and 1 out on the field, in log space. */
    private fun scaleForPull(pull: Double, width: Int, height: Int): Double {
        val far = scaleFor(width, height)
        return if (close <= 0.0) far else exp(ln(nearFor(width, height)) +
            (ln(far) - ln(nearFor(width, height))) * pull)
    }

    /** A mesh's own box, once. */
    private val meshBounds = HashMap<ObjMesh, Pair<Vector3, Vector3>>()
    private fun boundsOf(mesh: ObjMesh) = meshBounds.getOrPut(mesh) {
        val pts = mesh.points
        Vector3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vector3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
    }
    private fun corners(p: AssembleScene.RowPiece, model: Matrix44): List<Vector3> {
        val (lo, hi) = boundsOf(p.mesh)
        return (0..7).map { i -> (model * Vector4(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z, 1.0)).xyz }
    }

    /**
     * Every piece's model with [pick]'s lifts on it: a lifted block stands toward the camera and larger about its
     * own middle — the middle of the box round all of its copies — so it is drawn in front of the field and
     * reads as picked out of it, where it stands on screen.
     */
    private fun liftedModels(pieces: List<AssembleScene.RowPiece>, pick: KitPick?, eye: Vector3): List<Matrix44> {
        if (pick == null || (pick.lifted.isEmpty() && pick.shifts.isEmpty() && pick.scales.isEmpty())) return pieces.map { it.model }
        val middles = pieces.filter { (pick.lifted[it.seed] ?: 0.0) > 0.0 || it.seed in pick.scales }.groupBy { it.seed }.mapValues { (_, ps) ->
            val cs = ps.flatMap { corners(it, it.model) }
            Vector3((cs.minOf { it.x } + cs.maxOf { it.x }) / 2.0, (cs.minOf { it.y } + cs.maxOf { it.y }) / 2.0, (cs.minOf { it.z } + cs.maxOf { it.z }) / 2.0)
        }
        return pieces.map { p ->
            val l = pick.lifted[p.seed] ?: 0.0
            val size = pick.scales[p.seed] ?: 1.0
            val m = middles[p.seed]
            val lifted = if ((l <= 0.0 && size == 1.0) || m == null) p.model else buildTransform {
                translate(m + eye * (LIFT_FORWARD * l))
                scale((1.0 + LIFT_GROW * l) * size)
                translate(-m)
            } * p.model
            pick.shifts[p.seed]?.let { buildTransform { translate(it) } * lifted } ?: lifted
        }
    }

    /**
     * The shift in the kit's world that moves a thing [screen] pixels across the frame — y down — at kit time [t]
     * from [yaw], in a frame [width] by [height]: along the camera's own right and up, so it moves on the frame
     * and not toward or away from it.
     */
    fun offsetFor(screen: Vector2, t: Double, yaw: Double, width: Int, height: Int): Vector3 {
        val up = upAt(yaw); val right = up.cross(eyeAt(yaw))
        return (right * screen.x - up * screen.y) / scaleAt(t, width, height)
    }

    /** Every block's box on the frame, in its pixels, y down, by the block's seed. */
    private fun screenBoxes(pieces: List<AssembleScene.RowPiece>, models: List<Matrix44>, eye: Vector3, up: Vector3,
                            scale: Double, width: Int, height: Int, at: Vector3 = look): Map<Int, Rectangle> {
        val right = up.cross(eye)
        return pieces.indices.groupBy { pieces[it].seed }.mapValues { (_, idx) ->
            val pts = idx.flatMap { i -> corners(pieces[i], models[i]) }.map { v ->
                val q = v - at
                Vector2(width / 2.0 + q.dot(right) * scale, height / 2.0 - q.dot(up) * scale)
            }
            val x0 = pts.minOf { it.x }; val y0 = pts.minOf { it.y }
            Rectangle(x0, y0, pts.maxOf { it.x } - x0, pts.maxOf { it.y } - y0)
        }
    }

    /** Where every block stands on a frame [width] by [height] at kit time [t] from [yaw], nothing lifted. */
    fun blocksAt(t: Double, yaw: Double, width: Int, height: Int, pitch: Double? = null, pull: Double? = null,
                 aim: Vector3? = null): Map<Int, Rectangle> {
        val pieces = kit.catalogue(t).first
        val elevation = pitch?.let { Math.toRadians(it) } ?: iso
        return screenBoxes(pieces, pieces.map { it.model }, eyeAt(yaw, elevation), upAt(yaw, elevation),
            pull?.let { scaleForPull(it, width, height) } ?: scaleAt(t, width, height), width, height, aim ?: look)
    }

    /** The point the camera looks at, and the middle of the blocks at kit time [t] — the mean of their middles. */
    val lookPoint get() = look
    fun middleAt(t: Double): Vector3 {
        val middles = boxesAt(t).values.map { (lo, hi) -> (lo + hi) * 0.5 }
        return middles.fold(Vector3.ZERO) { a, b -> a + b } / middles.size.toDouble()
    }

    /** Every block's box in the kit's world at kit time [t], its lowest corner and its highest, by its seed. */
    fun boxesAt(t: Double): Map<Int, Pair<Vector3, Vector3>> =
        kit.catalogue(t).first.groupBy { it.seed }.mapValues { (_, ps) ->
            val cs = ps.flatMap { corners(it, it.model) }
            Vector3(cs.minOf { it.x }, cs.minOf { it.y }, cs.minOf { it.z }) to Vector3(cs.maxOf { it.x }, cs.maxOf { it.y }, cs.maxOf { it.z })
        }

    /** How near the camera every block stands at kit time [t] from [yaw], by its seed: the larger, the nearer. */
    fun nearnessAt(t: Double, yaw: Double): Map<Int, Double> {
        val eye = eyeAt(yaw)
        return kit.catalogue(t).first.groupBy { it.seed }.mapValues { (_, ps) ->
            ps.flatMap { corners(it, it.model) }.let { cs -> cs.sumOf { it.dot(eye) } / cs.size }
        }
    }

    /**
     * The kit at kit time [t], seen from the corner [yaw] degrees round, into the whole of [drawer]'s target.
     * With a [pick], some blocks are picked out of it, and the frame's box of every block is handed back.
     */
    fun draw(drawer: Drawer, t: Double, yaw: Double, pick: KitPick? = null, pitch: Double? = null): Map<Int, Rectangle> {
        val (pieces, drawn) = kit.catalogue(t)
        return drawPieces(drawer, pieces, if (boxLines) drawn else emptyList(), yaw, pulled(t),
            if (boxes && !boxesAlways) kit.apartAt(t) else 1.0, pick, pitch = pitch)
    }

    /**
     * [pieces] and the boxes' [drawn] lines, seen from [yaw] with the camera [pull] of the way out, the boxes
     * [shown] that strong: what [draw] does at a moment of the kit's own clock, for a caller that places the
     * pieces itself. With a [pick], the frame's box of every block is handed back. [clear] false draws them over
     * whatever the caller has already put down, which must then have cleared the depth itself. [pitch] stands the
     * camera at another elevation, in degrees; null is the isometric one.
     */
    fun drawPieces(drawer: Drawer, pieces: List<AssembleScene.RowPiece>, drawn: List<Pair<Pair<Vector3, Vector3>, Double>>,
                   yaw: Double, pull: Double, shown: Double, pick: KitPick? = null, clear: Boolean = true,
                   pitch: Double? = null, aim: Vector3? = null): Map<Int, Rectangle> {
        val at = aim ?: look
        val elevation = pitch?.let { Math.toRadians(it) } ?: iso
        val eye = eyeAt(yaw, elevation); val up = upAt(yaw, elevation)
        paint.parameter("eye", eye)
        val segments = drawn.filter { it.second > 0.02 }.map { it.first }
        if (segments.isNotEmpty()) lines = wire.segments(segments, lines).first
        val scale = scaleForPull(pull, drawer.width, drawer.height)
        val w = drawer.width / scale; val h = drawer.height / scale
        val models = liftedModels(pieces, pick, eye)
        if (clear) drawer.clear(paper)
        drawer.isolated {
            drawer.projection = orthoMatrix(-w / 2.0, w / 2.0, -h / 2.0, h / 2.0, -1000.0, 1000.0)
            drawer.view = lookAtMatrix(at + eye * 300.0, at, up)
            drawer.drawStyle.cullTestPass = CullTestPass.ALWAYS
            // The shadows and the grid lie on the ground and hide nothing.
            drawer.depthWrite = false
            drawer.depthTestPass = DepthTestPass.ALWAYS
            // Only a piece at rest throws one: in the air it would lie far off on the ground, loose.
            drawer.shadeStyle = flat
            if (shadows) pieces.filter { it.active < 0.5 }.forEach { drawer.model = flatten * it.model; drawer.vertexBuffer(it.mesh.vertexBuffer, DrawPrimitive.TRIANGLES) }
            drawer.model = Matrix44.IDENTITY
            drawer.shadeStyle = null
            // The boxes stand only while the kit stands apart: in as it explodes, out as it comes together.
            fun grid() = lines?.let {
                if (segments.isNotEmpty() && shown > 0.0) wire.edges(drawer, it, segments.size * 6, eye, scale, colour = ink.opacify(shown), width = number("LINE", 1.2))
            }
            // A flat grid lies under the pieces; a box's edges stand in depth with them, the near ones
            // crossing in front of the piece in the box and the far ones behind it.
            if (!boxes) grid()
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.shadeStyle = paint
            pieces.forEachIndexed { i, it ->
                paint.parameter("colour", pick?.colours?.get(it.seed) ?: colourOf[it.seed] ?: top)
                paint.parameter("dim", if (pick != null && it.seed !in pick.lit) pick.dim else 0.0)
                drawer.model = models[i]
                drawer.vertexBuffer(it.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
            }
            if (boxes) {
                drawer.model = Matrix44.IDENTITY
                drawer.shadeStyle = null
                drawer.depthWrite = false
                grid()
            }
        }
        paint.parameter("dim", 0.0)
        return if (pick == null) emptyMap() else screenBoxes(pieces, models, eye, up, scale, drawer.width, drawer.height, at)
    }
}

/**
 * How a caller picks blocks out of the kit, by their seeds: every block not [lit] goes [dim] of the way toward
 * the ground, a block in [lifted] stands out of the field by its amount, 0 to 1, a block in [colours] is drawn
 * in its own, a block in [shifts] stands that far from where the kit puts it, and a block in [scales] is drawn
 * that size about its own middle.
 */
class KitPick(val dim: Double = 0.0, val lit: Set<Int> = emptySet(), val lifted: Map<Int, Double> = emptyMap(),
              /** A colour of its own for a block, in place of the one the kit dealt it. */
              val colours: Map<Int, ColorRGBa> = emptyMap(),
              /** A shift of its own for a block, in the kit's world, laid over everything else. */
              val shifts: Map<Int, Vector3> = emptyMap(),
              /** A size of its own for a block, about its own middle: 0 is gone, 1 as the kit has it. */
              val scales: Map<Int, Double> = emptyMap())

/** How far toward the camera a picked block stands, in cells, and how much larger it is drawn. */
private const val LIFT_FORWARD = 30.0
private const val LIFT_GROW = 0.18
