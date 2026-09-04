# openrndr-wn-sandbox

An OPENRNDR sandbox. Sketches live in `src/main/kotlin`, assets in `data/`.

## Configuration: always use .env

**All configuration and secrets go in `.env` at the project root — project wide, no
exceptions.** Never hardcode a key, token, id or path in a sketch, and never expect
values to be exported in the shell.

- `.env` is gitignored and holds the real values.
- `.env.example` is committed and documents every key the project needs.
- Read values in Kotlin through [`Env`](src/main/kotlin/Env.kt):

```kotlin
Env.require("FIGMA_TOKEN")        // throws with a message naming the missing key
Env["FIGMA_PAGE"]                 // null when unset
Env.boolean("FIGMA_REFRESH")      // true / 1 / yes
```

Real environment variables override `.env`, so a single value can be changed for one
run without editing the file.

When adding anything that needs configuration, add the key to **both** `.env` and
`.env.example`, with a comment in `.env.example` explaining what it is.

## Running

```
./gradlew run                                     # TemplateProgram
./gradlew run -Popenrndr.application=FigmaKt      # any other main
```

`build.gradle.kts` sets `-Dorg.openrndr.gl3.gl_type=gl` so runs use native OpenGL
rather than the ANGLE default, which this machine renders incorrectly.

## figma-rest

`figma-rest/` is part of this codebase, not a dependency. It is wired in as a Gradle
included build (`includeBuild("figma-rest")` in `settings.gradle.kts`), so edits to
`figma-rest/src` are picked up on the next run with no publishing step.

Two things there must stay in step with the root build, or it will not resolve:
Kotlin `2.2.10`, and `jvmToolchain(17)` — a higher toolchain makes Gradle reject the
variant as incompatible.

Its `FigmaApi.file()` writes each raw response to `data/template.json` as a side
effect. That file is gitignored.

Two deserialization bugs in `Node.kt` are fixed locally — do not revert them when
pulling changes in, or parsing a real document breaks:

- `interactions` was `List<String?>`; Figma sends objects, so it is `List<JsonElement>`.
- `Slice` had no supertype, so `"SLICE"` could not resolve in `Node`'s polymorphic
  scope and any document containing a slice failed to parse. It now extends `Node()`.

## Figma frames

`Figma.kt` holds both halves: `fetchFigmaFrames()` exports every frame on `FIGMA_PAGE`
to its own svg in `data/figma` with a `frames.json` manifest, so later runs need
neither a token nor a network, and its `main` steps through them with the arrow keys.

Frames are exported with `svgOutlineText = true`: OPENRNDR's SVG loader builds shapes,
so outlined text renders while live `<text>` elements would not.

## demo01

`Demo01.kt` subdivides the frame: one box, then a box per press of `→`, the newest one
taking the bottom band and the older ones stacking in rows above it.

The layout is `packBoxes(space, count)`, a pure function from a space and a box count to
a list of rectangles — no time, no state. The sketch only interpolates from where the
boxes are to where that function says they belong, so any change to the layout keeps
animating for free.

`DEMO01_RECORD=true` records the run to `video/` (`DEMO01_FPS`, `DEMO01_DURATION`), and
`DEMO01_AUTOSTEP` steps on a timer so a clip needs no keyboard.

**Never read `seconds` outside the draw loop in a sketch that records.** `ScreenRecorder`
runs with `frameClock = true` by default: it swaps `program.clock` for a frame-index
clock in `beforeDraw` and puts the system clock back in `afterDraw`. Event handlers run
between draws, so `seconds` there is wall-clock time while the draw loop sees video time,
and the two drift apart the moment encoding falls behind realtime. A timestamp taken in a
key handler then sits in the draw loop's future and the animation never plays — the
recording snaps between states instead of moving. Demo01 therefore only sets `count` in
its key handler and does the retarget in the draw loop.

## demo02

`Demo02.kt` is demo01 with the rectangles replaced by the drawings in `data/svg`.
`DEMO02_SHEET` chooses the sheet and defaults to `subset.svg`, the hand-picked selection;
the same `_RECORD` / `_FPS` / `_AUTOSTEP` / `_DURATION` keys as demo01 record a clip.

`packTrain()` replaces demo01's grid. A grid cannot hold these objects without waste —
they run from 0.85 to 3.29 wide-to-high, so any one cell shape letterboxes a fifth of the
average box and more than half of the worst — and packing them into rows cannot fill a
3840x1080 frame either, because whatever a row cannot use is left at its end.

So they are not packed as rows at all. **The objects are one strip, laid end to end a fixed
margin apart, wrapped across as many lines as fill the frame.** An object that reaches the
end of a line is neither moved down whole nor stretched to hide the shortfall: the line is
cut and it continues at the start of the next one. Every line but the last is then full
edge to edge by construction, every object keeps its own proportions and the same margin,
and the only space left over is the open end of the line still being laid — which is the
piece being built rather than a hole in it. Filled area at fifteen objects went from 46% of
the frame under a row-packed wall to 84%.

The number of lines is whichever lets the objects be biggest: on `n` lines an object can be
no taller than the lines leave room for and no taller than a strip of `n * width` will
carry, so whichever limit binds is met exactly — either the lines fill the height or the
strip fills every line. Adding an object lengthens the strip and, when it no longer fits,
drops the height a step; everything then slides along the line together.

Drawing follows the same idea: a box may extend past the frame, and the draw loop clips it
there and draws it again one line down and a line width left, so the two halves line up.

Colour marks the head of the line, not the object — an object arrives in `HIGHLIGHT` red
and turns `SOLID` purple once the next one lands on it. Both are the Figma brand values,
`#FF0000` and `#5E00FF`, read off `data/figma`.

`loadObjectSheet()` recovers the objects from a flat svg. Figma's export keeps no groups,
ids or layer names — both sheets are one flat list of `<path>` with only `d` and `fill` —
so the `obj_` / `Vector` structure has to be inferred:

- **Captions are dropped by position, not by colour.** Artwork and type use one colour
  each, and the type colour is the group whose paths are far shorter. That alone misses
  the first caption line, which is set in the *artwork* colour. What holds for all of them
  is that type sits with type, so a path within `CAPTION_REACH` line heights above a known
  caption is also a caption. Drawings clear that band by 29px or more. The rule is stable
  anywhere between 1.5x and 3x line height on both sheets.
- **The grid is read off the file**, from the gaps that run clear across the sheet, rather
  than assumed. Objects come back in reading order because a Figma export lists paths that
  way and the cells are collected into a `LinkedHashMap`.

That yields 15 objects from `subset.svg`, 112 from `objects-front.svg` and 115 from
`objects-iso.svg`, one path each. Nothing in it is tied to a particular sheet: `subset.svg`
draws its artwork in blue rather than green and is read correctly without changes, because
the caption colour is derived per file rather than named. If a sheet is ever re-exported
with Figma's "Include id attribute" enabled, the `obj_` groups survive and all of this can
be replaced by reading the names.

The objects are precast components, so the full front sheet is mostly plain silhouettes —
a slab is a rectangle, `DRST_M24_1500` is a hairline. That is the asset, not a parsing
fault; `subset.svg` picks the ones with more profile to them.

When judging a layout change here, measure it rather than eyeballing the window: set
`DEMO02_RECORD`/`_AUTOSTEP`/`_DURATION`, then read filled area and object displacement off
the clip with ffmpeg. A python model of the layout is easy to get subtly wrong — one built
during this work had the objects in the wrong order and disagreed with the sketch by 40
points of coverage.

## decision

`Decision.kt` is the decision-making piece: scattered dots and crooked lines resolving,
one dot at a time, into a cluster on a clean cross.

What stands at each dot is one of demo02's precast elements, off `DECISION_SHEET`, drawn in
the dot's own colour — the svgs place and colour the decisions, the sheet says what they are.
`DECISION_SHEET=none` draws the plain circles instead — that word and not an empty value,
because `Env` reads blank as unset and unset falls back to the sheet.

**Both keyframes are drawings, not code.** `data/svg/non-decided.svg` is where everything
starts and `data/svg/decided.svg` is where it ends up; the sketch reads the pair and works
out for itself which shape in the one is which shape in the other. To change the
composition you move the circles in Figma and re-export — nothing in the file knows how
many dots there are, what colour they are, or where the centre is.

**The lines are not animated; they are read off the dots.** A line belongs to the dots that
share its colour and stands wherever that group has got to: no red dot settled and the red
line lies at the angle it was drawn at, one settled and it is half way, both and it is
exactly the axis. So the line is the state of the argument and the cross at the end is not
a thing that gets drawn — it is what is left once everyone agrees.

Like `packBoxes` in demo01, the layout is a pure function: `stateAt(settled)` maps one
number — how many dots have decided — to every position on screen. No time, no state. The
sketch only interpolates from where things are to where that says they belong, so a change
to the rule keeps animating for free.

