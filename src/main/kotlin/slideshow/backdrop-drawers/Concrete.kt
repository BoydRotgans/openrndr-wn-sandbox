// ============================================================================ //
//  No `package` declaration: it belongs with the backdrop drawers, which are in
//  the default package because they stand on loadObjectSheet.
// ============================================================================ //

import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.WrapMode
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import java.io.File

/**
 * A wall of concrete to project a backdrop onto, as a shade style to hang on the drawer.
 *
 * Set it once for a whole frame and everything drawn after it is multiplied by the texture —
 * fills, strokes and lettering alike — so the picture reads as light thrown onto a concrete wall
 * rather than as concrete-coloured objects sitting on black. The ground stays whatever it was,
 * which is what a projector's black is.
 *
 * **It is sampled in canvas pixels, not in anything's own space**, and that is the whole of what
 * makes it material rather than a pattern painted on: the concrete is nailed to the wall and a
 * shape is a *window* onto it. So the stone does not slide when a piece slides, and two pieces
 * standing side by side are cut from one slab rather than each carrying its own copy.
 * `gl_FragCoord` is in the canvas's own pixels here, since everything is drawn into a
 * canvas-sized buffer before it ever reaches a window — so the tiling is the same whatever the
 * window is doing, and a still matches a filmed frame.
 *
 * **It has to touch both `x_fill` and `x_stroke`.** A stroked line takes its colour from
 * `x_stroke` and a fill and a glyph from `x_fill`, so a transform touching only one leaves the
 * drawn edge — or the type — floating clean over a wall everything else is standing on.
 *
 * Shared by [OpeningScene] and [ConveyorScene] rather than written twice: it is one idea about
 * what these walls *are*, and two copies would drift the moment either was tuned.
 */
fun concreteWall(
    file: File?,
    /** How big one tile of the texture is drawn, against its own pixels. */
    scale: Double = 1.0,
    /** How much of the texture reaches what is drawn: 1 is all of it, 0 leaves it flat. */
    mix: Double = 1.0,
    /** Named in the warning when the file is not there, so a blank wall says why. */
    owner: String = ""
): ShadeStyle? {
    if (file == null) return null
    if (!file.isFile) {
        println("no texture at ${file.path} — \"$owner\" draws flat")
        return null
    }

    val stone = loadImage(file).apply {
        wrapU = WrapMode.REPEAT
        wrapV = WrapMode.REPEAT
    }
    return shadeStyle {
        fragmentTransform = """
            vec2 uv = gl_FragCoord.xy / p_tile;
            vec3 stone = texture(p_stone, uv).rgb;
            x_fill.rgb = mix(x_fill.rgb, stone * x_fill.rgb, p_mix);
            x_stroke.rgb = mix(x_stroke.rgb, stone * x_stroke.rgb, p_mix);
        """.trimIndent()
        parameter("stone", stone)
        parameter("tile", Vector2(stone.width * scale, stone.height * scale))
        parameter("mix", mix)
    }
}
