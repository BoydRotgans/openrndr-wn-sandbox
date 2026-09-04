import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import java.io.File
import kotlin.random.Random

/**
 * handover — two sets of crosses taking the floor from one another, for ever.
 *
 *     ./gradlew run -Popenrndr.application=HandoverKt
 *
 * One set is white on black paper, the other black on white, and neither ever fades into
 * the other: they take turns. A wave crosses the lattice; the set whose turn it is rotates
 * a quarter, comes to rest, and hands over. Because a cross has four-fold symmetry, a
 * quarter turn puts it back exactly where it started — so the wave can pass through it and
 * leave no trace of having been there, and the piece has no beginning or end.
 *
 * The shape is the plus of five squares, and everything rests on the two sets **tiling the
 * plane exactly**: at the moment of the handover, when both are square to the grid, white
 * crosses on black paper and black crosses on white paper are then the very same picture,
 * and the polarity can be thrown without anything moving.
 *
 * That only holds for the right lattice, and it is easy to pick the wrong one. Step by
 * `sqrt 10` on a lattice turned by **`atan 1/3`** — the lattice on `(3,1)` and `(-1,3)` —
 * and the second set, offset by `(1,2)`, interleaves with the first perfectly: one cross per
 * five units of area, which is exactly the area of a cross. Turned by `atan 3` instead, the
 * lattice on `(1,3)` and `(-3,1)`, it looks much the same and is not a tiling at all: a
 * fifth of the plane is covered twice and a fifth not at all, and the handover then cuts a
 * straight seam clean through the crosses that happen to straddle it.
 *
 * The wave is a ramp over the lattice rather than over the canvas — `u.y + slope*u.x`, in
 * lattice steps — so it advances cross by cross instead of sweeping a straight edge across
 * the paper, and the diagonal band it makes is built out of the tiling rather than laid over
 * it. The two sets run half a period apart, so one is always at rest while the other turns.
 *
 * **It is a shader on a single rectangle**, which is what the inversion needs: the paper is
 * white in one part of the frame and black in another, and there is no join between them to
 * hide because every pixel decides its own from the same ramp that decides the turn.
 *
 * Time is periodic in `HANDOVER_PERIOD`, so a clip of exactly that length is a seamless loop.
 * `HANDOVER_SAVE=t` writes the frame at `t` seconds and quits.
 *
 * The construction is the one in Cameron Priestman's piece, by way of the Shadertoy
 * reproduction the brief came with.
 */