The svgs carry no ids (Figma exports a flat list of `<circle>` and `<path>`), so the pairing
is inferred. Colour splits the shapes into groups, then within a group they are matched so
the **total distance travelled is as small as it can be** — that is what keeps the
arrangement recognisable across the move, the top-right dot going to the top-right slot
rather than crossing the frame to swap with its twin. Exhaustive up to 8 shapes of a colour,
greedy above.

One trap in reading those files: Figma writes a clip rect in `<defs>` at exactly the artboard
size, and OPENRNDR's loader hands it over alongside the artboard. The artboard is picked as
the largest closed shape and survives only because it comes first in the document, `maxBy`
keeping the first of equal areas. A white background means that tie went the other way.

**Colour is load-bearing, so it is never overwritten — only over-inked.** The colours in the
svgs pair the shapes across the two drawings and say which dots move which line, so
`DECISION_INK` changes what reaches the paper and leaves the scene alone. One value paints
everything alike (`FFFFFF` is the black and white version); several are handed out to the
colours in the order the drawing introduces them, so `FFFFFF,7A7A7A` keeps the two groups
apart in tone — worth having, because otherwise nothing on screen says why a line moves when
it does. `DECISION_PAPER` sets the ground and takes it from the svg when empty.

The committed `.env` runs the elements in black and white — `DECISION_SHEET=data/svg/subset.svg`
with `DECISION_INK=FFFFFF`. `DECISION_SHEET=none` goes back to plain dots, and clearing
`DECISION_INK` in `.env` puts the Figma colours back; each is one line, and the two are
independent.

**`DECISION_OBJECT_SCALE` scales the gathering, not just the element.** A dot is 70 units
across and the decided cluster sits 116 apart, so an element drawn at the dot's own size is a
smudge — but simply enlarging it piles the four on top of each other. So the decided positions
are pushed out from the point they gather on by the same factor: the composition is the one
that was drawn, enlarged, and the cross still threads between the elements the way it threads
between the circles. 3.0 reads; past about 4 the scattered state starts to crowd.

**The scale is capped by the motion, not by the still.** The scattered state is a fixed
513x376 whatever the scale is, so pushing the gathering out shrinks the difference between the
two states: 1.0 tightens 4.4x, 2.0 tightens 2.2x, 3.0 only 1.5x. At 3.0 both keyframes still
photograph well and the clip is nearly dead — the elements shuffle rather than converge, which
only shows up watching it, not in the stills. 2.0 is the trade that keeps 140 unit elements
and a visible gathering. Getting both would mean dropping the square-box fit and aligning each
element to the corner of its quadrant, so the cluster is as tight as the elements allow.

Each element is fitted into the square its dot occupied, so a 3.29:1 slab and a 0.85:1 corner
end up very different sizes and the decided cluster is raggeder than the four circles were.
That is the elements being real components rather than a layout fault — `DECISION_OBJECTS`
picks which ones, and `0,5,8,12` (gable, T panel, corner, doorway) are four silhouettes that
read apart at a glance. Left unset they come in sheet order, which is a run of plain slabs.

`DECISION_ORDER=near` is the default: smallest move first, so the hold-out is the last to
come round and the final step is both the biggest move and the one that squares the last
line. `far` opens on the big move instead but then spends its last two steps on refinements,
which reads flatter — the near order builds. `file` and `random` are also there.

The clip is `DECISION_RECORD=true DECISION_AUTOSTEP=1.8 DECISION_DURATION=12`: it opens on a
held scattered state, decides four times, and holds the resolved cross for the last three
seconds. `DECISION_AUTOSTEP` stays 0 in `.env` rather than being set there, because it
overrides the arrow keys every frame and would make an ordinary run unsteerable.

`DECISION_STILLS=true` writes one png per resting state to `screenshots/` and quits, which is
how to judge a change to the keyframes — but only the keyframes. Judging the *motion* needs
the clip, which is how the dead 3.0 scale above got through a still review. Timing is read
only inside the draw loop — see the `ScreenRecorder` note under demo01.

## handover

`Handover.kt` is the op-art piece: two sets of crosses taking the floor from one another, for
ever. One set is white on black paper, the other black on white. They never blend — they take
turns. A wave crosses the lattice, the set whose turn it is rotates a quarter and comes to
rest, and the polarity is thrown.

**A cross has four-fold symmetry**, so a quarter turn puts it back exactly where it started
and the wave leaves no trace of having passed. That is what lets the piece have no beginning
and no end.

**Everything else rests on the two sets tiling the plane exactly.** When both are square to
the grid, white crosses on black paper and black crosses on white paper are the very same
picture, so the polarity can be thrown with nothing moving. That only holds for the right
lattice and it is easy to pick the wrong one:

- Step `sqrt 10` on a lattice turned by **`atan 1/3`** — the lattice on `(3,1)` and `(-1,3)`
  — and a second set offset by `(1,2)` interleaves perfectly. One cross per five units of
  area, which is exactly a cross's area.
- Turned by `atan 3` instead — `(1,3)` and `(-3,1)` — it looks near enough identical and is
  not a tiling at all. Sampling it puts a fifth of the plane under two crosses and a fifth
  under none. The handover then cuts a straight seam clean through whichever crosses straddle
  it, which is exactly how the fault shows up on screen. The Shadertoy this came from uses
  `1.25`, i.e. `atan 3`, and has the seam.

The wave is a ramp over the *lattice* rather than over the canvas — `u.y + slope*u.x` in
lattice steps — so it advances cross by cross and the diagonal band is built out of the tiling
instead of laid across it. The two sets run half a period apart, so one is always at rest while
the other turns.

**It is a shader on one rectangle**, which is what the inversion needs: the paper is white in
one part of the frame and black in another, and there is no join between them to hide, because
every pixel decides its own from the same ramp that decides the turn.

Time is periodic in `HANDOVER_PERIOD`, so a clip of that length loops. It is not quite
bit-exact — `mod` rounds differently at the two ends and the frames differ by 1 level in 255
on antialiased edges — but nothing is visible. `HANDOVER_SAVE=t` writes the frame at `t`
seconds and quits, which is how to compare settings without filming them.

### A panel instead of the cross

`HANDOVER_SHEET` stands one of demo02's precast panels on the lattice instead of the cross.
The panel is drawn once into a mask covering the three-by-three box the cross occupied, and
the shader reads it back per pixel, so the frame is still one rectangle with one shader on it.

**Two of the three things that make the cross work are properties of the cross, and a panel
has neither.** It is worth being plain about what that costs:

- *A quarter turn is not the identity.* The cross comes back to itself every 90 degrees, which
  is why the wave can pass through and leave no trace. A panel has to go the whole way round,
  so `HANDOVER_TURN` is 360 for a sheet. At 90 it still works but the turn is then **kept**,
  and the frame fills with bands of panels standing at 0, 90, 180 and 270 — a different and
  quite good piece, four periods long instead of one.
- *A panel does not tile.* This is the real loss. The polarity handover was invisible only
  because the two sets of crosses cover the plane exactly, so white-on-black and black-on-white
  are the same picture. Two sets of panels cover about half of it, so inverting gives a field
  of marks on white meeting a field of marks on black along one hard edge — measured and
  looked at, and it is as bad as it sounds. So with a sheet the polarity holds still: both sets
  are ink on paper and what the wave hands over is the turn alone.

`HANDOVER_FIT` is how much of the cross's box the panel gets, and it is not decoration. A cross
fills five ninths of its box and touches its neighbours only at the arm tips; a panel is solid
to its corners, so at full size it runs into the next one along the lattice and a row of them
reads as one black mass. 0.85 keeps them separate while still weaving.

`HANDOVER_SHEET=none` gets the cross back, and with it the polarity.

An earlier version of this file did the same trick with plain squares on a checkerboard, where
the two states are the board and the board slid one cell sideways. It works and the geometry is
sound — a checkerboard is tightest at rest, and turning the squares only opens clearance — but
it stays a checkerboard throughout, where the crosses give a proper figure-ground inversion.

## Map data

`MapData.kt` collects Dutch map data from PDOK, the government's open data service. No
key, no account: `collectMapData()` pulls an extent once into `data/collected/<place>` as
GeoJSON with a `manifest.json`, so later runs need neither network nor credentials.
`MAP_REFRESH=true` pulls again.

Four layers, from two registers:

| layer | source | carries |
|---|---|---|
| buildings | BAG WFS `bag:pand` | footprint, `gebruiksdoel`, `bouwjaar`, `status`, area |
| roads | BGT `wegdeel` | road, pavement and path *surfaces*, not centre lines |
| water | BGT `waterdeel` | `plus_type=gracht` is the moat |
| nature | BGT `begroeidterreindeel` | planting and grass |

**Building function comes free.** `gebruiksdoel` is already denormalised onto `pand` from
the units inside it, so contours and function need no join. It is one of twelve fixed BAG
categories and combines: `winkelfunctie,woonfunctie` is a shop with flats over it. Empty
means the building has no registered unit — a shed, garage or transformer hut — which is
12% of the Den Bosch centre and 91% out at the rural edge. That is the register being
right, not a parsing fault.

