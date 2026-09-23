import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BlendMode
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorType
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
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
import org.openrndr.math.transforms.buildTransform
import org.openrndr.math.transforms.lookAt as lookAtMatrix
import org.openrndr.math.transforms.ortho as orthoMatrix
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * The site in three dimensions: every mark of [Site] extruded from its own outline to a prism of
 * its own height, standing on the cell the mosaic gives it, under one sun with a real shadow map —
 * so a tall mark's shadow climbs the side of the one beside it and lies across its roof and the
 * street, the way the block city's do.
 *
 * World units are the site's pixels: x across, z down the wall (the site's y), y up.
 *
 * **With a [plan] it is a town**: the marks keep only to the street blocks, trees stand in the
 * parks, and the ground carries the roads, kerbs and lawns and takes the shadows. **The site is
 * laid edge to edge in every direction**, as many copies as the camera can see, so the city runs
 * off every side of the frame and there is never an edge of the world in shot. The plan wraps on
 * the tile, so the copies meet without a seam.
 *
 * A mark's height is worked out in the vertex shader from its box's circle — the Opening course's
 * own rule — so the whole site rises and sinks in one draw call a copy.
 */
class SiteCity(
    private val site: Site,
    private val plan: CityPlan? = null,
    private val groundMap: ColorBuffer? = null,
    /** A concrete texture, mipmapped so its average is its last level. */
    private val stoneMap: ColorBuffer? = null,
    /** Photographs in a grid atlas of [photoGrid] columns and rows, [photoCount] of them filled. */
    private val photos: ColorBuffer? = null,
    private val photoGrid: Int = 1,
    private val photoCount: Int = 0
) {

    /** Where the camera stands and where the sun is. All angles in degrees. */
    class View(
        val target: Vector2 = Vector2(1920.0, 540.0),
        /** Half the world width the frame shows across. 1920 is the whole site at pitch 90. */
        val half: Double = 1920.0,
        val yaw: Double = 0.0,
        val pitch: Double = 90.0,
        /** The direction the shadows fall across the site, 135 being down and to the left as in the mosaic. */
        val sunAngle: Double = 135.0,
        val sunElevation: Double = 22.0
    )

    /**
     * A flat paint for a block, three colours and nothing between them: its roof, the side facing
     * the sun, and whatever the sun does not reach — a side turned away or a face under a cast
     * shadow. [share] is how many of the blocks take it, relative to the other paints.
     */
    class Paint(
        val roof: ColorRGBa, val side: ColorRGBa, val shade: ColorRGBa, val share: Double = 1.0,
        /** A wall turned away from the sun; by default the same as [shade]. */
        val back: ColorRGBa = shade
    )

    /** How the marks stand and what they are drawn in. */
    class Look(
        /** Tallest mark, in site pixels. The mosaic's shadows read as a tower of 100. */
        val tower: Double = 100.0,
        /** false: every mark stands at [lift]; true: the boxes build and unbuild on the mosaic's cycle. */
        val cycle: Boolean = false,
        val lift: Double = 1.0,
        val roof: Double = 1.0,
        val litSide: Double = 0.62,
        val darkSide: Double = 0.34,
        val shadow: Double = 0.28,
        /** A mark lying flat, not yet built or taken down, as a share of its roof; below 0 it is not drawn. */
        val flat: Double = 0.18,
        val tint: ColorRGBa = ColorRGBa.WHITE,
        /** The greys the marks' own tones are spread between. */
        val toneLow: Double = 0.06,
        val toneHigh: Double = 0.85,
        /** Concrete worked into every face against its own average: how hard, and pixels a repeat. */
        val stone: Double = 0.0,
        val stoneScale: Double = 900.0,
        /** The share of buildings clad in a photograph off [SiteCity.photos], and the two tones it is printed in. */
        val photo: Double = 0.0,
        val photoDark: ColorRGBa = ColorRGBa.BLACK,
        val photoLight: ColorRGBa = ColorRGBa.WHITE,
        /** Laid edge to edge so the city fills the frame; off, the site stands alone on black. */
        val tiled: Boolean = true,
        /**
         * Flat colour instead of the greys: every block takes one of these paints, and the ground is
         * [groundLit] where the sun reaches it and [groundShade] under a shadow. Empty keeps the greys.
         */
        val paints: List<Paint> = emptyList(),
        val groundLit: ColorRGBa = ColorRGBa.WHITE,
        val groundShade: ColorRGBa = ColorRGBa.GRAY,
        /** The share of blocks that stand at all; the rest are left out, and the ground shows. */
        val keep: Double = 1.0,
        /** The ground's grey where there is no plan to read it from. */
        val groundTone: Double = 0.6,
        /**
         * Ambient occlusion: how much the ground darkens beside a block and a wall toward its foot.
         * 0 is none. [aoReach] is how far it spreads, in site pixels.
         */
        val ao: Double = 0.0,
        val aoReach: Double = 60.0,
        /** How much of its own cell a block's footprint takes, about its middle; below 1 the floor shows between. */
        val inset: Double = 1.0,
        /** Heights raised to this power: above 1 most blocks are low and a few stand tall. */
        val heightPower: Double = 1.0,
        /**
         * A block's height taken from its own footprint rather than from [tower]: between [propLow]
         * and [propHigh] of its shorter side, in three steps. The blocks come out squat and near cubic,
         * a field of volumes, where a height of its own on a narrow cell makes a skyline of towers.
         */
        val proportional: Boolean = false,
        val propLow: Double = 0.35,
        val propHigh: Double = 1.1
    )

    private val mesh: VertexBuffer = build()
    private val ground: VertexBuffer = vertexBuffer(format, 6).also { vb ->
        vb.put {
            val m = 60000.0
            listOf(Vector2(-m, -m), Vector2(m, -m), Vector2(m, m), Vector2(-m, -m), Vector2(m, m), Vector2(-m, m)).forEach {
                write(Vector3(it.x, -0.5, it.y)); write(Vector3.UNIT_Y); write(Vector4(0.0, 0.0, 0.0, 1.0)); write(Vector2.ZERO)
            }
        }
    }
    private val depth: RenderTarget = renderTarget(MAP, MAP) { colorBuffer(type = ColorType.FLOAT32); depthBuffer() }

    // The height map the occlusion reads: the site and a margin seen from straight above at a quarter
    // of its size, then blurred. The margin is filled from the neighbouring copies so it wraps.
    private val aoWidth = ((site.width + 2 * AO_MARGIN) / 4).toInt()
    private val aoHeight = ((site.height + 2 * AO_MARGIN) / 4).toInt()
    private val heights: RenderTarget = renderTarget(aoWidth, aoHeight) { colorBuffer(type = ColorType.FLOAT16); depthBuffer() }
    private val blurred = org.openrndr.draw.colorBuffer(aoWidth, aoHeight, type = ColorType.FLOAT16)
    private val blurredTwice = org.openrndr.draw.colorBuffer(aoWidth, aoHeight, type = ColorType.FLOAT16)
    private val blur = org.openrndr.extra.fx.blur.ApproximateGaussianBlur()
    // Lazy: `raise` is declared further down, and class properties initialise in order.
    private val heightStyle by lazy { shadeStyle {
        vertexTransform = raise
        fragmentTransform = "x_fill = vec4(max(v_worldPosition.y, 0.0) / p_tower, 0.0, 0.0, 1.0);"
    } }

    private fun occlusionMap(drawer: Drawer, look: Look, uniforms: (ShadeStyle) -> Unit): ColorBuffer {
        val cx = site.width / 2.0
        val cz = site.height / 2.0
        val hw = site.width / 2.0 + AO_MARGIN
        val hh = site.height / 2.0 + AO_MARGIN
        drawer.isolatedWithTarget(heights) {
            heights.clearColor(0, ColorRGBa.BLACK)
            heights.clearDepth(1.0, 0)
            drawer.projection = orthoMatrix(-hw, hw, -hh, hh, -20000.0, 20000.0)
            drawer.view = lookAtMatrix(Vector3(cx, 5000.0, cz), Vector3(cx, 0.0, cz), Vector3(0.0, 0.0, -1.0))
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            uniforms(heightStyle)
            drawer.shadeStyle = heightStyle
            for (i in -1..1) for (j in -1..1) {
                drawer.model = buildTransform { translate(i * site.width, 0.0, j * site.height) }
                drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
            }
        }
        // Quarter size, so a pixel of the map is four of the site.
        val sigma = (look.aoReach / 4.0).coerceIn(1.0, 40.0)
        blur.window = (sigma * 2.5).toInt().coerceIn(2, 25)
        blur.sigma = sigma
        blur.apply(heights.colorBuffer(0), blurred)
        blur.apply(blurred, blurredTwice)
        return blurredTwice
    }

    private val boxes: Array<Vector4> = Array(48) { i ->
        site.boxes.getOrNull(i)?.let { Vector4(it.rect.center.x, it.rect.center.y, site.reach(i), it.delay * site.period) } ?: Vector4.ZERO
    }

    // va_leaf: x tone, y height share, z box, w kind — 0 a mark, 1 the ground, 2 a tree (always standing).
    private val rise = """
        float h = 1.0;
        if (va_leaf.w < 0.5) {
            if (p_cycle == 0) {
                h = p_lift;
            } else {
                vec4 b = p_boxes[int(va_leaf.z + 0.5)];
                float t = p_time - b.w;
                t = t < 0.0 ? p_period - 0.001 : mod(t, p_period);
                float d = distance(va_origin, b.xy);
                if (t < p_grow + p_hold) {
                    float u = clamp(t / p_grow, 0.0, 1.0);
                    float r = b.z * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u));
                    h = smoothstep(0.0, 1.0, (r - d) / p_band + 0.5);
                } else {
                    float u = clamp((t - p_grow - p_hold) / p_grow, 0.0, 1.0);
                    float r = b.z * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u));
                    h = 1.0 - smoothstep(0.0, 1.0, (r - d) / p_band + 0.5);
                }
            }
        }
    """

    private val raise = """
        $rise
        // Only a share of the blocks stand at all, picked by where they stand: a sparse town.
        if (va_leaf.w < 0.5 && p_keep < 1.0 && fract(sin(dot(va_origin * 0.021, vec2(12.9898, 78.233))) * 43758.5453) > p_keep) h = 0.0;
        if (va_leaf.w < 0.5) {
            // leaf.y carries the footprint's short side in its whole part and the height share in its fraction.
            float share = fract(va_leaf.y);
            float tall = p_proportional == 1
                ? floor(va_leaf.y) * p_inset * mix(p_propLow, p_propHigh, floor(share * 2.999) / 2.0)
                : pow(share, p_heightPower) * p_tower;
            x_position.y *= tall * h;
            // Each block drawn in to a share of its own cell, which opens lanes of floor between them.
            x_position.xz = va_origin + (x_position.xz - va_origin) * p_inset;
        }
        else if (va_leaf.w > 1.5) x_position.y *= va_leaf.y;       // a tree's height is in pixels, not a share of the tower
    """

    private val depthStyle = shadeStyle {
        vertexTransform = raise
        fragmentTransform = """
            vec4 lp = p_lightVP * vec4(v_worldPosition, 1.0);
            x_fill = vec4(lp.z, 0.0, 0.0, 1.0);
        """
    }

    private val faceStyle = shadeStyle {
        vertexPreamble = "out float vRise;"
        vertexTransform = "$raise\nvRise = h;"
        fragmentPreamble = """
            in float vRise;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
            // Where on its face a point lies, in pixels: a roof or the ground by x and z, a wall along itself and up.
            vec2 faceUv(vec3 wp, vec3 n) {
                if (n.y > 0.5) return wp.xz;
                vec2 along = normalize(vec2(-n.z, n.x));
                return vec2(dot(wp.xz, along), -wp.y);
            }
            // How much stands around a point, 0..1 of the tower, off the blurred height map; the map
            // covers the site and a margin, so a point is folded onto the tile first.
            float around(vec3 wp) {
                vec2 q = vec2(mod(wp.x, p_site.x), mod(wp.z, p_site.y));
                vec2 uv = vec2((q.x + p_aoMargin) / (p_site.x + 2.0 * p_aoMargin), 1.0 - (q.y + p_aoMargin) / (p_site.y + 2.0 * p_aoMargin));
                return texture(p_aoMap, uv).r;
            }
            // The occlusion at a point of height y: what stands around it above it, and for a wall how
            // near its foot it is.
            float occlusion(vec3 wp, bool wall) {
                if (p_ao <= 0.0) return 1.0;
                float y = wp.y / p_tower;
                float over = max(0.0, around(wp) - y) * 1.6;
                float foot = wall ? (1.0 - smoothstep(0.0, p_aoReach, wp.y)) * 0.55 : 0.0;
                return 1.0 - p_ao * clamp(over + foot, 0.0, 1.0);
            }
            float stone(vec3 wp, vec3 n) {
                if (p_stone <= 0.0) return 1.0;
                float s = texture(p_stoneMap, faceUv(wp, n) / p_stoneScale).r;
                float mean = textureLod(p_stoneMap, vec2(0.5), 20.0).r;
                return mix(1.0, s / max(mean, 0.05), p_stone);
            }
            float lit(vec3 wp) {
                vec4 lp = p_lightVP * vec4(wp, 1.0);
                vec2 uv = lp.xy * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return 1.0;
                float sum = 0.0;
                vec2 texel = vec2(1.0 / p_map);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    sum += lp.z - p_bias > texture(p_depth, uv + vec2(i, j) * texel).r ? 0.0 : 1.0;
                return sum / 9.0;
            }
        """
        fragmentTransform = """
            vec3 L = normalize(p_toSun);
            if (p_paints > 0) {
                // Flat colour: one of three a block, the ground lit or shaded, and nothing between.
                vec3 n = normalize(v_worldNormal);
                if (n.y < 0.5 && dot(n, normalize(p_eye)) < 0.0) n = -n;
                float facing = dot(n, L);
                float sun = facing > 0.0 ? lit(v_worldPosition + n * 1.5) : 0.0;
                if (va_leaf.w > 0.5 && va_leaf.w < 1.5) {
                    x_fill = vec4(mix(p_groundShade.rgb, p_groundLit.rgb, step(0.5, sun)) * occlusion(v_worldPosition, false), 1.0);
                } else {
                    if (vRise < 0.02) discard;
                    float pick = hash(va_origin * 0.017 + 3.0);
                    int k = p_paints - 1;
                    for (int i = 0; i < 4; i++) { if (i < p_paints && pick < p_paintShare[i]) { k = i; break; } }
                    vec3 bright = n.y > 0.5 ? p_paintRoof[k].rgb : p_paintSide[k].rgb;
                    // A wall turned from the sun takes its own colour; a face the sun could reach but a
                    // neighbour hides takes the cast shadow's.
                    vec3 c = (n.y < 0.5 && facing <= 0.0) ? p_paintBack[k].rgb : mix(p_paintShade[k].rgb, bright, step(0.5, sun));
                    x_fill = vec4(c * occlusion(v_worldPosition, n.y < 0.5), 1.0);
                }
            } else if (va_leaf.w > 0.5 && va_leaf.w < 1.5) {
                // The ground: the plan's tone for a road, a kerb, a lawn or a plot, under the shadows.
                vec2 uv = fract(v_worldPosition.xz / p_site);
                float g = p_hasPlan == 1 ? texture(p_plan, vec2(uv.x, 1.0 - uv.y)).r : p_groundTone;
                float sun = lit(v_worldPosition + vec3(0.0, 1.5, 0.0));
                x_fill = vec4(p_tint.rgb * g * mix(p_shadow, 1.0, sun) * stone(v_worldPosition, vec3(0.0, 1.0, 0.0)) * occlusion(v_worldPosition, false), 1.0);
            } else {
                vec3 n = normalize(v_worldNormal);
                vec3 toEye = normalize(p_eye);
                // Walls are two-sided: whichever side is seen is the side that faces out.
                if (n.y < 0.5 && dot(n, toEye) < 0.0) n = -n;
                float facing = dot(n, L);
                float sun = facing > 0.0 ? lit(v_worldPosition + n * 1.5) : 0.0;
                float face = n.y > 0.5 ? p_roof : (facing > 0.0 ? p_litSide : p_darkSide);
                float shade = mix(p_shadow, 1.0, sun);
                if (n.y < 0.5 && facing <= 0.0) shade = 1.0;         // the side away from the sun keeps its own tone
                if (p_flat < 0.0 && vRise < 0.02) discard;
                float up = smoothstep(0.0, 0.25, vRise);
                float tone = mix(p_toneLow, p_toneHigh, clamp((va_leaf.x - 0.06) / 0.79, 0.0, 1.0));
                if (va_leaf.w > 1.5) tone = p_toneLow + (p_toneHigh - p_toneLow) * 0.35;
                vec3 base = vec3(tone);
                // A share of the buildings clad in a photograph, printed in two tones, a picture a building.
                float pick = hash(va_origin * 0.013);
                if (p_photo > 0.0 && va_leaf.w < 0.5 && pick < p_photo && p_photoCount > 0) {
                    int k = int(floor(hash(va_origin * 0.031 + 7.0) * float(p_photoCount)));
                    // The atlas was drawn y-down, so its first row is the texture's top and each picture's v runs down.
                    vec2 cell = vec2(float(k % p_photoGrid), float(p_photoGrid - 1 - k / p_photoGrid));
                    vec2 f = fract(faceUv(v_worldPosition, n) / 220.0 + hash(va_origin) * 3.0);
                    f.y = 1.0 - f.y;
                    vec3 pc = texture(p_photos, (cell + f) / float(p_photoGrid)).rgb;
                    float lum = dot(pc, vec3(0.299, 0.587, 0.114));
                    base = mix(p_photoDark.rgb, p_photoLight.rgb, smoothstep(0.08, 0.92, lum)) * 1.25;
                }
                float value = face * shade * mix(max(p_flat, 0.0), 1.0, up) * stone(v_worldPosition, n) * occlusion(v_worldPosition, n.y < 0.5);
                x_fill = vec4(p_tint.rgb * base * value, 1.0);
            }
        """
    }

    fun draw(drawer: Drawer, time: Double, view: View, look: Look) {
        val a = Math.toRadians(view.sunAngle)
        val e = Math.toRadians(view.sunElevation)
        val toSun = Vector3(-cos(a) * cos(e), sin(e), -sin(a) * cos(e)).normalized

        // The camera.
        val y = Math.toRadians(view.yaw)
        val p = Math.toRadians(view.pitch.coerceIn(1.0, 90.0))
        val offset = Vector3(sin(y) * cos(p), sin(p), cos(y) * cos(p))
        val up = Vector3(-sin(y) * sin(p), cos(p), -cos(y) * sin(p))
        val right = (offset * -1.0).cross(up).normalized
        val target = Vector3(view.target.x, 0.0, view.target.y)
        val aspect = drawer.width.toDouble() / drawer.height
        val hx = view.half
        val hy = view.half / aspect

        // What the frame sees of the world, between the ground and the tallest roof, and the stretch
        // toward the sun from which a roof could still throw a shadow into it.
        val tall = look.tower * 1.05
        val reach = tall / tan(e).coerceAtLeast(0.05)
        val seen = mutableListOf<Vector3>()
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
            val origin = target + offset * 6000.0 + right * (sx * hx) + up * (sy * hy)
            for (level in listOf(0.0, tall)) {
                val q = origin - offset * ((origin.y - level) / offset.y)
                seen += Vector3(q.x, 0.0, q.z)
                seen += Vector3(q.x, 0.0, q.z) + Vector3(toSun.x, 0.0, toSun.z).normalized * reach
            }
        }

        val centre = seen.fold(Vector3.ZERO) { s, v -> s + v } / seen.size.toDouble()
        val lightView = lookAtMatrix(centre + toSun * 8000.0, centre, if (view.sunElevation > 85.0) Vector3(0.0, 0.0, -1.0) else Vector3.UNIT_Y)
        val inLight = seen.flatMap { listOf(it, Vector3(it.x, tall, it.z)) }.map { (lightView * Vector4(it.x, it.y, it.z, 1.0)).xyz }
        val pad = 60.0
        val lightProjection = orthoMatrix(
            inLight.minOf { it.x } - pad, inLight.maxOf { it.x } + pad,
            inLight.minOf { it.y } - pad, inLight.maxOf { it.y } + pad,
            -inLight.maxOf { it.z } - 3000.0, -inLight.minOf { it.z } + 3000.0
        )
        val lightVP = lightProjection * lightView

        // Which copies of the site the frame and its shadows can reach.
        val copies: List<Vector2> = if (!look.tiled) listOf(Vector2.ZERO) else {
            val x0 = floor((seen.minOf { it.x } - tall) / site.width).toInt()
            val x1 = floor((seen.maxOf { it.x } + tall) / site.width).toInt()
            val z0 = floor((seen.minOf { it.z } - tall) / site.height).toInt()
            val z1 = floor((seen.maxOf { it.z } + tall) / site.height).toInt()
            (x0..x1).flatMap { i -> (z0..z1).map { j -> Vector2(i * site.width, j * site.height) } }
        }

        fun uniforms(style: ShadeStyle) {
            style.parameter("boxes", boxes)
            style.parameter("time", time)
            style.parameter("period", site.period)
            style.parameter("grow", site.grow)
            style.parameter("hold", site.hold)
            style.parameter("band", site.band)
            style.parameter("cycle", if (look.cycle) 1 else 0)
            style.parameter("lift", look.lift)
            style.parameter("keep", look.keep)
            style.parameter("inset", look.inset)
            style.parameter("heightPower", look.heightPower)
            style.parameter("proportional", if (look.proportional) 1 else 0)
            style.parameter("propLow", look.propLow)
            style.parameter("propHigh", look.propHigh)
            style.parameter("tower", look.tower)
            style.parameter("lightVP", lightVP)
        }
        fun city(d: Drawer) = copies.forEach { c ->
            d.model = buildTransform { translate(c.x, 0.0, c.y) }
            d.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
        }

        drawer.isolatedWithTarget(depth) {
            depth.clearColor(0, ColorRGBa(4.0, 0.0, 0.0, 1.0))
            depth.clearDepth(1.0, 0)
            drawer.projection = lightProjection
            drawer.view = lightView
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            uniforms(depthStyle)
            drawer.shadeStyle = depthStyle
            city(drawer)
        }

        drawer.isolated {
            drawer.projection = orthoMatrix(-hx, hx, -hy, hy, -20000.0, 20000.0)
            drawer.view = lookAtMatrix(target + offset * 6000.0, target, up)
            drawer.drawStyle.blendMode = BlendMode.REPLACE
            drawer.depthWrite = true
            drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
            uniforms(faceStyle)
            faceStyle.parameter("depth", depth.colorBuffer(0))
            faceStyle.parameter("map", MAP.toDouble())
            faceStyle.parameter("bias", 0.0012)
            faceStyle.parameter("eye", offset)
            faceStyle.parameter("toSun", toSun)
            faceStyle.parameter("roof", look.roof)
            faceStyle.parameter("litSide", look.litSide)
            faceStyle.parameter("darkSide", look.darkSide)
            faceStyle.parameter("shadow", look.shadow)
            faceStyle.parameter("flat", look.flat)
            faceStyle.parameter("toneLow", look.toneLow)
            faceStyle.parameter("toneHigh", look.toneHigh)
            faceStyle.parameter("stone", if (stoneMap != null) look.stone else 0.0)
            faceStyle.parameter("stoneScale", look.stoneScale)
            faceStyle.parameter("stoneMap", stoneMap ?: depth.colorBuffer(0))
            faceStyle.parameter("photo", if (photos != null) look.photo else 0.0)
            faceStyle.parameter("photos", photos ?: depth.colorBuffer(0))
            faceStyle.parameter("photoGrid", photoGrid)
            faceStyle.parameter("photoCount", photoCount)
            faceStyle.parameter("photoDark", look.photoDark)
            faceStyle.parameter("photoLight", look.photoLight)
            val paints = look.paints.take(4)
            val total = paints.sumOf { it.share }.coerceAtLeast(1e-9)
            var running = 0.0
            faceStyle.parameter("paints", paints.size)
            val shares = DoubleArray(4) { i -> paints.getOrNull(i)?.let { running += it.share; running / total } ?: 2.0 }
            faceStyle.parameter("paintShare", Vector4(shares[0], shares[1], shares[2], shares[3]))
            faceStyle.parameter("paintRoof", Array(4) { i -> paints.getOrNull(i)?.roof ?: ColorRGBa.BLACK })
            faceStyle.parameter("paintSide", Array(4) { i -> paints.getOrNull(i)?.side ?: ColorRGBa.BLACK })
            faceStyle.parameter("paintShade", Array(4) { i -> paints.getOrNull(i)?.shade ?: ColorRGBa.BLACK })
            faceStyle.parameter("paintBack", Array(4) { i -> paints.getOrNull(i)?.back ?: ColorRGBa.BLACK })
            faceStyle.parameter("groundTone", look.groundTone)
            faceStyle.parameter("ao", look.ao)
            faceStyle.parameter("aoReach", look.aoReach)
            faceStyle.parameter("aoMargin", AO_MARGIN)
            faceStyle.parameter("aoMap", if (look.ao > 0.0) occlusionMap(drawer, look, ::uniforms) else depth.colorBuffer(0))
            faceStyle.parameter("groundLit", look.groundLit)
            faceStyle.parameter("groundShade", look.groundShade)
            faceStyle.parameter("tint", look.tint)
            faceStyle.parameter("site", Vector2(site.width, site.height))
            faceStyle.parameter("hasPlan", if (groundMap != null) 1 else 0)
            faceStyle.parameter("plan", groundMap ?: depth.colorBuffer(0))
            drawer.shadeStyle = faceStyle
            drawer.model = Matrix44.IDENTITY
            if (look.tiled || look.paints.isNotEmpty()) drawer.vertexBuffer(ground, DrawPrimitive.TRIANGLES)
            city(drawer)
        }
    }

    private fun build(): VertexBuffer {
        class V(val p: Vector3, val n: Vector3, val leaf: Vector4, val origin: Vector2)
        val out = ArrayList<V>(700_000)
        fun prism(shape: MarkShape, centre: Vector2, sx: Double, sy: Double, tag: Vector4) {
            fun at(q: Vector2) = Vector2(centre.x + q.x * sx, centre.y - q.y * sy)
            shape.triangles.forEach { q -> val w = at(q); out += V(Vector3(w.x, 1.0, w.y), Vector3.UNIT_Y, tag, centre) }
            val place = buildTransform { translate(centre); scale(sx, -sy) }
            shape.contours.forEach { c ->
                val pts = c.transform(place).adaptivePositions(0.5)
                if (pts.size < 2) return@forEach
                val ring = if (pts.first().distanceTo(pts.last()) < 1e-6) pts else pts + pts.first()
                for (k in 0 until ring.size - 1) {
                    val a = ring[k]; val b = ring[k + 1]
                    val d = b - a
                    if (d.length < 1e-6) continue
                    val n = Vector3(d.y, 0.0, -d.x).normalized
                    val a0 = Vector3(a.x, 0.0, a.y); val b0 = Vector3(b.x, 0.0, b.y)
                    val a1 = Vector3(a.x, 1.0, a.y); val b1 = Vector3(b.x, 1.0, b.y)
                    listOf(a0, b0, b1, a0, b1, a1).forEach { out += V(it, n, tag, centre) }
                }
            }
        }
        val kept = site.leaves.filter { it.mark >= 0 && (plan?.clear(it.rect) ?: true) }
        kept.forEach { leaf ->
            val m = site.marks[leaf.mark]
            val side = kotlin.math.floor(minOf(leaf.rect.width, leaf.rect.height))
            prism(m, leaf.centre, leaf.rect.width / m.aspect, leaf.rect.height,
                Vector4(leaf.tone, side + leaf.height.coerceIn(0.0, 0.999), leaf.box.toDouble(), 0.0))
        }
        plan?.trees?.forEach { (c, r, h) ->
            prism(plan.treeShape, c, r * 2.0, r * 2.0, Vector4(0.32, r * h, 0.0, 2.0))
        }
        return vertexBuffer(format, out.size).also { vb ->
            vb.put { out.forEach { write(it.p); write(it.n); write(it.leaf); write(it.origin) } }
        }.also {
            println("site city: ${kept.size} of ${site.leaves.size} marks, ${plan?.trees?.size ?: 0} trees, ${out.size / 3} triangles")
        }
    }

    private companion object {
        const val MAP = 4096
        const val AO_MARGIN = 400.0
        val format = vertexFormat {
            position(3)
            normal(3)
            attribute("leaf", VertexElementType.VECTOR4_FLOAT32)
            attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        }
    }
}
