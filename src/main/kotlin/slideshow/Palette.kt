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
 * Three palettes are enough for the show: [onBlack] for the slides and the catalogue walls,
 * [onPaper] for a wall that stands on white, [onGrey] for the quote's ground. A slide that
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
        /** The house colours: the red, the two blues, the greys. */
        val RED: ColorRGBa = ColorRGBa.fromHex("FF0000")
        val BLUE: ColorRGBa = ColorRGBa.fromHex("3D5AE0")
        val SKY: ColorRGBa = ColorRGBa.fromHex("4674D6")
        val NAVY: ColorRGBa = ColorRGBa.fromHex("1E3A72")
        val WHITE: ColorRGBa = ColorRGBa.fromHex("FFFFFF")
        val BLACK: ColorRGBa = ColorRGBa.fromHex("000000")
        val GREY: ColorRGBa = ColorRGBa.fromHex("D9D9D9")
        val GROUND_GREY: ColorRGBa = ColorRGBa.fromHex("3C3C3C")
        val PAPER: ColorRGBa = ColorRGBa.fromHex("F5F7FA")

        /** The slides, and the catalogue walls: white ink on black, a navy shadow that reads on it. */
        val onBlack = Palette(
            paper = BLACK, ink = WHITE, accent = RED, structure = BLUE, quiet = GREY,
            shadow = ColorRGBa.fromHex("2E4A8A"), shadowDeep = NAVY
        )

        /** A wall on white: dark ink, the navy as structure, a grey shadow and black where two lap. */
        val onPaper = Palette(
            paper = PAPER, ink = ColorRGBa.fromHex("111111"), accent = RED, structure = NAVY,
            quiet = ColorRGBa.fromHex("8A8A8A"),
            shadow = ColorRGBa.fromHex("C4C4C4"), shadowDeep = BLACK
        )

        /** The quote's dark grey ground. */
        val onGrey = onBlack.on(GROUND_GREY)
    }
}
