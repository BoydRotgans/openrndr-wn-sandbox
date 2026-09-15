package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.color.Linearity
import org.openrndr.draw.BlendMode
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.CullTestPass
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.WrapMode
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.lookAt
import org.openrndr.math.transforms.ortho
import slideshow.Backdrop
import slideshow.Cut
import slideshow.FPS
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * The block city: a field of cubic buildings in isometric, drawn as an architectural model —
 * thin dark outlines, some faces ruled into a square grid, white and grey planes, and one
 * hard-edged shadow thrown across everything from the upper left. The camera pans across it
 * slowly and sways a little either side of the isometric angle, for ever.
 *
 * **Everything is real geometry under a real light, and the drawing is made afterwards.** A
 * shear onto the floor — the yard's shadow — cannot do this picture, because here the shadows
 * are the subject and they fall on *other blocks*: a tower's shadow climbs the wall of the block
 * beside it and lies across its roof. So the city is rendered once from the light into a depth
 * map, and once from the camera, where every lit pixel asks the map whether something stands
 * between it and the sun. The map is read at four texels and blended, so a shadow's edge is one
 * texel wide and straight — hard, but not jagged.
 *
 * **The tones are graphic, not physical.** A face takes one of three values by which way it
 * points — roof, the side toward the light, the side away from it — and only the first two can
 * fall into cast shadow, which is the fourth value. The side away from the light is drawn in its
 * own mid grey and never darkened further, though physically it is in shadow too: that is the
 * illustrator's convention the reference follows, and it is what keeps the picture at four flat
 * tones rather than two.
 *
 * **The outlines are found on the picture, not drawn on the blocks.** A line drawn inside each
 * face along its own edges comes out double where two faces meet and single where a face meets
 * the ground, and a contact edge between two abutting roofs gets none. Instead the camera pass
 * writes each face's *identity* into a float buffer beside its tone, and a second pass draws a
 * line wherever the identity changes between neighbouring pixels — so every visible edge, crease,
 * silhouette and contact gets exactly one line of one width, and the shadows, which are not
 * edges of anything, get none. The grid ruled across a face is the one line drawn in the face
 * itself, off its own in-plane coordinates, at the same width.
 *
 * **The city is a tile on a torus, so the pan loops.** Blocks are packed into a [tile]-unit square
 * with wrap-around, and a block that reaches past the edge is drawn whole rather than cut, its
 * far end landing exactly where the next copy of the tile leaves that cell empty. The camera
 * moves one tile diagonally per [period] — one lattice vector, so the frame at the end of a period
 * is the frame at the start — and the sway and any turning are whole cycles of that period.
 * Everything is a function of the frame: pan, yaw, and the tiles in view.
 *
 * **The shadow map's window is fitted every frame to what is in view.**
 * The visible slab of world — the frame's corners dropped onto the ground and onto the tallest
 * roof — is carried into light space and the map covers exactly that footprint: any caster that
 * can shadow a visible point lies along the light ray through it, so nothing outside the
 * footprint matters. At 4096 texels over a wall showing thirty-odd units, a texel is under a
 * pixel.
 *
 * **A lens is what would make the buildings pass one another, and the show does not use one.**
 * Under the parallel projection every block crosses the screen at the same speed, so a pan is a
 * picture being dragged — no parallax is available at any speed, it being a property of the
 * projection. Under a [lens] the near ones sweep past the far ones and a lower [pitch] stands the
 * walls tall and lets them overlap, which reads as a camera standing in a city where this wall
 * wants a drawing of one; `pitch = 25.0, lens = 14.0, unit = 150.0, horizon = 0.55` is that
 * version, kept for a wall that wants it. [unit] holds at the ground point the camera looks at
 * either way, so a lens stands the eye back exactly as far as that needs — 29 units out and 12 up
 * at those numbers, which must be above the tallest roof, so stacks are capped under a lens and
 * kept under the parallel view. A lens also sees much farther, so the light's depth map is then
 * two — one fitted to the near part of the frame and one to all of it — and the grid on a face
 * fades out where its pitch falls under a few pixels, lines closer together than their own width
 * being a smear rather than a grid.
 *
 * **The sun can go round.** [sun] days a period turns the light through a full circle while
 * its elevation climbs from [dawn] to [noon] and back, so the day opens on long shadows lying
 * across half the city, tightens to short ones under a high sun, and lengthens again toward the
 * loop. The tones are keyed to the light rather than to the screen, so the lit side of the city
 * follows the sun round; and because a turning sun crosses every wall's plane twice a day, the
 * side tone crossfades over a narrow band of sun angle rather than snapping — with the fixed
 * light that band is never entered and the sides are pure tones.
 *
 * **Only back faces go into the shadow map.** A face that samples the map is always lit, so it
 * is never in the map, so it can never shadow itself — no bias to tune, no acne, no shadow standing
 * off its own caster. Blocks have no bottom, and do not need one: a ray that leaves a block through
 * its floor lands inside its own footprint, under it.
 */
/** A roof in the city, in lattice units: its footprint and the level of its top. */
class Roof(val x: Double, val z: Double, val w: Double, val d: Double, val top: Double)

/**
 * A catalogue piece standing in the city: its buffer (positions and normals, `ObjMesh`'s), where
 * its centre is in lattice units, its scale in units per normalised unit, its yaw, and whether it
 * takes the accent. Drawn through the city's own depth map and outline pass, so it shadows the
 * blocks and they shadow it.
 */
class CityPiece(val buffer: VertexBuffer, val centre: Vector3, val scale: Double, val yaw: Double, val accent: Boolean = true)

