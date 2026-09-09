// ============================================================================ //
//  No `package` declaration: this stands on ObjMesh, which lives in the default
//  package. It sits at the root beside ObjectFabric and ObjectMosaic rather than
//  in backdrop-drawers/, because it is no longer a backdrop's alone — the yard,
//  the gallery and the case study all draw through it, so there is one
//  implementation of the camera, the fit, the shear and the shadow count rather
//  than three that drift.
// ============================================================================ //

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ChannelMask
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.StencilOperation
import org.openrndr.draw.StencilTest
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.Rectangle
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A piece fitted to one height that holds at any angle: its scale, and its sweep, in piece heights. */
class IsoFitted(val mesh: ObjMesh, val scale: Double, val sweep: Double)

/**
 * A piece on the wall this frame: where its centre is, how big, how far it has turned, and
 * what it is drawn in.
 *
 * [tint] is null for a wall in one colour — the yard and the gallery — and the ink passed to
 * [IsoPieces.draw] stands for it. A collage gives every piece its own.
 */
class IsoPlaced(
    val mesh: ObjMesh, val centre: Vector3, val scale: Double, val angle: Double,
    val tint: ColorRGBa? = null,
    /**
     * Whether this piece is a window onto [IsoPieces.window]'s picture. One piece at a time,
     * as a rule: a picture showing through every piece on the wall is a texture, where showing
     * through one is that piece being *about* something.
     */
    val window: Boolean = false,
    /**
     * Whether this piece throws a shadow. False for one that has faded into the wall: there is
     * one stencil count for the whole wall, so a shadow cannot be faded piece by piece — it is
     * cast or it is not, and a piece dissolved to nothing must stop.
     */
    val casts: Boolean = true
)

/**
 * Catalogue pieces in the round, on a white ground, in one colour under a low sun.
 *
 * **Isometric, because it is the one view that shows a piece as a solid in one colour.** A
 * parallel projection keeps every piece the same size wherever it stands on the wall, and the
 * three faces at the corner are what tell a beam from a slab when both are flat red. Yaw 45,
 * pitch `atan(1/sqrt 2)` — the angles that foreshorten the axes alike, and the projection
 * `Objects` runs in.
 *
 * **A piece is fitted and spaced by what it reaches at any angle, not by what it shows at
 * one.** A 24 m beam turning on its centre sweeps a circle its own length across; measured at
 * its home angle it fits its slot and then swings through both neighbours. So its width is its
 * sweep, `2 · spinRadius`, the same whatever the angle, and its height is the most that sweep
 * can reach up the screen at this pitch — `halfHeight · cos(pitch) + spinRadius · sin(pitch)`,
 * a bound rather than a measurement, so a layout holds still while the pieces turn. The rule
 * `Objects` keeps for the same reason. The fit has to be on the *screen*: at this pitch a
 * piece's depth projects onto the screen's vertical as well as its height does, and a beam
 * lying away from the viewer, fitted by its height alone, came out 1016 px against the 540
 * asked for.
 *
 * **The shadow is a shear, sharp, and drawn before the pieces.** A parallel light on a
 * parallel camera makes laying a piece flat on its floor one matrix — `Objects`' poster
 * shadow. It is applied between the placement and the rotation, in world orientation, so the
 * shadow lies along the light however the piece has turned. Every shadow is drawn before any
 * piece, with no depth written, so a piece always stands on a shadow and never under one.
 *
 * **Shadows come in exactly two tones — one layer, and more than one — and that is a count
 * in the stencil buffer, not a blend.** A flattened piece laps itself wherever it doubles
 * back, so alpha would darken one shadow's own overlaps as if they were two. Each shadow is
 * drawn twice with the colour channels shut: the first pass sets a flag bit over its
 * footprint, the second increments the pixel wherever the flag is set — an increment clears
 * the flag and bumps the count in one operation, so a second fragment of the same shadow
 * finds no flag and counts nothing. Two fills across the wall then read the count back with
 * `EQUAL` and `NOT_EQUAL` under bit masks, which read the same whichever way round the
 * driver's comparison runs: an ordered test was tried and passed on the untouched wall too,
 * painting the whole thing black.
 *
 * **Only along the wall can a long shadow go and stay on it.** Toward the viewer it runs off
 * the bottom edge; away from the viewer it lies behind the piece, which covers it — measured,
 * 0% of the wall was shadow that way. So the light stands to one side.
 */
