import kotlinx.serialization.json.JsonObject
import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The city as a figure-ground plan: buildings solid, everything else the ground they
 * stand in. Buildings arrive one at a time, and the roads, water and planting fade in
 * softly underneath once enough of the fabric is up to read as a city.
 *
 *     ./gradlew run -Popenrndr.application=CityMapKt
 *
 * Drag to pan, scroll to zoom, 0 resets the view, R replays, space pauses.
 *
 * Everything is real geometry, never an image: contours are triangulated once at load and
 * drawn from vertex buffers, so the view transform is the only thing a frame changes and
 * an edge stays exact however far you zoom into it.
 *
 * That is also what makes the reveal cheap. Buildings are stored in the order they are
 * revealed, so everything already settled is one contiguous run of triangles — a single
 * draw call for the whole city so far — and only the few still fading are drawn one by
 * one. A frame costs the same whether 10 buildings are up or 7839.
 */
fun main() = application {
    val canvasWidth = Env["CITY_WIDTH"]?.toIntOrNull() ?: 1920
    val canvasHeight = Env["CITY_HEIGHT"]?.toIntOrNull() ?: 1080
    val windowScale = Env["CITY_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.7

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
    }

    program {
        val area = collectMapData()

        val revealDuration = Env["CITY_DURATION"]?.toDoubleOrNull() ?: 26.0
        val buildingFade = Env["CITY_BUILDING_FADE"]?.toDoubleOrNull() ?: 0.9
        // The ground arrives once the fabric reads as a city, so the buildings land first.
        val contextAt = revealDuration * (Env["CITY_CONTEXT_AT"]?.toDoubleOrNull() ?: 0.45)
        val contextFade = Env["CITY_CONTEXT_FADE"]?.toDoubleOrNull() ?: 5.0

        /** Ground up from the first frame, so the rivers are there the whole way. */
        val groundAlways = Env.boolean("CITY_GROUND_ALWAYS")

        // A flight: start close enough to watch single buildings land, and pull back while
        // they do. Setting CITY_ZOOM_FROM is what turns it on.
        // CITY_FOCUS holds the frame on a named point rather than the middle of the
        // extent — "x,y" in RD metres, or a place to geocode.
        val focusPoint = Env["CITY_FOCUS"]?.let { query ->
            Regex("""\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*""").matchEntire(query)?.let {
                Vector2(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
            } ?: resolvePlace(query)
        }

        val camera = MapCamera(area, canvasWidth, canvasHeight,
            cover = Env["CITY_FIT"]?.lowercase() != "contain",
            homeZoom = Env["CITY_ZOOM"]?.toDoubleOrNull() ?: 1.0,
            focusPoint = focusPoint)

        val requestedZoomFrom = Env["CITY_ZOOM_FROM"]?.toDoubleOrNull()
        val requestedZoomTo = Env["CITY_ZOOM_TO"]?.toDoubleOrNull() ?: 1.0

        // The shot runs either way round. Above zoomTo, zoomFrom opens tight on a single
        // element and pulls back off it; below, it opens on the whole extent and pushes in.
        // Only the wider end of the two can show the edge of the collected data, and which
        // end that is depends on the direction — so both are floored and the question does
        // not have to be asked. They are settled again once the opening element is known,
        // because recentring the frame moves the nearest edge and so moves that floor.
        var zoomFrom = requestedZoomFrom?.let { maxOf(it, camera.zoomFillingFrame()) }
        var zoomTo = maxOf(requestedZoomTo, camera.zoomFillingFrame())
        var flying = zoomFrom != null && zoomFrom != zoomTo

        /** Everything already up on the first frame, so the shot is the move and nothing else. */
        val buildingsAlways = Env.boolean("CITY_BUILDINGS_ALWAYS")

        // The closing beat: the elements the camera comes to rest on leave the plan and
        // stand in a grid. It only means anything at the end of a push-in — there has to be
        // a frame to come to rest on — so it is off unless asked for.
        val gridEnabled = Env.boolean("CITY_GRID")
        val gridLimit = Env["CITY_GRID_MAX"]?.toIntOrNull() ?: 120
        // Slow and overlapping by default: the grid starts forming while the camera is
        // still moving and finishes after it has stopped, so neither reads as a cut.
        val gridAt = Env["CITY_GRID_AT"]?.toDoubleOrNull() ?: (revealDuration * 0.6)
        val gridTime = Env["CITY_GRID_TIME"]?.toDoubleOrNull() ?: (revealDuration * 0.55)

        // ...and then the grid is taken apart one element at a time until a single one is
        // left standing in the middle. It begins where the grid finishes settling, so the
        // frame is never both assembling and emptying at once.
        val lastEnabled = Env.boolean("CITY_LAST")
        val lastAt = Env["CITY_LAST_AT"]?.toDoubleOrNull() ?: (gridAt + gridTime)
        val lastTime = Env["CITY_LAST_TIME"]?.toDoubleOrNull() ?: (revealDuration * 0.5)
        val lastOrder = Env["CITY_LAST_ORDER"] ?: "far"
        /** The survivor's own ink, for a run that wants it picked out. Unset leaves it alike. */
        val lastInk = Env["CITY_LAST_INK"]?.let { ColorRGBa.fromHex(it) }
        /**
         * How much the survivor grows as the rest go. 1.0 leaves it at the size the grid
         * gave it, which on a cell this size is a 71 px mark in a 1920 px frame — true to
         * the packing, and very little to end a shot on.
         */
        val lastScale = Env["CITY_LAST_SCALE"]?.toDoubleOrNull() ?: 1.0

        // The opening is stated outright rather than left to a curve: this many buildings
        // arrive at an even, countable rate over this many seconds, and the camera holds
        // still while they do. Only then does the pull-back start. Tying the reveal to the
        // visible area alone could not do this — over a modest zoom range the same rule
        // puts 166 buildings down in the first four seconds, which is not something anyone
        // can watch arrive one at a time.
        val openingCount = Env["CITY_OPENING_COUNT"]?.toIntOrNull() ?: 12
        val openingTime = Env["CITY_OPENING_TIME"]?.toDoubleOrNull() ?: 8.0

        val buildingFeatures = area.features("buildings")
        val chosenOrder = revealOrder(buildingFeatures, area, Env["CITY_ORDER"] ?: "age")

        // The shot opens on something rather than on an empty frame, so whichever building
        // sits closest to where the camera is held is moved to the front of the reveal. It
        // is one building out of place in the ordering, and it buys a first frame with a
        // single element standing in the middle of it.
        val order = run {
            val centre = focusPoint ?: Vector2(area.centre[0], area.centre[1])
            val nearest = chosenOrder.minByOrNull { centroid(buildingFeatures[it]).squaredDistanceTo(centre) }
            if (nearest == null) chosenOrder
            else listOf(nearest) + chosenOrder.filter { it != nearest }
        }

        // Elements must still read once the pull-back is finished, so the floor is worked
        // out from the *final* zoom rather than chosen in metres: a fraction of the canvas
        // width, converted back into ground units at the widest view of the shot.
        val minScreen = Env["CITY_OBJECT_MIN_SCREEN"]?.toDoubleOrNull() ?: 0.0
        val minObjectSize = if (minScreen > 0.0)
            (canvasWidth * minScreen) / camera.pixelsPerMetre(zoomTo) else 0.0
        if (minObjectSize > 0.0)
            println("elements floored at %.0f m, which is 1/%.0f of the frame when fully out"
                .format(minObjectSize, 1.0 / minScreen))

        // A lattice for the elements to sit on. "auto" sizes a cell to hold one element
        // plus its margin, which is the tightest grid that still keeps them all apart.
        val gridSetting = Env["CITY_OBJECT_GRID"] ?: "off"
        val objectGap = Env["CITY_OBJECT_GAP"]?.toDoubleOrNull() ?: 1.0
        val objectSizeM = Env["CITY_OBJECT_SIZE"]?.toDoubleOrNull() ?: 8.0
        val objectGrid = when {
            gridSetting.equals("off", true) -> 0.0
            gridSetting.equals("auto", true) -> {
                val element = if (minObjectSize > 0.0) minObjectSize else objectSizeM
                element + objectGap * (if (minObjectSize > 0.0) minObjectSize / objectSizeM else 1.0)
            }
            else -> gridSetting.toDoubleOrNull() ?: 0.0
        }
        if (objectGrid > 0.0) println("elements on a %.0f m lattice".format(objectGrid))

        print("triangulating... ")
        val began = System.currentTimeMillis()

        // Either the buildings themselves, or the catalogue packed onto them. Both come out
        // as one mesh with a run of triangles per building, so everything downstream — the
        // reveal, the bands, the flight — does not know or care which it is drawing.
        val ordered = order.map { buildingFeatures[it] }

        // Where the very first part is actually drawn, which is not the same as where its
        // building is. An element is packed somewhere inside the plan rather than on its
        // centroid, so on the objects fabric the two are a building's width apart — enough
        // to put the opening element in a corner of the frame at a close zoom.
        var openingCentre: Vector2? = null

        // The elements that end the shot standing in a grid, and the mesh of just those —
        // one part each, because unlike everything else they have to move on their own.
        // They are held out of the fabric below rather than drawn over it: at rest they sit
        // exactly where the packing put them, so with the grid switched off, or before it
        // begins, the picture is the same one either way.
        var moves: List<GridMove> = emptyList()
        var moversMesh = Mesh(null, IntArray(0))
        var cullRanks = IntArray(0)
        /** The middle of the frame the shot rests on, which is where the last element ends. */
        var closingCentre = Vector2.ZERO

        val fabric = if ((Env["CITY_FABRIC"] ?: "footprints").lowercase() == "objects") {
            val sheet = File(Env["CITY_OBJECT_SHEET"] ?: "data/svg/objects-iso.svg")
            val templates = loadObjectTemplates(sheet)
            println("packing ${templates.size} objects from ${sheet.name} onto the plans... ")
            val placements = objectPlacements(
                ordered, templates,
                objectSize = Env["CITY_OBJECT_SIZE"]?.toDoubleOrNull() ?: 8.0,
                gap = Env["CITY_OBJECT_GAP"]?.toDoubleOrNull() ?: 1.0,
                maxPerBuilding = Env["CITY_OBJECT_MAX"]?.toIntOrNull() ?: 48,
                minSize = minObjectSize,
                gridSize = objectGrid,
                gridOrigin = focusPoint ?: camera.areaCentre
            )

            if (gridEnabled) {
                // The frame the push comes to rest on — so the elements that take part are
                // exactly the ones on screen at the end, and none arrive from off frame.
                moves = gridMoves(placements, templates, camera.worldFrame(zoomTo), gridLimit,
                    fill = Env["CITY_GRID_FILL"]?.toDoubleOrNull() ?: 0.8)
                val taken = moves.mapTo(HashSet()) { it.placement }
                moversMesh = meshOfTriangles(moves.map { it.placement.triangles(templates) }, area.origin)
                val frame = camera.worldFrame(zoomTo)
                closingCentre = frame.center
                if (lastEnabled) cullRanks = cullOrder(moves, frame, lastOrder)
                val travel = moves.map { it.home.distanceTo(it.slot) }
                println(("grid: %d elements in the closing frame, moving %.0f m on average " +
                        "and %.0f m at most — %.0f%% and %.0f%% of the frame's width")
                    .format(moves.size, travel.average(), travel.maxOrNull() ?: 0.0,
                        100 * travel.average() / frame.width,
                        100 * (travel.maxOrNull() ?: 0.0) / frame.width))
                val parts = placements.map { building ->
                    building.filter { it !in taken }.flatMap { it.triangles(templates) }
                }
                openingCentre = firstPartCentre(parts)
                meshOfTriangles(parts, area.origin)
            } else {
                val parts = placements.map { building -> building.flatMap { it.triangles(templates) } }
                openingCentre = firstPartCentre(parts)
                meshOfTriangles(parts, area.origin)
            }
        } else {
            openingCentre = ordered.firstOrNull()?.let { centroid(it) }
            meshOf(ordered.map { it.shapes() }, area.origin)
        }
        // Which of the ground layers are drawn at all. Leaving one out skips loading and
        // triangulating it too, which is most of the work: the road surfaces alone are
        // 8.5M of the 8.6M vertices in this extent.
        // "none" rather than an empty value, because Env treats a blank as unset and would
        // hand back the default — an empty string cannot say "no ground" through it.
        val groundLayers = (Env["CITY_GROUND_LAYERS"] ?: "nature,water,roads")
            .split(",").map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "none" }.toSet()
        fun groundMesh(name: String) =
            if (name in groundLayers) area.meshOfLayer(name) else Mesh(null, IntArray(0))

        val roads = groundMesh("roads")
        val water = groundMesh("water")
        val nature = groundMesh("nature")
        println("${fabric.parts} buildings, ${roads.parts + water.parts + nature.parts} ground surfaces, " +
                "${(fabric.vertexCount + roads.vertexCount + water.vertexCount + nature.vertexCount) / 1000}k vertices " +
                "in ${(System.currentTimeMillis() - began) / 1000.0}s")

        // A pull-back is held on the opening element rather than on the middle of the
        // extent, so the first frame has that one element dead centre and the city opens
        // out around it. The centre still never moves — this picks *which* fixed point the
        // shot is built on, once, before the clock starts; it is the camera chasing the
        // arrivals frame by frame that reads as the shot following an object, and that is
        // still not done. An explicit CITY_FOCUS outranks it.
        //
        // It is only for a pull-back. With the whole city up from the first frame there is
        // no opening element to open on, and a push-in wants the middle of the extent as
        // its destination rather than whichever plan happened to sort first.
        //
        // Recentring moves the nearest edge of the data, so the floor on both ends of the
        // flight is worked out again afterwards or the shot could run past the extent.
        if (flying && focusPoint == null && !buildingsAlways && zoomFrom!! > zoomTo) {
            openingCentre?.let { first ->
                camera.recentre(first)
                zoomFrom = maxOf(requestedZoomFrom!!, camera.zoomFillingFrame())
                zoomTo = maxOf(requestedZoomTo, camera.zoomFillingFrame())
                flying = zoomFrom != zoomTo
                println("holding on the opening element at %.0f, %.0f; pulling back to %.2fx"
                    .format(first.x, first.y, zoomTo))
            }
        }
        if (flying) println("flight %.2fx -> %.2fx over %.0fs%s"
            .format(zoomFrom!!, zoomTo, revealDuration,
                if (buildingsAlways) ", everything up from the first frame" else ""))

        // --- view ---------------------------------------------------------------------
        // RD New is metres with y pointing north, so the projection is a flip and a scale.
        // Zoom is only a factor on that: nothing is ever resampled.
        // Where the camera looks, for every point in the reveal: the running centre of
        // everything built so far. In age order that starts on the medieval core and drifts
        // outward exactly as the city grows, so the frame stays on the fabric that is
        // actually arriving instead of on a point picked in advance. A first attempt eased
        // straight from the core to the middle of the extent and had left the old town by
        // the time the old town was still the only thing being built — one lone building in
        // an empty frame. Being a running mean it cannot jump, so the move is smooth.
        // "cover" rather than "contain": the extent is square and the canvas usually is
        // not, so fitting it inside would letterbox the drawing against a band of empty
        // paper. Filling the frame and cropping is what the plan wants.
        mouse.scrolled.listen { camera.zoomAt(camera.pointer(it.position, width), it.rotation.y) }
        mouse.dragged.listen { camera.panBy(it.dragDisplacement * (canvasWidth / width.toDouble())) }

        var paused = false
        var startedAt: Double? = null
        var elapsed = 0.0
        var pausedAt = 0.0

        keyboard.keyDown.listen { event ->
            when {
                event.name == "0" -> camera.reset()
                event.name == "r" -> startedAt = null          // the draw loop restamps it
                event.key == KEY_SPACEBAR -> paused = !paused
            }
        }

        /**
         * How many buildings have arrived by [t].
         *
         * On a flight this is tied to the zoom, not to the clock, and that is the whole
         * trick of the shot. Told to reveal 201 346 buildings evenly over half a minute,
         * the sketch places seven thousand a second — nothing anyone can watch arrive. So
         * instead the count is held proportional to the *area the camera can see*: pulled
         * right in, a handful of buildings fills the frame and they land one at a time;
         * as the view widens the same visible density needs thousands, and they flood in.
         * The rate you perceive stays about even from the first building to the last.
         */
        fun revealedAt(t: Double): Double {
            if (buildingsAlways) return fabric.parts.toDouble()
            // The area rule below only makes sense while the frame is opening out. Pushing
            // in, the visible area shrinks, and tying the count to it would take buildings
            // away again — so a push-in reveals on the clock like a still camera does.
            if (!flying || zoomTo >= zoomFrom!!)
                return (t / revealDuration).coerceIn(0.0, 1.0) * fabric.parts
            // One element is up before the clock starts: at t = 0 this returns 1 for both
            // the head and, because it ignores negative time, the settled count too — so
            // the first frame holds a single element at full strength rather than a ghost
            // fading in from nothing.
            if (t <= openingTime) return 1.0 +
                    (t / openingTime).coerceIn(0.0, 1.0) * (openingCount - 1.0)
            val range = zoomFrom!! / zoomTo
            val p = ease(((t - openingTime) / (revealDuration - openingTime)).coerceIn(0.0, 1.0))
            // after the opening the count follows the area the camera can see: visible area
            // goes as 1/zoom^2 and the zoom decays geometrically, so the rate you perceive
            // stays about even while the frame widens by a factor of twenty
            val curve = (Math.pow(range, 2.0 * p) - 1.0) / (range * range - 1.0)
            return openingCount + (fabric.parts - openingCount) * curve
        }

        fun drawCity(drawer: Drawer, time: Double) {
            drawer.clear(BACKGROUND)
            drawer.stroke = null

            if (flying) {
                // the camera holds through the opening, then flies — geometric either way,
                // so the same expression pulls back or pushes in
                val p = ease(
                    ((time - openingTime) / (revealDuration - openingTime)).coerceIn(0.0, 1.0)
                )
                // geometric, so the pull-back reads as an even speed rather than racing at
                // the start and crawling at the end
                camera.zoom = zoomFrom!! * Math.pow(zoomTo / zoomFrom, p)

                // The centre never moves. An earlier version had the camera follow where
                // buildings were arriving, which tracked about the frame and read as the
                // shot chasing one object; holding still lets the city open out around a
                // fixed point instead. That point is the middle of the collected extent,
                // which for a MAP_RADIUS extent is exactly MAP_CENTRE — the Markt here.
            }

            drawer.isolated {
                camera.apply(drawer)
                drawer.drawStyle.clip = camera.clip()

                // The ground, softly: one opacity for all of it, eased so it arrives
                // without an edge. Water sits under the roads so bridges read across it.
                val ground = if (groundAlways) 1.0 else ease((time - contextAt) / contextFade)
                nature.draw(drawer, NATURE, ground)
                water.draw(drawer, WATER, ground)
                roads.draw(drawer, ROAD, ground * 0.85)

                // Buildings. Everything settled is one run of triangles.
                val head = revealedAt(time)
                val arriving = head.roundToInt().coerceAtMost(fabric.parts)

                // CITY_BUILDING_FADE=0 is the hard cut: a building is simply there on the
                // frame it arrives. Then the whole revealed run is the settled prefix, the
                // fade window is empty and the banded draw below has nothing to do — so the
                // whole city, however large, is one call.
                val settled = if (buildingFade <= 0.0) arriving else
                    revealedAt(time - buildingFade)
                        .coerceIn(0.0, fabric.parts.toDouble()).toInt()

                fabric.drawPrefix(drawer, BUILDING, settled)

                // ...and the ones still arriving are drawn in a fixed number of bands.
                //
                // They are a contiguous run whose opacity falls off along it, so instead of
                // a call per building the run is cut into FADE_BANDS pieces, each drawn at
                // one opacity. That matters at scale: over 200 000 buildings the fade window
                // spans some 7000 of them, and a call each dropped this to 49 fps. Banded it
                // is a fixed two dozen calls whatever the city's size, and nothing shows —
                // at that rate 7700 buildings land per second, so a band is already well
                // under the eye's ability to see one arrive.
                val fading = arriving - settled
                if (fading > 0) {
                    val bands = minOf(FADE_BANDS, fading)
                    for (band in 0 until bands) {
                        val from = settled + fading * band / bands
                        val to = settled + fading * (band + 1) / bands
                        if (to <= from) continue
                        // the window holds exactly the last buildingFade seconds of
                        // arrivals, oldest first, so opacity ramps across it
                        val through = (band + 0.5) / bands
                        fabric.drawRange(drawer, from, to, BUILDING, ease(1.0 - through))
                    }
                }

                // The closing grid. These elements were held out of the fabric above, so
                // this is the only place they are drawn and there is no ghost left behind
                // on the plan as they leave it.
                //
                // A call each is affordable here where it would not be for the city,
                // because the grid is the elements in one frame — a hundred or so, capped
                // by CITY_GRID_MAX — rather than every element on the map.
                if (moves.isNotEmpty()) {
                    val settle = ease(((time - gridAt) / gridTime).coerceIn(0.0, 1.0))

                    // How many have been taken away by now. Linear on purpose where every
                    // other move in the file is eased: this one is counted rather than
                    // travelled — an eased ramp would crowd the removals at one end and
                    // read as a stutter, where an even rate reads as a count down to one.
                    val emptying = if (cullRanks.isEmpty()) 0.0
                                   else ((time - lastAt) / lastTime).coerceIn(0.0, 1.0)
                    val gone = (emptying * (moves.size - 1)).toInt()

                    moves.forEachIndexed { index, move ->
                        if (move.building >= arriving) return@forEachIndexed
                        val rank = if (index < cullRanks.size) cullRanks[index] else -1
                        if (rank in 0 until gone) return@forEachIndexed
                        var at = move.home * (1.0 - settle) + move.slot * settle
                        // The survivor takes the middle of the frame as the others go. Its
                        // slot is only the *nearest* cell centre to it, and with an even
                        // number of rows the middle of the frame falls on a cell edge — so
                        // left in its slot the last element sits half a cell off centre,
                        // which on a six-row grid is 90 px of a 1080 px frame.
                        if (rank < 0 && cullRanks.isNotEmpty())
                            at = at * (1.0 - ease(emptying)) + closingCentre * ease(emptying)
                        // v -> at + k(v - home): the element travels to its slot and, if it
                        // would overrun the cell, closes to the size that fits on the way.
                        //
                        // In the mesh's own coordinates, which are relative to the extent's
                        // centre (see meshOfTriangles). A pure translation does not care —
                        // a delta is a delta — but a scale is about a *point*, and taking
                        // that point in RD metres instead throws the element out by
                        // origin * (k - 1), which is a hundred and fifty kilometres.
                        var k = 1.0 + (move.scale - 1.0) * settle
                        if (rank < 0 && cullRanks.isNotEmpty() && lastScale != 1.0)
                            k *= 1.0 + (lastScale - 1.0) * ease(emptying)
                        val ink = if (rank < 0 && lastInk != null) lastInk else BUILDING
                        drawer.isolated {
                            drawer.translate(at - area.origin)
                            drawer.scale(k, k)
                            drawer.translate(area.origin - move.home)
                            moversMesh.drawRange(drawer, index, index + 1, ink)
                        }
                    }
                }
            }
            drawer.drawStyle.clip = null
        }

        // A fixed canvas so a recording is the same size whatever the window is.
        val canvas = renderTarget(canvasWidth, canvasHeight) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH_STENCIL)      // filled shapes are stencil clipped
        }

        // When the composition is actually over, which is not the same as when the reveal
        // is: the closing beats run on past it. Stills are spread across this rather than
        // across the reveal, or the last beat is off the end of the sheet and a change to
        // it cannot be judged without filming the run.
        val showEnds = maxOf(
            revealDuration + contextFade,
            if (gridEnabled) gridAt + gridTime else 0.0,
            if (lastEnabled) lastAt + lastTime else 0.0
        )

        val stills = Env["CITY_STILLS"]?.toIntOrNull() ?: 0
        var still = 0

        val debug = Env.boolean("CITY_DEBUG")
        var frames = 0
        var lastReport = 0.0

        if (Env.boolean("CITY_RECORD")) {
            extend(ScreenRecorder().apply {
                outputFile = Env["CITY_VIDEO"] ?: "video/citymap.mp4"
                frameRate = Env["CITY_FPS"]?.toIntOrNull() ?: 60
                // the window shows the canvas shrunk, so the clip is captured at the
                // canvas's own size rather than the window's — same trick as demo02
                contentScale = 1.0 / windowScale
                Env["CITY_VIDEO_DURATION"]?.toDoubleOrNull()?.let { maximumDuration = it }
            })
        }

        extend {
            // Every timestamp is read here and never in an event handler: ScreenRecorder
            // swaps program.clock for a frame clock while it draws, so `seconds` in a
            // handler is wall time while the draw loop is on video time, and the two drift
            // apart the moment encoding falls behind. See the note in CLAUDE.md.
            if (startedAt == null) { startedAt = seconds; pausedAt = 0.0 }
            if (paused) startedAt = seconds - pausedAt else { elapsed = seconds - startedAt!!; pausedAt = elapsed }

            val time = if (stills > 0) (still + 1.0) / stills * showEnds else elapsed

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawCity(drawer, time)
            }
            drawer.image(canvas.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())

            if (debug) {
                frames++
                if (seconds - lastReport > 2.0) {
                    println("%.0f fps at zoom %.1fx".format(frames / (seconds - lastReport), camera.zoom))
                    frames = 0; lastReport = seconds
                }
            }

            if (stills > 0) {
                val target = File(area.layer("buildings").file.parentFile, "city-%02d.png".format(still))
                canvas.colorBuffer(0).saveToFile(target)
                println("saved ${target.name} at t=%.1fs".format(time))
                still++
                if (still >= stills) application.exit()
            }
        }
    }
}