Coordinates stay in **EPSG:28992 (RD New)**: already metres, y pointing north. Drawing it
is a flip and a scale, never a projection, and 1 unit is 1 metre on the ground.

Three things are load-bearing and easy to undo by accident:

- **`sortBy=identificatie` on the BAG paging.** WFS may return an unsorted result in any
  order it likes per request, and pages of an unstable order silently overlap and skip.
  Sorted, consecutive pages of 500 came back with zero overlap.
- **BGT features with a non-null `eind_registratie` are dropped.** The register is
  historical and hands back every version ever recorded. Over this extent that is *more
  than half* of what arrives — 8215 retired road surfaces against 6568 live ones — so
  keeping them draws the city as it was and as it is on top of each other. There is no CQL
  on that API to filter it server side.
- **Layers are written compact and rounded to the centimetre.** Pretty-printed GeoJSON puts
  every coordinate on its own line: the roads alone were 315 MB that way against 82 MB now.
  The services return up to eleven decimal places on registers accurate to ~0.2 m.

**Two extents are collected, and they coexist.** `MAP_RADIUS` gives a square around a
point; `MAP_MARGIN` gives the city's own outline grown by that many metres, which is the
only way to promise a margin on every side — the city is neither square nor centred on its
market square, so a radius that clears the far edge overshoots the near one. They cache to
separate folders (`-r1000`, `-m5000`), so switching is one env var and no refetch.

**Past a few kilometres the register has to change.** BGT is drawn to the kerbstone: over
446 km² it would carry six times the vertices for detail far below a pixel, and half of
what it returns is retired versions to throw away again. `MAP_DETAIL=auto` switches to
TOP10NL (1:10 000) above 6 km across. The result is that the 446 km² extent has *fewer*
vertices than the 4 km² one — 7.8M against 8.6M. Buildings stay BAG either way, because
nothing else carries the real footprint and its function.

Note the layer names hold slightly different things across the two: `nature` is BGT
`begroeidterreindeel` (planting only) at detail, and TOP10NL `terrein_vlak` (all land use,
so farmland too) when wide. That is why the wide map has a green ground rather than paper.

**The BAG WFS refuses a `startIndex` above 50 000** — it says so outright and points at the
bulk extracts. So a query matching more than that cannot be paged to the end at all, and
`fetchBagWfs` splits the extent into quarters until every tile is under the limit and pages
each tile on its own. A bbox query returns whatever *touches* the box, so a building on a
tile edge arrives twice; the identifier set is what makes that harmless. The 22 km extent
takes 16 tiles for its 201 346 buildings.

Geocoding the city name does **not** give you the city centre: a woonplaats resolves to the
centroid of the whole municipal area, which for 's-Hertogenbosch is 2.3 km north of the old
town. `MAP_CENTRE` names a street or square instead, or takes an explicit `x,y` in RD.

`MapView.kt` draws the same data coloured by function, as a check on the collection.

`MapGeometry.kt` holds what every map sketch needs: GeoJSON to `Shape` (keeping holes as
holes), `Mesh`/`meshOf` for triangulating a layer into one vertex buffer, and `MapCamera`
for the RD-metres-to-canvas transform with zoom, pan and the extent clip. Both sketches
draw through it, so there is one implementation of the projection rather than two that
drift.

## City map

`CityMap.kt` is the figure-ground plan: buildings solid, everything else the ground they
stand in. Buildings arrive one at a time and the ground fades in under them once enough
fabric is up to read as a city. Drag pans, scroll zooms, `0` resets, `R` replays, space
pauses.

**Everything is real geometry, never an image.** Contours are triangulated once at load
(`org.openrndr.shape.triangulate`) into one vertex buffer per layer, so a frame changes only
the view transform and an edge stays exact at any zoom — at 12x you can read individual
bollards and road markings.

That is also what makes the reveal cheap, and it is the whole trick of the file:
**buildings are stored in the order they are revealed**, so everything already settled is
one contiguous run of triangles — a single draw call for the entire city so far — and only
the ~270 still fading are drawn individually. A frame costs the same whether 10 buildings
are up or 7839. 8.6M vertices triangulate in ~3s and it holds a vsync-capped 120 fps.

`CITY_ORDER=age` is the default and orders by `bouwjaar`, so the city assembles itself in
the order it was really built — the old town first, then the rings. `centre` spreads
outward, `random` shuffles with a fixed seed.

The extent is square and the canvas usually is not, so `CITY_FIT` defaults to `cover`:
fill the frame and crop, rather than letterbox the drawing against empty paper.

**A bbox query returns every feature that touches the box**, so a long canal or rail surface
can reach kilometres past it and streak across the paper. The draw clips to the extent.

### The flight

`CITY_ZOOM_FROM` starts the shot pulled in and pulls back over `CITY_DURATION`. Two things
make it work, and neither is obvious:

**The reveal is tied to the zoom, not the clock.** Asked to reveal 201 346 buildings evenly
over half a minute, the sketch places seven thousand a second — nothing anyone can watch
arrive. So the revealed count is instead held proportional to *the area the camera can
see*: pulled right in, a handful of buildings fills the frame and they land one at a time;
as the view widens the same visible density needs thousands. The rate you perceive stays
about even from the first building to the last.

**The centre never moves.** Two earlier versions tried to follow where buildings were
arriving — first easing toward the middle of the extent, then tracking the newest arrivals
— and both were wrong. The first had left the old town while the old town was still the
only thing being built, giving one lone building in an empty frame. The second tracked
about and read as the shot chasing a single object. The camera now holds the middle of the
collected extent, which for a `MAP_RADIUS` extent is exactly `MAP_CENTRE`, and the city
opens out around that fixed point.

**The opening is stated, not derived.** `CITY_OPENING_COUNT` buildings arrive at an even
rate over `CITY_OPENING_TIME` with the zoom held still, and only then does the pull-back
begin. This has to be explicit because the reveal curve alone cannot do it: over a modest
zoom range the area rule puts 166 buildings down in the first four seconds.

The opening zoom is a measured number, not a taste. The median gap between consecutive
arrivals in age order is 165 m and the 90th percentile is 619 m, so a frame narrower than
that skips past most of them — a first attempt at 25x (a 96 m view) opened on empty ground.
`CITY_ZOOM_FROM=4` gives a 600 m view, where a 12 m building still reads 77 px wide at 4K.

`CITY_GROUND_ALWAYS=true` keeps roads, water and planting up from the first frame.

Recording sets `contentScale = 1.0 / windowScale`, so a clip comes out at the canvas size
and not the window's — the same trick as demo02.

### Buildings as catalogue objects

`CITY_FABRIC=objects` replaces every footprint with Willy Naessens elements packed onto the
ground it stands on: the plan becomes the bin, not the drawing. `ObjectFabric.kt` does it,
reading the sheet through demo02's `loadObjectSheet`.

Because the elements are packed at a **real size in metres** (`CITY_OBJECT_SIZE`, default 8),
the count is not decoration — it is roughly how many precast pieces that plan would take. A
house gets one or two, a warehouse dozens, and the town reads as a field of components whose
density is the density of what is actually built there.

Each of the 115 objects is triangulated **once**, normalised to height 1 and centred, so
placing one is only a scale and an offset however many thousand times it appears. Packing is
demo02's shelf idea at building scale with one difference: demo02 lets an object run past the
end of a line and cuts it, because there the strip is the subject. Here the bin is a real
building, so a candidate is kept only if it fits the row *and* its centre lands inside the
footprint — which is what stops an L-shaped building being filled across its notch. A
building smaller than a single element gets one shrunk to its plan rather than being dropped.

The committed `.env` runs this as the catalogue city: `CITY_FABRIC=objects` with
`CITY_GROUND_LAYERS=none` and the ink and paper inverted, so it is buildings alone, white
on black, with nothing of the map left around them. `CITY_OBJECT_SIZE=18` rather than the
default 8 — the size is a legibility floor as much as a grain, because on the 2 km extent
at zoom 1 an 8 m element is about 8 px and the town comes out as texture rather than as
components. Bigger elements, or `CITY_ZOOM=3` and a crop, are the two ways to get the
pieces to read.

**No two elements touch.** Packing a building on its own is not enough: neighbours know
nothing of each other and their bounding boxes overlap wherever a building sits at an
angle, so elements ran into one another across plot edges. A shared `Occupancy` grid now
refuses any placement that would come within `CITY_OBJECT_GAP` of one already down. The
test is between bounding boxes rather than contours, which is deliberately strict — a
contour always lies inside its box, so boxes kept a margin apart guarantee the drawn edges
are at least that far apart. It costs some density on the diagonal isometric shapes, whose
boxes are loose, but it cannot let two elements touch. A uniform grid keeps it linear over
a couple of hundred thousand placements.

Elements take the size the plan allows, up to `CITY_OBJECT_SIZE`. Drawing them all at one
fixed size was tried and is worse: the size carries the scale of the building as well as
the count, and a terrace of small ones beside a shed of one reads as grain rather than as
a catalogue in several sizes.