class IsoPieces(
    /**
     * How far a shadow leans per unit of height — the sun's height. 1 is 45 degrees; 5 is a
     * sun eleven degrees up and a shadow five times the height of whatever casts it. It is
     * the *world* height that casts, so a standing column throws a long one and a beam lying
     * down a short one.
     */
    private val slant: Double = 5.0,
    /**
     * Where the light stands, as an angle on the ground plane in degrees: 0 is world +x, 90
     * world +z. 135 leans the shadow to screen-left at this camera, −45 is the mirror.
     */
    private val light: Double = 135.0
) {
    val pitch: Double = atan(1.0 / sqrt(2.0))

    val eye: Vector3 = run {
        val a = Math.toRadians(45.0)
        Vector3(cos(pitch) * sin(a), sin(pitch), cos(pitch) * cos(a))
    }

    /** Screen-right in world space for a `lookAt(eye, 0, Y)`. */
    val right: Vector3 = (-eye).cross(Vector3.UNIT_Y).normalized

    /** Screen-up in world space. World Y projects onto it by `cos(pitch)`, not whole. */
    val up: Vector3 = right.cross((-eye).normalized).normalized

    private lateinit var flat: ShadeStyle
    private lateinit var windowed: ShadeStyle
    private var picture: ColorBuffer? = null

    /** Needs a GL context, so it is called from a slide's `load` rather than at construction. */
    fun load() {
        flat = shadeStyle { fragmentTransform = "x_fill = p_tint;" }

        // A piece as a **window onto a picture nailed to the wall**: the picture is sampled in
        // *wall pixels*, not in the piece's own space, so it does not slide when the piece
        // slides and two pieces side by side are cut from one image. The opening wall's trick
        // for cutting its pieces out of concrete — and the reason no unwrapping is needed,
        // since these meshes carry no texture coordinates at all.
        //
        // What comes through is a **duotone in the piece's own colour**: the picture's
        // luminance drives the value and the tint keeps the hue, so a piece reads as itself
        // with the project inside it rather than as a photograph in the shape of a piece.
        windowed = shadeStyle {
            fragmentTransform = """
                // gl_FragCoord counts up the screen and a loaded image already comes back the
                // way `drawer.image` draws it, so the two agree and no flip belongs here. One
                // was put in on the strength of the render-target rule under the chapter card,
                // where a target *is* drawn y-down — and it turned every photograph over.
                vec2 uv = (gl_FragCoord.xy - p_corner) / p_span;
                vec3 shot = texture(p_photo, uv).rgb;
                float lum = dot(shot, vec3(0.2126, 0.7152, 0.0722));
                vec3 duo = p_tint.rgb * (p_floor + (1.0 - p_floor) * lum * p_gain);
                x_fill = vec4(mix(p_tint.rgb, duo, p_blend), 1.0);
            """.trimIndent()
        }
    }

    /**
     * The picture the pieces are windows onto, and how far through it comes. [corner] and
     * [span] are where it lies on the wall in pixels, so an image can be fitted to cover.
     */
    fun window(photo: ColorBuffer?, corner: Vector2, span: Vector2, blend: Double) {
        picture = photo
        windowed.parameter("corner", corner)
        windowed.parameter("span", span)
        windowed.parameter("blend", blend)
        windowed.parameter("floor", 0.22)
        windowed.parameter("gain", 1.25)
        photo?.let { windowed.parameter("photo", it) }
    }

    /** [mesh] fitted to one piece height, held back where its sweep would pass [widest] piece heights. */
    fun fit(mesh: ObjMesh, widest: Double): IsoFitted {
        val across = 2.0 * mesh.spinRadius
        val tall = 2.0 * (mesh.halfHeight * cos(pitch) + mesh.spinRadius * sin(pitch))
        var scale = 1.0 / tall.coerceAtLeast(1e-6)
        if (across * scale > widest) scale = widest / across
        return IsoFitted(mesh, scale, across * scale)
    }

    /**
     * [fitted] standing on a ground plane — the one that crosses the screen at [floorScreen],
     * in wall pixels with the wall's centre at 0 — with its centre [x] across, one piece
     * height being [unit] pixels, turned by [angle].
     */
    fun standing(fitted: IsoFitted, x: Double, floorScreen: Double, unit: Double, angle: Double): IsoPlaced {
        val scale = fitted.scale * unit
        val g = floorScreen / up.y                       // the plane, world y = G
        return IsoPlaced(fitted.mesh, right * x + Vector3.UNIT_Y * (g + fitted.mesh.halfHeight * scale), scale, angle)
    }

    /**
     * Everything on the wall: the shadows counted and painted in two tones, then the pieces.
     *
     * [onTheGround] is drawn between the two, which is the only place anything can go: the
     * shadow fills are opaque sheets across the whole wall, so something drawn before them is
     * painted over, and something drawn after the pieces is painted over *them*. A photograph
     * blending in under the pieces goes here.
     */
    fun draw(
        drawer: Drawer, w: Double, h: Double, placed: List<IsoPlaced>,
        ink: ColorRGBa, shadow: ColorRGBa, deep: ColorRGBa,
        onTheGround: () -> Unit = {}
    ) {
        val lean = Math.toRadians(light)

        // 1. The stencil zeroed across the wall, so the count starts from nothing each frame.
        wall(drawer, w, h) {
            drawer.drawStyle.channelWriteMask = ChannelMask(false, false, false, false)
            drawer.drawStyle.stencil.stencilFunc(StencilTest.ALWAYS, 0, 0xff)
            drawer.drawStyle.stencil.stencilOp(StencilOperation.REPLACE, StencilOperation.REPLACE, StencilOperation.REPLACE)
            drawer.rectangle(Rectangle(0.0, 0.0, w, h))
        }

        // 2. Every shadow counted into the stencil, once per pixel per piece.
        view(drawer, w, h) {
            for (p in placed) if (p.casts) piece(drawer, p, ColorRGBa.BLACK, asShadow = true, lean = lean)
        }

        // 3. The count read back as two colours. The stencil holds twice the count, the flag
        // bit being the low one and clear by now: exactly one is 2 under a mask of 0xfe, and
        // two or more is any bit above the ones place under 0xfc.
        wall(drawer, w, h) {
            drawer.drawStyle.stencil.stencilOp(StencilOperation.KEEP, StencilOperation.KEEP, StencilOperation.KEEP)
            drawer.drawStyle.stencil.stencilTest = StencilTest.EQUAL
            drawer.drawStyle.stencil.stencilTestReference = 2
            drawer.drawStyle.stencil.stencilTestMask = 0xfe
            drawer.fill = shadow
            drawer.rectangle(Rectangle(0.0, 0.0, w, h))

            drawer.drawStyle.stencil.stencilTest = StencilTest.NOT_EQUAL
            drawer.drawStyle.stencil.stencilTestReference = 0
            drawer.drawStyle.stencil.stencilTestMask = 0xfc
            drawer.fill = deep
            drawer.rectangle(Rectangle(0.0, 0.0, w, h))
        }

        // 4. Anything laid on the ground, over the shadows and under the pieces.
        wall(drawer, w, h) {
            drawer.drawStyle.stencil.stencilTest = StencilTest.DISABLED
            drawer.fill = ColorRGBa.WHITE
            onTheGround()
        }

        // 5. The pieces, standing on their shadows.
        view(drawer, w, h) {
            for (p in placed) piece(drawer, p, p.tint ?: ink, asShadow = false, lean = lean)
        }
    }

    /** The iso camera: parallel, in wall pixels, so a wall width is a wall width on screen. */
    private fun view(drawer: Drawer, w: Double, h: Double, body: () -> Unit) = drawer.isolated {
        drawer.ortho(-w / 2.0, w / 2.0, -h / 2.0, h / 2.0, -20000.0, 20000.0)
        drawer.lookAt(eye * 5000.0, Vector3.ZERO, Vector3.UNIT_Y)
        drawer.shadeStyle = flat
        drawer.stroke = null
        body()
        drawer.shadeStyle = null
    }

    /** The wall as a flat sheet, for the stencil clear and the two fills. */
    private fun wall(drawer: Drawer, w: Double, h: Double, body: () -> Unit) = drawer.isolated {
        drawer.ortho()
        drawer.view = Matrix44.IDENTITY
        drawer.model = Matrix44.IDENTITY
        drawer.shadeStyle = null
        drawer.stroke = null
        drawer.depthWrite = false
        drawer.depthTestPass = DepthTestPass.ALWAYS
        body()
    }

    private fun piece(drawer: Drawer, p: IsoPlaced, tint: ColorRGBa, asShadow: Boolean, lean: Double) {
        drawer.isolated {
            // A shadow lies on one plane, so it needs no depth of its own and must not write
            // any — a piece drawn after it has to be free to stand on it.
            drawer.depthWrite = !asShadow
            drawer.depthTestPass = if (asShadow) DepthTestPass.ALWAYS else DepthTestPass.LESS_OR_EQUAL
            drawer.translate(p.centre)
            // The shear sits between the placement and the rotation: in world orientation, so
            // the shadow lies along the light however the piece has turned, and about the
            // piece's own bottom, which rotation about a vertical axis leaves where it is.
            if (asShadow) drawer.model = drawer.model * flattened(-p.mesh.halfHeight * p.scale, lean)
            drawer.rotate(Vector3.UNIT_Y, Math.toDegrees(p.angle))
            drawer.scale(p.scale)
            // Per piece, not per pass: only the one that asked for it is a window.
            val style = if (!asShadow && p.window && picture != null) windowed else flat
            drawer.shadeStyle = style
            style.parameter("tint", tint)

            if (!asShadow) {
                drawer.drawStyle.stencil.stencilTest = StencilTest.DISABLED
                drawer.vertexBuffer(p.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
                return@isolated
            }

            // Counted into the stencil rather than painted, once per pixel however many times
            // the flattened copy laps itself: a flag bit over the footprint, then an increment
            // only where the flag is set, which clears the flag as it counts.
            drawer.drawStyle.channelWriteMask = ChannelMask(false, false, false, false)
            drawer.drawStyle.stencil.stencilFunc(StencilTest.ALWAYS, 1, 0x01)
            drawer.drawStyle.stencil.stencilOp(StencilOperation.REPLACE, StencilOperation.REPLACE, StencilOperation.REPLACE)
            drawer.vertexBuffer(p.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)

            drawer.drawStyle.stencil.stencilWriteMask = 0xff
            drawer.drawStyle.stencil.stencilTest = StencilTest.EQUAL
            drawer.drawStyle.stencil.stencilTestReference = 1
            drawer.drawStyle.stencil.stencilTestMask = 0x01
            drawer.drawStyle.stencil.stencilOp(StencilOperation.KEEP, StencilOperation.KEEP, StencilOperation.INCREASE)
            drawer.vertexBuffer(p.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
        }
    }

    /**
     * Everything laid flat on the plane `y = floor`, leaning [slant] per unit of height in the
     * direction [lean]: the shear `Objects` casts its shadows with. `Objects` leans −x −z,
     * which at yaw 45 is straight away from the viewer and puts the whole shadow behind the
     * piece — measured, 0% of the wall was shadow.
     */
    private fun flattened(floor: Double, lean: Double): Matrix44 {
        val kx = slant * cos(lean)
        val kz = slant * sin(lean)
        return Matrix44(
            1.0, kx, 0.0, -kx * floor,
            0.0, 0.0, 0.0, floor,
            0.0, kz, 1.0, -kz * floor,
            0.0, 0.0, 0.0, 1.0
        )
    }
}