/**
 * The middle of the first non-empty part, which is where a pull-back opens. Measured off
 * the triangles rather than off the building, because an element is packed somewhere inside
 * its plan and the two are a building's width apart.
 */
private fun firstPartCentre(parts: List<List<Vector2>>): Vector2? =
    parts.firstOrNull { it.isNotEmpty() }?.let { tri ->
        Vector2(
            (tri.minOf { it.x } + tri.maxOf { it.x }) / 2,
            (tri.minOf { it.y } + tri.maxOf { it.y }) / 2
        )
    }

// Figure and ground. The buildings carry the drawing; everything else is quieter than it
// would be on a map, because here it is only the ground the figure sits in.
private val BACKGROUND = ColorRGBa.fromHex(Env["CITY_PAPER"] ?: "#FFFFFF")
private val BUILDING = ColorRGBa.fromHex(Env["CITY_INK"] ?: "#000000")
private val WATER = ColorRGBa.fromHex("#0A0A0A")
private val ROAD = ColorRGBa.fromHex("#D8D4CC")
private val NATURE = ColorRGBa.fromHex("#ECEDE6")

/** How many opacity steps the arriving buildings are drawn in. */
private const val FADE_BANDS = 24

/** Smooth on both ends, so nothing arrives or lands with an edge. */
private fun ease(t: Double): Double {
    val c = t.coerceIn(0.0, 1.0)
    return c * c * (3.0 - 2.0 * c)
}

/** The order buildings arrive in. */
private fun revealOrder(buildings: List<JsonObject>, area: MapArea, mode: String): List<Int> = when (mode) {
    // The city assembles itself in the order it was really built: the old town fills in
    // first because that is where the pre-1700 fabric is, then the rings around it.
    "age" -> buildings.indices.sortedBy { buildings[it].property("bouwjaar")?.toIntOrNull() ?: 9999 }
    // Outward from the middle, like ink spreading.
    "centre" -> {
        val centre = Vector2(area.centre[0], area.centre[1])
        buildings.indices.sortedBy { centroid(buildings[it]).squaredDistanceTo(centre) }
    }
    "random" -> buildings.indices.shuffled(Random(0))
    else -> buildings.indices.toList()
}

private fun centroid(feature: JsonObject): Vector2 {
    var x = 0.0; var y = 0.0; var n = 0
    feature.rings().forEach { ring -> ring.forEach { x += it[0]; y += it[1]; n++ } }
    return if (n == 0) Vector2.ZERO else Vector2(x / n, y / n)
}
