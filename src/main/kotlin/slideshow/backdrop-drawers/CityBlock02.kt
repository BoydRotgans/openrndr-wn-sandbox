package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.color.Linearity
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector3
import slideshow.Backdrop
import slideshow.Cut
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition

/**
 * The block city with the grid taken off: the same field of cubic buildings under the same
 * turning sun, but every face plain — a massing model rather than an architectural drawing.
 *
 * **It stands on [BlockCity] rather than copying it.** The shadow maps, the outline pass, the
 * torus tile and the day are one implementation, and this file is only what differs: the grid
 * off, and two things that have to change with it, both found by measuring the plain wall
 * across its day rather than looking at one frame of it.
 *
 * - **The day is shallower.** With the grid on, a high sun leaves the big lit faces ruled and
 *   readable; with it off they are blank, and at three quarters of the day the wall was 89% in
 *   its top two tones — white on white with a hairline between. `noon` comes down from 60 to
 *   34 degrees, so the shadows stay at least a block and a half long all day: measured at the
 *   same frame, cast shadow went from 7% of the wall to 30%.
 * - **The step between a roof and a lit wall is wider.** On the ruled wall the two are 0.97
 *   and 0.86, and the grid does the rest of the telling apart; here there is nothing else, so
 *   the lit wall drops to 0.76 and the unlit tones with it. That is 53 levels between roof and
 *   wall against 28, and it is what keeps a corner a corner when the sun is high.
 *
 * The show dresses it in the house colours: navy for the walls and the shadows alike and white
 * for the roofs and the ground alike, so the city is one navy mass cut by white shapes the way
 * the brand graphic draws it; no outlines, with the silhouettes softened instead; and one block
 * in twenty-five in the red. That is all stated in `Slideshow.kt` — the defaults here are the
 * grey plain wall, and every one of those choices is an argument.
 *
 * Everything else — the pan, the sway, the loop, the tile, the lens option — is [BlockCity]'s
 * and is documented there. The random numbers are drawn whether or not a face is ruled, so
 * this is the very same city as the drawn one, block for block.
 */
class CityBlock02(
    override val name: String = "Blocks 02",
    /** Days a period, 0 for the fixed light. See [BlockCity.sun]. */
    sun: Int = 1,
    /** Seconds the pan takes to cross one tile — the loop. */
    period: Double = 240.0,
    /** The sun's elevation at the start and the middle of a day. Noon is low here on purpose — see above. */
    dawn: Double = 15.0,
    noon: Double = 34.0,
    /** Degrees the yaw sways either side of isometric, and whole sways a period. */
    sway: Double = 10.0,
    sways: Int = 1,
    /** Pixels one grid unit takes across the wall. */
    unit: Double = 120.0,
    /** Share of the ground the blocks stand on. */
    density: Double = 0.78,
    /** The tones. The roof is the drawn wall's; the walls sit lower than the drawn wall's — see above. */
    top: ColorRGBa = grey(0.97),
    lit: ColorRGBa = grey(0.76),
    unlit: ColorRGBa = grey(0.46),
    unlitAcross: ColorRGBa = grey(0.57),
    shade: ColorRGBa = grey(0.24),
    paper: ColorRGBa = grey(0.95),
    ink: ColorRGBa = grey(0.30),
    /** Line width in wall pixels; 0 for flat shapes with no outline at all. */
    line: Double = 1.6,
    /** A colour for a share of the blocks, and the share — see [BlockCity.accent]. */
    accent: ColorRGBa? = null,
    accents: Double = 0.04,
    /** Where the light travels from at dawn — see [BlockCity.light]. */
    light: Vector3 = Vector3(2.0, -2.5, -1.0),
    seed: Int = 7,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    private val city = BlockCity(
        name = name, sun = sun, period = period, dawn = dawn, noon = noon, sway = sway, sways = sways,
        unit = unit, density = density, top = top, lit = lit, unlit = unlit, unlitAcross = unlitAcross,
        shade = shade, paper = paper, ink = ink, line = line, accent = accent, accents = accents,
        light = light, seed = seed,
        ruled = false,
        transition = transition, sound = sound
    )

    override val background: ColorRGBa get() = city.background
    override fun load(program: Program) = city.load(program)
    override fun draw(drawer: Drawer, stage: Stage) = city.draw(drawer, stage)

    private companion object {
        fun grey(v: Double) = ColorRGBa(v, v, v, 1.0, Linearity.SRGB)
    }
}
