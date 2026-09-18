package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorType
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import slideshow.Backdrop
import slideshow.Breathe
import slideshow.Stage
import slideshow.frames
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A tryout: a facade of narrow, deep cells under a light swinging across the top of it, drawn
 * in two tones — lit is white and everything else is black — so the picture is nothing but the
 * shapes the shadows cut. Given a [mask], the same facade sets type.
 *
 * **The facade is a height field and the whole wall is one shader on one rectangle.** Seen
 * straight on, every point of a relief is only a depth, so a pixel can ask for itself whether
 * the light reaches it: step from the surface toward the light and see whether the relief ever
 * rises above the ray. No geometry, no shadow map, and the cell is a function that is changed
 * by editing it.
 *
 * - **A cell** is a raised frame — a ledge top and bottom, a mullion either side — around a
 *   recess, and the recess holds one of five things: nothing, a ramp across it, a blade down the
 *   middle of it, a step raised in its lower half, or a triangle across its top. Each throws a
 *   different shadow: the ledge a band across the top, the mullion and the blade a bar down the
 *   side, the step a second band half way down, and the triangle the diagonal wedge — the one
 *   shape a frame of straight members cannot throw on its own.
 * - **The choice is made per run, not per cell.** A row is dealt into runs of [group] cells, and
 *   every cell in a run is the same, mirrored or not together — so the facade reads in beats of
 *   repeated shapes, the way the reference does, rather than as noise. Both are hashed from where
 *   the run is, so the same frame always draws the same wall.
 * - **Two tones, no grey.** A flat face is white when the light reaches it and black when it does
 *   not. A sloped face — a ramp, a wedge, a turned panel — is white when it catches most of the
 *   light the flat wall does and black when it turns clearly away: judged by the lambert term on
 *   its own, slopes this shallow stay lit from any side and never go black. An earlier version shaded in greys, with an
 *   ambient darkened by depth and a concrete grain; both read as noise and went.
 * - **The light comes from above and swings side to side** by [sweep] degrees, dropping to
 *   [low] at either end of the swing and rising to [high] as it passes overhead. A light going
 *   right round has a quarter where it is below the cells and the wall goes black. Everything is
 *   a function of [Stage.loop], so it loops exactly.
 *
 * **Type is set by turning the panels, not by painting them.** With a [mask], every cell holds
 * one panel tilted to one side and a little up, and a cell whose area is mostly ink in the mask
 * has its panel turned the other way. Nothing else differs between a letter and the ground: with
 * the light to one side the panels turned away go black and the words stand out; as it swings
 * through overhead both sets face it and the words sink into the wall; past it, they come back
 * inverted. The small upward tilt is what makes the words disappear in white rather than black
 * as the light crosses.
 *
 * **[inkDepth] sets type by depth instead, and then it is the shadows that carry the words.** The
 * facade stays the facade — the same bars and wedges, cell for cell — but a cell over a letter is
 * cut [inkDepth] deep rather than [depth], its frame still flush with the wall around it. A deep
 * recess fills with shadow and a shallow one barely does, so the letters are the dark part of the
 * wall and the ground keeps its texture on white. The type is strongest with the light low at
 * either end of its swing, where even the frames of a letter throw shadows the width of their
 * cells, and eases as it passes overhead; it never inverts, because nothing about a letter turns.
 * The recess is sunk into the wall rather than its frame raised out of it, so a letter throws no
 * shadow onto the ground beside it and one word cannot run into the next.
 *
 * It works the other way round too, and that is the better picture: letters **shallower** than
 * the ground. Then the letters stay lit and the ground fills with shadow as the sun drops, so the
 * words are revealed by the shadow gathering around them rather than drawn in it.
 *
 * **[orbit] sends the sun round the wall instead of swinging it across the top.** Turned panels
 * read as a crossfade under a moving light — every panel of a letter changes tone at the same
 * instant, so the picture dissolves from one state to the other and nothing appears to move. Set
 * by depth, with every face flat, the only thing that changes is cast shadow: it turns inside every
 * cell as the sun goes round, and lengthens as it sinks from [high] to [low] half way through the
 * turn and climbs again. That is what reads as a sun, and the type comes and goes with its height.
 *
 * **[jitter] and [depthJitter] keep the cells from moving as one.** A grid of identical recesses
 * fills with shadow in lockstep, which reads as a switch. So each cell's opening is shifted within
 * its frame by up to [jitter] of the frame's width, and cut up to [depthJitter] deeper or shallower,
 * both hashed from where the cell is: the shadows sit a little differently in every slot, and the
 * reveal runs through the wall at slightly different moments rather than landing all at once.
 *
 * The mask is decided **per cell**, off how much of the cell is ink, so a letter is built out of
 * whole cells the way the louvre reference is, and a letter's edge never cuts through a panel.
 * The mask is fitted into the wall whole and centred, and read as dark ink on a light ground;
 * [invert] reads it the other way round. The cell grid depends on the wall's size, which is only
 * known at `draw`, so it is counted on the first frame and again only if the wall changes — some
 * tens of milliseconds; the image itself is decoded in `load`.
 *
 * **[edge] runs from the hard wall to a soft one**, and softens the tones and the letters. At 0 a
 * face snaps between black and white and a letter is whole cells turned or not. At 1 a sloped face
 * grades between the two as the light swings — so the words fade in and out rather than flipping
 * — flat faces take a share of the light by how squarely it meets them, and a cell half over a
 * letter gets a panel half turned. [feather] blurs the mask by that many cells first, so the
 * letter outlines grade over more than the one cell they cross.
 *
 * **The shadows stay hard whatever [edge] says, and their edge is one pixel wide.** A soft version
 * softened them too, and the wall lost the one thing that makes it this piece: a cast shadow is a
 * shape with an edge, and graded it reads as dirt on the face. The edge is antialiased by how
 * narrowly a ray clears the thing that would block it, against the height a pixel of travel gains
 * along the ray — so it is a pixel wide at any distance from its caster. An earlier version used
 * the usual penumbra term, which widens with distance: set hard enough to read as hard it came
 * out narrower than a pixel next to its caster, and every diagonal shadow edge stepped.
 *
 * **[concrete] projects the wall onto concrete**, the way the opening scene does: the lit tone is
 * multiplied by the texture, sampled in canvas pixels so the stone is nailed to the wall rather
 * than carried by the cells, and the shadows stay black because black times stone is black. It
 * is the same sampling as `concreteWall`, written here because that lives in the default package
 * and this file cannot reach it.
 *
 * **[lights] sends several lights past instead of one sun.** Each comes in low and faint, climbs
 * and brightens as it crosses, and sinks and fades out again; they arrive from different sides, a
 * share of the loop apart, so one is always leaving as the next is coming in. Every light casts its
 * own shadows and the wall takes the sum of what reaches it, so a point blocked from one light and
 * not the others is grey — shadows overlap in tones, and the only black is where every light is
 * blocked. Each light's weight is a steep power of a sine, so one light leads for most of its pass
 * and hands over to the next rather than all of them sharing the sky: shared evenly — a squared
 * sine, which sums to a constant — every shadow was filled in by the others to a third of black and
 * the words washed out. The sum is divided by the total, so the wall's brightness holds as they
 * change hands, and [contrast] pulls the result back toward two tones while an overlap stays grey.
 * Up to four; one gives the single sun back, swinging or orbiting.
 *
 * **[hollow] darkens every recess floor against the frame around it**, lit or not, the way a hole
 * reads darker than the face it is cut into. It is the same for every floor whatever its depth,
 * and that matters: a depth-dependent darkening would give a letter's shallow cells away before
 * any shadow does.
 *
 * **The reveal opens on a plain grid, and it has to open with the light straight on.** Any angle
 * at all throws a shadow in proportion to depth, so a letter's shallow cells carry thinner lines
 * than the deep ground around them and the words can be read in the hairlines before the reveal
 * has begun — which is exactly what the first version did, opening at 80 degrees. At 90 there is no
 * shadow anywhere, the grid is drawn by [hollow] alone, and letter and ground are identical to the
 * pixel. [hold] then keeps the light near square-on for a while before it leans away: the dip is
 * raised to that power, so the wall stands as a plain grid for the first few seconds of the turn.
 *
 * **[masks] gives the wall more than one text, and it circles through them one a turn.** The text
 * changes at the seam of the loop, which is where the light stands square on and no cell throws a
 * shadow — so letter and ground are identical there and the swap cannot be seen: the grid goes
 * plain, and the next turn draws a different sentence out of it. It is counted off the loop, so it
 * is where it is at any frame. `M` steps one text further on than the count says; [alternate] off
 * holds whichever text `M` has chosen.
 *
 * **[reveal] plays the reveal once rather than for ever**, which is what a wall that stands for
 * minutes wants — a chapter card. The sun opens square on, as the orbit does, and leans away to
 * [low] over [reveal] seconds of the slide's own frame count; then it stays low and keeps going
 * round, so the words stay read out of the wall while the shadows turn slowly inside every cell.
 * It never comes back square on, so the title never sinks into the grid again. Counted off
 * `stage.frame`, so a card that is replayed as its chapter opens reveals itself again.
 *
 * **`T` hides and shows the concrete, and `I` inverts the whole wall** — the finished picture,
 * concrete included, so the shadows come out light on a dark wall and nothing else changes.
 *
 * **`P` switches to soft shadows**, and back — a shadow that is sharp where it starts and fades
 * as it falls, the way a chair leg's shadow is crisp at the foot and dissolves across the floor.
 * Two things make it, both measured from the point on the floor to the wall that casts onto it,
 * along the light: the edge widens by [softness] of that distance, and the shadow lightens from
 * [softTone] by [fade] of it. So a shadow is hard and dark in the corner of a cell, where the wall
 * meets the floor, and opens out soft and pale toward its far end; a broad light never quite
 * reaches black. On a floor point that is lit, the distance is to the rim it passes instead, which
 * is what widens the edge of the shadow the rim throws. The
 * hard version stays the default because it is what holds the grid: soft, the cells' edges go to
 * gradients and the wall reads as relief rather than as a lattice.
 *
 * **`Q`/`A` add and take away a column, `W`/`S` a row.** The counts are the grid's, not a size,
 * so the cells change shape to fill the wall exactly — no part column at the edge — and the mask
 * is recounted onto the new grid. It opens on [rows] rows and as many columns as [aspect] gives.
 *
 * **Every pixel is sampled four times** ([samples]), on a rotated grid, with the edge smoothing
 * inside each sample halved to match — so an edge is resolved rather than blurred, and holds still
 * as the light moves instead of crawling. It costs four times the shading, and two things pay for
 * it, both measured in the studio at 3840x1080:
 *
 * - **The march steps half a pixel for as far as a shadow can reach**, rather than a fixed 64:
 *   the shaped facade went from 87 fps to a steady 120.
 * - **A wall of plain recesses is not marched at all.** Every frame is level with the wall and
 *   everything else is below it, so a floor point is lit exactly when its line to the light leaves
 *   through its own opening — one projection and a rectangle test. With the sun orbiting low over
 *   deep cells the march fell to 74 fps at the bottom of the turn; solved, it holds 120 and more
 *   the whole way round, and the edge is exact rather than stepped toward.
 */