fun main() = application {
    val canvasWidth = Env["HANDOVER_WIDTH"]?.toDoubleOrNull() ?: 1080.0
    val canvasHeight = Env["HANDOVER_HEIGHT"]?.toDoubleOrNull() ?: 1080.0
    val windowScale = Env["HANDOVER_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.75

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
    }

    program {
        val paper = ColorRGBa.fromHex(Env["HANDOVER_PAPER"] ?: DEFAULT_PAPER)
        val ink = ColorRGBa.fromHex(Env["HANDOVER_INK"] ?: DEFAULT_INK)

        /** How many units the frame is high. A cross arm is one unit wide, three long. */
        val scale = Env["HANDOVER_SCALE"]?.toDoubleOrNull() ?: DEFAULT_SCALE

        /** Seconds for the whole cycle, and therefore the length of a seamless loop. */
        val period = Env["HANDOVER_PERIOD"]?.toDoubleOrNull() ?: DEFAULT_PERIOD

        /** How much the wave leans as it crosses the lattice. 0 makes it run flat. */
        val slope = Env["HANDOVER_SLOPE"]?.toDoubleOrNull() ?: DEFAULT_SLOPE

        // What stands on the lattice. `none` keeps the cross the whole construction is built
        // on; a sheet stands one of its drawings there instead — see the note below on what
        // that costs, because a panel is neither four-fold symmetric nor a shape that tiles.
        val sheetName = Env["HANDOVER_SHEET"] ?: DEFAULT_SHEET
        val objects = if (sheetName.equals(NO_SHEET, true)) emptyList() else loadObjectSheet(File(sheetName))

        /** How much of the cross's box an object is allowed, so panels stay separate. */
        val fit = Env["HANDOVER_FIT"]?.toDoubleOrNull() ?: DEFAULT_FIT

        var pick = Env["HANDOVER_OBJECT"]?.toIntOrNull()
            ?: if (objects.isEmpty()) 0 else Random(Env["HANDOVER_SEED"]?.toIntOrNull() ?: 1).nextInt(objects.size)

        // How far a pass turns a tile. A quarter for the cross, because a quarter is the
        // cross's own symmetry and puts it back exactly where it was. A panel has no such
        // symmetry, so a whole turn is the only angle that leaves no trace — but a quarter
        // works too and reads differently: the turn is then kept rather than undone, and the
        // frame fills with bands of panels standing at 0, 90, 180 and 270 degrees. That
        // takes four passes to come back round, which is four periods of loop.
        val quarter = Env["HANDOVER_TURN"]?.toDoubleOrNull()
            ?: if (objects.isEmpty()) 90.0 else DEFAULT_TURN

        // The object is drawn once into the three-by-three box the cross occupies, and the
        // shader reads it back per pixel. Doing it as a mask rather than as geometry is what
        // keeps the whole frame a single rectangle with one shader on it.
        val mask = renderTarget(MASK, MASK) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        mask.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        mask.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        var drawn = -1
        fun paintMask(drawer: org.openrndr.draw.Drawer) {
            drawer.isolatedWithTarget(mask) {
                drawer.ortho(mask)
                drawer.clear(ColorRGBa.BLACK)
                drawer.stroke = null
                drawer.fill = ColorRGBa.WHITE
                if (objects.isNotEmpty()) {
                    // Inset inside the box the cross filled. A cross is only five ninths of
                    // its box and reaches its neighbours at the arm tips alone; a panel
                    // fitted to the whole box is solid to the corners, so at full size it
                    // meets the next one along the lattice and the two read as one mass.
                    val side = MASK * fit
                    val edge = (MASK - side) / 2.0
                    drawer.isolated {
                        objects[pick.mod(objects.size)]
                            .drawFitted(drawer, Rectangle(edge, edge, side, side))
                    }
                }
            }
            mask.colorBuffer(0).generateMipmaps()
            drawn = pick
            if (objects.isNotEmpty()) println("object ${pick.mod(objects.size)} of ${objects.size} from $sheetName")
        }

        keyboard.keyDown.listen { if (it.key == KEY_SPACEBAR && objects.isNotEmpty()) pick++ }

        val canvas = renderTarget(canvasWidth.toInt(), canvasHeight.toInt()) { colorBuffer() }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("HANDOVER_RECORD")) {
            extend(ScreenRecorder().apply {
                frameRate = Env["HANDOVER_FPS"]?.toIntOrNull() ?: 60
                contentScale = 1.0 / windowScale
                maximumDuration = Env["HANDOVER_DURATION"]?.toDoubleOrNull()
                    ?: (period * if (quarter < 180.0) LOOPS else 1.0)
            })
        }

        val save = Env["HANDOVER_SAVE"]?.toDoubleOrNull()

        extend {
            val now = save ?: seconds
            if (pick != drawn) paintMask(drawer)

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(paper)
                drawer.stroke = null
                drawer.shadeStyle = shadeStyle {
                    fragmentPreamble = PREAMBLE
                    fragmentTransform = TRANSFORM
                    parameter("size", Vector2(canvasWidth, canvasHeight))
                    parameter("scale", scale)
                    // the cycle is six long, so this is where the clock is put onto it
                    parameter("phase", now / period * 6.0)
                    parameter("slope", slope)
                    parameter("ink", ink)
                    parameter("paper", paper)
                    // an edge a pixel and a half wide, said in the units the crosses are in
                    parameter("aa", scale / canvasHeight * 1.5)
                    parameter("masked", if (objects.isEmpty()) 0.0 else 1.0)
                    parameter("quarter", Math.toRadians(quarter))
                    parameter("mask", mask.colorBuffer(0))
                }
                drawer.rectangle(0.0, 0.0, canvasWidth, canvasHeight)
                drawer.shadeStyle = null
            }

            canvas.colorBuffer(0).generateMipmaps()
            drawer.image(canvas.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())

            if (save != null) {
                val target = File("screenshots/handover-%05.1f.png".format(save))
                target.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(target)
                println("saved ${target.path}")
                application.exit()
            }
        }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * `K` and `TILT` are the plus tiling: step `sqrt 10` on a lattice turned by `atan 3`, which
 * is the lattice on `(1,3)` and `(-3,1)`. `cover` gives how much of the pixel one set's
 * cross covers, and hands back how far through its turn that cross is — which is what the
 * caller needs to know whose turn it is.
 *
 * A cross is `max(min(x,y), max(x,y) - 1) < 1/2` folded into the first quadrant: a bar three
 * long and one wide, crossed with itself.
 */