**The pull-back stops before the data runs out.** `zoomFillingFrame()` works out the widest
view still entirely inside the extent, and the flight is floored at it, so the edge of the
collected area never comes into shot as a straight line of background. It depends on where
`CITY_FOCUS` holds the frame: the distance to the *nearest* edge binds, so an off-centre
frame pulls back less than one held in the middle.

`CITY_STILLS=n` writes n stills across the timeline and quits, which is how to judge a
change without filming it. Timing is read only inside the draw loop — see the
`ScreenRecorder` note under demo01.

## objects

`Objects.kt` shows one piece of the catalogue at a time, in the round. `J` and `K` step
through the folder, drag turns it, scroll comes closer, `0` resets, space stops the spin.

**`data/objects` is the catalogue as real 3D geometry** — the same 115 precast concrete
pieces the svg sheets hold as flat silhouettes, exported from an IFC model through Blender,
in real metres. `data/unique_objects` is the building those came out of: 388 unique shapes
standing for **89 410 instances**, MEP and steel included, with a `manifest.csv` carrying
instance count, material, IFC class and source model per shape. The 115 concrete pieces are
exactly its `concrete-family+type` rows, renamed by part name — so the two folders are the
same export at two scopes and both load through `ObjMesh.kt`.

The building is called The Circle, and by count it is mostly fixings rather than structure:
the most-used concrete piece is `HPKM39`, an 11 cm column shoe, at 2622, while `TC-BALK` — a
23.8 m beam — appears 466 times. The top ten shapes are 39% of all instances and 46 shapes
appear exactly once.

### Reading the meshes

Three things about these files decide how `ObjMesh.kt` is written, and two of them are traps:

- **Faces are not triangles.** 88% are quads and the rest run to 104 sides, because the
  exporter kept IFC's profiles whole.
- **328 of those faces are concave**, which is what rules out the obvious fan triangulation.
  A wall panel with an opening in it is exactly the case a fan gets wrong, and it gets it
  wrong by drawing a triangle straight across the opening. So faces are **ear-clipped** on a
  projection onto their own dominant plane. `WAND_27`'s doorway is the proof: it is there
  only because of this.
- **Coordinates are real metres, Z up** — IFC's convention, not OPENRNDR's, so the loader
  turns them Y up and keeps the real size for the caption.

The manifest is a free oracle for the first of those: its `triangles` column matches
`sum(n-2)` over the faces for all 388 files, so the arithmetic can be checked without
rendering anything.

Meshes are normalised to a unit bounding sphere and centred, which is what lets one camera
suit a folder spanning five orders of magnitude — `VLOER_2` is 144 m across and
`RASTERPLAAT` is 5 cm.

### Framing, and why the numbers cannot pick a piece

**Almost nothing in the catalogue fits a 16:9 frame.** These are structural components, so
they are long: columns run 19:1 to 25:1, beams 13:1. Framed wide, any of them is a hairline.

**And sorting cannot choose from what is left.** By face count the richest piece here is
`KUNSTSTOFUITSPARING_3` — 135 faces, 1.88:1, half as deep as it is wide, the best fit by
every measure. It renders as a smooth grey disc, because its faces are one round profile
finely cut rather than any articulation. This is the same trap already recorded for the svg
sheets, where a slab is a rectangle and `DRST_M24_1500` is a hairline: complexity in the file
is not form on the screen. `OBJECTS_CONTACT=true` draws all 115 into one sheet, and the eye
settles in a second what the sort could not.

**The camera fits the silhouette, not the bounding sphere.** Fitting the sphere is the easy
version and it wastes the frame, because nothing here is ball-shaped — a wall panel fitted
that way sits in about 60% of the height. Each piece is fitted instead by its reach around
the spin axis and its half height, whichever binds. Neither number changes as the piece
turns, so the fit holds all the way round and the object does not breathe while it spins.

### Colour and edges

**Each side takes its own colour and every edge is drawn twice** — a dark line on the edge
with a lighter one just inside it. That pair is the point: one line cannot read against
arbitrary colours, because black vanishes into a dark face and white into a pale one.
`OBJECTS_PALETTE` cycles a list of hex values, `colour` gives every side a hue a golden angle
from the last, and left empty it is black and white.

**Black and white has to come from which way a face points, not from its place in the file.**
Stepping black and white down the face list was the obvious way and it does not work, because
the order faces appear in a file has nothing to do with which of them meet: it alternates on
`FUND` by luck and leaves every visible face of `WAND_27` white, which comes out as a bare
line drawing. Orientation cannot fail that way — the sides of a piece point along different
axes by definition. Three orientations against two tones means one pair still shares a tone,
and that is what the edges are for. The light is kept weak
(`OBJECTS_SHADE`, 0 for wholly flat) because the colours are what separate the sides and a
gradient across them only muddies it.

Two things this rests on, both in `ObjMesh.kt`:

- **Colour is baked per face at load.** Once a polygon has become three triangles there is
  nothing left to say they were ever one side, so it is decided at the one moment the loader
  still knows.
- **Only real edges are drawn**, and being an edge of the original polygon is not enough to
  qualify. Ear clipping fills a face with diagonals that are edges of nothing, so each
  triangle carries a mask of which of its three edges came from the ring — but the ring lies
  too. The exporter writes a face with a hole in it as **two coplanar polygons meeting along
  a seam**: `WAND_33`'s window is a nine-corner ring that detours into the opening and comes
  back, and those seam edges are ring-adjacent like any other. Drawing them puts two long
  diagonals across a blank panel, which is what the first version did.

  The test that works is the crease. Every edge in `data/objects` is shared by exactly two
  faces — all 10 431 of them — so there is always a second normal to compare against, and an
  edge is real when the faces either side of it actually turn a corner. `OBJECTS_CREASE` is
  the threshold in degrees. Width is measured in pixels off the barycentric derivative, so a
  line holds its weight at any angle.

Colouring the contact sheet also makes the earlier point visible at a glance: the pieces with
the highest face counts — `HIJSOOG_DIAM_63`, the `KONN_ANKER` family, `PAAL` — come out as
dense colour striping, which is exactly the round-profile tessellation that fooled the sort.

### Two styles

`OBJECTS_STYLE=poster` is the default and is all shader work: no outlines at all, the forms
told apart by tone alone. `line` is the black-and-white technical drawing described above.

Three things make the poster style, and each is a decision rather than a setting:

- **Three tones, keyed to the axis a face points along.** Any corner of a box shows three
  faces on three different axes, so they always land on three different values and the form
  reads with no line on it. It has to be the *object-space* normal: a view normal would swing
  the tones around as the piece turned, and the point is that a face keeps its tone.
- **One gradient down the whole piece**, not one per face, so the object is lit as a single
  thing and faces sharing a tone stay a family.
- **The shadow is a shear, not a projection.** A parallel light on a parallel camera means
  laying the piece flat on its floor is one matrix — no second camera, no shadow map. It is
  rendered to its own buffer and blurred before being laid under the piece, because the
  flattened copy overlaps itself and would darken where it doubled back.

Both ramps are dithered by half a level. A ramp this shallow crosses far fewer than 256
values across a 1920px frame, so undithered it quantises into visible rings — which it did.

### Lettering on the faces

`OBJECTS_TEXT` tiles a word over the surfaces and walks it slowly across them. Two things
about it are worth keeping:

- **The meshes carry no texture coordinates.** They are structural components exported from
  IFC, not models made for rendering, so there is nothing to unwrap. The shader uses a face's
  own two in-plane object coordinates as its uv instead, picked by the axis the face points
  along. The consequence is that the word runs *continuously across the piece* rather than
  being fitted to each face — it steps round a corner the way a decal would.
- **The u axis is flipped by the sign of the normal**, which is the rule a cube map uses.
  Taken raw the word comes out mirrored on every face pointing down a negative axis, which is
  exactly what the first version did on all six sides.

Two further traps, both found the hard way:

- **The uv is centred on the tile, not on its corner.** A piece straddles zero, so the raw uv
  range sits across the tile's *edges* — and the word is in the middle. At about one repeat
  per piece the lettering vanished entirely.
- **Most macOS fonts are collections, and stb_truetype will not open one.** `Rockwell.ttc`
  holds Regular, Italic, Bold and Bold Italic in one file, and `loadFont` fails on it
  outright — not with the wrong weight, with `failed to load font`. So there is no way to ask
  it for a bold. `FontCollection.kt` lifts the named face out into a standalone font under
  `build/` and that is what gets loaded. A collection is not a special format inside: each
  face is an ordinary sfnt table directory sharing the file, so writing one out on its own is
  copying the tables it names and rebuilding its directory with the offsets moved. No glyph
  data is touched and no font library is needed.
- **The word is set to the tile, not the tile to the word**, so type stays one size on the
  surface whatever is typed — otherwise a long name runs off its tile and is cut in half. The
  fit is kept to 78% of the tile, because the sum of the glyph advances is not the ink: at
  88% the trailing S of a 13-letter name painted past the edge and, the tile being wrapped,
  was lost rather than merely cropped.