class ShadowFacade(
    override val name: String = "Shadows",
    /** A picture whose dark parts are set as turned panels. Null for the facade on its own. */
    private val mask: File? = null,
    /** Several pictures, one a turn of the loop. Takes the place of [mask] when given. */
    private val masks: List<File> = emptyList(),
    /** Circle through [masks] a turn each; off, the wall holds whichever `M` has chosen. */
    private val alternate: Boolean = true,
    /**
     * How far the picture travels across the wall during its turn, as a share of the wall's
     * width, left to right: it stands where it was drawn at the middle of the turn, when the sun
     * is lowest and it reads, and is that far to either side at the seams, where the grid is plain.
     * It moves a whole cell at a time, the grid's own step, since a cell is either turned or not.
     * 0 holds it still.
     */
    private val drift: Double = 0.0,
    /** Read the mask as light ink on a dark ground instead. */
    private val invert: Boolean = false,
    /** 0 for the hard two-tone wall, 1 for soft tones and soft letter edges. Shadows stay hard. */
    private val edge: Double = 0.0,
    /** Cells of blur on the mask before it is read, for softer letter outlines. */
    private val feather: Int = 0,
    /** Recess depth of a cell over a letter, in cell heights, or 0 to set type by turned panels. */
    private val inkDepth: Double = 0.0,
    /** Rows of cells up the wall; the cell width follows from [aspect]. */
    private val rows: Int = 6,
    /** A cell's width over its height. */
    private val aspect: Double = 0.3,
    /** How deep the recess is, in cell heights. Deeper throws longer shadows. */
    private val depth: Double = 0.14,
    /** Half the width of a mullion and half the height of a ledge, in cell heights. */
    private val mullion: Double = 0.03,
    private val ledge: Double = 0.04,
    /** Cells in a run that all take the same shape. Not used with a mask. */
    private val group: Int = 3,
    /** Share of runs mirrored, and the shares that take a ramp, a blade, a step and a wedge; the rest are open. */
    private val mirrored: Double = 0.5,
    private val ramps: Double = 0.2,
    private val blades: Double = 0.15,
    private val stepShare: Double = 0.2,
    private val wedges: Double = 0.25,
    /** Seconds for the light to swing across and back. */
    period: Double = 20.0,
    /** Degrees the light swings either side of straight above. Not used with [orbit]. */
    private val sweep: Double = 45.0,
    /** Send the light once right round the wall per loop, sinking to [low] half way, rather than swinging it. */
    private val orbit: Boolean = false,
    /** How far each cell's opening is shifted within its frame, as a share of the frame's width. */
    private val jitter: Double = 0.0,
    /** How much deeper or shallower each cell's recess is cut, as a share of its depth. */
    private val depthJitter: Double = 0.0,
    /** How many lights pass the wall, up to four. One is the single sun, swinging or on [orbit]. */
    private val lights: Int = 1,
    /** Degrees of sky each passing light crosses. */
    private val travel: Double = 140.0,
    /** How steeply a passing light's weight rises and falls: higher gives each light the sky to itself for longer. */
    private val lead: Double = 6.0,
    /** 0 leaves the tones as the lights make them; 1 pushes them hard toward black and white. */
    private val contrast: Double = 0.0,
    /** Open on soft shadows rather than hard; `P` switches between them either way. */
    private val softShadows: Boolean = false,
    /** How much a soft shadow's edge widens per unit it falls from its caster. */
    private val softness: Double = 0.6,
    /** How dark a soft shadow gets at its darkest, 0 black to 1 no shadow at all. */
    private val softTone: Double = 0.05,
    /** How much a soft shadow lightens toward its far end, 0 not at all to 1 gone. */
    private val fade: Double = 0.4,
    /** How much darker a recess floor is than the frame face, 0 to 1. */
    private val hollow: Double = 0.0,
    /** How long an orbiting light lingers at [high] before it leans away: the dip's power, 1 for a plain cosine. */
    private val hold: Double = 1.0,
    /** Seconds to lean the light from square on to [low] once, then hold it there; 0 dips and rises every loop. */
    private val reveal: Double = 0.0,
    /**
     * Degrees the sun climbs off [low] and settles back, over [breathePeriod] seconds, once the
     * reveal has leaned it away: the shadow in every cell lengthens and shortens with it, so the
     * letters darken and lighten slowly instead of holding one tone. 0 holds the sun at [low].
     */
    private val breathe: Double = 0.0,
    private val breathePeriod: Double = 45.0,
    /**
     * A lamp wandering over the wall: a broad soft spot of light, [glowSize] of the wall's height
     * to its edge, that leaves the lit faces [glow] darker away from it than under it. It crosses
     * the wall on a path that does not retrace itself, once across in about [glowPeriod] seconds.
     * Shadows stay black wherever it stands. 0 lights the wall evenly.
     */
    private val glow: Double = 0.0,
    private val glowSize: Double = 0.8,
    private val glowPeriod: Double = 60.0,
    /** The light's elevation above the wall at the ends of the swing and overhead, in degrees. */
    private val low: Double = 40.0,
    private val high: Double = 60.0,
    /** A texture to project the wall onto, tiled in canvas pixels. Null draws it flat. */
    private val concrete: File? = null,
    /** How big one tile of the texture is drawn, against its own pixels. */
    private val concreteScale: Double = 1.0,
    /** How much of the texture reaches the wall: 1 is all of it, 0 leaves it flat. */
    private val concreteMix: Double = 1.0,
    /** Samples a pixel: 1, or 4 on a rotated grid for edges that hold still as the light moves. */
    private val samples: Int = 4,
    /**
     * Open inverted: the finished picture's tones the other way round, so shadow is light and the
     * lit wall dark. `I` still flips it. The chapter card runs this since 16 September: cutting
     * the letters shallow and the ground deep was tried first and left the ground a mid grey,
     * because at 10px cells the lit frames are half of every cell whatever its depth.
     */
    private val inverted: Boolean = false,
    private val dark: ColorRGBa = ColorRGBa.fromHex("000000"),
    private val light: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    override val sound: slideshow.Sound? = null
) : Backdrop() {

    override val loop = frames(period)
    private val revealFrames = frames(reveal)

    /** The sun's breathing and the lamp's wandering: both [Breathe], the principle for what stands for minutes. */
    private val breath = Breathe(breathePeriod)
    private val lamp = Breathe(glowPeriod)

    /**
     * A wall with several texts is not finished until every one has had its turn, so a written
     * run holds it for as many loops as there are texts. Held for one, the export of 15 September
     * never showed the third of three forms.
     */
    override val settle: Int get() = loop * masks.size.coerceAtLeast(1)
    override val background: ColorRGBa get() = dark

    private var pictures = emptyList<BufferedImage>()
    private var shownMask = 0
    private lateinit var none: ColorBuffer
    private var grid: ColorBuffer? = null
    private var gridFor = emptyList<Any>()
    private var stone: ColorBuffer? = null

    /** The grid in force: rows, and columns once the first frame has said how wide the wall is. */
    private var rowCount = rows

    /** Whether the concrete is drawn, which `T` flips. */
    private var stoneShown = true

    /** Whether the shadows are soft, which `P` flips. */
    private var softNow = softShadows

    /** Whether the wall is drawn inverted, which `I` flips. */
    private var invertNow = inverted
    private var columnCount = 0

    override fun load(program: Program) {
        // Bound in place of a grid or a texture when there is none, so the shader always has its samplers.
        none = colorBuffer(1, 1, type = ColorType.UINT8)
        pictures = masks.ifEmpty { listOfNotNull(mask) }.mapNotNull { file ->
            runCatching { ImageIO.read(file) }.getOrNull()
                .also { if (it == null) println("$name: no mask at $file, skipping it") }
        }
        stone = concrete?.let { file ->
            if (!file.isFile) {
                println("$name: no texture at ${file.path}, drawing flat")
                null
            } else loadImage(file).apply {
                wrapU = WrapMode.REPEAT
                wrapV = WrapMode.REPEAT
            }
        }
    }

    override fun key(name: String): Boolean {
        when (name) {
            "q" -> columnCount = (columnCount + 1).coerceAtMost(MAX_COLUMNS)
            "a" -> columnCount = (columnCount - 1).coerceAtLeast(1)
            "w" -> rowCount = (rowCount + 1).coerceAtMost(MAX_ROWS)
            "s" -> rowCount = (rowCount - 1).coerceAtLeast(1)
            "t" -> {
                stoneShown = !stoneShown
                println("${this.name}: concrete ${if (stoneShown) "on" else "off"}")
                return true
            }
            "m" -> {
                if (pictures.size < 2) return false
                shownMask = (shownMask + 1) % pictures.size
                println("${this.name}: text moved on by ${shownMask} of ${pictures.size}")
                return true
            }
            "i" -> {
                invertNow = !invertNow
                println("${this.name}: ${if (invertNow) "inverted" else "upright"}")
                return true
            }
            "p" -> {
                softNow = !softNow
                println("${this.name}: ${if (softNow) "soft" else "hard"} shadows")
                return true
            }
            else -> return false
        }
        println("${this.name}: $columnCount columns x $rowCount rows")
        return true
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val cell = stage.height / rowCount
        if (columnCount == 0) columnCount = max(1, (stage.width / (cell * aspect)).roundToInt())
        // The cells take whatever shape fills the wall exactly with the counts in force.
        val cellAspect = stage.width / (columnCount * cell)
        val turn = 2.0 * PI * stage.loop
        val swing = sin(turn)
        // Orbiting, the sun starts overhead-high at the top of the wall, goes once round, and is
        // lowest on the far side; swinging, it crosses the top and is lowest at either end.
        val azimuth = if (orbit) PI / 2.0 + turn else PI / 2.0 + Math.toRadians(sweep) * swing
        val dip = when {
            // Once, off the slide's own frame count, and then held low.
            revealFrames > 0 -> (0.5 - 0.5 * cos(PI * (stage.frame.toDouble() / revealFrames).coerceIn(0.0, 1.0))).pow(hold)
            orbit -> (0.5 - 0.5 * cos(turn)).pow(hold)
            else -> swing * swing
        }
        // Once the reveal has leaned the sun away it breathes: up `breathe` degrees and back on its
        // own slow period, counted from the end of the reveal so it joins the lean without a step.
        val settledFor = stage.frame - (if (revealFrames > 0) revealFrames else 0)
        val rise = if (breathe > 0.0 && settledFor > 0) breathe * breath.at(settledFor) else 0.0
        val elevation = Math.toRadians((high - (high - low) * dip + rise).coerceAtMost(89.0))
        val toLight = Vector3(cos(elevation) * cos(azimuth), cos(elevation) * sin(azimuth), sin(elevation))

        // The lamp's place, in the shader's wall pixels (y up): Breathe's wander, two waves a
        // golden ratio apart, so it crosses the wall rather than going round one track.
        val glowAt = lamp.wander(stage.frame, stage.width, stage.height)

        // The lights in force, as a direction and a weight each. One is the sun above; several
        // each make one pass a loop, from their own side and a share of the loop apart.
        val count = lights.coerceIn(1, MAX_LIGHTS)
        val passing = if (count == 1) listOf(Vector4(toLight.x, toLight.y, toLight.z, 1.0)) else (0 until count).map { i ->
            val phase = (stage.loop + i.toDouble() / count).mod(1.0)
            val arc = sin(PI * phase)
            val heading = PI / 2.0 + 2.0 * PI * i / count
            val az = heading + (phase - 0.5) * Math.toRadians(travel)
            val el = Math.toRadians(low + (high - low) * arc)
            Vector4(cos(el) * cos(az), cos(el) * sin(az), sin(el), arc.pow(lead))
        }

        // One text a turn, swapped at the seam where the light is square on and nothing shows.
        val text = if (alternate && pictures.size > 1) (stage.cycle + shownMask) % pictures.size else shownMask
        val cells = pictures.getOrNull(text)?.let { cellsOf(it, text, stage.width, stage.height, rowCount, columnCount) }

        drawer.stroke = null
        drawer.shadeStyle = shadeStyle {
            fragmentPreamble = PREAMBLE
            fragmentTransform = TRANSFORM
            parameter("size", Vector2(stage.width, stage.height))
            parameter("cell", cell)
            parameter("aspect", cellAspect)
            parameter("depth", depth)
            parameter("mullion", mullion)
            parameter("ledge", ledge)
            parameter("group", group.toDouble())
            parameter("mirrored", mirrored)
            parameter("ramps", ramps)
            parameter("blades", blades)
            parameter("stepShare", stepShare)
            parameter("wedges", wedges)
            parameter("masked", if (cells != null) 1.0 else 0.0)
            parameter("shift", (columnCount * drift * (stage.loop - 0.5)).roundToInt().toDouble())
            parameter("inkDepth", inkDepth)
            parameter("jitter", jitter)
            parameter("depthJitter", depthJitter)
            parameter("mask", cells ?: none)
            parameter("grid", Vector2((cells ?: none).width.toDouble(), (cells ?: none).height.toDouble()))
            for (i in 0 until MAX_LIGHTS) parameter("light$i", passing.getOrElse(i) { Vector4.ZERO })
            parameter("lightCount", count.toDouble())
            parameter("contrast", contrast.coerceIn(0.0, 1.0))
            parameter("soft", if (softNow) 1.0 else 0.0)
            parameter("softness", softness)
            parameter("softTone", softTone.coerceIn(0.0, 1.0))
            parameter("fade", fade.coerceIn(0.0, 1.0))
            parameter("hollow", hollow.coerceIn(0.0, 1.0))
            // One pixel in cell heights, split either side of an edge — half that when the pixel is
            // sampled four times, since the samples do the rest of the smoothing.
            parameter("aa", 0.75 / cell / if (samples >= 4) 2.0 else 1.0)
            parameter("samples", samples.toDouble())
            // Every cell a plain recess, set by depth or not at all: shadows can be solved rather than marched.
            parameter("plain", if ((pictures.isEmpty() || inkDepth > 0.0) && ramps + blades + stepShare + wedges == 0.0) 1.0 else 0.0)
            parameter("edge", edge.coerceIn(0.0, 1.0))
            parameter("dark", dark)
            parameter("light_tone", light)
            parameter("stoned", if (stone != null && stoneShown) 1.0 else 0.0)
            parameter("stone", stone ?: none)
            parameter("tile", Vector2((stone ?: none).width * concreteScale, (stone ?: none).height * concreteScale))
            parameter("stoneMix", concreteMix)
            parameter("inverted", if (invertNow) 1.0 else 0.0)
            parameter("glow", glow.coerceIn(0.0, 1.0))
            parameter("glowAt", glowAt)
            parameter("glowRadius", max(glowSize * stage.height, 1.0))
        }
        drawer.rectangle(stage.bounds)
        drawer.shadeStyle = null
    }

    /**
     * One texel a cell holding how much of it is ink, 0..1, blurred over [feather] cells. Row 0 is
     * the **bottom** row, because the shader counts cells up the wall. Float, so a cell exactly
     * half over a letter is exactly 0.5 rather than whatever eight bits round it to.
     */
    private fun cellsOf(image: BufferedImage, which: Int, width: Double, height: Double, rows: Int, columns: Int): ColorBuffer {
        val key = listOf(which, width, height, rows, columns)
        grid?.let { if (gridFor == key) return it }
        grid?.destroy()

        val cell = height / rows
        val cellWidth = width / columns
        val iw = image.width
        val ih = image.height
        val pixels = image.getRGB(0, 0, iw, ih, null, 0, iw)

        // Fitted whole and centred: a wall pixel (x, y down) is image pixel (x - ox, y - oy) / scale.
        val scale = min(width / iw, height / ih)
        val ox = (width - iw * scale) / 2.0
        val oy = (height - ih * scale) / 2.0

        val coverage = Array(rows) { DoubleArray(columns) }
        for (r in 0 until rows) {
            // Row r from the bottom, in wall pixels with y down.
            val top = height - (r + 1) * cell
            val y0 = ((top - oy) / scale).toInt()
            val y1 = ((top + cell - oy) / scale).toInt()
            for (c in 0 until columns) {
                val left = c * cellWidth
                val x0 = ((left - ox) / scale).toInt()
                val x1 = ((left + cellWidth - ox) / scale).toInt()
                var ink = 0
                var seen = 0
                for (y in maxOf(y0, 0) until minOf(y1, ih)) for (x in maxOf(x0, 0) until minOf(x1, iw)) {
                    val p = pixels[y * iw + x]
                    val lum = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 765.0
                    if ((lum < 0.5) != invert) ink++
                    seen++
                }
                coverage[r][c] = if (seen > 0) ink.toDouble() / seen else 0.0
            }
        }
        val soft = blurred(coverage, feather)

        val buffer = colorBuffer(columns, rows, type = ColorType.FLOAT32).apply {
            filter(MinifyingFilter.NEAREST, MagnifyingFilter.NEAREST)
        }
        val shadow = buffer.shadow
        for (r in 0 until rows) for (c in 0 until columns) {
            val v = soft[r][c]
            // The shadow buffer counts its rows from the top and texelFetch from the bottom, so
            // row r is written at the far end. Written at r the words came out upside down.
            shadow[c, rows - 1 - r] = ColorRGBa(v, v, v, 1.0)
        }
        shadow.upload()

        grid = buffer
        gridFor = key
        return buffer
    }

    /** A box blur of [radius] cells, across and then up, clamped at the edges of the grid. */
    private fun blurred(grid: Array<DoubleArray>, radius: Int): Array<DoubleArray> {
        if (radius <= 0) return grid
        val rows = grid.size
        val columns = grid[0].size
        val across = Array(rows) { r ->
            DoubleArray(columns) { c ->
                (-radius..radius).sumOf { grid[r][(c + it).coerceIn(0, columns - 1)] } / (2 * radius + 1)
            }
        }
        return Array(rows) { r ->
            DoubleArray(columns) { c ->
                (-radius..radius).sumOf { across[(r + it).coerceIn(0, rows - 1)][c] } / (2 * radius + 1)
            }
        }
    }

    private companion object {
        const val MAX_LIGHTS = 4
        const val MAX_ROWS = 200
        const val MAX_COLUMNS = 800

        val PREAMBLE = """
            // The most steps a shadow may take. Low sun over deep cells reaches a hundred pixels and
            // more at half a pixel a step; the march only runs as far as each shadow needs.
            const int MARCH = 256;

            float hash(vec2 c) { return fract(sin(dot(c, vec2(127.1, 311.7))) * 43758.5453); }

            // 1 inside [a, b], 0 outside, softened over w either side of each end.
            float band(float x, float a, float b, float w) {
                return smoothstep(a - w, a + w, x) * (1.0 - smoothstep(b - w, b + w, x));
            }

            // Height of the relief at q, in cell heights: 0 at the back of a recess, p_depth at the
            // face of the frame. q is in cell heights, y up.
            float relief(vec2 q) {
                vec2 id = vec2(floor(q.x / p_aspect), floor(q.y));
                vec2 u = vec2(q.x - id.x * p_aspect, q.y - id.y);
                vec2 run = vec2(floor(id.x / p_group), id.y);
                float w = p_aa;

                bool masked = p_masked > 0.5;
                // Set by depth, a letter is the facade cut deeper: same cells, same shapes.
                bool deep = masked && p_inkDepth > 0.0;
                bool turned = masked && !deep;
                if (!turned && hash(run + 31.0) < p_mirrored) u.x = p_aspect - u.x;

                // Each cell's opening sits a little off centre in its frame, hashed from the cell.
                vec2 shift = (vec2(hash(id + 3.0), hash(id + 5.0)) - 0.5) * 2.0 * p_jitter
                           * vec2(max(p_mullion - 2.0 * w, 0.0), max(p_ledge - 2.0 * w, 0.0));
                u -= shift;
                float frame = 1.0 - band(u.x, p_mullion, p_aspect - p_mullion, w)
                                  * band(u.y, p_ledge, 1.0 - p_ledge, w);

                // Across and up the opening, 0..1.
                float openW = p_aspect - 2.0 * p_mullion;
                float openH = 1.0 - 2.0 * p_ledge;
                float x = clamp((u.x - p_mullion) / openW, 0.0, 1.0);
                float y = clamp((u.y - p_ledge) / openH, 0.0, 1.0);

                float inner = 0.0;
                if (turned) {
                    // One panel filling the opening, tilted across and a little up. The upward
                    // tilt is a third of the sideways one, measured as a slope rather than as
                    // a share of the cell, so it holds for any cell shape.
                    //
                    // turn is +1 for the ground and -1 for ink: the panel turned the other way.
                    // Hard, it is one or the other at half coverage; soft, a cell half over a
                    // letter stands its panel square across, tilted only upward.
                    float coverage = texelFetch(p_mask, ivec2(clamp(id - vec2(p_shift, 0.0), vec2(0.0), p_grid - 1.0)), 0).r;
                    float spread = mix(0.0, 0.5, p_edge);
                    float turn = p_edge > 0.0
                        ? 1.0 - 2.0 * smoothstep(0.5 - spread, 0.5 + spread, coverage)
                        : (coverage > 0.5 ? -1.0 : 1.0);
                    float kx = 0.5;
                    float ky = 0.33 * kx * openH / openW;
                    inner = 0.9 * (kx * (0.5 + turn * (x - 0.5)) + ky * (1.0 - y)) / (kx + ky);
                } else {
                    float pick = hash(run);
                    if (pick < p_ramps) {
                        // A ramp across the whole opening, from the back up to nearly the face.
                        inner = 0.85 * x;
                    } else if (pick < p_ramps + p_blades) {
                        // A blade down the middle, standing to the face.
                        inner = band(x, 0.4, 0.6, w / openW);
                    } else if (pick < p_ramps + p_blades + p_stepShare) {
                        // A step raised over the lower half.
                        float wy = w / openH;
                        inner = 0.6 * (1.0 - smoothstep(0.5 - wy, 0.5 + wy, y));
                    } else if (pick < p_ramps + p_blades + p_stepShare + p_wedges) {
                        // A triangle across the top, a third of the way down at one side and
                        // nothing at the other, its face tilted toward that side: its long edge
                        // throws the diagonal, and the face is black or white by which side the
                        // light is on.
                        float wy = w / openH;
                        float edge = 0.67 + 0.33 * x;
                        inner = smoothstep(edge - wy, edge + wy, y) * mix(1.0, 0.3, x);
                    }
                }

                float shape = max(frame, inner);
                if (deep) {
                    // The share of the cell that is letter decides how deep it is cut, with the
                    // frame's top level with the wall either way.
                    float coverage = texelFetch(p_mask, ivec2(clamp(id - vec2(p_shift, 0.0), vec2(0.0), p_grid - 1.0)), 0).r;
                    float spread = mix(0.0, 0.5, p_edge);
                    float ink = p_edge > 0.0
                        ? smoothstep(0.5 - spread, 0.5 + spread, coverage)
                        : (coverage > 0.5 ? 1.0 : 0.0);
                    float cut = mix(p_depth, p_inkDepth, ink)
                              * (1.0 + (hash(id + 11.0) - 0.5) * 2.0 * p_depthJitter);
                    return p_depth - cut * (1.0 - shape);
                }
                return p_depth * shape;
            }

            // How much of the light reaches p: 1 lit, 0 in shadow, over one pixel of edge.
            //
            // The edge is measured, not guessed at: moving the receiver one pixel along the light
            // raises the ray over whatever it passes by the height a pixel of travel gains, so a
            // ray that clears the nearest obstacle by less than that is a pixel that straddles
            // the shadow's edge, and is lit by that share.
            float lightAt(vec3 p, vec3 L) {
                if (L.z <= 0.001) return 0.0;
                float tMax = (p_depth - p.z) / L.z;
                if (tMax <= 0.0) return 1.0;
                float pixel = p_aa / 0.75;
                float band = pixel * L.z / max(length(L.xy), 1e-3);
                // Steps of about half a pixel across the wall, as many as the reach needs. A fixed 64
                // spent most of them on shadows ten pixels long, and at four samples a pixel that
                // was the frame rate.
                float reach = tMax * length(L.xy) * p_cell;
                float steps = clamp(ceil(reach / 0.5), 4.0, float(MARCH));
                float dt = tMax / steps;
                // Start once the ray has risen a full edge above the surface it leaves: nearer
                // than that, the clearance being measured is from the receiver itself, and a flat
                // face in full light came out a shade of grey.
                float t = max(dt * 0.5 + p_aa, band / L.z);
                // Soft, the edge widens with the distance travelled from the receiver, so it is
                // sharp where the caster is near and broad where it is far.
                float spread = p_soft * p_softness * L.z;
                float closest = 1e9;
                for (int i = 0; i < MARCH; i++) {
                    vec3 s = p + L * t;
                    closest = min(closest, (s.z - relief(s.xy)) / (band + spread * t));
                    if (closest < -0.5) return p_soft * p_softTone;
                    t += dt;
                    if (t > tMax || float(i) >= steps) break;
                }
                float reached = p_soft > 0.5 ? smoothstep(-0.5, 0.5, closest) : clamp(0.5 + closest, 0.0, 1.0);
                return mix(p_soft * p_softTone, 1.0, reached);
            }

            // A wall of plain recesses, solved rather than marched.
            //
            // Every frame is level with the wall and everything else is below it, so a point on the
            // floor of a recess is lit exactly when the straight line from it toward the light leaves
            // through its own opening: carried up to the wall's plane it lands at u + d * L.xy / L.z,
            // and once it is there nothing stands higher. So a cell's shadow is one projection and a
            // test against its own rectangle — no march, and an edge that is exactly where it is,
            // antialiased by its distance in pixels. The march is kept for the shaped cells.
            float plainShade(vec2 q, vec3 L) {
                if (L.z <= 0.001) return 0.0;
                vec2 id = vec2(floor(q.x / p_aspect), floor(q.y));
                vec2 u = vec2(q.x - id.x * p_aspect, q.y - id.y);
                float w = p_aa;
                vec2 shift = (vec2(hash(id + 3.0), hash(id + 5.0)) - 0.5) * 2.0 * p_jitter
                           * vec2(max(p_mullion - 2.0 * w, 0.0), max(p_ledge - 2.0 * w, 0.0));
                vec2 lo = vec2(p_mullion, p_ledge) + shift;
                vec2 hi = vec2(p_aspect - p_mullion, 1.0 - p_ledge) + shift;

                float d = p_depth;
                if (p_masked > 0.5) {
                    float coverage = texelFetch(p_mask, ivec2(clamp(id - vec2(p_shift, 0.0), vec2(0.0), p_grid - 1.0)), 0).r;
                    float spread = mix(0.0, 0.5, p_edge);
                    float ink = p_edge > 0.0
                        ? smoothstep(0.5 - spread, 0.5 + spread, coverage)
                        : (coverage > 0.5 ? 1.0 : 0.0);
                    d = mix(p_depth, p_inkDepth, ink);
                }
                d *= 1.0 + (hash(id + 11.0) - 0.5) * 2.0 * p_depthJitter;

                // Distances are in cell heights; 0.75 / p_aa turns them into the edge's own width.
                float toEdge = 0.75 / p_aa;
                vec2 inLo = u - lo;
                vec2 inHi = hi - u;
                float open = clamp(0.5 + min(min(inLo.x, inLo.y), min(inHi.x, inHi.y)) * toEdge, 0.0, 1.0);
                vec2 exit = u + d * L.xy / L.z;
                vec2 outLo = exit - lo;
                vec2 outHi = hi - exit;
                float clear = min(min(outLo.x, outLo.y), min(outHi.x, outHi.y));
                float floorLit;
                if (p_soft > 0.5) {
                    // Soft: measured from this floor point to what casts onto it, across the wall.
                    // The rim is `rim` away along the light; the cell's wall is where the light's
                    // line first leaves the opening. A point in shadow is cast on by the wall, and
                    // is close to it near the corner; a lit point is only ever passed by the rim.
                    float across = length(L.xy);
                    vec2 dir = L.xy / max(across, 1e-4);
                    float rim = d * across / L.z;
                    vec2 toWall = vec2(
                        dir.x > 1e-4 ? (hi.x - u.x) / dir.x : (dir.x < -1e-4 ? (lo.x - u.x) / dir.x : 1e9),
                        dir.y > 1e-4 ? (hi.y - u.y) / dir.y : (dir.y < -1e-4 ? (lo.y - u.y) / dir.y : 1e9));
                    float fallen = clamp(min(min(toWall.x, toWall.y), rim), 0.0, rim);
                    float width = 1.0 / toEdge + p_softness * fallen;
                    float darkest = mix(p_softTone, 1.0, p_fade * fallen / max(rim, 1e-4));
                    floorLit = mix(darkest, 1.0, smoothstep(-0.5 * width, 0.5 * width, clear));
                } else {
                    floorLit = clamp(0.5 + clear * toEdge, 0.0, 1.0);
                }

                float face = smoothstep(mix(0.1, -0.1, p_edge), mix(0.25, 0.9, p_edge), L.z);
                return face * mix(1.0, floorLit * (1.0 - p_hollow), open);
            }

            // How lit the wall is at wall pixel px, 0..1: every light's share of what reaches it.
            float shade(vec2 px) {
                vec2 q = px / p_cell;
                vec4 lights[4] = vec4[4](p_light0, p_light1, p_light2, p_light3);
                float sum = 0.0;
                float total = 0.0;

                if (p_plain > 0.5) {
                    for (int i = 0; i < 4; i++) {
                        if (float(i) >= p_lightCount) break;
                        if (lights[i].w <= 0.0) continue;
                        sum += lights[i].w * plainShade(q, normalize(lights[i].xyz));
                        total += lights[i].w;
                    }
                    return total > 0.0 ? sum / total : 0.0;
                }

                float h = relief(q);

                // Half a pixel either side: a wider difference smears an edge's steep normal over the
                // flat face beside it, and on cells a dozen pixels wide that is a real share of the face.
                float e = p_aa;
                float dx = relief(q + vec2(e, 0.0)) - relief(q - vec2(e, 0.0));
                float dy = relief(q + vec2(0.0, e)) - relief(q - vec2(0.0, e));
                vec3 n = normalize(vec3(-dx / (2.0 * e), -dy / (2.0 * e), 1.0));

                // A flat face is lit when the light reaches it. A sloped one is lit when it catches
                // most of what the flat wall does, and black when it turns clearly away — judged
                // against the wall rather than on its own, because slopes this shallow get some light
                // from almost anywhere and would never go black. The first rule tried was which side
                // the slope faced, and with the light overhead it put every slope and every edge on
                // neither side and drew them all black. The near-vertical walls at an edge are a pixel
                // wide and are no face anyone sees; they are left to the shadow like the flat face.
                // p_edge widens every step: hard, each is a switch, and soft, each grades.
                float k = p_edge;
                float tilt = length(n.xy);
                for (int i = 0; i < 4; i++) {
                    if (float(i) >= p_lightCount) break;
                    if (lights[i].w <= 0.0) continue;
                    vec3 L = normalize(lights[i].xyz);
                    float flatLit = smoothstep(mix(0.1, -0.1, k), mix(0.25, 0.9, k), dot(n, L));
                    float share = dot(n, L) / max(L.z, 1e-3);
                    float slopeLit = smoothstep(mix(0.76, 0.2, k), mix(0.84, 1.2, k), share);
                    float sheer = smoothstep(2.0, 4.0, tilt);
                    float facing = mix(flatLit, mix(slopeLit, flatLit, sheer), smoothstep(0.03, 0.1, tilt));
                    sum += lights[i].w * (facing > 0.0 ? facing * lightAt(vec3(q, h), L) : 0.0);
                    total += lights[i].w;
                }
                // Every floor a shade under the face, whatever its depth.
                float below = 1.0 - smoothstep(0.9 * p_depth, 0.99 * p_depth, h);
                return (total > 0.0 ? sum / total : 0.0) * (1.0 - p_hollow * below);
            }
        """.trimIndent()

        val TRANSFORM = """
            vec2 px = vec2(c_boundsPosition.x, 1.0 - c_boundsPosition.y) * p_size;
            float lit;
            if (p_samples >= 4.0) {
                // A rotated grid: four samples, no two sharing a row or a column, so a horizontal
                // or vertical edge — which is every edge the frame has — is resolved in four steps.
                lit = 0.25 * (shade(px + vec2(0.125, 0.375)) + shade(px + vec2(-0.375, 0.125))
                            + shade(px + vec2(0.375, -0.125)) + shade(px + vec2(-0.125, -0.375)));
            } else {
                lit = shade(px);
            }
            // Toward two tones again, keeping a grey where the lights' shadows overlap.
            float spread = 0.5 * (1.0 - 0.8 * p_contrast);
            lit = mix(lit, smoothstep(0.5 - spread, 0.5 + spread, lit), p_contrast);
            vec3 tone = mix(p_dark.rgb, p_light_tone.rgb, lit);
            if (p_stoned > 0.5) {
                // The wall is concrete with the light thrown on it: sampled in canvas pixels, so
                // the stone stays put while the shadows move over it, and black stays black.
                vec3 stone = texture(p_stone, gl_FragCoord.xy / p_tile).rgb;
                tone = mix(tone, stone * tone, p_stoneMix);
            }
            if (p_inverted > 0.5) tone = 1.0 - tone;
            if (p_glow > 0.0) {
                // The lamp, on the finished tone: brightest under it and p_glow darker away from
                // it, whichever way round the wall is drawn. Black stays black either way.
                vec2 g = (px - p_glowAt) / p_glowRadius;
                tone *= mix(1.0 - p_glow, 1.0, exp(-dot(g, g)));
            }
            x_fill = vec4(tone, 1.0);
        """.trimIndent()
    }
}