private val PREAMBLE = """
const float K = 3.16227766016838;      // sqrt(10)
const float TILT = 0.32175055439664;   // atan(1/3) — see the note on the lattice

vec2 turned(vec2 p, float a) {
    float c = cos(a), s = sin(a);
    return vec2(p.x * c + p.y * s, -p.x * s + p.y * c);
}

// The cross, said as a signed distance: a bar three long and one wide, crossed with itself.
float crossAt(vec2 q, float aa) {
    vec2 a = abs(q);
    return smoothstep(0.5 + aa, 0.5 - aa, max(min(a.x, a.y), max(a.x, a.y) - 1.0));
}

// An object off the sheet, drawn once into a mask and read back here. The mask holds the
// three-by-three box the cross occupies, so an object simply stands where the cross stood.
float maskAt(vec2 q, sampler2D mask) {
    vec2 uv = q / 3.0 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 0.0;
    return texture(mask, uv).r;
}

float cover(vec2 U, float set, float phase, float slope, float aa,
            float masked, float quarter, sampler2D mask, out float turn) {
    // the second set is the first one shifted, so the two interleave
    U += set * vec2(1.0, 2.0);

    // which tile of this set is nearest, in lattice steps
    vec2 u = floor(turned(U, TILT) / K + 0.5);

    // A ramp over the lattice, the two sets half a cycle apart: one turns while one rests.
    // `cycle` counts the turns already taken rather than throwing them away, so a shape with
    // no four-fold symmetry stays where the last wave left it instead of snapping back.
    float prog = phase - u.y - slope * u.x + 3.0 * set;
    float cycle = floor(prog / 6.0);
    turn = clamp(prog - cycle * 6.0 - 4.0, 0.0, 1.0);

    float a = (cycle + turn) * quarter;
    if (set > 0.0) a = -a;

    vec2 centre = turned(u * K, -TILT);
    vec2 q = turned(U - centre, a);
    return masked > 0.5 ? maskAt(q, mask) : crossAt(q, aa);
}
""".trimIndent()

/**
 * Whose turn it is decides the polarity outright, with no blend between them: while the
 * first set is turning it is white on black paper, and the moment it comes to rest the
 * second set takes over as black on white. That throw is invisible because both sets are
 * square to the grid at that moment and together they tile the plane, so the two pictures
 * are the same picture.
 */
private val TRANSFORM = """
vec2 res = p_size;
vec2 U = (c_boundsPosition.xy * res - res * 0.5) / res.y * p_scale;

float turn, other;
float first  = cover(U, 0.0, p_phase, p_slope, p_aa, p_masked, p_quarter, p_mask, turn);

vec4 colour;
if (p_masked > 0.5) {
    // A panel does not tile, so the two sets cannot stand in for one another and the
    // polarity has to hold still: both sets are ink on paper, and what the wave hands over
    // is the turn alone. Inverting here instead would put a field of marks on white against
    // a field of marks on black, meeting along one hard edge — see the note in the header.
    float second = cover(U, 1.0, p_phase, p_slope, p_aa, p_masked, p_quarter, p_mask, other);
    colour = mix(p_paper, p_ink, max(first, second));
} else if (turn <= 0.0) {
    float second = cover(U, 1.0, p_phase, p_slope, p_aa, p_masked, p_quarter, p_mask, other);
    colour = mix(p_paper, p_ink, second);
} else {
    colour = mix(p_ink, p_paper, first);
}

x_fill = colour;
""".trimIndent()

private const val DEFAULT_PAPER = "#F0EEE9"
private const val DEFAULT_INK = "#141414"

private const val DEFAULT_SHEET = "data/svg/subset.svg"
private const val NO_SHEET = "none"

/** Size of the mask the chosen object is drawn into. */
private const val MASK = 512

private const val DEFAULT_FIT = 0.85
private const val DEFAULT_TURN = 360.0

/** Passes it takes a quarter turn to come back round, and so the length of that loop. */
private const val LOOPS = 4.0

private const val DEFAULT_SCALE = 15.0
private const val DEFAULT_PERIOD = 24.0
private const val DEFAULT_SLOPE = 0.3