- **`loadFont` parses its path as a URI**, which rejects a space, and nearly every font on a
  Mac lives at a path with one in it. `OBJECTS_TEXT_FONT` copies such a file to a plainer path
  under `build/` and loads that; a path that does not resolve at all falls back to the bundled
  font rather than stopping the run. `data/fonts` holds one weight of IBM Plex and no bold, so
  a bold has to come from outside the project.

`OBJECTS_TEXT_SCALE` is how much of the piece one repeat covers, so smaller means bigger
type; `OBJECTS_TEXT_SPEED` is repeats a second. The clock reaches the shader from the draw
loop only — see the `ScreenRecorder` note under demo01.

### The grid, and equal spacing

Two things had to be measured rather than bounded before the field read evenly.

**Every copy is one size**, and fitting each cell separately is wrong: unequal cells draw the
piece larger in a tall row than in a short one, and the field reads as a set of sizes rather
than as one object repeated.

**The gap is what is held fixed, not the cell.** Unequal cells were tried first and cannot
work here — with every copy at one size, unequal cells necessarily leave unequal space
between the pieces, and what the eye reads in a field like this is the space. So one gap,
measured against the piece, is used in both directions and the size is whatever then fits.

That still came out 15% wider down the frame than across it, because the spacing was using
`spinRadius` and `reachY` — the worst case over a whole turn, which is right for holding the
*size* steady as the camera tilts but carries slack as padding. Spacing now uses the piece's
**measured** half-extent, its corners projected onto the view axes at the home angle: a
constant, so the layout still does not move when the camera does, and the gaps come out equal.

### Isometric

`OBJECTS_PROJECTION=iso` is the default, and it is a projection rather than a camera angle:
**orthographic**, at 45 degrees and an elevation of `atan(1/sqrt 2)`, which is the angle that
foreshortens the three axes equally. Standing a perspective camera at those angles is not the
same thing — the far end of a 23 m beam still tapers. With no vanishing point the camera's
distance stops meaning anything, so it only has to stand clear of the piece and the frame is
sized instead of the camera moved: `fitHalfHeight` is the orthographic twin of `fitDistance`.
`perspective` gives the ordinary camera back, which is better for judging a piece as an object
rather than as a drawing.

### The grid

`Q`/`A` add and drop a column, `W`/`S` a row, from the single piece up to a field of itself;
`OBJECTS_GRID_X`/`_Y` set where it opens, and it opens on the single piece. Column widths and row heights come off a fixed seed
rather than being equal, so it has a beat to it instead of reading as graph paper.

**The grid is irregular in where things sit, not in how big they are.** Every copy is drawn at
one size. Fitting each cell separately is the obvious thing and it is wrong: the cells are
deliberately unequal, so it draws the piece larger in a tall row than in a short one and the
field reads as a set of sizes rather than as one object repeated.

What limits a common size is not the smallest cell but **the closest pair of neighbours**,
which is a weaker constraint and worth the difference — a narrow column beside a wide one
still leaves its piece plenty of room, where sizing off the narrow cell alone shrinks
everything to match the worst case. So the size is the largest that clears every neighbour and
both ends of each axis.

The name, size and count under the piece are off — what is on the paper is the drawing and
nothing else. `OBJECTS_CAPTION=true` puts them back, which is worth having when stepping
through a folder of 115. With a caption the drawing stops above it rather than running under
it, and the clearance above then has to be measured from the axis centre rather than from the
frame, since that axis is no longer the frame.

**It is a property of the projection rather than a layout pass.** A parallel view has no
vanishing point, so putting the piece elsewhere on the paper at another size is just a shift
and a scale of the orthographic window — no second camera and no model transform, and every
cell is necessarily the same piece from the same angle. It follows that the grid is
orthographic only: under perspective each cell would want its own frustum, so a perspective
run shows the single piece.

`OBJECTS_STILL=true` writes the opening piece to `screenshots/` and quits, which is how to
judge a change without watching it. Timing is read only inside the draw loop — see the
`ScreenRecorder` note under demo01.

Note `orx-obj-loader` is enabled in `build.gradle.kts` but the loader here is hand-rolled,
because the concave faces need ear clipping and owning the parse also gives the real metres,
the axis flip and the fit measurements in one pass.

## slideshow

`src/main/kotlin/slideshow` is the presentation system: a deck of sketches, clicked
through, some of them with clicks inside them.

```
./gradlew run -Popenrndr.application=SlideshowKt
```

The show is a handful of keys: `->` and `<-` for clicks, `up`/`down` for whole slides,
`0` back to the first slide, `r` to replay the current one, `d` for the debug view, `esc`
to quit. `0` cuts and resets the slide's own frame count, so the deck is left exactly as it
boots — starting over rather than a move in the show. There is no mouse binding and no
jump-to-any-slide key — a stray click cannot advance a talk, and `SLIDES_START` is how you
open on a slide while working on it.

The folder is in three parts. **`Slideshow.kt` is the show** — the one file to open, and
the only one to edit to change what the talk is. **`slide-drawers/` is a file per slide**,
so adding one is adding a file rather than growing a list. Everything else is the engine,
which never mentions configuration: it takes a `Show` value, which is what lets a deck be
run from a second launcher, or from a test, with no `.env` anywhere near it.

`Slideshow.kt` has **no `package` declaration** while the rest of the folder is `package
slideshow`, and that is the only reason it is not packaged: `Env` lives in the default
package and Kotlin cannot import from the default package, so the file that reads `.env`
has to sit in it too. `slide-drawers/` mismatches its package the other way round — a
package name cannot hold a hyphen, so the folder is `slide-drawers` and the package is
`slideshow.drawers`. Kotlin does not require them to match, and the folder name is the one
that has to read well in a file tree.

**A slide is a pure function of one number.** `Stage.position` is a continuous, eased
step index: at rest it sits on a whole number and a click moves it to the next one over
the slide's `stepFrames`. Everything a slide reveals is a clamped window on that one
value, which is what `stage.on(n)` is —

```kotlin
drawer.circle(stage.center, 160.0 * stage.on(1))   // arrives on the first click
drawer.rectangle(bar(stage.between(2, 4)))         // arrives on the second, leaves on the fourth
```

so a build-up and a build-down are the same expression read either way, and neither needs
a variable. This is `packBoxes` and `stateAt` again: one number in, a whole composition
out, and the same payoff — change the layout and it keeps animating for free. It is also
what makes the deck steerable at all. Clicking back is not an undo, it is a smaller
number; jumping to slide 4 is not a fast-forward through the ones before it. A slide that
recorded what had already happened could do neither.

**Time is frames, and the clock is read in one place.** A slide says "30 frames", never
"half a second", and the only call to `program.seconds` is in the driver's draw loop —
which is exactly what `ScreenRecorder` replaces with video time (the note under demo01).
Key handlers move the deck and take no timestamp, so there is nothing to drift. `FPS` is
the rate frame counts are quoted at, not the display's: on a 120 Hz screen the clock still
advances 60 a second, and recording at another rate still plays back at the right speed
because the frame count is derived from `seconds` rather than from frames drawn.

**Each slide is drawn into its own buffer and the transition composites the two.** That
is what lets slides with different grounds hand over cleanly — there is no moment where
one background is painted over the other, only two finished pictures being mixed. `Cut`
(no handover at all), `Fade` and `Push` are the three; a slide declares how it *arrives*,
and stepping backwards replays that same transition reversed, so going back undoes exactly
what going forward did. Stepping back off the front of a slide lands on the **last** click
of the one before it, fully built, rather than resetting it.

`SLIDES_STILLS=true` writes one png per click of every slide and quits, which is how to
check a change without clicking through it. It cannot show a transition, though — stills
are all taken on cuts. Judging a handover needs `SLIDES_RECORD` with `SLIDES_AUTOSTEP`,
and then reading frames off the clip: the push is right when the two slides are edge to
edge with no gap and no overlap, and the cut is right when consecutive frames go from one
background to the other with no blended frame between them.

`SLIDES_DEBUG` (or `d`) puts up the overlay, drawn on the **window** on top of the
finished frame rather than into the canvas — so it scales with the screen instead of the
composition and never lands in a still or a clip. Three plates:

- **left, live**: slide, click, `position`, the click being played and how far into it —
  `build down 0.25 / 0.70s 36%` — the loop as elapsed against its length, frame and fps,
  and the handover while one is running.
- **right, the deck's timings**: every slide with its click count, how long one click
  takes, its loop and how it arrives. This is what answers "how long is that reveal, and
  how long does the build down take" without clicking to either of them — both are a
  slide's own `stepFrames`, and slide 4 of the demo runs at 0.70s against the deck's 0.45s
  because a build down that leaves at reveal speed reads as things going missing.
- **bottom**: the deck as a strip, one segment per slide subdivided by its clicks.

Durations are quoted in seconds because a rehearsal is timed in seconds, but frames are
what the deck runs on — `frames(0.7)` is the thing that is set.

The overlay times a click but cannot tell one that reveals from one that takes away, since
that is inside `draw`: a forward click reads "reveal" and a backward one "build down", and
`stepName(step)` is how a slide corrects that. Demo slide 4 builds *down* on a forward
click and says so.

