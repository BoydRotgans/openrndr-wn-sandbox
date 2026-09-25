package slideshow

import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.WrapMode
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import java.io.File

/**
 * The concrete over the whole frame: the picture as if projected onto a concrete wall. One
 * texture, fixed to the frame rather than to anything in it, laid over the finished image where
 * it is shown in the window — so no still or preview carries it.
 *
 * It lives here rather than inside `present` so the show and the sandbox's course studio lay the
 * very same wall over their pictures, read off the same `SLIDES_CONCRETE*` keys, instead of two
 * copies that drift.
 *
 * **It only ever darkens, but for the floor.** Light on a wall is the picture times the stone, and
 * stone is never brighter than white — dividing the texture by its *average* instead pushed every
 * lighter-than-average pixel past white, so a white piece clipped to flat white with a few specks
 * and read as overexposed. So the grain is measured against the texture's bright end (its 98th
 * percentile of brightness, read off the file once), and `mix` exaggerates it: this photograph is
 * mid grey within ±9%, far too flat to show at 1. Brightness only, so the stone's own faint hue
 * does not tint the house colours.
 */
class ConcreteWall(
    val texture: ColorBuffer,
    private val mix: Double,
    private val scale: Double,
    /** The darkest the wall goes: black is lifted to this grey, linear light, before the grain. */
    private val floor: Double
) {
    private val bright = brightEnd(texture)

    /** The wall as a shade style for drawing a picture of [canvas] pixels into the window. */
    fun style(canvas: Vector2): ShadeStyle = shadeStyle {
        fragmentTransform = """
            vec2 uv = c_boundsPosition.xy * p_canvas / p_tile;
            // The photograph is an sRGB texture, so the sampler hands it back decoded to
            // linear light — far darker than its file, which is what the bright end was
            // measured on. Taken back to the file's values first, or the grain goes below
            // zero everywhere and the whole frame comes out black.
            float stone = pow(dot(texture(p_stone, uv).rgb, vec3(0.299, 0.587, 0.114)), 1.0 / 2.2);
            float grain = min(stone / p_bright, 1.0);
            // Black is lifted to the floor first, so a black picture is dark stone rather
            // than a hole in the wall. Only what is near black: the lift fades out as the
            // brightest channel rises, so a navy or a blue keeps its colour — lifting every
            // dark channel alike added grey to them and washed the chapter card's blues out.
            float nearBlack = 1.0 - smoothstep(0.0, p_floorReach, max(x_fill.r, max(x_fill.g, x_fill.b)));
            x_fill.rgb = (x_fill.rgb + p_floor * nearBlack) * clamp(1.0 - p_mix * (1.0 - grain), 0.0, 1.0);
        """.trimIndent()
        parameter("stone", texture)
        parameter("canvas", canvas)
        parameter("tile", Vector2(texture.width * scale, texture.height * scale))
        parameter("mix", mix)
        parameter("bright", bright)
        parameter("floor", floor)
        // Linear light: 0.06 is about 70 of 255, under the navy's blue channel.
        parameter("floorReach", 0.06)
    }

    companion object {
        /** The wall off [path], or null — with a line saying why — when there is no texture to read. */
        fun load(path: String?, mix: Double, scale: Double, floor: Double): ConcreteWall? {
            val file = path?.let { File(it) } ?: return null
            if (!file.isFile) { println("concrete: no texture at ${file.path}"); return null }
            val texture = runCatching { loadImage(file) }.getOrElse { println("concrete: could not read ${file.path}"); return null }
            texture.wrapU = WrapMode.REPEAT; texture.wrapV = WrapMode.REPEAT
            texture.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            texture.generateMipmaps()
            return ConcreteWall(texture, mix, scale, floor).also {
                println("concrete: ${file.path}, bright end %.3f, grain x%.1f".format(it.bright, mix))
            }
        }

        /**
         * The bright end of a texture: the 98th percentile of its brightness, sampled on a coarse grid
         * off the texture itself — measured off its own bytes, which are the file's sRGB values. What
         * the grain is measured against, so the stone's lightest patches leave the picture as it is and
         * everything else darkens it.
         */
        private fun brightEnd(texture: ColorBuffer): Double = runCatching {
            texture.shadow.download()
            val step = maxOf(1, minOf(texture.width, texture.height) / 256)
            val values = ArrayList<Double>()
            for (y in 0 until texture.height step step) for (x in 0 until texture.width step step) {
                val c = texture.shadow[x, y]
                values += 0.299 * c.r + 0.587 * c.g + 0.114 * c.b
            }
            texture.shadow.destroy()
            values.sort()
            values[(values.size * 0.98).toInt().coerceAtMost(values.size - 1)].coerceIn(0.05, 1.0)
        }.getOrElse { println("concrete: could not measure the texture (${it.message})"); 1.0 }
    }
}
