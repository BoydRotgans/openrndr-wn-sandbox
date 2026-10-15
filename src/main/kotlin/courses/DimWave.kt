import org.openrndr.draw.ShadeStyle

/**
 * A soft dark wave going slowly through the coloured pieces, red and blue each in a rhythm of its own:
 * a broad gradient drifting across the frame that takes a piece's fill from full red or blue down toward
 * dark and back. Only the fill: the white edges are drawn over it and never dim.
 *
 * It is laid in frame space, so it passes through whatever the camera shows the same way from every
 * angle, and the two colours tell apart by the fill itself (red more red than blue). `<PREFIX>_DIM` is how
 * dark it goes at its darkest, 0 for none; `_DIM_SIZE` its wavelength in frame heights; `_DIM_RED` and
 * `_DIM_BLUE` the seconds each colour's wave takes to come round.
 */
class DimWave(private val prefix: String) {
    private fun number(k: String, default: Double) = Env["${prefix}_$k"]?.toDoubleOrNull() ?: default
    private val depth = number("DIM", 0.0).coerceIn(0.0, 1.0)
    private val size = number("DIM_SIZE", 1.6).coerceAtLeast(0.1)
    private val red = number("DIM_RED", 47.0).coerceAtLeast(1.0)
    private val blue = number("DIM_BLUE", 61.0).coerceAtLeast(1.0)

    /** Hands the wave its settings and the clock; `frameHeight` is the frame's height in pixels. */
    fun apply(style: ShadeStyle, time: Double, frameHeight: Double) {
        style.parameter("dimDepth", depth)
        style.parameter("dimSize", size)
        style.parameter("dimRed", red)
        style.parameter("dimBlue", blue)
        style.parameter("dimClock", time)
        style.parameter("dimHeight", frameHeight)
    }

    companion object {
        /** `vec3 dimWave(vec3 c)`, for a fragment preamble. */
        const val GLSL = """
            vec3 dimWave(vec3 c) {
                if (p_dimDepth <= 0.0) return c;
                vec2 q = gl_FragCoord.xy / p_dimHeight;
                bool red = c.r > c.b;
                // Each colour its own way across the frame and its own time round.
                vec2 dir = red ? normalize(vec2(1.0, 0.35)) : normalize(vec2(-0.8, 0.55));
                float period = red ? p_dimRed : p_dimBlue;
                float w = 0.5 + 0.5 * sin(6.2831853 * (dot(q, dir) / p_dimSize - p_dimClock / period));
                // Longer at full colour than at its darkest, and soft both ways.
                w = smoothstep(0.15, 1.0, w);
                return c * (1.0 - p_dimDepth * w);
            }
        """
    }
}