With the overlay up, `p` holds the clock and `.` steps it a frame at a time, which is the
only way to look at a transition properly. Those keys belong to the debug view rather than
to the show and are dead while it is down.

**Everything is composed at the canvas size and the window only shows that image**, the
same arrangement as demo02 and Decision: one render target at `SLIDES_WIDTH` x
`SLIDES_HEIGHT`, mipmapped, drawn into the window at `SLIDES_WINDOW_SCALE`. So the preview,
a still and a recorded frame are the same pixels at different sizes, and the composition
does not change with the window. The committed `.env` runs the 3840x1080 frame at half in
the window. The canvas is *fitted* into the window rather than stretched to it, so
`SLIDES_FULLSCREEN` on a projector of another shape letterboxes instead of distorting.

That only pays off if slides lay out against `stage.bounds` rather than in fixed pixels.
The demo takes sizes off the **height** — the dimension every canvas here shares —
and positions off the width, so the same deck composes at either. Written against 1920 in
absolute numbers it sits in the left third of the wide frame, which is what the first
version did.

### Declaring the show

The running order is a Kotlin builder in `Slideshow.kt`, and so is the shape of the frame
it plays in:

```kotlin
val show = slideshow {
    canvas(3840, 1080)
    window(0.5)

    chapter("Introduction") {
        subchapter("Opening") {
            slide(TitleSlide())
            slide(RevealSlide(), title = "Circle, then square", notes = "...")
        }
    }
}
```

This was a json file first, and Kotlin is better here for one reason that matters:
**`slide(TitleSlide())` is the constructor, not a name to look up.** A json deck can only
carry a slide's *name*, so it needs a registry mapping names to constructors — a second
list to keep in step, and a typo that surfaces only when the file is read. In Kotlin the
compiler resolves it, the IDE completes it, renaming the class updates the show, and a
slide that does not exist will not build. Chapters and titles read the same either way, so
the json bought nothing and cost the registry. It also means a slide is an ordinary object
here: constructed with arguments, used twice under two titles, or built in a loop.

`title` renames an appearance in the debug view when the class name is not what you want to
read, and `notes` is whatever you want to remember about a slide — both optional, both only
ever shown in the debug view.

**The show declares, `.env` overrides.** `withEnv()` lets any `SLIDES_*` key win for a
single run, and an empty key leaves the show's own value standing. So the talk is committed
in one file and the run is steered from another: nothing set for an afternoon's filming —
`SLIDES_RECORD`, `SLIDES_AUTOSTEP`, `SLIDES_START` — ends up committed as part of it.

**The show is declared as a hierarchy and played as a flat list, and both are true at
once.** Chapters and subchapters are what the file says and what the debug view reports —
its first line is `1.2  Motion / Up and down`, and the deck strip along the bottom breaks
where the chapters break, so the shape of the talk is visible and not just its length.
The deck itself steps through the slides in the order they were declared, one after
another, with no boundary a click can feel: a chapter is something you can see in the show,
never something you have to navigate.

`runningOrder()` prints the tree at startup, which is the whole talk on a few lines:

```
1  Introduction
   1.1  Opening
       1  Title                1 click   0.45s  cut
       2  Circle, then square  3 clicks  0.45s  fade
2  Motion
   2.1  In a loop
       3  Loop                 2 clicks  0.45s  loop 5.0s  push
```

### Putting a sketch in the deck

`Swivel01Slide` and `Swivel02Slide` are `demos/Swivel01.kt` and `demos/Swivel02.kt` moved
across, and what had to change moving them is the whole of what a slide asks of a sketch:

- **`program.seconds` becomes `stage.frame` or `stage.loop`.** A sketch may read a clock; a
  slide may not. The deck has to be clickable backwards, jumpable into and pausable, and
  none of that works against wall time — under `ScreenRecorder` the two disagree outright
  (the note under demo01). Swivel01's sweep runs off `stage.frame`, so it plays the same on
  its tenth frame whether it is watched, recorded or stepped. Swivel02 declares
  `loop = frames(40.0)` and reads `stage.loop`.
- **A loop that was a coincidence becomes a statement.** Swivel02's terms came back around
  every forty seconds because of how its constants happened to multiply out. Written against
  `stage.loop` they are a whole number of cycles *by construction* — four swings and four
  slab-widths of travel — so the seam is exact rather than nearly right. Checked rather than
  assumed: the visible slabs at phase 0 and phase 1 are identical in position, depth and
  rotation.
- **`configure { width/height }` becomes a fit.** The sketches framed a 4:1 banner; a pane is
  16:9. The scene is in world units, so the drawers take `across` — how much world the pane
  shows — and derive the vertical from the pane's own shape, rather than stretching a
  sketch's window shape into a slide's.
- **Anything the sketch built at startup goes in `load`.** Both build a `boxMesh` there, so
  no click ever waits on a vertex buffer.

Swivel02 also referenced a `colors` list that was not in the file, so the project did not
build; it now carries its own three values.

#### The slabs carry copy

`SwivelBlock` is what a slab says — a headline, an optional note under it, or a list set
small and ranged left — and `Swivel02Slide(blocks = listOf(...))` is the whole of putting
words on the train. The slabs take the blocks in turn and repeat, so the same drawer stands
up another train of another thing with a different list and nothing in the file changes.

**The type is one size across the whole train, not fitted to each slab.** This is the one
place the deck deliberately does *not* use `setToFit`: fitting would set "19 bedrijven" half
again as large as "950 medewerkers" purely because it is shorter, and a row of figures that
are all the same kind of thing has to look like it. So the size is stated and only the
wrapping varies.

**The loop closes for any number of blocks.** The travel, the swing and the four-slab
pattern of turns and depths only come back together after `lcm(4, blocks.size)` slab-widths,
which for five blocks is twenty. Two things had to change for that to hold, and the second
is the one that bites:

- The turn is `lcm` slab-widths long rather than four, so the copy is back where it started
  at the seam as well as the geometry.
- **The slabs drawn are the ones around wherever the train has got to**, not a fixed
  `-10..10`. A fixed range survives a four-wide cycle and nothing longer: at twenty the
  train has travelled entirely off the right of the frame by the end of the turn and left an
  empty stage behind it. The range now follows the travel, and closure was checked
  numerically for one to seven blocks rather than watched.

Two traps in drawing on a face, both of which render something plausible:

- **The face is mirrored, so X turns as well as Y.** The camera stands at negative Z looking
  back, which puts local +X on the *left* of the screen — the same reason Swivel01's
  rightmost panel appears on the left. `scale(-1.0, -1.0, 1.0)`, not `scale(1.0, -1.0, 1.0)`.
- **`text()` draws in whatever face the drawer was last left holding.** `TypeBlock.draw`
  sets `fontMap` itself, so a headline is always right; the list did not, and came out in a
  previous slide's 22pt caption face. Anything calling `drawer.text` directly has to set the
  face first.

#### The city on two clicks

`CityMapSlide` is `CityMap.kt` in the deck, and it is the case where **only the timing had
to change**. The sketch is a film — a reveal on a clock, a flight over `CITY_DURATION`, the
grid at `CITY_GRID_AT`, the cull at `CITY_LAST_AT` — and none of that survives a deck,
because a slide may be clicked backwards, jumped into and paused, and a timestamp can do
none of the three. Each beat is a window on `position` instead:

    0   the whole town, every element standing on the plan it was packed onto
    1   the camera pushes in, and the elements it lands on lift off the plan into a grid
    2   the grid empties from the outside in until one element is left in the middle

The grid gathers over the back of the *first* click rather than getting one of its own, so
each click is one legible change — the town closes in and orders itself, then everything
goes but one — and the slide rests on a settled grid you can talk over.

**The keys that say what is drawn are kept; the keys that say when are gone.**
`CITY_FABRIC`, `CITY_OBJECT_*`, `CITY_INK`/`CITY_PAPER`, `CITY_ZOOM_FROM`/`_TO`,
`CITY_GRID_MAX`, `CITY_LAST_*` and the `MAP_*` extent are read as the constructor's
defaults, so the slide comes up as whatever the sketch was last tuned to and the two cannot
drift apart. The reveal goes with the timing keys — buildings arriving one at a time is a
rate against a clock, and a click is not a rate. The committed `.env` already ran it
`CITY_BUILDINGS_ALWAYS=true`, which is the same picture, and a slide's opening state has to
be something you can hold on anyway.

**Do not ease `stage.on(n)`. The deck has already eased it.** `position` is
`easeInOutCubic` of the click's ramp, so a slide that eases it again eases twice, and the
move collapses into the middle of the click: measured off a 12-second push filmed that way,
the camera had finished by 8.5s and the last three and a half seconds were a still frame.
It looks like a taste problem and is an arithmetic one — the ink curve over the clip is
flat from 21.5s to 26s, and it is flat because of the second ease. Anything that *travels*
here is now drawn straight off `position`.

