package slideshow

import org.openrndr.color.ColorRGBa

/**
 * Colour as a role, never a value — principle 1 of `style-guide/principles.md`.
 *
 * A drawer asks for [accent] or [structure] and the palette decides what that is on the ground
 * it stands on. Five roles carry the whole evening, and the shadow pair is derived from the
 * ground rather than stated beside every wall:
 *
 *  - [paper]      the ground the thing stands on
 *  - [ink]        the thing itself, when nothing is being said about it
 *  - [accent]     the one thing being said right now — red, and one accent idea at a time
 *  - [structure]  the supporting mass, the context, the rest of the chart — blue
 *  - [quiet]      what is deliberately not being talked about
 *
 * Every colour is built with `fromHex`, so it arrives on the wall as written: the plain
 * constructor's colours are sRGB-lifted in a shader (the note under BlockCity in CLAUDE.md).
 *
 * Two palettes are enough for the show: [onBlack] for the slides and the catalogue walls, and
 * [onPaper] for a wall that stands on white. There is no grey ground: black is what a slide
 * paints, and the show's concrete overlay (`SLIDES_CONCRETE_FLOOR`) turns black into the dark
 * grey concrete on the wall, one ground for every slide. A slide that
 * brings its own colour because its content does — the sectors' playful set — takes a palette
 * and adds to it, which is what makes the exception visible as one.
 */
data class Palette(
    val paper: ColorRGBa,
    val ink: ColorRGBa,
    val accent: ColorRGBa,
    val structure: ColorRGBa,
    val quiet: ColorRGBa,
    /** What one shadow is drawn in on this ground, and what two lying over one another are. */
    val shadow: ColorRGBa,
    val shadowDeep: ColorRGBa
) {
    /** The same roles on another ground. */
    fun on(ground: ColorRGBa) = copy(paper = ground)

    companion object {
        /**
         * The house colours: **one red and one blue**, and the greys. Every red and every blue in the
         * show is one of these two (28 September, "the same red and blue tints at all levels"); there
         * were a bright blue, a sky blue and a navy before, and a darker red for sides. Where a design
         * needs a darker face of either — the side of a slab, where two shadows lap — it is shaded
         * from these with `shade`, never stated as another hex.
         *
         * They are a setting rather than a constant: [Brand] reads them from `show-colours.json` at
         * the start of a run, and the organizer moves them. `FF0000` and `023F88` are the defaults.
         */
        val RED: ColorRGBa get() = Brand.red
        val BLUE: ColorRGBa get() = Brand.blue
        val WHITE: ColorRGBa = ColorRGBa.fromHex("FFFFFF")
        val BLACK: ColorRGBa = ColorRGBa.fromHex("000000")
        val GREY: ColorRGBa = ColorRGBa.fromHex("D9D9D9")
        val PAPER: ColorRGBa = ColorRGBa.fromHex("F5F7FA")

        /** The slides, and the catalogue walls: white ink on black, a blue shadow that reads on it. */
        val onBlack get() = Palette(
            paper = BLACK, ink = WHITE, accent = RED, structure = BLUE, quiet = GREY,
            shadow = BLUE, shadowDeep = BLUE.shade(0.6)
        )

        /** A wall on white: dark ink, the blue as structure, a grey shadow and black where two lap. */
        val onPaper get() = Palette(
            paper = PAPER, ink = ColorRGBa.fromHex("111111"), accent = RED, structure = BLUE,
            quiet = ColorRGBa.fromHex("8A8A8A"),
            shadow = ColorRGBa.fromHex("C4C4C4"), shadowDeep = BLACK
        )

    }
}