class BlockCity(
    override val name: String = "Blocks",
    /** Pixels one grid unit takes across the wall at the home angle, at the ground point the camera holds. */
    private val unit: Double = 120.0,
    /**
     * The camera's elevation in degrees. 35.264, `atan(1/sqrt 2)`, is the isometric angle that
     * foreshortens the three axes alike and is the reference's drawing; lower and the walls
     * stand taller, the roofs flatten, and the blocks overlap — more a city, less a diagram.
     */
    private val pitch: Double = 35.264,
    /**
     * The lens, as a vertical field of view in degrees, or 0 for the parallel projection. A
     * parallel projection cannot make one building pass another: every block moves across the
     * screen at the same speed and a pan is a picture being dragged. With a lens the near ones
     * sweep past the far ones, which is what a city going by looks like. [unit] still holds at
     * the ground point the camera looks at, so the zoom means the same under either, and the
     * camera stands back far enough for that to be so — a long lens stands well back and eases
     * the parallax, a wide one comes in close and its eye drops toward the roofs.
     */
    private val lens: Double = 0.0,
    /** The square the city repeats on, in units a side. Wide enough that no copy shows twice. */
    private val tile: Int = 64,
    /** Seconds the pan takes to cross one tile — the loop. */
    private val period: Double = 240.0,
    /** Degrees the yaw sways either side of isometric, and whole sways a period. 0 holds still. */
    private val sway: Double = 10.0,
    private val sways: Int = 1,
    /** Whole turns of the city a period, 0 for none. Turning shows the far sides of the blocks too. */
    private val turns: Int = 0,
    /**
     * The direction the light travels, in world units: +x is screen-right-down at the home
     * angle, −z screen-left-down, −y down. Toward +x and a little −z, the shadow of a block runs
     * to the right and slightly down the screen, lands on the walls that face screen-left, and is
     * about nine tenths as long as the block is tall. With [sun] on, only its azimuth is read:
     * it is where the light stands at dawn.
     */
    private val light: Vector3 = Vector3(2.0, -2.5, -1.0),
    /**
     * Days a period, 0 for a light that holds still at [light]. With a day the sun goes right
     * round — 360 degrees of azimuth, from [light]'s — and climbs from [dawn] to [noon] and back,
     * so the shadows open long, shorten toward the middle of the day and lengthen again. Whole
     * days, so the loop still closes.
     */
    private val sun: Int = 0,
    /** The sun's elevation at the start and the middle of a day, in degrees: 15 throws a shadow nearly four times the height, 60 a little over half. */
    private val dawn: Double = 15.0,
    private val noon: Double = 60.0,
    /** Share of the ground the blocks stand on. */
    private val density: Double = 0.78,
    /** The four flat tones: roofs, the side toward the light, the side away from it, and cast shadow. */
    private val top: ColorRGBa = grey(0.97),
    private val lit: ColorRGBa = grey(0.86),
    private val unlit: ColorRGBa = grey(0.55),
    /**
     * The unlit tone of the walls running the other way — the ones whose normal is along z
     * rather than x. Under the fixed light only one family is ever unlit, so this never shows
     * and the reference's three tones stand; under a turning sun there is a quarter of the day
     * with the light ahead of the camera and both families unlit, when the city went to one
     * flat grey with nothing but outlines telling a corner from a wall. A shade apart, the two
     * families keep the form through it.
     */
    private val unlitAcross: ColorRGBa = grey(0.62),
    private val shade: ColorRGBa = grey(0.24),
    /** The ground. */
    private val paper: ColorRGBa = grey(0.95),
    /** The outlines, and how dark a line is relative to the face it crosses, whichever is darker. */
    private val ink: ColorRGBa = grey(0.30),
    private val dim: Double = 0.45,
    /**
     * Line width in wall pixels. 0 draws no outlines at all; the picture is then flat shapes,
     * and the silhouettes are softened across their identity edges instead, since the lines
     * were what antialiased them.
     */
    private val line: Double = 1.6,
    /**
     * A colour for a share of the blocks — the house red among the navy — and that share.
     * Null leaves every block in the tones above. An accented block takes [accent] on its roof
     * and lit side, a shade of it away from the light, and a deeper shade under cast shadow,
     * so it stays a red block in shadow rather than vanishing into the navy.
     */
    private val accent: ColorRGBa? = null,
    private val accents: Double = 0.04,
    /** Pitch of a grid ruled over the ground, in units; 0 leaves it plain. */
    private val groundGrid: Double = 0.0,
    /**
     * Whether the faces are ruled into a square grid at all. False is the plain massing model —
     * see `CityBlock02`. The random numbers are drawn either way, so the city is the same city
     * ruled or plain.
     */
    private val ruled: Boolean = true,
    /** Shadow map texels a side. */
    private val shadowMap: Int = 4096,
    /** Where the ground point the camera holds sits on the wall, as a share of the height from the top. */
    private val horizon: Double = 0.5,
    private val seed: Int = 7,
    /** The wall this composes for; `draw` fits it to whatever it is given and re-allocates if that differs. */
    private val wall: Pair<Int, Int> = 3840 to 1080,
    /**
     * Catalogue pieces to stand in the city this frame, a function of the stage so a slide can
     * move them on a click. Empty for the plain city. They go through the same passes as the
     * blocks — the light's depth map, the camera's facts, the outline — and are tiled with it.
     */
    private val pieces: (Stage) -> List<CityPiece> = { emptyList() },
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    private var mesh: VertexBuffer? = null
    private var tallest = 1.0
    private var overhang = 0
    private var warned = false

    private lateinit var scene: RenderTarget
    /** The light's depth maps: one fitted to the near part of the frame, one to all of it. */
    private lateinit var near: RenderTarget
    private lateinit var far: RenderTarget
    private lateinit var depthOnly: ShadeStyle
    private lateinit var facing: ShadeStyle
    private lateinit var facingPiece: ShadeStyle
    private lateinit var outlines: ShadeStyle

    /** The roofs of the base tile, for anything that wants to stand on one. Filled at load. */
    var roofs: List<Roof> = emptyList()
        private set
    /** The camera's ground point this frame, in lattice units — where the frame is held. */
    var held: Vector3 = Vector3.ZERO
        private set

    // --- the city ----------------------------------------------------------- //

    /** A block on the lattice: its footprint, the level it stands on, its height, and a grid pitch per face. */
    private class Block(val x: Int, val z: Int, val w: Int, val d: Int, val base: Int, val height: Int, val grid: DoubleArray, val accent: Boolean)

    /**
     * The tile: blocks packed onto a [tile]-square torus from the seed, some with a smaller block
     * standing on them. Footprints and heights are drawn from tables weighted toward the small,
     * with a tail of towers; the grid is likelier on a tall block, since a ruled face is what
     * makes a tower read as a building rather than a post.
     */
    private fun generate(): List<Block> {
        val rnd = Random(seed)
        val cells = tile * tile
        val occupied = IntArray(cells) { -1 }
        val blocks = mutableListOf<Block>()
        var covered = 0
        var attempts = 0

        fun cell(x: Int, z: Int) = Math.floorMod(x, tile) + Math.floorMod(z, tile) * tile
        // Which blocks take the accent is decided off a stream of its own, keyed on the block,
        // so switching the accent on or off or changing its share lays out the same city.
        fun accented(index: Int) = accent != null && Random(seed * 7919 + index).nextDouble() < accents
        fun grids(height: Int): DoubleArray {
            val ruledHere = rnd.nextDouble() < (0.38 + 0.05 * height).coerceAtMost(0.9)
            val pitch = if (!ruledHere) 0.0 else if (rnd.nextDouble() < 0.22) 0.5 else 1.0
            // `ruled` is tested last so the draw happens either way and the seed lays out the
            // same city whether or not anything is ruled on it.
            return DoubleArray(5) { if (pitch > 0.0 && rnd.nextDouble() < 0.78 && ruled) pitch else 0.0 }
        }

        while (attempts < cells * 8 && covered < density * cells) {
            attempts++
            val w = rnd.pick(FOOTPRINT)
            val d = rnd.pick(FOOTPRINT)
            val x = rnd.nextInt(tile)
            val z = rnd.nextInt(tile)
            var free = true
            loop@ for (i in 0 until w) for (j in 0 until d) if (occupied[cell(x + i, z + j)] >= 0) { free = false; break@loop }
            if (!free) continue
            val id = blocks.size
            for (i in 0 until w) for (j in 0 until d) occupied[cell(x + i, z + j)] = id
            covered += w * d
            val h = rnd.pick(HEIGHT)
            blocks += Block(x, z, w, d, 0, h, grids(h), accented(id))

            // A smaller block standing on it, set in from at least one edge so it reads as a
            // step rather than as the same block drawn taller. Not on the towers: a stack on a
            // nine would stand thirteen, above the eye of the lens camera, and a roof passing
            // through the eye is a frame of the inside of a block.
            // The stack is dropped from a block already tall **only under a lens**, whose eye
            // stands at a height a thirteen-unit tower would reach through — and a roof passing
            // through the eye is a frame of the inside of a block. The parallel view has no eye
            // to poke and keeps every stack. The numbers are drawn either way, so the seed lays
            // out the same city whichever camera reads it.
            if (w * d >= 2 && rnd.nextDouble() < 0.4) {
                val w2 = 1 + rnd.nextInt(w)
                val d2 = 1 + rnd.nextInt(d)
                if (w2 < w || d2 < d) {
                    val x2 = x + rnd.nextInt(w - w2 + 1)
                    val z2 = z + rnd.nextInt(d - d2 + 1)
                    val h2 = rnd.pick(UPPER)
                    val g2 = grids(h + h2)
                    if (lens <= 0.0 || h <= 6) blocks += Block(x2, z2, w2, d2, h, h2, g2, accented(blocks.size))
                }
            }
        }
        println("blocks: ${blocks.size} on a ${tile}x$tile tile, %.0f%% covered".format(100.0 * covered / cells))
        return blocks
    }

    /**
     * The tile as one vertex buffer: five faces a block — no bottom — and the ground as one quad.
     * A face carries its normal, its own in-plane coordinates and extent (for the grid), its
     * grid pitch, and its identity: 0 for the ground, and one number per face of every block,
     * which is what the outline pass draws between.
     */
    private fun build(blocks: List<Block>): VertexBuffer {
        val faces = mutableListOf<FloatArray>()   // one flat record per vertex: 3+3+2+2+1+1+1

        fun quad(origin: Vector3, u: Vector3, v: Vector3, uLen: Double, vLen: Double, n: Vector3, grid: Double, id: Float, accent: Float = 0f) {
            val p0 = origin
            val p1 = origin + u * uLen
            val p2 = origin + u * uLen + v * vLen
            val p3 = origin + v * vLen
            fun vert(p: Vector3, a: Double, b: Double) = floatArrayOf(
                p.x.toFloat(), p.y.toFloat(), p.z.toFloat(),
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat(),
                a.toFloat(), b.toFloat(), uLen.toFloat(), vLen.toFloat(), grid.toFloat(), id, accent
            )
            faces += vert(p0, 0.0, 0.0); faces += vert(p1, uLen, 0.0); faces += vert(p2, uLen, vLen)
            faces += vert(p0, 0.0, 0.0); faces += vert(p2, uLen, vLen); faces += vert(p3, 0.0, vLen)
        }

        val t = tile.toDouble()
        quad(Vector3(0.0, 0.0, 0.0), Vector3.UNIT_Z, Vector3.UNIT_X, t, t, Vector3.UNIT_Y, groundGrid, 0f)

        for ((i, b) in blocks.withIndex()) {
            val x0 = b.x.toDouble(); val x1 = x0 + b.w
            val z0 = b.z.toDouble(); val z1 = z0 + b.d
            val y0 = b.base.toDouble(); val y1 = y0 + b.height
            val w = b.w.toDouble(); val d = b.d.toDouble(); val h = b.height.toDouble()
            val id = (1 + i * 5).toFloat()
            val red = if (b.accent) 1f else 0f
            // Each quad's u × v is its outward normal, so every face winds counter-clockwise
            // seen from outside and the passes can cull by side.
            quad(Vector3(x0, y1, z0), Vector3.UNIT_Z, Vector3.UNIT_X, d, w, Vector3.UNIT_Y, b.grid[0], id, red)          // roof
            quad(Vector3(x1, y0, z0), Vector3.UNIT_Y, Vector3.UNIT_Z, h, d, Vector3.UNIT_X, b.grid[1], id + 1, red)      // +x
            quad(Vector3(x0, y0, z0), Vector3.UNIT_Z, Vector3.UNIT_Y, d, h, -Vector3.UNIT_X, b.grid[2], id + 2, red)     // -x
            quad(Vector3(x0, y0, z1), Vector3.UNIT_X, Vector3.UNIT_Y, w, h, Vector3.UNIT_Z, b.grid[3], id + 3, red)      // +z
            quad(Vector3(x0, y0, z0), Vector3.UNIT_Y, Vector3.UNIT_X, h, w, -Vector3.UNIT_Z, b.grid[4], id + 4, red)     // -z
        }

        val buffer = vertexBuffer(FORMAT, faces.size)
        buffer.put {
            for (f in faces) {
                write(f[0], f[1], f[2]); write(f[3], f[4], f[5]); write(f[6], f[7]); write(f[8], f[9]); write(f[10]); write(f[11]); write(f[12])
            }
        }
        return buffer
    }

    // --- load --------------------------------------------------------------- //

    override fun load(program: Program) {
        val blocks = generate()
        mesh = build(blocks)
        roofs = blocks.map { Roof(it.x.toDouble(), it.z.toDouble(), it.w.toDouble(), it.d.toDouble(), (it.base + it.height).toDouble()) }
        tallest = blocks.maxOf { it.base + it.height }.toDouble()
        overhang = blocks.maxOf { max(it.x + it.w, it.z + it.d) - tile }.coerceAtLeast(0)

        near = depthTarget()
        far = depthTarget()
        scene = sceneTarget(wall.first, wall.second)

        // The light's view of the city: its depth along the light, written as a float so the
        // camera pass can compare against it with the same matrix.
        depthOnly = shadeStyle {
            fragmentTransform = """
                vec4 lp = p_lightVP * vec4(v_worldPosition, 1.0);
                x_fill = vec4(lp.z, 0.0, 0.0, 1.0);
            """.trimIndent()
        }

        // The camera's view: not a picture yet, but the facts the picture is made from — which
        // face this is, which tone it takes, how much of it is in shadow, and whether a grid
        // line crosses it. Packed into a float buffer's three colour channels, because the
        // driver premultiplies by alpha on the way out and an identity there would scale the rest.
        facing = shadeStyle {
            vertexPreamble = """
                out vec3 vNormal; out vec2 vLocal; out vec2 vSize; flat out float vGrid; flat out float vId; flat out float vAccent;
            """.trimIndent()
            vertexTransform = """
                vNormal = va_normal; vLocal = va_local; vSize = va_size; vGrid = va_grid; vId = va_id; vAccent = va_accent;
            """.trimIndent()
            fragmentPreamble = """
                in vec3 vNormal; in vec2 vLocal; in vec2 vSize; flat in float vGrid; flat in float vId; flat in float vAccent;

                // Four texels of a depth map, blended, so a shadow's edge is one texel wide and straight.
                float pcf(sampler2D map, vec2 suv, float z, float res, float bias) {
                    vec2 t = suv * res - 0.5;
                    vec2 f = fract(t);
                    vec2 b = (floor(t) + 0.5) / res;
                    vec2 px = vec2(1.0 / res, 0.0);
                    float s00 = step(texture(map, b).r + bias, z);
                    float s10 = step(texture(map, b + px.xy).r + bias, z);
                    float s01 = step(texture(map, b + px.yx).r + bias, z);
                    float s11 = step(texture(map, b + px.xx).r + bias, z);
                    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
                }
                bool inside(vec2 uv) { return all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))); }
            """.trimIndent()
            fragmentTransform = """
                vec3 n = normalize(vNormal);
                vec3 l = normalize(-p_light);
                float ndl = dot(n, l);

                // The tone by orientation: ground, roof, or a side — and a side is lit by how
                // squarely the sun's azimuth meets it. A fixed light stands well outside the
                // band, so the sides are pure tones; a turning sun crosses every wall's plane
                // twice a day, and the band is what turns that from a snap into a crossfade of
                // a few seconds.
                float side = 0.0;
                float ll = length(l.xz);
                if (ll > 1e-4) side = smoothstep(-0.15, 0.15, dot(n.xz, l.xz / ll));
                // A side is 2 + its lit fraction for a wall along x and 4 + it for one along z,
                // so the family is a whole step and reading it back cannot be confused with a
                // half-lit wall. The fraction is held just under one so a fully lit face does
                // not carry into the next whole number.
                float family = abs(n.z) > abs(n.x) ? 1.0 : 0.0;
                float tone = (vId < 0.5) ? 0.0 : (n.y > 0.5 ? 1.0 : 2.0 + 2.0 * family + min(side, 0.999));
                // An accented block is the same tone eight higher — a whole step above every
                // plain one, so the outline pass reads it off the top and takes the rest as is.
                if (vId > 0.5 && vAccent > 0.5) tone += 8.0;

                // Cast shadow, for the faces the light reaches: the near map where it covers
                // this point, the far one beyond it.
                float s = 0.0;
                if (ndl > 0.0) {
                    vec4 lp = p_nearVP * vec4(v_worldPosition, 1.0);
                    vec2 suv = lp.xy * 0.5 + 0.5;
                    if (inside(suv)) s = pcf(p_near, suv, lp.z, p_res, p_nearBias);
                    else {
                        lp = p_farVP * vec4(v_worldPosition, 1.0);
                        suv = lp.xy * 0.5 + 0.5;
                        if (inside(suv)) s = pcf(p_far, suv, lp.z, p_res, p_farBias);
                    }
                }

                // The grid ruled over the face, off its own coordinates, at the outline's width.
                // It fades out where its pitch falls under a few pixels — a far face under a lens
                // — because lines closer together than their own width are a smear, not a grid.
                float grid = 0.0;
                if (vGrid > 0.0) {
                    vec2 g = abs(fract(vLocal / vGrid + 0.5) - 0.5) * vGrid;
                    vec2 fw = max(fwidth(vLocal), vec2(1e-6));
                    float d = min(g.x / fw.x, g.y / fw.y);
                    float pitchPx = vGrid / max(fw.x, fw.y);
                    grid = clamp(p_line * 0.5 - d + 0.5, 0.0, 1.0) * smoothstep(4.0, 10.0, pitchPx);
                }

                // Identity and grid cover share red — the cover quantised under the identity —
                // the tone is green, with a side's lit fraction as its fraction, and the shadow
                // is blue. Nothing in alpha: the driver premultiplies by it on the way out.
                x_fill = vec4(vId * 1024.0 + floor(grid * 1023.0), tone, s, 1.0);
            """.trimIndent()
        }

        // A catalogue piece's facts: the same tone rule read off its world normal, its identity
        // and accent as uniforms rather than attributes — its buffer is `ObjMesh`'s, positions
        // and normals — and no grid. The shadow lookup is the blocks'.
        facingPiece = shadeStyle {
            fragmentPreamble = """
                float pcf(sampler2D map, vec2 suv, float z, float res, float bias) {
                    vec2 t = suv * res - 0.5;
                    vec2 f = fract(t);
                    vec2 b = (floor(t) + 0.5) / res;
                    vec2 px = vec2(1.0 / res, 0.0);
                    float s00 = step(texture(map, b).r + bias, z);
                    float s10 = step(texture(map, b + px.xy).r + bias, z);
                    float s01 = step(texture(map, b + px.yx).r + bias, z);
                    float s11 = step(texture(map, b + px.xx).r + bias, z);
                    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
                }
                bool inside(vec2 uv) { return all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))); }
            """.trimIndent()
            fragmentTransform = """
                vec3 n = normalize(v_worldNormal);
                if (!gl_FrontFacing) n = -n;
                vec3 l = normalize(-p_light);
                float ndl = dot(n, l);
                float side = 0.0;
                float ll = length(l.xz);
                if (ll > 1e-4) side = smoothstep(-0.15, 0.15, dot(n.xz, l.xz / ll));
                float family = abs(n.z) > abs(n.x) ? 1.0 : 0.0;
                float tone = n.y > 0.5 ? 1.0 : 2.0 + 2.0 * family + min(side, 0.999);
                if (p_accent > 0.5) tone += 8.0;
                float s = 0.0;
                if (ndl > 0.0) {
                    vec4 lp = p_nearVP * vec4(v_worldPosition, 1.0);
                    vec2 suv = lp.xy * 0.5 + 0.5;
                    if (inside(suv)) s = pcf(p_near, suv, lp.z, p_res, p_nearBias);
                    else {
                        lp = p_farVP * vec4(v_worldPosition, 1.0);
                        suv = lp.xy * 0.5 + 0.5;
                        if (inside(suv)) s = pcf(p_far, suv, lp.z, p_res, p_farBias);
                    }
                }
                x_fill = vec4(p_id * 1024.0, tone, s, 1.0);
            """.trimIndent()
        }

        // The drawing: the tones laid on from the facts, and a line wherever the identity
        // changes between a pixel and any neighbour within reach, as wide as asked.
        outlines = shadeStyle {
            fragmentPreamble = """
                // The colour a pixel's facts make: tone, lit fraction, shadow and grid, in the
                // wall's palette or the accent's.
                vec3 colourOf(vec4 facts) {
                    float id = floor(facts.r / 1024.0);
                    float grid = (facts.r - id * 1024.0) / 1023.0;
                    float tone = facts.g;
                    bool red = tone >= 8.0;
                    if (red) tone -= 8.0;
                    // The family is the whole step — 2 for a wall along x, 4 for one along z —
                    // and the lit fraction is what is left. Reading the family off a half were
                    // the two a single step apart is what an earlier `tone < 2.5` got wrong: a
                    // wall along x more than half lit took the other family's dark end.
                    vec3 dark = red ? (tone < 4.0 ? p_accentDark.rgb : p_accentAcross.rgb) : (tone < 4.0 ? p_unlit.rgb : p_unlitAcross.rgb);
                    vec3 roof = red ? p_accent.rgb : p_top.rgb;
                    vec3 lit = red ? p_accent.rgb : p_lit.rgb;
                    vec3 shade = red ? p_accentShade.rgb : p_shade.rgb;
                    float side = clamp(fract(tone) / 0.999, 0.0, 1.0);
                    vec3 base = tone < 0.5 ? p_paper.rgb : (tone < 1.5 ? roof : mix(dark, lit, side));
                    vec3 c = mix(base, shade, facts.b);
                    return mix(c, min(p_ink.rgb, c * p_dim), grid);
                }
            """.trimIndent()
            fragmentTransform = """
                vec2 uv = vec2(c_boundsPosition.x, 1.0 - c_boundsPosition.y);
                vec4 here = texture(p_scene, uv);
                float id = floor(here.r / 1024.0);
                vec3 c = colourOf(here);

                if (p_line > 0.0) {
                    float d = 1e9;
                    for (int j = -3; j <= 3; j++) {
                        for (int i = -3; i <= 3; i++) {
                            if (i == 0 && j == 0) continue;
                            float other = floor(texture(p_scene, uv + vec2(float(i), float(j)) * p_texel).r / 1024.0);
                            if (abs(other - id) > 0.5) d = min(d, length(vec2(float(i), float(j))));
                        }
                    }
                    float cover = clamp(p_line * 0.5 - d + 1.0, 0.0, 1.0);
                    c = mix(c, min(p_ink.rgb, c * p_dim), cover);
                } else {
                    // No line to cover the silhouettes, so they are softened here instead: a
                    // pixel on an identity edge takes a share of its differing neighbours'
                    // colour, the four beside it at full weight and the four corners at half.
                    // A straight edge becomes a two-pixel ramp and a staircase smooths; an
                    // edge between two faces of one colour is left exactly as it was.
                    vec3 acc = vec3(0.0);
                    float away = 0.0;
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            if (i == 0 && j == 0) continue;
                            float wgt = (i == 0 || j == 0) ? 1.0 : 0.5;
                            vec4 nb = texture(p_scene, uv + vec2(float(i), float(j)) * p_texel);
                            if (abs(floor(nb.r / 1024.0) - id) > 0.5) { acc += colourOf(nb) * wgt; away += wgt; }
                        }
                    }
                    c = (c * (6.0 - away) + acc) / 6.0;
                }
                x_fill = vec4(c, 1.0);
            """.trimIndent()
        }
    }

    private fun depthTarget(): RenderTarget = renderTarget(shadowMap, shadowMap) {
        colorBuffer(ColorFormat.R, ColorType.FLOAT32)
        depthBuffer()
    }.also {
        it.colorBuffer(0).apply {
            filterMin = MinifyingFilter.NEAREST; filterMag = MagnifyingFilter.NEAREST
            wrapU = WrapMode.CLAMP_TO_EDGE; wrapV = WrapMode.CLAMP_TO_EDGE
        }
    }

    private fun sceneTarget(w: Int, h: Int): RenderTarget = renderTarget(w, h) {
        colorBuffer(ColorFormat.RGBa, ColorType.FLOAT32)
        depthBuffer()
    }.also {
        it.colorBuffer(0).apply {
            filterMin = MinifyingFilter.NEAREST; filterMag = MagnifyingFilter.NEAREST
            wrapU = WrapMode.CLAMP_TO_EDGE; wrapV = WrapMode.CLAMP_TO_EDGE
        }
    }

    // --- draw --------------------------------------------------------------- //

    override fun draw(drawer: Drawer, stage: Stage) {
        val mesh = mesh ?: return
        val w = stage.width
        val h = stage.height
        if (scene.width != w.toInt() || scene.height != h.toInt()) {
            scene.colorBuffer(0).destroy(); scene.destroy()
            scene = sceneTarget(w.toInt(), h.toInt())
        }

        // Where the camera is this frame: one tile diagonally per period, the yaw a whole
        // number of sways and turns per period, so the loop closes. It is the *fraction* of
        // the period that places everything, and the camera stays inside the base tile while
        // the world wraps around it: the frame at the end of a period is then computed from
        // the very same numbers as the frame at its start, and comes out byte-identical.
        // Carrying the camera on across the tiles instead left it a last float bit away, and
        // an outline standing on a pixel boundary landed one column over.
        val t = stage.frame.toDouble() / FPS
        val phase = (t / period).let { it - floor(it) }
        val c = Vector3(tile * 0.5 + tile * phase, 0.0, tile * 0.5 - tile * phase)
        held = c
        val standing = pieces(stage)
        val yaw = Math.toRadians(45.0 + sway * sin(2.0 * PI * sways * phase) + 360.0 * turns * phase)
        val elev = Math.toRadians(pitch)
        val e = Vector3(cos(elev) * sin(yaw), sin(elev), cos(elev) * cos(yaw))
        val right = (-e).cross(Vector3.UNIT_Y).normalized
        val up = right.cross(-e).normalized

        // The frame in world units at the held ground point, [horizon] of the way down it. A
        // lens stands the eye back far enough that the frame is that size where it looks;
        // the parallel projection stands anywhere, so it stands well clear.
        val halfW = w / unit / 2.0
        val topEdge = h / unit * horizon
        val bottomEdge = -h / unit * (1.0 - horizon)
        val perspective = lens > 0.0
        val distance = if (perspective) (h / unit / 2.0) / tan(Math.toRadians(lens / 2.0)) else 400.0
        val eye = c + e * distance
        val view = lookAt(eye, c, Vector3.UNIT_Y)
        if (perspective && eye.y < tallest + 0.5 && !warned) {
            warned = true
            println("blocks: the eye stands %.1f units up and the tallest block is %.0f — a longer lens, a smaller unit or a steeper pitch keeps the camera above the roofs".format(eye.y, tallest))
        }

        /** A ray through the frame at ([sx], [sy]): from the eye under a lens, along the view otherwise. */
        fun ray(sx: Double, sy: Double): Pair<Vector3, Vector3> {
            val onFrame = c + right * sx + up * sy
            return if (perspective) eye to (onFrame - eye).normalized else onFrame to -e
        }

        /**
         * The slab of world in view, as the frame's corner rays meet the ground and the tallest
         * roof, no farther than [cap] along a ray — a ray that never comes down is taken to the
         * cap. Under a lens a ray starts at the eye and runs forward only, so one that has
         * already passed the roof plane stands at the eye; a parallel ray starts on the frame
         * plane and runs both ways, and clamping it at the frame lost the nearest shadows. The
         * convex hull of these points holds everything the frame can see between the planes.
         */
        fun region(cap: Double): List<Vector3> {
            val points = ArrayList<Vector3>(8)
            val nearest = if (perspective) 0.0 else -cap
            for (sx in doubleArrayOf(-halfW, halfW)) for (sy in doubleArrayOf(bottomEdge, topEdge)) {
                val (origin, dir) = ray(sx, sy)
                for (y in doubleArrayOf(0.0, tallest)) {
                    val k = if (dir.y < -1e-6) ((y - origin.y) / dir.y).coerceIn(nearest, cap) else cap
                    points += origin + dir * k
                }
            }
            return points
        }
        val farthest = 4.0 * tile
        val whole = region(farthest)
        val nearby = if (perspective) region(distance * 1.5) else whole

        // The depth range the camera needs, and the projection: an off-centre frustum whose
        // window at the eye's distance is exactly the frame above, or the parallel window.
        val nearPlane = if (perspective) 0.5 else 1.0
        val farPlane = if (perspective) whole.maxOf { (it - eye).length } + tallest + 2.0 else 800.0
        val projection = if (perspective) {
            val k = nearPlane / distance
            frustum(-halfW * k, halfW * k, bottomEdge * k, topEdge * k, nearPlane, farPlane)
        } else ortho(-halfW, halfW, bottomEdge, topEdge, nearPlane, farPlane)

        // The light: fixed, or a sun a whole number of days round per period, its azimuth
        // sweeping and its elevation a cosine between dawn and noon — low at the start of a
        // day, high in the middle, low again at its end.
        val ldir = if (sun > 0) {
            val day = 2.0 * PI * sun * phase
            val azimuth = atan2(light.z, light.x) + day
            val elevation = Math.toRadians(dawn.coerceAtLeast(3.0) + (noon - dawn.coerceAtLeast(3.0)) * (1.0 - cos(day)) / 2.0)
            Vector3(cos(elevation) * cos(azimuth), -sin(elevation), cos(elevation) * sin(azimuth))
        } else light.normalized
        val reach = tallest * sqrt(ldir.x * ldir.x + ldir.z * ldir.z) / -ldir.y
        val lightView = lookAt(c - ldir * 300.0, c, Vector3.UNIT_Y)
        val margin = 0.5

        /**
         * A depth map's window: the footprint of [points] in light space, exactly — any caster
         * that can shadow a visible point lies on the light ray through it — and a depth range
         * covering the whole slab over that footprint grown by the longest shadow, so every
         * caster up-light of it is in range. Returns the projection — the view is [lightView]
         * for both maps — and the bias, which is a few thousandths of a unit in that range.
         */
        fun window(points: List<Vector3>): Pair<Matrix44, Double> {
            var lx0 = 1e9; var lx1 = -1e9; var ly0 = 1e9; var ly1 = -1e9
            for (p in points) {
                val q = lightView * Vector4(p.x, p.y, p.z, 1.0)
                lx0 = min(lx0, q.x); lx1 = max(lx1, q.x); ly0 = min(ly0, q.y); ly1 = max(ly1, q.y)
            }
            val x0 = points.minOf { it.x } - reach; val x1 = points.maxOf { it.x } + reach
            val z0 = points.minOf { it.z } - reach; val z1 = points.maxOf { it.z } + reach
            var lz0 = 1e9; var lz1 = -1e9
            for (x in doubleArrayOf(x0, x1)) for (z in doubleArrayOf(z0, z1)) for (y in doubleArrayOf(0.0, tallest)) {
                val q = lightView * Vector4(x, y, z, 1.0)
                lz0 = min(lz0, q.z); lz1 = max(lz1, q.z)
            }
            val lightProjection = ortho(lx0 - margin, lx1 + margin, ly0 - margin, ly1 + margin, -lz1 - margin, -lz0 + margin)
            return lightProjection to 2.0 / (lz1 - lz0 + 2.0 * margin) * 0.004
        }
        val (nearProjection, nearBias) = window(nearby)
        val (farProjection, farBias) = if (perspective) window(whole) else nearProjection to nearBias
        val nearVP = nearProjection * lightView
        val farVP = farProjection * lightView

        // The copies of the tile that touch the whole footprint grown by the longest shadow,
        // allowing for a block reaching past its edge.
        val x0 = whole.minOf { it.x } - reach; val x1 = whole.maxOf { it.x } + reach
        val z0 = whole.minOf { it.z } - reach; val z1 = whole.maxOf { it.z } + reach
        val ti0 = floor((x0 - overhang) / tile).toInt(); val ti1 = floor(x1 / tile).toInt()
        val tj0 = floor((z0 - overhang) / tile).toInt(); val tj1 = floor(z1 / tile).toInt()
        fun tiles(drawer: Drawer) {
            for (i in ti0..ti1) for (j in tj0..tj1) {
                drawer.model = Matrix44.IDENTITY
                drawer.translate(i * tile.toDouble(), 0.0, j * tile.toDouble())
                drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
            }
        }
        /** The pieces, in every copy of the tile the blocks are drawn in, [each] called per piece before its draw. */
        fun standingPieces(drawer: Drawer, each: (Int, CityPiece) -> Unit) {
            if (standing.isEmpty()) return
            // Culled exactly as the blocks are: the depth map holds back faces only, so a lit
            // face is never in it and cannot shadow itself. Drawn unculled into the map, the
            // pieces came out striped with their own shadow.
            for (i in ti0..ti1) for (j in tj0..tj1) standing.forEachIndexed { k, p ->
                each(k, p)
                drawer.model = Matrix44.IDENTITY
                drawer.translate(p.centre + Vector3(i * tile.toDouble(), 0.0, j * tile.toDouble()))
                drawer.rotate(Vector3.UNIT_Y, Math.toDegrees(p.yaw))
                drawer.scale(p.scale)
                drawer.vertexBuffer(p.buffer, DrawPrimitive.TRIANGLES)
            }
        }

        // 1. The light's depth maps, back faces only.
        //
        // The projection and the view are set separately rather than handed over as one
        // product, so the driver forms `P * (V * M)` here exactly as the camera pass does.
        // Folded into `P * V` up front it is the same matrix in arithmetic and not in
        // floating point — matrix multiplication does not associate — and the depths came
        // out a last bit apart, which moved a shadow edge by a coverage step on 0.6% of the
        // wall. Invisible, and not the same picture.
        fun depthPass(target: RenderTarget, projection: Matrix44, view: Matrix44) = drawer.isolatedWithTarget(target) {
            target.clearColor(0, ColorRGBa(2.0, 0.0, 0.0, 1.0))
            target.clearDepth(1.0, 0)
            drawer.projection = projection
            drawer.view = view
            drawer.drawStyle.cullTestPass = CullTestPass.BACK
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.stroke = null
            depthOnly.parameter("lightVP", projection * view)
            drawer.shadeStyle = depthOnly
            tiles(drawer)
            standingPieces(drawer) { _, _ -> }
        }
        depthPass(near, nearProjection, lightView)
        if (perspective) depthPass(far, farProjection, lightView)

        // 2. The camera's facts, front faces only.
        drawer.isolatedWithTarget(scene) {
            scene.clearColor(0, ColorRGBa(0.0, 0.0, 0.0, 1.0))
            scene.clearDepth(1.0, 0)
            drawer.projection = projection
            drawer.view = view
            drawer.drawStyle.cullTestPass = CullTestPass.FRONT
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            drawer.stroke = null
            facing.parameter("nearVP", nearVP)
            facing.parameter("farVP", farVP)
            facing.parameter("light", ldir)
            facing.parameter("near", near.colorBuffer(0))
            facing.parameter("far", (if (perspective) far else near).colorBuffer(0))
            facing.parameter("res", shadowMap.toDouble())
            facing.parameter("nearBias", nearBias)
            facing.parameter("farBias", farBias)
            facing.parameter("line", line)
            drawer.shadeStyle = facing
            tiles(drawer)
            if (standing.isNotEmpty()) {
                facingPiece.parameter("nearVP", nearVP)
                facingPiece.parameter("farVP", farVP)
                facingPiece.parameter("light", ldir)
                facingPiece.parameter("near", near.colorBuffer(0))
                facingPiece.parameter("far", (if (perspective) far else near).colorBuffer(0))
                facingPiece.parameter("res", shadowMap.toDouble())
                facingPiece.parameter("nearBias", nearBias)
                facingPiece.parameter("farBias", farBias)
                drawer.shadeStyle = facingPiece
                val first = 1 + (roofs.size + 1) * 5
                standingPieces(drawer) { k, p ->
                    facingPiece.parameter("id", (first + k).toDouble())
                    facingPiece.parameter("accent", if (p.accent) 1.0 else 0.0)
                }
            }
        }

        // 3. The drawing.
        drawer.isolated {
            outlines.parameter("scene", scene.colorBuffer(0))
            outlines.parameter("texel", Vector2(1.0 / w, 1.0 / h))
            outlines.parameter("paper", paper)
            outlines.parameter("top", top)
            outlines.parameter("lit", lit)
            outlines.parameter("unlit", unlit)
            outlines.parameter("unlitAcross", unlitAcross)
            val red = accent ?: top
            outlines.parameter("accent", red)
            outlines.parameter("accentDark", red.shade(0.78))
            outlines.parameter("accentAcross", red.shade(0.88))
            outlines.parameter("accentShade", red.shade(0.55))
            outlines.parameter("shade", shade)
            outlines.parameter("ink", ink)
            outlines.parameter("dim", dim)
            outlines.parameter("line", line)
            drawer.shadeStyle = outlines
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            drawer.rectangle(stage.bounds)
            drawer.shadeStyle = null
        }
    }

    private companion object {
        val FORMAT = vertexFormat {
            position(3)
            normal(3)
            attribute("local", VertexElementType.VECTOR2_FLOAT32)
            attribute("size", VertexElementType.VECTOR2_FLOAT32)
            attribute("grid", VertexElementType.FLOAT32)
            attribute("id", VertexElementType.FLOAT32)
            attribute("accent", VertexElementType.FLOAT32)
        }

        /** Footprint sides, heights of blocks on the ground, and heights of blocks standing on another. */
        val FOOTPRINT = listOf(1 to 0.32, 2 to 0.36, 3 to 0.20, 4 to 0.08, 5 to 0.04)
        val HEIGHT = listOf(1 to 0.30, 2 to 0.32, 3 to 0.18, 4 to 0.10, 5 to 0.05, 7 to 0.03, 9 to 0.02)
        val UPPER = listOf(1 to 0.45, 2 to 0.35, 3 to 0.15, 4 to 0.05)

        /**
         * Stated as sRGB, which is what the wall shows. The plain constructor leaves a colour's
         * linearity unknown, and a uniform uploaded that way arrives sRGB-encoded — a 0.5 grey
         * came out on the wall at 0.74, and the shadow at nearly the tone of the unlit side.
         */
        fun grey(v: Double) = ColorRGBa(v, v, v, 1.0, Linearity.SRGB)

        /** An off-centre perspective projection, the window ([l], [r], [b], [t]) standing at [n]. */
        fun frustum(l: Double, r: Double, b: Double, t: Double, n: Double, f: Double) = Matrix44(
            2.0 * n / (r - l), 0.0, (r + l) / (r - l), 0.0,
            0.0, 2.0 * n / (t - b), (t + b) / (t - b), 0.0,
            0.0, 0.0, -(f + n) / (f - n), -2.0 * f * n / (f - n),
            0.0, 0.0, -1.0, 0.0
        )

        fun Random.pick(table: List<Pair<Int, Double>>): Int {
            var r = nextDouble() * table.sumOf { it.second }
            for ((value, weight) in table) { r -= weight; if (r <= 0.0) return value }
            return table.last().first
        }
    }
}