**Anything that is counted rather than travelled undoes it instead.** The cull removes an
element at a time, and on an eased position the removals crowd into the middle of the click
and read as a swell; the sketch had them linear for exactly this reason. `linear()` is the
closed-form inverse of `easeInOutCubic`, so `linear(stage.on(2))` is the fraction of the
click that has really elapsed. The two kinds of term are the distinction to keep in mind:
travel keeps the deck's ease, counts undo it.

**The closing beat runs over a quarter of its click.** A slide has one click length and the
push wants twelve seconds; the cull wants three, so it says so as a fraction —
`cull = linear(on(2)) / CULL`, clamped — and the rest of the click is the slide holding on
the result.

**The elements are hidden outright, one at a time, not faded.** Fading them was tried:
with a fade of a few ranks a dozen elements are part-way out at any moment and the field
reads as *dimming*, where an instant removal reads as a count. It also has a trap that an
instant removal does not — the count then has to run past the last rank by the fade's own
length, or the element that leaves last is still most of the way visible when the click
lands, which is invisible in a clip and shows up at once as the slide after it failing to
open on the same frame.

**It states the pane it composes for rather than reading `stage.bounds`, and it is the only
drawer that does.** Which elements join the grid is decided by the frame the push comes to
rest on, and the mesh is then built with those elements held out of it — all in `load`,
which is not told the pane. Doing it on the first frame instead would put a second of
packing and uploading on the click that brings the slide up, which is the one thing `load`
exists to prevent. So the shape is a constructor argument and `draw` *fits* what was built
into whatever pane it gets, the same fit `present` uses to put the canvas in the window.

**It has no `package` declaration**, for the same reason `Slideshow.kt` has none: the whole
city pipeline — `collectMapData`, `MapCamera`, `Mesh`, `objectPlacements`, `gridMoves` —
lives in the default package, and Kotlin cannot import from the default package into a named
one. A drawer standing on it has to sit in it too. `slide-drawers/` is a folder, not a
package, so this costs nothing but the note.

It adds about ten seconds to startup, all of it in `load`: collecting from
`data/collected`, packing 7839 plans and triangulating them. That is the deal `load` makes —
the show boots slower so no click ever waits.

#### A seamless cut, and the tree that takes the handover

`TreeSlide` opens on the frame `CityMapSlide` closed on — the same element, at the same
size, in the same place — so the cut between the two cannot be seen. Then one click fans a
node-link tree out of it: the labels ranged down either side of the pane and a curve running
from each of them into the middle.

**An ordinary cut between two slides that "draw the same thing" is not seamless, and that is
the whole reason [`Mark`](src/main/kotlin/slideshow/Mark.kt) exists.** Two slides computing
the same position and size from the same inputs agree only to the accuracy of two separate
pieces of arithmetic, and a couple of pixels of disagreement is exactly what the eye catches
on a hard cut. So the shape is *handed over* instead: `CityMapSlide.closingMark` carries the
survivor's own triangles, the pane pixels it stood at, and the ink and ground it stood on,
and the tree draws that. The two frames diff to nothing — measured, not judged: the city's
last still and the tree's first still are byte-identical across the whole 1920x1080 pane.

`Mark` is in `package slideshow` rather than beside either drawer, and has to be: the city
is in the default package (it stands on `collectMapData`) and the tree is in
`slideshow.drawers`, so the only place both can see is the engine's own package. It is a
reasonable thing to have there — a shape one slide leaves standing, in the units the next
one draws in — and its triangles are already flipped to y-down so the receiver does not have
to know they came out of a world.

**The handover is a load-order dependency, and that is the one fragile thing in it.**
`opening = { city.closingMark }` is called in the tree's `load`, and it can only answer if
the city was declared *earlier in the running order*, because `present` loads slides in
declaration order. Declared the other way round the tree opens on its own fallback, which
draws perfectly well and is silently wrong. `city` is therefore a named value in
`Slideshow.kt`, above `show`, for the same reason `panelFont` is.

The tree itself is three decisions:

- **The links are d3's `linkHorizontal`** — a cubic with both control points on the midline
  between its ends, which is what gives the tight bundle at the root and the even fan out to
  the labels. Growing one is `contour.sub(0, t)`: a shorter piece of the same curve, rather
  than a different curve.
- **The type is one size across both sides**, set to the tighter of the row pitch and the
  measure a column may take. So any number of labels of any length composes rather than
  clipping, and — the same argument as Swivel02's slabs — a list of things that are all the
  same kind of thing has to look like it. Fitting each side on its own would set a short
  list larger than the long one beside it.
- **The fan opens from the middle outwards**, staggered by each row's distance from the
  centre line, because that is the order a bundle can actually come apart in. Opened all at
  once the curves separate everywhere simultaneously and read as a wipe.

The root shrinks from the city's size to node size and turns from the city's ink to the
accent over that same click, so the recolour is part of the opening rather than a cut of its
own — which is what lets the first frame still be the city's exactly.

#### The globe

`GlobeSlide` is the same words again as a turning globe: a dot swells into a disc, and a word
at a time comes round it until the ring closes.

**The ring is rigid and turns as one body.** Every word sits at a fixed angle of its own — `i`
places back from the first — and the whole ring is rotated by a spin linear in time, so
everything on it moves at exactly one rate for the whole slide. Words are added at the
trailing end of the arc, which grows a word at a time until it meets the first again.

That is worth stating because the obvious arrangement is not rigid and looks it. Anchoring the
*newest* word at a fixed point on screen and counting the others back from it makes every
angle a function of how many have arrived — so the **arrival rate leaks into the rotation**,
and with arrivals that quicken the ring visibly unwinds early and settles late. The spin was
constant the whole time; what was moving was the ring deforming under it. It reads as a motion
problem and is a placement one.

**The type is one size and never changes**, and the pitch between two words is the angle one
line of that size takes at the full radius. Those two being constants is what leaves the
rotation as the only thing moving. The size is worked out once, from the finished globe and
the whole list: the ring's own spacing at full radius, and the room the longest word needs
between that radius and the frame. `size` sits where those two limits meet, which is where the
type is as large as it can be — a smaller disc and the ring binds, a larger one and the frame
does.

**The globe opens carrying words rather than empty**: `opening` are on the ring from the
first frame and the rest arrive up to `fill`. The ring takes its words from `labels` in turn
and comes round again when the list is shorter than `fill`, which is what keeps a hundred
words on it without inventing copy for them — and what the reference for this slide does.

**The radius is worked out, not stated.** The ring's own spacing gives a type size that grows
with the disc; the room out to the frame gives one that shrinks with it; where the two cross
is where the type is as large as it can be, and that is the radius. So a hundred words compose
as readily as forty — the disc simply comes out bigger. `size` overrides it for a composition
that wants another balance.

**The swell is an entrance, not a growth that runs through the piece**, and it has to be. A
globe that goes on growing has to go on packing its words tighter to keep them shoulder to
shoulder, and packing tighter is a deformation — the ring stops being rigid and the rotation
stops being one motion. A ring that opens already loaded cannot swell at all: its words are at
the finished pitch from the first frame and a smaller disc cannot hold them apart, so `from`
is 1.

**Three earlier arrangements failed here**, and each looks like a tuning problem and is not:

- *A ring at its finished spacing with the type sized to it* is the answer, but only once the
  disc is at full size. Reached any other way — a globe still growing, a type still
  shrinking — something has to move that is not the rotation.
- *Dividing the circle by what has arrived so far* gets large type early, and leaves half a
  turn of nothing between two words. That is not a ring being filled.
- *Sizing the type to the longest word on the ring* keeps them shoulder to shoulder and large
  at the start, but shrinks everything in a step the moment a long word lands.

**The pitch is floored at the finished ring's spacing**, so the circle closes exactly on the
last word rather than a degree or two either side of it. Measured on the settled frame: no gap
where it meets, and no overlap.

**The arrivals start slow and quicken** — `ramp` below 1 spaces them as `(k/n)^ramp` across the
build. With the ring rigid this is now purely when a word appears; it cannot touch the spin.

**It builds on `stage.frame`, not on clicks.** Forty-odd clicks to set out forty-odd words
would be a slide nobody could talk over. `stage.frame` is frames since this slide came up, so
replaying it or jumping into it starts the build again from nothing and a recorded run is
identical to a watched one — which a wall clock would give none of.

**The labels turn with the globe**, so a word is upside down for half of every turn. That is
the piece rather than an oversight: they are fixed to the thing that is spinning, and flipping
them to stay upright would snap each one over as it crossed the bottom.

### Writing one from scratch

Whatever a sketch does in `extend { }` goes in `draw`, and whatever it loads at startup
goes in `load` — which runs for every slide in the deck before the first frame, because a
show must not stall on a click. Write the class in `slide-drawers/`, then name it in
`Slideshow.kt`; there is nothing else to register. The five demo slides there are one of
each kind: a
one-click opener on a cut, a three-click reveal, a five-second loop that pushes in, a
build-up-then-down, and a hard cut.

### Two panes

