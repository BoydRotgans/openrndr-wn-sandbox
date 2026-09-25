import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.buildTransform

/**
 * The Opening course, the second step: **flat, in fill.** The same site as the Aperitif, still
 * seen straight down and still without depth — but the marks are now solid. The Aperitif's drawing
 * stays on the wall as a faint ghost, and each box fills it in: as the box's circle reaches a mark
 * it grows out of its own middle into a flat plate of its own grey, the box stands full, and the
 * circle takes the plates back again, leaving the drawing. No light and no shadow yet; that is the
 * First course's.
 *
 *     ./gradlew run -Popenrndr.application=CourseOpeningKt
 *
 * `OPENING_GHOST` is how strongly the drawing stays under the fill, 0 for none.
 */
fun main() = runCourse("course-2-opening", preview = 40.0) { openingCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.openingCourse(): (Drawer, Double) -> Unit {
    val site = Site.load()
    val ghost = Env["OPENING_GHOST"]?.toDoubleOrNull() ?: 0.18

    // Every mark as triangles on its cell, each vertex carrying its mark's middle, box and grey, so
    // the shader grows the plate about its own middle by the Opening course's circle.
    val format = vertexFormat {
        position(3)
        attribute("leaf", VertexElementType.VECTOR4_FLOAT32)
        attribute("origin", VertexElementType.VECTOR2_FLOAT32)
    }
    val leaves = site.leaves.filter { it.mark >= 0 }
    val count = leaves.sumOf { site.marks[it.mark].triangles.size }
    val plates = vertexBuffer(format, count).also { vb ->
        vb.put {
            leaves.forEach { l ->
                val m = site.marks[l.mark]
                val sx = l.rect.width / m.aspect
                val sy = l.rect.height
                m.triangles.forEach { q ->
                    write(Vector3(l.centre.x + q.x * sx, l.centre.y - q.y * sy, 0.0))
                    write(Vector4(l.tone, 0.0, l.box.toDouble(), 0.0))
                    write(l.centre)
                }
            }
        }
    }
    val outlines = leaves.flatMap { l ->
        val m = site.marks[l.mark]
        val place = buildTransform { translate(l.centre); scale(l.rect.width / m.aspect, -l.rect.height) }
        m.contours.map { it.transform(place) }
    }
    val boxes = Array(48) { i ->
        site.boxes.getOrNull(i)?.let { Vector4(it.rect.center.x, it.rect.center.y, site.reach(i), it.delay * site.period) } ?: Vector4.ZERO
    }
    val fill = shadeStyle {
        vertexPreamble = "out float vUp;"
        vertexTransform = """
            vec4 b = p_boxes[int(va_leaf.z + 0.5)];
            float t = p_time - b.w;
            t = t < 0.0 ? p_period - 0.001 : mod(t, p_period);
            float d = distance(va_origin, b.xy);
            float h;
            if (t < p_grow + p_hold) {
                float u = clamp(t / p_grow, 0.0, 1.0);
                h = smoothstep(0.0, 1.0, (b.z * (1.0 - pow(1.0 - u, 3.0)) - d) / p_band + 0.5);
            } else {
                float u = clamp((t - p_grow - p_hold) / p_grow, 0.0, 1.0);
                h = 1.0 - smoothstep(0.0, 1.0, (b.z * (1.0 - pow(1.0 - u, 3.0)) - d) / p_band + 0.5);
            }
            // A plate grows out of its own middle, so the fill reads as the mark being placed.
            float g = smoothstep(0.0, 1.0, h);
            x_position.xy = va_origin + (x_position.xy - va_origin) * g;
            vUp = g;
        """
        fragmentPreamble = "in float vUp;"
        fragmentTransform = """
            if (vUp < 0.002) discard;
            float tone = mix(0.22, 0.92, clamp((va_leaf.x - 0.06) / 0.79, 0.0, 1.0));
            x_fill = vec4(vec3(tone), 1.0);
        """
    }

    return { drawer: Drawer, time: Double ->
        if (ghost > 0.0) {
            drawer.stroke = ColorRGBa.WHITE.opacify(ghost)
            drawer.strokeWeight = 1.2
            drawer.fill = null
            drawer.contours(outlines)
        }
        fill.parameter("boxes", boxes)
        fill.parameter("time", time)
        fill.parameter("period", site.period)
        fill.parameter("grow", site.grow)
        fill.parameter("hold", site.hold)
        fill.parameter("band", site.band)
        drawer.shadeStyle = fill
        drawer.vertexBuffer(plates, DrawPrimitive.TRIANGLES)
        drawer.shadeStyle = null
    }
}
