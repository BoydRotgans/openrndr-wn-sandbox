import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import java.io.File

/**
 * The collected map coloured by what the buildings are *for*: every footprint painted by
 * its registered BAG function, over the roads, water and planting it sits in.
 *
 *     ./gradlew run -Popenrndr.application=MapViewKt
 *
 * Drag to pan, scroll to zoom, 0 resets the view, L hides the legend.
 *
 * This is a check on the data as much as a sketch — if the moat closes a ring around the
 * old town, the shops line the market square and the streets radiate out of it, then the
 * contours parsed correctly and the coordinates are being read as the metres they are.
 *
 * Like CityMap it triangulates once into vertex buffers rather than drawing shapes one at
 * a time. Colour is the only complication: a building has to be drawn in its function's
 * colour, so buildings are grouped by colour first and each group becomes its own mesh.
 * Eleven functions means eleven draw calls for 7839 buildings instead of 7839.
 *
 * MAP_VIEW_SAVE=true writes preview.png next to the layers and quits.
 */
fun main() = application {
    val canvasWidth = Env["MAP_VIEW_WIDTH"]?.toIntOrNull() ?: 1600
    val canvasHeight = Env["MAP_VIEW_HEIGHT"]?.toIntOrNull() ?: 1600
    val windowScale = Env["MAP_VIEW_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.55

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
    }

    program {
        val area = collectMapData()

        print("triangulating... ")
        val began = System.currentTimeMillis()

        // Buildings grouped by the colour they will be drawn in, so each colour is one
        // mesh and one draw call. The grouping is by colour rather than by function name
        // because several functions share the fallback grey and may as well share a call.
        val byColour = area.features("buildings")
            .groupBy { colourFor(it.functions()) }
            .mapValues { (_, features) -> meshOf(features.map { it.shapes() }, area.origin) }

        val roads = area.meshOfLayer("roads")
        val water = area.meshOfLayer("water")
        val nature = area.meshOfLayer("nature")

        val buildingCount = byColour.values.sumOf { it.parts }
        val vertices = (byColour.values.sumOf { it.vertexCount } +
                roads.vertexCount + water.vertexCount + nature.vertexCount)
        println("$buildingCount buildings in ${byColour.size} colours, " +
                "${roads.parts + water.parts + nature.parts} ground surfaces, " +
                "${vertices / 1000}k vertices in ${(System.currentTimeMillis() - began) / 1000.0}s")

        // The extent is square, and so is this canvas by default: a map wants to show the
        // whole of what was collected, so it fits rather than crops.
        val camera = MapCamera(area, canvasWidth, canvasHeight, cover = false,
            homeZoom = Env["MAP_VIEW_ZOOM"]?.toDoubleOrNull() ?: 1.0)

        mouse.scrolled.listen { camera.zoomAt(camera.pointer(it.position, width), it.rotation.y) }
        mouse.dragged.listen { camera.panBy(it.dragDisplacement * (canvasWidth / width.toDouble())) }

        var showLegend = true
        keyboard.keyDown.listen { event ->
            when {
                event.name == "0" -> camera.reset()
                event.name == "l" -> showLegend = !showLegend
                event.key == KEY_SPACEBAR -> camera.reset()
            }
        }

        // Type is sized off the canvas, not fixed: this draws at 1600px and gets shown
        // at whatever the window scale is, so a fixed 15pt was a tenth of the size it
        // looked in the editor. Everything below is measured in this one number.
        val type = Env["MAP_VIEW_TYPE"]?.toDoubleOrNull() ?: (canvasHeight / 62.0)
        val font = loadFont("data/fonts/default.otf", type)
        val canvas = renderTarget(canvasWidth, canvasHeight) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH_STENCIL)      // filled shapes are stencil clipped
        }

        fun drawMap(drawer: Drawer) {
            drawer.clear(BACKGROUND)
            drawer.stroke = null

            drawer.isolated {
                camera.apply(drawer)
                drawer.drawStyle.clip = camera.clip()

                nature.draw(drawer, NATURE)
                water.draw(drawer, WATER)
                roads.draw(drawer, ROAD)

                // One call per colour. Grey buildings — the ones with no registered unit —
                // go down first so the named functions read on top of them.
                byColour[NO_FUNCTION]?.draw(drawer, NO_FUNCTION)
                byColour.forEach { (colour, mesh) -> if (colour != NO_FUNCTION) mesh.draw(drawer, colour) }
            }
            drawer.drawStyle.clip = null

            // Type sits on its own ground rather than straight on the map: over a plan
            // this busy, dark text on a dark roof is simply not readable.
            // Type sits on its own ground rather than straight on the map: over a plan
            // this busy, dark text on a dark roof is simply not readable.
            val margin = type
            val row = type * 1.45
            val swatch = type * 0.8

            drawer.fontMap = font
            drawer.fill = PANEL
            drawer.rectangle(0.0, 0.0, canvasWidth.toDouble(), row * 2 + margin)
            drawer.fill = INK
            drawer.text("${area.place} - ${"%.0f".format(area.width)} x ${"%.0f".format(area.height)} m, " +
                    "RD New (EPSG:28992)", margin, margin + type * 0.4)
            drawer.text("$buildingCount buildings by registered function, ${area.registers()} via PDOK. " +
                    "Grey has no registered unit: sheds, garages, transformer huts.",
                margin, margin + row + type * 0.4)

            if (!showLegend) return
            val present = LEGEND.filterKeys { function -> byColour.containsKey(LEGEND[function]) }
            val labels = present.keys + "no registered unit"
            val panelHeight = labels.size * row + margin * 2
            // wide enough for the longest label, estimated from the type size rather than
            // measured: the panel only has to clear the text, not fit it exactly
            val panelWidth = margin * 2 + swatch + type * 0.7 +
                    labels.maxOf { it.length } * type * 0.58
            drawer.fill = PANEL
            drawer.rectangle(0.0, canvasHeight - panelHeight, panelWidth, panelHeight)

            val swatches = present.values.toList() + NO_FUNCTION
            labels.forEachIndexed { index, label ->
                val y = canvasHeight - panelHeight + margin + row * index + type * 0.8
                drawer.fill = swatches[index]
                drawer.rectangle(margin, y - swatch * 0.85, swatch, swatch)
                drawer.fill = INK
                drawer.text(label, margin + swatch + type * 0.7, y)
            }
        }

        val save = Env.boolean("MAP_VIEW_SAVE")
        val debug = Env.boolean("MAP_VIEW_DEBUG")
        var frames = 0
        var lastReport = 0.0

        extend {
            if (debug) {
                frames++
                if (seconds - lastReport > 2.0) {
                    println("%.0f fps at zoom %.1fx".format(frames / (seconds - lastReport), camera.zoom))
                    frames = 0; lastReport = seconds
                }
            }

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawMap(drawer)
            }
            drawer.image(canvas.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())

            if (save) {
                val target = File(area.layer("buildings").file.parentFile, "preview.png")
                canvas.colorBuffer(0).saveToFile(target)
                println("saved ${target.path}")
                application.exit()
            }
        }
    }
}