`panel(::ChapterPanel)` in `Slideshow.kt` splits the canvas: a left pane carrying the
chapter and subchapter, set large, and the slides on the right getting what is left over.
On the committed 3840x1080 canvas that is two keynote-sized 1920x1080 sides flush against
each other — `panel(::ChapterPanel, width = 1920, gap = 0)`, which are also its defaults.
The two grounds meeting is what divides the frame; `gap` opens a gutter instead, and the
canvas has to carry it (`canvas(3908, 1080)` with `gap = 68`) or the difference comes out
of the slide's own pane rather than the card's. Leave `panel(...)` out and a show is single-pane, exactly as before: `Show.panels`
and `settings.panelWidth` stay empty/null and `present()` takes the code path that paints
straight into the outer canvas with nothing else allocated.

**The card is a slide, not a decoration drawn into a corner.** `ChapterPanel` (in
`slide-drawers/`) is an ordinary `Slide`, built from a `Section` — `number`, `chapter`,
`subchapter`, read straight off the `chapter { subchapter { } }` nesting that already
declares the running order, so there is nothing to keep in step by hand. Being a real slide
is what lets it have its own `transition` (a `Fade`, by default) and its own handover, held
in a **second, independent `Deck`** running alongside the slide deck — `present()` composes
two panes into the outer canvas every frame instead of one, each with its own leaving/
arriving buffer pair for its own handover.

**Which card is up is never driven by a key.** It is driven, once a frame, from the slide
deck's own position: `show.panelOf[deck.index]` says which panel goes with the slide
currently up, and the panel deck is only asked to move when that number changes from the
frame before. That is the whole of "the left side stays visible as long as you're in that
chapter/subchapter" — the panel simply is not told to do anything while the slides beside
it are clicked through, and a `Deck` that is never re-targeted holds exactly where it is.
When the section *does* change, the panel deck is sent to it with `goTo(target, step, cut =
false)` — the same call the slide deck's own `nextSlide`/`previousSlide` make — so stepping
forward across a chapter boundary plays the card's transition forward and stepping *back*
across one plays it in reverse, for the same reason going back through the slide deck undoes
what going forward did: `goTo` computes `reverse = target < current.index` fresh every time,
regardless of which deck it belongs to.

**A card announces its chapter before it stands beside it, and that beat is a click of its
own.** A card comes up *over the slide pane*, holding the whole right-hand frame with the
title on it and nothing else, and the first click carries it across to the left, uncovering
the slide as it goes. So a chapter is opened rather than appearing already half spent beside
a slide nobody has seen yet.

Three things make that work, and each is somewhere different:

- **The card declares the click.** `ChapterPanel.steps = 2`, and that is what a card opts in
  with — a one-step card never moves and sits on the left from the first frame, exactly as
  before, so the two-pane arrangement still works with any panel drawer.
- **`present` composites it, because no card can draw the move itself.** A pane is 1920 wide
  and the move crosses 3840, so where the card *is* cannot be a translation inside its own
  buffer. The driver reads `stage.on(1)` off the card and offsets the finished pane by
  `slideOffsetX * (1 - opened)`. It also means **the slide pane is composited first and the
  card over it** — for half a click the card is in front of the slide, which is only
  possible in that order. At rest the two do not overlap and the order costs nothing.
- **The driver routes the click.** While a two-step card is still at step 0 the arrows
  belong to *it*: `→` opens the card instead of advancing the slide deck, and `←` at the
  first click of a section closes it back over the slide, so going back undoes exactly what
  going forward did. `0` puts the card back over the slide and replays its fade, which is
  what "exactly as it boots" means now.

Two consequences worth stating, because both were decided rather than fallen into:

- **Which step a card arrives on depends on the direction.** Stepping *forward* into a
  section lands on its first slide's first click, and the card opens over it; stepping
  *back* into one lands mid-chapter, where the card belongs on the left and was never
  opened again, so it arrives already across. `atSectionStart()` is the test, and it is the
  same test that closes a card left standing when `up`/`down` cross whole slides inside a
  section without ever offering it its click.
- **Stills hold every card closed.** A contact sheet is a record of the slides, and a card
  standing open is a move rather than a state of one — left open it would cover the first
  slide of every chapter.

**One card per section, not one per slide.** `ShowBuilder.add()` only builds a new
`ChapterPanel` when `(chapter, subchapter)` differs from the last slide added — every slide
under "In a loop" shares the one card built when the show first entered that subchapter, so
clicking through Loop's own two clicks never touches the panel deck at all (`panelOf` names
the same panel index for both, so the per-frame check finds nothing changed). A chapter can
also override the default with its own `panel` argument to `chapter(...)`, for a section that
needs a different card — that falls back to the show's own default wherever a chapter does
not set one.

**The chapter is not set at a size; it is set to the frame.** `setToFit` in
`slide-drawers/TypeBlock.kt` searches for the largest scale at which the passage still
fits, and the line breaks fall out of that — the same reasoning as demo02's line count and
the Objects grid: try the arrangements, keep whichever lets the drawing be biggest. So a
chapter of any length is composed rather than clipped, and renaming one needs nothing in
the drawer changed. `QuoteSlide` sets its pull quote the same way, which is why the two
share `TypeBlock` rather than each carrying their own wrapping: what differs between a
heading and a sentence is the measure and the leading, not the fitting.

**Lines break between words and after a hyphen.** "Waardekader en verantwoor-delijkheid"
comes out over three lines with the break falling inside the compound, which is the only
reason to write one hyphenated — a piece taken after a hyphen carries no space in front of
it, so the word closes up again whenever it does fit on one line.

**A card cuts between chapters, and fades its title up as it arrives.** Those are two
different things and only the first was ever wrong. A crossfade *between two cards* reads as
one title dissolving into another — as something happening beside the slide, rather than as
the heading having changed — so `transition = Cut` stands, and measured off a clip the left
pane holds one value, jumps once, and holds the next. The title's own entrance is another
matter now that a card arrives on a black frame with nothing else on it: instant, it reads
as a flash; over `FADE`, it reads as a title card. It runs off `stage.since(0, FADE)`, the
card's own frame count, because a `Cut` leaves `stage.enter` at 1 from the first frame —
there is no handover to hang it on, and there should not be.

**Biggest is not always the wanted setting, so the break can be stated.** "De wereld van
bouwen" maximised comes out over *two* lines — "van bouwen" sits beside "De wereld" at a
larger size than three lines can reach, because a third line costs more height than the
shorter measure wins back in width. Three lines is smaller type and a better rag, which is
a design decision, so it is made in the show rather than guessed at in the drawer:

```kotlin
chapter("De wereld van bouwen", panel = { ChapterPanel(it, panelFont, lines = 3) }) { ... }
```

`lines = n` searches the widest measure that still takes n lines; left off, the card fills
the frame with however many it likes.

**Three traps in setting type this way, all found by measuring rather than looking.**

**Measure with `advanceWidth`, never `characterWidth`.** The two sound alike and are not:
`FontImageMap.characterWidth(c)` returns the width of the glyph's box *in the atlas* — the
ink, not the advance — so a space measures zero and a line under-measures by about a sixth.
Fitting against it produces a card that overruns its own frame at exactly the scale the
search called a fit, which is what the first version did. `glyphMetrics[c].advanceWidth`
plus `kerning(prev, c)` is what `FontImageMapDrawer` itself advances the pen by, so
measuring that way is measuring the thing that will actually be drawn.

**`FontImageMap.size` is not the point size.** A face loaded at 190 reports `size` as
0.08 — it is an em scale rather than a measurement, and the number that was asked for comes
back as `leading` instead. Deriving a line height from it collapses the height term to
nothing, which lets the fit choose an enormous scale and throws the block clean off the top
of the frame. `TypeBlock` carries the size the caller already knows rather than reading it
back off the face.

**`Drawer` has its own `width` and `height`, and `isolated` gives you a `Drawer` receiver.**
So an unqualified `height` inside `drawer.isolated { }` resolves to the *render target's*
height, not the enclosing class's — the block gets translated by half the frame instead of
half itself. It draws, at the right size, in the wrong place, which is an expensive thing
to go looking for: the arithmetic prints correct, a standalone repro of the same call
renders correct, and only a marker drawn inside the same transform shows the transform
itself is displaced. Hoist anything named `width` or `height` into a local before the
lambda.

**The face comes from outside the project.** `data/fonts` holds one weight of IBM Plex and
no bold, so `SLIDES_PANEL_FONT` names a bold serif on the machine and [`usableFont`](src/main/kotlin/FontPath.kt)
stands it up: a missing file falls back to the bundled face rather than stopping the show,
a `.ttc` has the wanted face lifted out of it because stb_truetype will not open a
collection at all, and a path with a space in it is copied somewhere plainer because
`loadFont` parses its argument as a URI. `Objects.kt` has its own older copy of that logic
for `OBJECTS_TEXT_FONT` and could be moved onto this one.

`panelFont` is declared **above** `show` in `Slideshow.kt`, and has to be: top-level values
initialise in the order they are written, and the `panel { }` lambda is called *while*
`show` is being built, as the slides go in. Written underneath it is still null when the
cards are made.
