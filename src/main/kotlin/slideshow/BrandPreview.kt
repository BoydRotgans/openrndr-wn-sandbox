package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector3

/**
 * The organizer's colours on the running wall, before the show is started again in them.
 *
 * A run builds every slide in [Brand]'s colours and bakes them in, so a colour moved while it runs
 * cannot reach the slides themselves. It is mapped onto the finished frame instead, in one pass,
 * as the frame goes to the window: every pixel is read as some share of the red or the blue with
 * some grey under it — `a · key + b · white`, fitted in linear light — and where that describes the
 * pixel to within three levels of 255, it is drawn again as the same share of the new colour over
 * the same grey. That is exact on a flat colour, on a shade of it, and on its edges against black,
 * white or the greys, which is nearly the whole show. What the key could not have drawn is left
 * alone — more than the whole colour, or a little of it over a lot of grey — which is what keeps a
 * photograph's sky and its bluish concrete out of it (measured: all but a fraction of a percent of a
 * project photo untouched); the price is a thin fringe of the old colour where an edge crosses a
 * light ground at under four fifths coverage, and where red meets blue.
 * It is a preview, and the next start builds in the saved colours with the pass off.
 *
 * Off (no pass, no buffer) until a colour is moved. It works on the canvas as the window shows it,
 * so a still or a preview is never touched — those are taken off the canvas itself.
 */
class BrandPreview(private val width: Int, private val height: Int) {
    private var target: RenderTarget? = null

    private val style = shadeStyle {
        fragmentPreamble = """
            vec3 bpEncode(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
            // p as a·k + b·white, a and b at least 0: how much of the key, the grey under it, and how
            // far that is from p in sRGB levels.
            float bpFit(vec3 p, vec3 k, out float a, out float b) {
                float kk = dot(k, k), kw = k.r + k.g + k.b, pk = dot(p, k), pw = p.r + p.g + p.b;
                float det = kk * 3.0 - kw * kw;
                a = det > 1e-9 ? (pk * 3.0 - pw * kw) / det : 0.0;
                b = det > 1e-9 ? (kk * pw - kw * pk) / det : pw / 3.0;
                if (b < 0.0) { b = 0.0; a = pk / kk; }
                if (a < 0.0) { a = 0.0; b = pw / 3.0; }
                vec3 d = abs(bpEncode(p) - bpEncode(a * k + vec3(b)));
                return max(d.r, max(d.g, d.b));
            }
        """.trimIndent()
        fragmentTransform = """
            vec3 p = x_fill.rgb;
            float ar, br, ab, bb;
            float er = bpFit(p, p_redFrom, ar, br);
            float eb = bpFit(p, p_blueFrom, ab, bb);
            const float near = 3.0 / 255.0;
            // Only what the key could really have drawn: the colour covering a share c of a ground no
            // brighter than white, so a = c and b = (1 - c) · ground — never more than the whole, a and
            // b together no more than one — and either on black at a quarter of its strength or more
            // (every shade the show draws of either is; less is an edge nobody sees), or covering most
            // of a lighter ground. A sky, a light bluish concrete or a cloud in a photograph comes
            // out as more colour than there is, or a little over a lot of grey, and is left alone.
            bool redOk = er < near && ar + br < 1.03 && (br < 0.004 ? ar > 0.25 : ar > 0.8);
            bool blueOk = eb < near && ab + bb < 1.03 && (bb < 0.004 ? ab > 0.25 : ab > 0.8);
            if (redOk && (!blueOk || er <= eb)) x_fill.rgb = ar * p_redTo + vec3(br);
            else if (blueOk) x_fill.rgb = ab * p_blueTo + vec3(bb);
        """.trimIndent()
    }

    /** [source] with the colours the run was built in mapped onto the organizer's, or [source] itself while they agree. */
    fun apply(drawer: Drawer, source: ColorBuffer): ColorBuffer {
        if (!Brand.previewing) return source
        val t = target ?: renderTarget(width, height) { colorBuffer() }.also {
            it.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            it.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
            target = it
        }
        style.parameter("redFrom", linear(Brand.redHex))
        style.parameter("blueFrom", linear(Brand.blueHex))
        style.parameter("redTo", linear(Brand.shownRedHex ?: Brand.redHex))
        style.parameter("blueTo", linear(Brand.shownBlueHex ?: Brand.blueHex))
        drawer.isolatedWithTarget(t) {
            drawer.ortho(t)
            drawer.clear(ColorRGBa.BLACK)
            drawer.shadeStyle = style
            drawer.image(source, 0.0, 0.0, width.toDouble(), height.toDouble())
        }
        t.colorBuffer(0).generateMipmaps()
        return t.colorBuffer(0)
    }

    /** Six hex digits as linear light, stated so, since the canvas is sampled as linear light. */
    private fun linear(hex: String): Vector3 = ColorRGBa.fromHex(hex).toLinear().let { Vector3(it.r, it.g, it.b) }
}