private val BACKGROUND = ColorRGBa.fromHex("#F2F0EB")
private val INK = ColorRGBa.fromHex("#2F3A3F")
private val WATER = ColorRGBa.fromHex("#BFD4DE")
private val ROAD = ColorRGBa.fromHex("#E4E1DA")
private val NATURE = ColorRGBa.fromHex("#DCE3D2")

/** Ground for the type, so it stays readable over whatever the map is doing under it. */
private val PANEL = ColorRGBa.fromHex("#F2F0EB").opacify(0.88)

/** Buildings with no registered unit at all: sheds, garages, transformer huts. */
private val NO_FUNCTION = ColorRGBa.fromHex("#CFCBC2")

/**
 * A building carries every function registered inside it, so a shop with flats above is
 * "winkelfunctie,woonfunctie". Colour by the first function that is not housing: the
 * ground floor is what gives a street its character, and housing is so dominant it would
 * otherwise swallow the whole mix.
 */
private fun colourFor(functions: List<String>): ColorRGBa {
    val distinctive = functions.firstOrNull { it != "woonfunctie" }
    return LEGEND[distinctive] ?: LEGEND[functions.firstOrNull()] ?: NO_FUNCTION
}

private val LEGEND = linkedMapOf(
    "woonfunctie" to ColorRGBa.fromHex("#8A8377"),
    "winkelfunctie" to ColorRGBa.fromHex("#FF0000"),
    "kantoorfunctie" to ColorRGBa.fromHex("#5E00FF"),
    "bijeenkomstfunctie" to ColorRGBa.fromHex("#FF8A00"),
    "logiesfunctie" to ColorRGBa.fromHex("#00A3A3"),
    "onderwijsfunctie" to ColorRGBa.fromHex("#0066FF"),
    "gezondheidszorgfunctie" to ColorRGBa.fromHex("#E100A0"),
    "industriefunctie" to ColorRGBa.fromHex("#6B4A2F"),
    "sportfunctie" to ColorRGBa.fromHex("#00B140"),
    "overige gebruiksfunctie" to ColorRGBa.fromHex("#A8A29A")
)
