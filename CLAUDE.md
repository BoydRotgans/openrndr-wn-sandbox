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

## kinetic type

[`KineticType.kt`](src/main/kotlin/KineticType.kt) is a sentence set as separate words, dealt
across a frame so that none of them touch, and read out a word at a time:

```
./gradlew run -Popenrndr.application=KineticTypeKt
```

`b` outlines the boxes the deal gave the words. `KINETIC_TEXT` sets the sentence and
`_BEAT` / `_MOVE` / `_HOLD` / `_BLEED` steer it.

**It is the type on its own, and the chapter card is the same thing in components.**
`ObjectChapterPanel` paints this into a plate and a field of catalogue elements reads that plate
back, so the two share this file rather than each carrying its own arrangement — a change to how
the words fall is a change to both, and two copies would drift the moment either was tuned.

**The layout is a pure function of one number.** `at(frame)` maps a frame count to every word's
box and how far it has arrived; nothing is integrated and nothing is kept, so it can be scrubbed,
recorded, jumped into or stepped and shows the same picture at the same frame. `packBoxes` and
`stateAt` again, applied to a sentence.

Four decisions carry it:

- **The size is whatever lets every word stand apart.** The search opens *above* the size the
  sentence would be set at as a block and steps down until the whole of it is down — so a deal is
  as large as its own arrangement allows rather than as large as a block would be. It has to be a
  search: a word that cannot be placed would otherwise simply go missing from the sentence, which
  is the one thing a title may not do, and the first version dropped "bouwen" exactly that way.
- **No two words touch, and that is a property of the deal**, not a check afterwards: a word takes
  the first place offered that clears every word already down, and after a hundred refusals walks
  a lattice, which always finds one. Words are **placed widest first and handed back in reading
  order** — a long word left until last has to find a gap the short ones have already broken up,
  but the order they come back in is the order the sentence is built in.
- **A word may hang off the frame, but never by half.** `BLEED` is what lets the type be bigger
  than the frame and be *cropped*, which is what the reference does; it is capped at 0.45 on both
  axes, because past halfway a word stops being a cropped word and becomes a mark at the edge that
  happens to be made of letters.
- **The words arrive and leave; they do not travel.** Each comes in on its own beat, in reading
  order, and later goes the same way. What eases is *how far it has arrived*, and a word arrives
  by being drawn **fainter** — which on the card means a half-drawn word is a half-grown mark
  under it, so the elements swell into the letters rather than the letters fading on.

The turn is derived — a beat a word in, `HOLD` beats standing, a beat a word out — rather than
stated, because a stated length is a second number to keep in step and would be wrong for a
sentence of another length. A clip of one turn loops.

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
so adding one is adding a file rather than growing a list, and **`backdrop-drawers/` is a
file per backdrop** — the full-wall pictures around the talk (see [backdrops](#backdrops)).
Everything else is the engine,
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
what going forward did.

**`Cut` is the default, and the deck arrived at that one slide at a time.** `Fade` was, and
every slide was asked to stop doing it in turn until the answer was plainly all of them: a
talk moves between subjects, and a dissolve reads as one picture becoming another rather
than as the subject having changed. A slide that genuinely hands over says so — the `Push`
on the demo loop, or a fade between two states of one thing. It also means the seamless cut
into the tree is the default rather than something arranged. Stepping back off the front of a slide lands on the **last** click
of the one before it, fully built, rather than resetting it.

**`SLIDES_CUES` films a run hands-off**, and `SLIDES_AUTOSTEP` mostly cannot: a click of the
city takes twelve seconds and a click of the stack takes half of one, so any single interval
that lets the first finish holds the second twenty times longer than it needs. The cue list is
the same run with the pauses written out, one number per click — `5, 4, 14, 6, 3, 6, 16, 3,
2.5, 2.5, 2.5, 2.5, 5` is the first chapter — and when it runs out the deck stops where it is.
It is measured in frames against the frame the last cue was taken on, so a filmed run and a
watched one are the same run.

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

### One slide on its own

[`SlideStudio.kt`](src/main/kotlin/SlideStudio.kt) runs a single drawer at the size of the pane
it composes for — the drawer to work on rather than the show to click through, and the twin of
`CardStudio` for the other half of the canvas:

```
SLIDE=Stack ./gradlew run -Popenrndr.application=SlideStudioKt
```

`->` `<-` are the clicks, `up`/`down` the slides, `r` replays, `p` holds the clock and `.` `,`
step it, `s` writes a still, `d` the overlay, `esc` quits.

**The slides are the deck's own** — `show.slides`, the very objects the talk is made of — so
what is on screen here is what is on screen in the show and not a second arrangement of it that
has to be kept in step. It is also **a real `Deck` of one slide** rather than a hand-rolled
stage, so `position` eases over the slide's own `stepFrames`, `stepName` reads as it does in the
talk, and the debug overlay is the show's. A click here is the click the audience sees.

**Only the slide you ask for loads, and the seconds are the smaller half of why that matters.**
`present` loads all thirteen slides and the four mosaic cards before the first frame, because a
show must not hitch on a click; measured, that is about 3s against 0.1–0.3s for one slide. The
rest of the loop is the real saving — no clicking to reach the slide, a 1920 window rather than
a 3840 canvas beside a card you are not working on, and `s` / `SLIDE_AT` / `SLIDE_STILLS` scoped
to the one drawer instead of the deck. `up`/`down` load a neighbour the first time it is asked
for, so the slide next door costs a keypress rather than another JVM.

**The pane size is read off the show, never stated** — the canvas less the chapter card's half
and the gutter. A drawer lays out against `stage.bounds`, so handing it any other shape is
detailing it at a size it will never be seen at; stated here it would also go quietly wrong the
day the card changes width.

**`SLIDE` matches three things, and it has to be three.** A slide's `name` (`Cut`), its class
(`HardCutSlide`), and its title in the running order (`catalogue`). The class is the one
`SLIDES_START` cannot reach and the one that matters most here: **every** drawer in this deck
overrides `name`, so you would be editing `HardCutSlide.kt` with no way to ask for it but by
knowing it answers to "Cut". Detailing a drawer means navigating by file, so the file has to be
an address.

**It goes on the wall the way the show does, and lands where the slide does.** The projector
arrangement is inherited whole — `SLIDES_UNDECORATED`, `SLIDES_WINDOW_X/_Y`,
`SLIDES_WINDOW_SCALE` — so hanging the talk hangs the studio with it. The one thing it adds is
the offset: the show's window starts at the chapter card and the slide pane is the half beyond
it, so the studio is placed a card's width along and stands on the drawer's own projector.
`SLIDE_UNDECORATED` and `SLIDE_WINDOW_X/_Y` override that for one projector rather than two.

It reads `show.withEnv()` and not `show.settings`, and that is not a detail: the canvas size and
the whole projector arrangement live in `.env`, and the raw show carries only what
`Slideshow.kt` declares. Read raw it silently ignored every placement key and opened a full-size
window in the middle of the desk.

### Backdrops

The talk is one part of the evening's draaiboek, and the wall carries pictures around it: a
scene to arrive to, one to leave by, later the courses in between. Those are **backdrops**. A
[`Backdrop`](src/main/kotlin/slideshow/Backdrop.kt) is a `Slide` that takes the **whole wall** —
both projectors, 3840x1080 on the committed show — with no chapter card beside it. They are
declared with `backdrop(...)` in `Slideshow.kt`, outside the chapters, and drawn in
`backdrop-drawers/`, a file to a scene, the way slides are drawn in `slide-drawers/`.

**A backdrop is in the same deck as the slides, and that is the whole of how it is driven.**
`→` off the opening scene is the first slide, `→` off the last slide is the closing scene, `←`
retraces either, and `SLIDES_START=Opening` opens on one. There is nothing new to navigate and
no second set of keys. What differs is only the frame it composes for, which the engine asks
at the one place the two differ: `slide is Backdrop`.

**A handover with a backdrop on either side is composed on the wall, not pane by pane.** A
slide composes for its 1920 pane and a backdrop for the 3840 canvas, so the leaving and
arriving pictures are different shapes and the per-pane handover cannot mix them. `present`
instead renders the leaving picture *as the whole wall showed it* — a slide beside its card, or
a backdrop edge to edge — and the arriving one likewise, and the transition mixes those two.
Between two slides nothing changed: each pane still hands over on its own. It costs two
canvas-sized buffers, allocated only for a show that has a backdrop. The committed show cuts
into and out of both scenes, so none of this is visible there; it is what lets a backdrop
declare a `Fade` or a `Push` and have it work — `ObjectScene` takes a `transition` — and it
was checked on film rather than assumed: a one-second fade into the closing scene mixes the
card and the slide *as one picture* into the scene, with the elements rising through it.
Stills cannot show a handover (they are all taken on cuts), which is why it had to be filmed.

**The card arrives with its chapter when the show steps forward out of a backdrop.** While a
backdrop is up the panel deck holds where it is and is not drawn — not even asked to, since a
mosaic card repaints its plate every frame — but it has been *ticking* since the show booted,
so its title fade and its element reveal ran an hour before anyone saw them. Stepping forward
into a section from a backdrop therefore replays the card, so it builds as the chapter opens.
Stepping *back* into a section finds the card as it was left, the same rule as stepping back
within the deck. `0` on a backdrop leaves the card alone; it arrives with the first slide.
`Show.panelOf` carries `-1` for a backdrop, and every guard the two-step card needs
(`cardHoldsTheFrame`, `atSectionStart`, `openCard`) is off while one is up.

**The two ends of the evening are two drawers, and they have nothing in common but the sheet
loader.** That split is the point: the closing wall is a *kind* of wall and the opening is an
*occasion*, and keeping the second as a configuration of the first would mean every change made
for the opening landing on the closing one too.

- **`ObjectScene` is the kind**: a row of components standing on a wall, and then holding. The
  draaiboek's Uitloop is one — two elements off the subset, large, in the house colours on a
  light ground — and the courses between the talk and the exit will be others. Elements **7 and
  3**, the panel on two feet and the wider one on three, are the blocks it draws; the survey
  that picked them is one temporary edit of the picks to `(0 until 15).toList()` and
  `SLIDE=Opening SLIDE_AT=8` in the studio. Every element is one height, laid on one line a
  fixed gap apart and centred, rising from below the wall a beat apart and then standing.
  `standingRow` is its layout — a pure function of the wall and the shapes, `packBoxes` and
  `stateAt` again — and it sits *beside* the drawer the way `stackRows` sits in `StackUp.kt`,
  because it now has one caller. Lifting it out when a second wants it is one move.

- **`OpeningScene` is the occasion**: the wall the room arrives to, up for the better part of an
  hour, and the first thing anyone sees of the evening. It is black, and **the catalogue draws
  itself on it**. A single white line travels the whole edge of a piece, and the moment the
  outline closes the shape floods to solid white; it holds, slides up out of frame, and the next
  piece is drawn — right through `objects-front.svg`, so every one of the 112 variants gets its
  turn.

**The two halves of the opening wall are not a pair; they are two clocks.** Each 1920x1080 half
runs its own turn — draw, fill, hold, leave — and the right runs half a turn behind the left, so
the wall alternates left, right, left, right, with a new piece beginning as the one across from
it stands finished. Nothing on it is ever synchronised, which is what keeps it from reading as a
slideshow of pairs: there is always one piece being drawn and one standing to look at. `phase` is
the whole of that — 0.5 is the alternation, 0 puts the two back in step and draws them as a pair.
A turn is 19s a side, so a piece lands every 9.5s — the trace alone is 12.6s, and the piece then
stands finished for 4.8s, which is the only part of the turn that is reading time: the trace is
watched and the exit is a move, but the register can only be read once the piece has stopped. That is deliberate: the drawing is the wall's one moving
part and it is watched by a room that is *arriving* rather than attending, so a piece drawn
slowly enough to follow reads as a thing being made rather than a thing appearing. The halves take the sheet in turns, so the pieces still come up in reading order across
the wall — 0 left, 1 right, 2 left, 3 right — and the left only returns to the first piece after
`n / 2` turns, or after `n` when the sheet is odd, since an odd sheet swaps which side each piece
falls on every pass.

**The pen moves at a constant speed, and the contour's own parameter cannot give it that.**
`ShapeContour.sub(0, t)` walks the *segments* evenly, so on a piece whose corners are a cluster
of short edges the line crawls through the detail and races down the long sides. Each outline is
therefore resampled at load into equally spaced points, and the trace is the first `t` of them —
so a piece with 40 segments draws no faster than a rectangle with 4. A piece with a hole is
drawn as **one** pen rather than two at once: its outlines are laid end to end and the travel
runs through them in turn, so a doorway's opening is drawn after the panel around it closes.

**Once a piece floods, a caption arrives under it.** The sheet's name letter-spaced along the
top, where the piece stands in the pass ranged right, and one line beneath — and that is all of
it. **White**, so the wall is one ink: the lettering is the same white as the piece it stands
under, and the only other value on the wall is the black it is all drawn on. The house red is one
argument away and reads as a drawing marked *up*, where the white reads as a drawing captioned.

It is set in **pane pixels rather than in the piece's own space**, and has to be: the fit scale
runs from a 5cm plate to a 24m beam, so type inside that transform would be a hairline on one
piece and enormous on the next. `BlueprintLabel` takes the box the piece occupies *on the pane*
and places everything against that.

**Nothing is drawn on the piece and nothing points at it.** Two further layers are built and
**off**, each because it makes a materially different picture rather than a slightly busier one —
a decision to take rather than a default to inherit:

- `grid`, a ruled field over the whole pane. It goes over the piece rather than under it, which
  makes the white read as a plate being measured rather than as a shape with a background behind
  it; ruled off the *pane's* corner rather than the piece's, so it holds still as pieces pass
  through it.
- `marks`, the bracket: hairlines level with the piece's top and bottom run out to the frame,
  with `(A)` and `(B)` naming their ends. One switch and not two, because the letters say nothing
  without the lines to point at.

**There is deliberately little text.** A backdrop is read across a room and at a glance, so the
layer carries the drawing's title, the number in the run, and a single fact — the part name when
one is known, the proportion when it is not. An earlier version set a block of four fields with
the value column stated at a fixed offset, which the letter-spaced keys ran straight through
(`RATIO1:2.20`); measuring the column off the widest tracked key fixed that, and then cutting the
block to one line made the column unnecessary.

**The face is stated rather than inherited.** It was reaching Rockwell either way, but only
because `Slideshow.kt` happens to point the deck's furniture (`Type.file`) there — repoint the
talk's family and this wall's lettering would have followed it somewhere it was never designed
for. `fontPath` is now a constructor argument, and the show hands it the **regular** weight:
the bold read as a heading standing beside a piece rather than as a caption under it.

**The pieces are named from the catalogue's own register, and the pairing was proved before it
was trusted.** `data/csv/objects-115-details.csv` carries a row a piece — name, the assembly it
belongs to and that assembly's tag, the IFC class, the profile, and the box in millimetres. It is
paired with the sheet **by index**, which is only honest because the order was checked: an
isometric drawing's on-screen proportion is predictable from the piece's box — a base of
`(W + D) · cos 30` against a height of `(W + D) · sin 30 + H` — so the file can be tested against
the sheet directly. Taken in `obj_no` order the predicted proportion tracks the measured one at a
**correlation of 0.971**, 92 of the 115 within 12%. The two other ways of reading the columns as
a box correlate at **-0.275** and **-0.707**, so which column is width, depth and height is
settled by the same test rather than assumed from the header.

**A count mismatch drops the register entirely**, and that guard is the point of it. The iso
sheet recovers 115 and the *front* sheet recovers 112, so pointing the wall at the front sheet
with these details still attached would shift every name one place from the first gap onwards and
never say so. A wall of mislabelled components is worse than a wall of unlabelled ones, so when
the counts disagree the pieces fall back to carrying their proportion and the run says why.

Before the register arrived none of this was possible, and it is worth recording why rather than
re-attempting it: both sheets carry their captions as *outlined* type — `<path>` and not a single
`<text>` — so no name can be read out of the svg; the sheet is drawn cell by cell rather than to
one scale, so no real dimension can be recovered from it either (implied units-per-metre across
aspect-matched pairs runs 44.8 to 3152.9, an 11 911x spread); pairing the silhouettes to the 115
named meshes in `data/objects` by shape fails on the ground the sort failed on in `Objects.kt` —
91 of 112 match at an IoU of 0.90 or better but only **27** are clear of their runner-up, because
most of the catalogue is plain rectangles and every rectangle matches every other one perfectly;
and the sheet order matches no natural ordering of those meshes either, every candidate
correlating at essentially zero.

**No weight, and that is a decision rather than an omission.** The register carries none, and
multiplying a mesh's volume by a density would be confidently wrong for a good share of the
catalogue: `HPKM39` is a steel column shoe and is exported as `IfcBeam`, so the IFC class cannot
separate the steel fittings from the concrete elements. A density or material column is all it
would take.

**The fields are distributed to the corners rather than stacked in a block.** The maker along the
top and the piece's place in the pass opposite it; halfway down either side, what it belongs to
and what it is; at the foot, its name with its profile under it and its box opposite. Nothing is
repeated — each field is said once, in the place its length suits — so the pane is held at its
edges and the piece stands in clear space in the middle.

**The character set has to be named.** The default atlas has no `×`, so the size line came out as
`170  10  60 MM` — the glyph missing and its advance zero, which reads as a spacing bug rather
than an absent character. `TYPE_CHARACTERS` is the deck's own set and carries the figures this
wall sets. `IfcDiscreteAccessory` is split on its camel case for the same sort of reason: dropping
the prefix alone leaves `DISCRETEACCESSORY`, which nobody can read across a room.

**The face is stated rather than inherited.** It was reaching Rockwell either way, but only
because `Slideshow.kt` happens to point the deck's furniture (`Type.file`) there — repoint the
talk's family and this wall's lettering would have followed it somewhere it was never designed
for. `fontPath` is now a constructor argument, and the show hands it the **regular** weight:
the bold read as a heading standing beside a piece rather than as a caption under it.

**The pieces cannot be named, and the label says nothing it cannot prove.** Both sheets carry
their captions as *outlined* type — `<path>` and not a single `<text>` — so a part name cannot
be read out of the svg. Pairing the silhouettes to the 115 named meshes in `data/objects` does
not work either, and it was measured rather than assumed: the front sheet's aspect distribution
matches theirs closely (p50 2.15 against 2.00), so it is the same set of pieces, but the sheet is
drawn **cell by cell rather than to one scale** — implied units-per-metre across aspect-matched
pairs runs 44.8 to 3152.9, a 11 911x spread — so no real dimension can be recovered from it. And
matching by silhouette fails on the same ground the sort failed on in `Objects.kt`: rasterised
and compared by IoU, 91 of 112 pieces match a mesh at 0.90 or better but only **27** are clear of
their runner-up, because most of the catalogue is plain rectangles and every rectangle matches
every other one perfectly. So `PieceMeta.name` is nullable and stays empty unless a
`<sheet>.names.txt` beside the svg gives one name a line in reading order — a wall of
mislabelled components is worse than a wall of unlabelled ones. Everything else on the label is
read off the drawing and is exact: number in the sheet, proportion, how many contours it has, and
how much of its own box it fills.

**The stroke weight is divided back out of the transform.** The piece is fitted to its half by a
scale, and a scale takes the stroke with it, so a 5cm plate would be drawn with a hairline and a
24m beam with a slab. Dividing the weight by that scale is what makes every piece read as the
same hand drawing it.

The whole scene is a pure function of `stage.frame` — the turn is a division and the phase a
remainder — so it can be scrubbed, jumped into, paused or filmed and shows the same picture at
the same frame, which is the rule every drawer here is written to.

**The front sheet is mostly plain silhouettes, which is why the wall runs the iso one.** In
elevation a slab really is a rectangle and `DRST_M24_1500` really is a hairline — the asset
rather than a fault, but a poor thing to draw slowly. `objects-iso.svg` gives the same catalogue
as objects, and its 115 read as pieces of concrete rather than as boxes. Using a full sheet
either way is what shows every variant; `subset.svg` is the picked few. The closing wall reads
`SLIDES_BACKDROP_SHEET` separately, because the two want different sheets.

Splitting the scenes was checked to be a no-op both times it moved: the opening's still after
the class split was byte-identical to the one before it, and the closing wall's still is
byte-identical across folding `standingRow` back in.

- **`ConveyorScene` is the belt**, and the show stands one up straight after the opening: the
  draaiboek's *Opening / Welcome*. The catalogue goes past on **two rows running against each
  other**, flat in the house pair on black. It is a *kind*, like `ObjectScene`, not an occasion,
  which is what makes a second moment in the evening — *Eerste gang* — another `backdrop(...)`
  with the palette reversed and `reversed = true, move = 2.2, rest = 1.2` rather than anything
  new in the drawer. The show carried both for a while and runs one for now.

**The rows fill the height and run alternately against each other**, two of them as committed —
`rows` is the whole of it, and the layout follows from it: the rule between them and the piece
height are both fractions of a row's share, so five or fifteen compose the same way. A single belt is a queue; two crossing is a
plant. They share one strip of pieces and start a share of it apart, so no two rows carry the same
piece at the same moment.

**The rows are spread across the whole height with a rule between them and no margin at the ends.**
`piece` a shade under 1 is what does it: at exactly 1 the rows close into a single field and stop
reading as rows, and above it they ride over one another — the one overlap this wall does not want.
`piece` at exactly 1 is what the committed wall runs: the rows **meet**, so what separates one from
the next is the pieces' own notches and steps rather than a line of wall. Under 1 puts a rule back
between them — measured, 21/20/20/21 pixels at five rows, 5 and 6 at fifteen, a single 86 at two —
and above 1 they ride over one another, which is the one overlap this wall does not want.

**The depth comes from stacking, not from spacing.** Laid at one pitch the row is a line of
things; in runs of two to `stack`, each set back a sliver from the one in front, it becomes stock
leaning against itself. The run length is hashed from where the run starts, so the same frame
always draws the same wall, and the set-back is measured against the *row's* height rather than
the piece's — so a 3:1 slab and a square panel step back by the same amount and a stack keeps one
rhythm. Measured along a scan line, a row went from about 5–7 visible faces to **18–19**, the
narrowest 24 pixels of sliver and the widest a full 1509.

**The pieces cast a shadow on the ones they lap, and it costs nothing on the ground because the
ground is black.** The shadow is black too, so it is invisible where it falls on the wall and only
tells where it lands on another piece — which is precisely the overlap it is there to describe. No
mask, no second buffer, no test for what is underneath: draw it before the piece and it appears
only where there is something to fall on. Measured against the same frame with it off, it touches
**2.29% of the frame** and takes a red piece from 255 to 188 where it lands.

It is thrown **left**, against the stacking rather than with the travel — pieces are drawn in the
order they sit along the row, so the one on top is always the one to the right and its shadow has
to fall left to land on its neighbour. Which way the row is running has nothing to do with it.

**The overlap is along the row, never up it.** `gap` is negative, so pieces lap past one another
as they go — a row is a run of stock overlapping itself, and the rows are still rows. Positive
opens the belt out into separate components going past on black, which is the other picture this
drawer can make; the committed wall is the dense one, about a quarter black.

**The belt indexes rather than runs.** One piece advances a stride, opening a gap behind it, and
the piece behind closes that gap, and so on down the row: only one is moving at a time, which
reads as *machinery* where a constant scroll reads as a picture being slid past. A wave is one
move each, after which every piece has advanced the same stride and the row stands exactly as it
did, so it loops — and it is all a function of the frame, nothing carried between them. Measured
across one move: exactly two edges shift per frame, which is one piece, and the shifts run
79 → 234 → 320 → 142 px as the ease takes it up and sets it down.

**The cascade runs with the motion, not against it, and that is what makes a row's direction
readable.** Stepping the *leading* piece first is what a real queue must do — it is the only one
with room ahead — but then the disturbance sweeps backwards while the pieces go forwards, and the
eye follows the disturbance: a row travelling left reads as moving right. These pieces lap over one
another, so there is no queue to respect and the trailing piece can go first. Measured per row
before and after, ignoring the wrap at the seam: the rows were 12/0 left, 0/12 right, 13/3, 0/16
and an ambiguous 7/8; with the wave turned they read 12/0, 0/12, 12/0, 0/15 and 11/3 — clean
alternation, and the ambiguity gone.

**The scatter is what stops it being clockwork.** Every row runs the same wave off the same frame
count, so without it all five step at the same instant and stop at the same instant — and pieces
this heavy moving in perfect unison read as a mechanism rather than as stock being handled. Each
row is given a phase of its own and each piece a delay of its own inside its slot, both **hashed
from where they are** rather than drawn from a running random: scattered, but the same frame always
draws the same wall. The delay is bounded by the rest, so every piece still moves exactly once a
wave and the row still comes round to itself. Measured over 21 samples the rows moving at once run
1, 2 or 3 of the five — and never all five, which was the whole complaint.

**One cascade is not enough, and the reason is spatial rather than mechanical.** A single gap
travelling the row is what an indexing conveyor really does — but the row is far longer than the
frame, fifteen pieces of which about five are on screen, so the moving piece is out of shot two
thirds of the time and the wall simply sits there. `gaps` spaces several cascades a share of the
row apart so one is always in view; on screen it still reads as one piece moving and then the one
behind it, because the others are a screen's width away. Measured: with one cascade the frame was
still for most samples, with three it is still for 24% — which is the `rest` between steps, and
deliberate.

**The gap between pieces is generous on purpose.** Closed up they butt into one another and the
belt reads as a mosaic of red and blue blocks rather than as separate components going past — it
is the black between them that makes them things rather than a pattern.

**The belt is one strip, not a screen of separate objects.** Every piece is laid end to end a
fixed gap apart, the whole strip is offset by the clock, and what falls inside the frame is drawn.
112 pieces at this size is far more belt than the frame can hold, so it never repeats within a
pass, and it crosses the two projectors as one continuous run rather than as two halves doing the
same thing. The offset is a multiplication and a remainder, so it is where it is at any frame with
nothing carried between them.

**The belts are the whole frame, and that is a correction.** It was a band across the middle
first, the way the draaiboek draws it — but the draaiboek is a page, where a row among several
reads as a row. Across 3840x1080 the same band is a letterbox: two pale strips and a stripe, and
the eye takes the strips for a fault rather than for a margin. Full height, the belts simply *are*
the wall.

**A piece is drawn at the belt's height unless that would make it enormously long.** `DRST_M24_1500`
is 63 times wider than it is tall, which at belt height is a single piece thirty thousand pixels
long — a quarter of an hour to pass, and it reads as a bar rather than as a component. Anything
wider than `widest` of the frame is scaled down whole instead, keeping its proportions and losing
height.

**Drawing the pieces to their real relative size is possible and is off, and the arithmetic is
why.** The register carries the real millimetres, so the belt can scale every piece against the
tallest instead of fitting each to the row — a column shoe standing beside a wall at the size it
really is. But the catalogue runs from a 1mm shim to a 14.3m wall, a ratio of **14 259**: with the
tallest filling a row the shortest is a fortieth of a pixel, and **50 of the 112 pieces come out
under seven pixels**. Flooring the small ones does not rescue it either — a floor generous enough
to see puts **72 of the 112 at the floor**, so most of the belt is no longer to scale and the idea
has been given up to keep the pieces. The way that does work is to carry the components and not
the fittings: at a metre and up it is 45 pieces across a 13x range with the smallest still some 27
pixels. `smallest` is that cut, and `details` is what enables any of it.

The committed show runs `subset.svg` instead — the fifteen hand-picked shapes, all at one height.
The full sheet to scale is a stronger *idea* and a weaker *picture*: two thirds of the catalogue
drops off the belt to make it work, and what is left is mostly long low slabs.

**Names are dropped rather than shrunk or clipped**, on two tests. The type is one size along the
whole belt — a row of components all labelled the same way — so a name set smaller on a narrow
piece would read as a different kind of thing. They are off in the committed show (`labels`), and when on they are
dropped where the piece is **too narrow** to hold it, and where the piece is **too hollow**: a good part of the catalogue is a ring of section
in elevation, `HALFEN_38/17_L=15` among them, and a white name set on one lies across the belt
showing through its middle. `solidity` is measured off the drawing, so that test is exact. A third trap is in the placement:
the inset has to be measured against **the size the face was asked for**, never against
`FontImageMap.height` — the map reports the atlas's metrics, the same trap as `FontImageMap.size`
being an em scale rather than a point size, and an inset taken from it came out a few pixels and
jammed every name against its piece's top edge.

**The front sheet can be named at all because the gap in it was measured.** The register is 115
rows and `objects-front.svg` recovers 112, so pairing by index would shift every name from the
first gap onwards and never say so. An order-preserving alignment of drawn proportions against the
register lands on rows **103, 113 and 114** being the ones it does not draw — and with that skip
applied, **all 112** drawings match their row's proportion to within 12%, allowing either width or
depth to be the horizontal since some pieces are drawn as a side elevation. `alignedTo` holds that
and returns nothing for any other pairing of counts, so an unknown sheet goes unnamed rather than
mislabelled.

- **`YardScene` is the catalogue in the round**: the 115 `.obj` pieces of `data/objects` — the
  same catalogue the sheets hold flat, as the real geometry — laid in one row at one height on a
  white ground, in one colour with a long sharp shadow, each turning slowly on its own vertical
  axis as the row drifts across the wall. The belt wall's arrangement in three dimensions, and
  the last wall before the exit — the catalogue the belt showed flat at the start, now as things.
  It stands on `loadObjMeshes` and so has no `package` declaration either.

**The row is one strip, laid end to end at each piece's own width plus a gap**, the offsets
cumulative and worked out once at load; the strip wraps, and where a piece is on any frame is a
multiplication and a remainder, its angle another — nothing carried between frames, so it scrubs
and films. The pieces stand where they are; an earlier version dropped each one in from above as
its place entered the wall, and was taken out for being more than the wall needed.

**Because the pieces turn, they are spaced and fitted by what they reach at any angle, not by
what they show at one.** A 24 m beam turning on its centre sweeps a circle its own length
across; measured at its home angle it fitted its slot and would have swung through both
neighbours. So a piece's width is its sweep, `2 · spinRadius`, the same whatever the angle, and
its height is the most that sweep can reach up the screen at the iso pitch — `halfHeight ·
cos(pitch) + spinRadius · sin(pitch)`, a bound rather than a measurement, so the row holds still
while the pieces turn. That is the rule `Objects` keeps for the same reason. The width cap is the
belt's `widest`, for the belt's reason: to scale, the catalogue runs from a 5 cm plate to a 144 m
floor. The pieces are a golden turn out of phase with one another, so the row does not rotate as
one thing.

**The fit has to be measured on the screen, not in Y.** At the iso pitch a piece's *depth*
projects onto the screen's vertical as well as its height does, so a 24 m beam lying away from
the viewer is short in Y and a wall tall on screen: fitted by `halfHeight` alone it came out
1016 px against the 540 asked for. Everything vertical here is measured along the view's own up
vector, the same measurement the Objects grid spaces with.

**The shadow is a shear, sharp, and drawn before the pieces.** A parallel light on a parallel
camera makes laying a piece flat on its floor one matrix — `Objects`' poster shadow. It is
applied *between* the placement and the rotation, in world orientation, so the shadow lies along
the light however the piece has turned; applied in the piece's own frame it turns with the
piece. Every shadow is drawn before any piece, with no depth written, so a piece always stands on
a shadow and never under one — the whole of the depth sorting needed. The ground is a plane,
world `y = G`, and every piece stands and turns on it; the wall's ground line is where that plane
crosses the screen.

**Shadows come in exactly two tones — one layer, and more than one — and that is a count in
the stencil buffer, not a blend.** A flattened piece laps *itself* wherever it doubles back, so
alpha would darken a single shadow's own overlaps as if they were two; that is why the first
version was opaque, and why `Objects` renders its shadow to a buffer and blurs it. Counting
does it sharp: each shadow is drawn twice with the colour channels shut, the first pass setting
a flag bit over its footprint, the second incrementing the pixel wherever the flag is set — an
increment clears the flag *and* bumps the count in one operation, so a second fragment of the
same shadow finds no flag and counts nothing. Two fills across the wall then read the count
back: exactly one shadow paints the house navy, two or more paint black. The stencil holds
twice the count, the flag being its low bit, and the two fills test it with `EQUAL` and
`NOT_EQUAL` under bit masks — the one-shadow fill `EQUAL 2` masked `0xfe`, the more-than-one
fill `NOT_EQUAL 0` masked `0xfc` — because those read the same whichever way round the
comparison runs. An ordered test was tried first, `LESS_OR_EQUAL` against 4 for "two or more",
and it passed on the untouched wall as well: the whole wall went black under the pieces. A
translucent version was built before that, navy at 0.55 over white, and measured as two tones;
it was too faint and went. Measured as it stands: white 78%, navy 9%, red 8%, black 5%, and
nothing else.

**A piece is drawn a whole wall's width before it reaches the wall.** Its shadow, thrown left, lies
on the wall long before the piece does; culled at the edge the shadow *popped* in as the piece
arrived. The body is clipped by the window and only the shadow shows, which is the point.

**The lean direction matters, and only along the wall works.** `Objects` leans its shadow
toward −x −z, which at yaw 45 is straight *away* from the viewer: the shadow lies entirely behind
the piece and the piece covers it — measured, 0% of the wall was shadow. Toward the viewer it
runs off the bottom edge. So the light stands to one side and the shadow lies **along the wall**:
`light = 135` leans it to screen-left, back over the pieces that came before, and −45 is the
mirror. A turning light was tried — the shadow sweeps round and passes behind each piece once a
turn, measured going 0.6% → 4.9% of the wall and back — and dropped for a fixed low sun:
`slant = 5`, eleven degrees up, so a shadow is five times the height of what casts it and runs
under the next several pieces. It is the *world* height that casts, so a standing column throws
a long one and a beam lying down a short one. White ground rather than black, because a shadow
has nowhere to fall on black.

- **`GalleryMode` is the yard's picture without the yard's drift**: the whole catalogue, every
  one of the 115 pieces once, on a dense grid across the white ground, every piece turning
  slowly on its own axis — and **each offset in phase by a sine of where it stands**, so a wave
  of "facing" runs across the wall and no two pieces show the same face at once. Calm, because
  nothing moves quickly and nothing starts or stops.

**Everything is a function of the frame.** A piece's angle is the frame times one slow rate
plus its phase, and the phase is a sine of its column (`ripple` waves across the wall) shifted a
share of a turn per row — nothing scheduled, nothing carried between frames. The wall began as a
grid where one piece at a time turned a quarter and came to rest in a shuffled order, and that
was measured working (exactly one cell changing per beat); it was replaced by the continuous
field, which the same three dials describe with less machinery.

**The grid is read off the count, not stated.** Given the pieces and the wall's proportion it
takes the row count that leaves the fewest cells empty and, among those, the cell nearest
square: 115 on this wall comes out 23 by 5 exactly. The pieces are shuffled onto the cells from
the seed, because the catalogue is alphabetical and thirty-odd `WAND_n` would otherwise stand in
a block; a short `picks` list repeats to fill the grid instead, which is how the wall began —
three pieces on 6x2, then 9x3 — before it was asked for everything.

**A piece's ground plane is not at the foot of its cell, and the bottom row fell off the wall
until it was moved.** A turning piece's near corner swings toward the viewer and so *below* the
plane it stands on, by up to its sweep radius times `sin(pitch)`; stood on the cell's foot, the
bottom row reached past y = 1080. So the plane sits that far up from the box's foot, and the
whole any-angle box — that much under the plane and the rest above — is the middle `fill` of the
cell, with the gutter split evenly above and below. Measured: red from 18 to 1062 on a 1080 wall,
and no piece leaving its cell. The fill is 0.84 and the sweep cap 0.86 of the cell's width so the
gutters match across and down, which is what makes it read as a collection rather than a field,
and the whole grid stands inside a margin of 7% of the wall's height on every side;
and the gallery's sun is lower than the yard's (`slant` 2.2 against 5) so each piece's shadow
stays mostly in its own cell — black, the two-or-more tone, went from 8% of the wall to 1.5%.

**Everything below the layout is [`IsoPieces`](src/main/kotlin/slideshow/backdrop-drawers/IsoPieces.kt)**,
shared by the yard and the gallery: the iso camera, the any-angle fit, the shear, the two-tone
shadow count in the stencil. One implementation rather than two that drift; the two walls differ
only in where they put the pieces and what they do with the frame.

Neither drawer has a `package` declaration, for the reason `ObjectChapterPanel` has none: they
stand on `loadObjectSheet`, which is in the default package, and so does `standingRow` because
they do. A backdrop that stands on nothing there can be `package slideshow.backdrops`.

The studio opens a backdrop at the wall's size — `SLIDE=Opening` — and gives it no projector
offset, since it stands on both. Each kind is drawn into a canvas of its own and fitted into the
window, so `up`/`down` between a slide and a backdrop letterboxes rather than resizing. In the
running order a backdrop line carries `backdrop` and stands apart from the chapters.

### A third kind of slide

`Slide`, `Backdrop` and now [`Scene`](src/main/kotlin/slideshow/Scene.kt). A slide composes for
the pane beside its chapter card; a backdrop takes the whole wall and is a picture *around* the
talk; a **scene takes the whole wall and is part of** it. It is what a full-bleed moment is: the
subject fills the room and the furniture gets out of the way.

Declared with `scene(...)` in a chapter it is listed under that chapter and the card is simply
not drawn while it is up, arriving again with the next slide. Declared at the top level it
stands beside the backdrops and composes exactly as one does — whole wall, no card — so what
the two kinds still say differently is what the thing *is*, and the running order prints which.
The case study stands there, last before the closing wall.

**Adding a kind used to mean editing the driver in fourteen places, and now costs one
property.** Every `slide is Backdrop` in `Show.kt`, `ShowBuilder.kt` and `SlideStudio.kt` — the
buffers to allocate, the bounds to draw into, whether a card can be seen beside it, whether one
must replay when the show steps out, how a handover with something narrower is composed — was
really asking *does this take the whole wall*. That is `Slide.wide`, and both wide kinds set it;
a fourth would set it and edit nothing. `Slide.kind` is the name the running order prints.

**A wide slide is never given a card, and that is now read off the slide** rather than off which
builder function declared it: `ShowBuilder.add` checks `slide.wide`. Before, `backdrop(...)`
appended `-1` by hand, which is the sort of thing a new kind silently gets wrong.

#### The case study

[`CaseStudy`](src/main/kotlin/slideshow/scene-drawers/CaseStudy.kt) is the first scene: the
catalogue's pieces pasted up as a collage, then each one taken full frame in turn. Twelve cases
on a 4x3 grid, each in its own colour out of a playful palette, and a click takes the first
piece full frame, the next click the second, to the end.

**A piece stands for a sector, not for itself.** The caption is the subject — what is built —
and the element is demoted to a note under it, because the wall is about the sectors and the
catalogue is only how they are said. Six of the sector names are the draaiboek's own words, out
of the notes to slides 4, 39 and 40; the rest are a proposal. Swapping which element stands for
a sector is one word in the show.

**Zooming is a scale and a shift, because the projection is parallel.** No frustum to move and
no second camera: putting a piece full frame is scaling every piece's position and size about
that piece's centre, which lands it at the middle of the wall at whatever size is asked for —
the same fact the Objects grid rests on. It also keeps the ground one plane through the zoom, so
the shadows go on landing on it: a piece's foot is `(g − focus.y) · z` whatever piece it is.

**Between two cases the camera pulls back before it goes in, and that costs no click.** The zoom
is interpolated in log space — the only way a scale reads as even — with a dip toward the
overview at the half way point, so the move is out, across and in again. A click spent on the way
out would double the length of the set and say nothing. The caption fades with how far in the
camera is, so one number drives both rather than a second schedule.

**The overview's grid is stated, not derived, and it has to be.** The rule that reads a grid off
the count picks the cell nearest square, which on a 3.56:1 wall put all six cases in *one row* of
886-pixel cells: a piece was already 704 px tall in the overview and "full frame" was 662 — a
zoom *out*. More rows, smaller cells. Six cases also left the wall 89% white; twelve at `fill`
1.35 — over 1, so they overflow their cells and lap — brings it to 74% and still zooms 2.38x.

**The photograph is seen *through* the piece, and only there.** As the camera closes, a piece
stops being flat colour and becomes a window onto its project's photograph; the wall around it
stays paper. The picture is sampled in **wall pixels** rather than in the piece's own space, so
it is nailed to the wall and the piece is a window onto it rather than a thing wrapped in it —
the opening wall's trick for cutting its pieces out of concrete, and what makes this possible at
all, since these meshes carry no texture coordinates to unwrap. What comes through is a
**duotone in the piece's own colour**: the picture's luminance drives the value and the tint
keeps the hue, so a piece reads as itself with the project inside it rather than as a photograph
in the shape of a piece. It blends on the same number the caption fades on.

**A piece the scatter would push through a neighbour is walked back toward its own cell until
it clears.** Solids seen in the round passing through one another read as a fault rather than as
a collage, and scatter alone produced exactly that. The test is between the pieces' any-angle
boxes rather than their contours — the city's rule for packing plans, deliberately strict, since
a contour always lies inside its box. The cap on a piece's width has to allow for the *largest*
the scatter may size it to (`1 + collage/2`), not the nominal size: capped without that, a wide
beam scaled up came out broader than its own cell and crossed into its neighbours wherever it
stood, which no amount of walking back could clear. Measured on the overview: 217 pixels of 4.1
million where two different pieces touch, at 75% white.

**The picture is not flipped, and the rule that says so is not the render-target rule.**
`gl_FragCoord` counts up the screen and a loaded image already comes back the way `drawer.image`
draws it, so the two agree and no flip belongs in the sampler. One was put in on the strength of
the chapter card's note — where a *render target* really is drawn y-down — and it turned every
photograph over. Checked by measuring the source's top and bottom bands against the rendered
piece's, in the centre column only and ignoring clipped pixels: both brighter at the top.

**One piece at a time, and the picture is sized to that piece.** Only the case the camera has
centred is a window; its neighbours stay flat colour, because a picture showing through every
piece on the wall is a *texture*, where showing through one is that piece being *about*
something. The style is therefore chosen per piece rather than per pass. And the picture is laid
over the focused piece's own box rather than over the wall, so what fills it is the whole
photograph rather than the crop of it that happened to fall where the piece stood. Measured:
5–12% of the wall is still pure palette colour when a case is closed — the neighbours — while
the tone count is over a hundred.

A whole-wall version was built first, the photograph laid on the ground between the shadows and
the pieces — `IsoPieces.draw` still takes an `onTheGround` block for that, the only place
anything can go between opaque shadow fills and the pieces. It worked and was the wrong picture:
the wall became a photograph with a shape standing on it, where the point is the shape. Measured
on the window version: the overview is 8 tones and the corners stay white, and a closed case is
113 to 232 tones with the wall still paper.

`data/case-studies` holds three placeholders, dealt round until there is a photograph a sector.

**`steps` has to be knowable before `load` runs.** `present` prints the running order first,
so a step count taken from the loaded meshes reported a thirteen-click scene as two clicks — it
is counted off the declared cases instead, and everything that indexes by step clamps.

**The navy is deliberately not in the palette.** It is what the shadows are drawn in, and a navy
piece beside a navy shadow reads as a hole rather than as a colour: measured, it was 10% of the
wall and the eye took the two for one thing.

**Every piece keeps its own little ground.** The shear casts each shadow from the piece's own
bottom, so shifting a piece up or down takes its shadow with it — which is what a collage wants,
each cutting pasted at its own height with its own shadow under it, rather than one horizon
everything stands on. The scatter is drawn from a seed, so the same frame always draws the same
collage.

`IsoPieces` moved from `backdrop-drawers/` to the root beside `ObjectFabric` and `ObjectMosaic`,
because it is no longer a backdrop's alone: the yard, the gallery and the case study all draw
through it. `IsoPlaced` gained a `tint`, null meaning "the one ink this wall runs".

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

`GlobeSlide` is the same words again as a turning globe: a small disc ringed by fifteen of
them, drawing tighter and finer until it carries fifty.

**The ring is closed from the first frame and stays closed.** It opens carrying `opening`
words all the way round, and every word added after that is taken in by the ring drawing
tighter — the pitch narrows, the type comes down with it, and the disc grows to carry the
extra. At no point is it an arc filling in; it is always a complete ring, only finer.

**The size and the radius are not settings; they are read off the count.** At any number of
words there is one radius where the ring's own spacing and the room the longest word needs out
to the frame ask for the same size of type, and that is the largest the type can be — bigger
and the frame binds, smaller and the ring does. Fifteen words want a 104px disc and 35px type;
fifty want 231px and 23px. Everything between follows from the same expression, so the
composition is never tuned, it is solved, every frame. `size` fixes the radius by hand if a
composition wants another balance.

**The spin has a period of its own**, `turn`, rather than one revolution over the build. The
ring goes on turning long after the last word has landed, and tying the two together makes a
slide that fills quickly also spin quickly, which is backwards.

**Nothing accelerates, because the pitch is what runs linearly in time** — not the word count.
Each word's angle is `spin - i * pitch` with both terms linear, so every word on the ring has a
constant angular velocity for the whole slide. Running the *count* linearly instead makes the
pitch go as 1/t, which winds the ring hard at the start and barely at the end: the spin is
constant either way, and **what reads as the rotation changing is the ring deforming under
it**. That was a real bug once and it looks like a motion problem when it is a placement one.
A pitch linear in time also delivers words slowly at first and quickening, which is the pacing
wanted, without anything being asked for twice.

**The one thing a closed ring cannot avoid** is that its words shift as it tightens: a closed
loop of fifteen cannot become a closed loop of fifty with everything standing still. They
flow gently towards the first word and new ones come in behind it.

**The ring takes its words from `labels` in turn and comes round again** when the list is
shorter than `fill`, which is what keeps the ring dense without inventing copy for
it — and what the reference for this slide does.

**It builds on `stage.frame`, not on clicks.** Fifty clicks to set out fifty words would be
a slide nobody could talk over. `stage.frame` is frames since this slide came up, so
replaying it or jumping into it starts the build again from nothing and a recorded run is
identical to a watched one — which a wall clock would give none of.

**The labels turn with the globe**, so a word is upside down for half of every turn. That is
the piece rather than an oversight: they are fixed to the thing that is spinning, and flipping
them to stay upright would snap each one over as it crossed the bottom.

#### The stack

`StackUp` is `demo01`'s `packBoxes` as a slide: the first band is the whole frame, and every
click adds another underneath while the ones already up close ranks above it.

**The layout is a pure function of one number**, which is the whole of why it belongs in a
deck. [`stackRows`](src/main/kotlin/slideshow/slide-drawers/StackUp.kt) maps a space and a
*count of bands* to every rectangle on screen — no time in it, no state — and the count it is
asked for is `1 + stage.position`, which is continuous. The band arriving is given a fraction
of its weight, so at 3.4 the fourth band is four tenths of its height and the three above have
closed up by exactly that much. That is the entire animation: clicking back is a smaller
number rather than an undo, and `SLIDES_START` into the middle of it composes rather than
fast-forwarding. Same idea as `packBoxes` and `stateAt` in Decision, and the same payoff —
change the proportions and it keeps animating for free.

**A band's height is read off its shape.** A row given one label runs the full width and stands
`FULL_SPAN` taller than a row split in two, so the drawing's proportions come out of what the
content is rather than being stated in pixels. `span` overrides it where a particular band
should carry more or less weight.

**The type is one size across every band, worked out from the *finished* stack** rather than
the one on screen. Fitted to each band it would set the opening band — which is the whole
frame — enormous and shrink it click by click, so the reveal would read as the words receding
rather than as the stack filling. One size means the type is right at the end, which is the
state the slide is held on.

#### The ladder

`CarbonLadder` is the CO₂-prestatieladder as a staircase: three white rungs on black, each
standing on the one below and a run to the right of it, climbing from bottom-left to
top-right, and then the certificate pointing at the top one. Four states a click apart,
read off `data/ref/CO₂-prestatieladder.pdf`, between the ESG framework and the crowd.

**Each rung arrives by taking its step.** It comes in from one run to the *left* of where it
will stand — which is directly over the rung below — and slides right into place as it fades
up, so the click shows the ladder gaining a step rather than a block appearing. Travel and
fade are the deck's own eased `on(n)`, undoubled, and the same number, so they cannot come
apart. The first rung has no click to arrive on, the slide opening on a cut, so it takes its
step on the slide's own clock over one click's length — and only while the slide is still on
its first state, so stepping back into it from the crowd finds the ladder built rather than
the bottom rung sliding in again under a finished top.

**The top rung is fitted, not clipped.** Three runs carry its right edge past the pane, and
the reference holds it inside a small margin by drawing it narrower: a rung's width is the
lesser of the ladder's width and what is left to the margin from where it *rests* — from
where it rests and not from where it is, or it would grow as it slid.

**The lettering was measured wrong once, by a third.** The sizes were first read off the
reference's word boxes as 1.25 em a box; Rockwell's boxes in that file are 1.6 em, and the
type came out a third too large — the rung's line spanning 736 px against the reference's
570, and the certificate's leader line left with no room between the note and the rung. The
widths are what settle a size, not the box heights.

**CO₂ is set, not asked for.** Rockwell has no U+2082 — checked in the face's own cmap — and a
missing glyph draws nothing and advances nothing, so the line reads "CO -reductie" and looks
like a spacing bug. The subscript is set as an ordinary digit at 0.62 of the size, dropped
0.13 em. That was the analysis slide's private trick; it now lives in `TypeBlock.kt` as
`setLine` and `advanceWithSubscripts`, shared by every slide that says CO₂, and the analysis
slide's stills are byte-identical across the move.

#### The crowd

`Crowd` is the social and governance pillars told as people: one figure, then the group
around it, then the lines between them, then the group as an arrow with one out in front,
then the whole as a disc with a share of it marked out. Five states a click apart, read off
`data/ref/social.pdf`, and the slide after the ESG framework in the second chapter.

**Every figure is a slot, and a slot keeps its figure for the whole slide.** Each state is a
*formation* — a place per slot — and `position` says which two the slide is between. A slot
in both travels from the one place to the other; a slot only in the later one stands up at
its place, nearest the middle first, so a formation grows out of the figures already there
rather than being dealt over them. That is what makes it one crowd re-forming rather than
five pictures of crowds, and it is `packBoxes` and `stateAt` again. Slot 0 is the individual
the slide opens on: it stays exactly where it is while the group forms around it, is one of
the white kin, is the one out ahead of the arrow, and comes back to the middle of the whole.

**Which figure goes where is matched, not ranked.** Both formations order their places
nearest-the-middle first, but handing rank *n* of the one to rank *n* of the other sends a
figure from the left of the crowd to the right of the arrow and the paths cross. Each figure
already standing takes the free place nearest to where it is, in figure heights from the
formation's own middle — Decision's pairing rule, greedy because the disc runs to seven
hundred.

**A click with travel in it has two phases, and the arrivals wait for the travel.** Run
together, the arrow's new figures sprouted through a crowd that had barely begun to shrink —
measured off a frame a quarter of the way in. So the figures already standing travel over the
first `TRAVEL` of the click and the new ones stand up from `ARRIVE_AT`, each phase keyed off an
interval of the click's *linear* time with a smoothstep of its own, the city's rule for its
grid. Where nothing travels — the one standing still while the crowd forms — the arrivals
take the whole click. `linear()` moved to `Timing.kt` for this; it was the city's alone.

**The silhouettes are the 3D people flattened, not drawings of people.**
`data/ref/silhouette_people_lowpoly_obj.obj` carries 114 low-poly figures, Y up with the feet
on the ground; each is projected along the direction that shows it *widest* — the principal
axis of its footprint — so a standing figure is seen from the front and a striding one from
the side, which is the mix the reference has. The 40 under three quarters of the tallest are
the seated and the children, and are left out. The 73 that remain go into one vertex buffer,
normalised to adult heights rather than each to its own so a short figure stays short, and
every figure on the wall is an instance of one of them — a disc of 732 is 73 draw calls, not
732, with each instance carrying its foot, its size (negative across for a mirrored one) and
its colour. Measured, the whole pass is 3 ms at the disc.

**They are drawn into a multisampled buffer of their own and laid on the pane as a picture.**
At 26 pixels tall a silhouette is all edge, and the deck's buffers are not multisampled; the
resolve is `copyTo`, which blits and so resolves. The lines of the network are drawn on the
pane *under* that picture, from one figure's chest to another's, so a line is covered wherever
it crosses a body and what shows is the stretch between them, ending at the silhouettes'
edges with nothing measured — the belt wall's shadow trick, inverted.

The arrow is a polygon — a shaft four rows deep, a head eight — and a grid cell is in it if
its centre is; the head's rows shorten toward the tip by themselves. The share of the disc is
the part of it outside a second circle standing left of the middle, which is a crescent along
the right edge, widest at the middle and tapering to nothing top and bottom, as the reference
draws it. Titles are a list, one a state, so a run of the same title holds and a change
crossfades on the click.

#### The card on its own

[`CardStudio.kt`](src/main/kotlin/CardStudio.kt) runs one chapter card at 1920x1080 — the card
to work on rather than the show to click through:

```
./gradlew run -Popenrndr.application=CardStudioKt
```

`->` `<-` step the cards, `r` replays, `j` and `k` step the mark size, `b` switches between the
field and the plate it reads, `p` holds the clock and `.` `,` step it, `s` writes a still, `d`
the readout.

**The cards are the deck's own** — `show.panels`, the very objects the slideshow stands beside
its slides — so what is on screen here is what is on screen in the talk and not a second
arrangement of it that has to be kept in step. **Only the cards load, never the slides**: the
deck spends some ten seconds collecting and packing the city, and a card is a tenth of a
second, so iterating on one costs the second kind of startup rather than the first. All four
load up front, so stepping between them is instant.

What the card is made of is steered from `.env` — the `SLIDES_CARD_*` keys are
`ObjectChapterPanel`'s own constructor defaults, so a value tried here is the value the show
runs with and the two cannot drift apart.

`CARD_RECORD=true` films it instead. **One chapter to a run**: `ScreenRecorder` writes a file
per program and a program is one `application {}`, so filming four cards is four runs —
`CARD=1` … `4`. Clumsier to type than a loop, and much simpler than restarting GLFW between
takes. `CARD_STILLS=true` writes a png and quits, and `CARD_AT=6,14` writes the frames at those
seconds instead — **two of them a period apart is how the drift's loop was checked**, because a
png is lossless where a clip's own compression noise is louder than the difference being looked
for. Measured that way the two frames are byte-identical.

The window is the card's own size and the canvas is fitted into it rather than stretched, so a
still, a filmed frame and what is on screen are the same pixels; `CARD_WINDOW` scales the
window and the recorder's `contentScale` puts the resolution back.

### The export

`SLIDES_RECORD=true SLIDES_CUES=auto` films the whole deck hands-off and leaves four files in
`video/`: the film, a `.cues` log of every cue the run fired and the frame it fired on, a
`.wav` rendered from that log, and — with ffmpeg on the path — the two mixed into
`presentation-mixed.mp4`. `SLIDES_VIDEO` names the film and the rest stand beside it.

**The soundtrack is rendered, not recorded, and that is what makes it in line.** Under
`ScreenRecorder` the draw loop runs on video time while the speakers play on wall time, so
whatever the machine puts out during filming drifts from the picture the moment the encoder
falls behind — at 3840 wide it always does. But every cue is fired on a *deck frame*, and a
deck frame is video time by construction. `Speakers` keeps a log of what it was asked and
when, whether or not a device was there to hear it, and [`Soundtrack`](src/main/kotlin/slideshow/Soundtrack.kt)
replays the driver's own rules over that log — the same two-pass levelling, the same fade
as a function of the frame, one voice per sustained cue picking a fade up from wherever the
gain had got to, loops wrapping, one-shots ringing out — into a wav of exactly the film's
length. Each cue lands on the sample its frame maps to, however slowly the film was made.

**The log is the deliverable behind the deliverable.** `MixSoundtrackKt` renders and mixes
again from the `.cues` file alone — no window, no device, no deck — which is how to change a
level in `Slideshow.kt` without filming again, or to mix on a machine where ffmpeg was
missing when the film was made. The mix is held under full scale rather than clipped: cues
are levelled to -23 dBFS with a -6 dB ceiling each, two landing together can pass 0, and the
whole track is brought down by whatever the loudest moment needs, and the report says by
how much.

**A written cue list is holds, and `auto` writes it off the deck.** One number per state:
long enough for the state to finish moving — its click, or the slide's own opening, which is
`Slide.settle` and which the globe overrides with the length of its build — plus a reading
time, `SLIDES_HOLD` for a slide and `SLIDES_HOLD_WIDE` for a wall that is one picture. The
list it wrote is printed at startup, to copy into `SLIDES_CUES` and tune by hand where a state
wants more or less than the rule gives it. A filmed run ends one hold after its last click; a
watched one stands where the list ran out, as before.

**The mux runs after the window closes, and has to.** `ScreenRecorder` finishes its file as
the program ends, so `present` hands the export out of the program as a closure and runs it
once `application {}` has returned — the film is on disk by then and not before.

### Sound

The deck makes a noise. A slide — a chapter card included — declares a cue, and the driver
fires it: `1-01.wav` under every chapter, off `data/sounds/WN-0909`.

**A cue is a value, not a player.** [`Sound`](src/main/kotlin/slideshow/Sound.kt) names a
file, a gain and whether it loops, and says nothing about when it is heard;
`Slide.sound` carries it beside `transition` and `background`, because it is the same kind of
thing — what the slide *is*, declared once and read by the driver. **A slide never plays its
own sound**, for the reason it never reads a clock: the deck can be clicked backwards, jumped
into, filmed and stepped, and only `present` knows which of those is happening.

It is stated in `Slideshow.kt` rather than in a drawer, the way `title` and `notes` are: a cue
is direction. `chapterCue` is one value handed to every card, so the four cannot drift apart —
a chapter that wants its own is `chapterCard` passing a different `sound`, keyed on
`section.number` the way it already keys its picture.

**Looping is off by default, and most cues want it off.** A sting is a transition — it is over
when it is over, and holding it under the slide would be a different kind of thing entirely.
`ambience` under the opening wall is the one exception in the show, and it has to be: that
scene is up for the better part of an hour while the room comes in, so its sound is a *room*
rather than an event.

**What decides whether a cue fades is bed against sting, not loop against one-shot.** `fadeIn`
and `fadeOut` are frames on the `Sound`, and they exist because a bed switched on at full gain
reads as a fault and one cut off mid-phrase reads as the machine being turned off. A sting has
nothing to fade: it is a shape in its own right, and taking the front off it takes the attack
with it. The ambience is six seconds in and two and a half out — longer in than out, because a
room fills slowly and is left briskly. The **map's** cue does not loop and still wants both,
which is what keeps the two questions apart.

**A `fadeOut` is what says a cue belongs to its slide.** `Sound.sustained` is that test, and it
decides two things at once: whether the driver takes the cue away as the slide is left, and
whether it gets a voice of its own instead of a place in the shared one-shot pool — a pooled
voice is reused by the next cue, so there would be nothing left to find when the fade has to
run. It matters most for the long ones: `1-02.wav` runs 25s against the map's 12-second click,
so without it the cue would still be playing two slides later, under the tree and the globe.
A sting declares no fade, is never held, and rings out — cutting a half-second mark off at the
click would be more noticeable than letting it finish.

**A fade picks up from wherever the gain got to**, rather than from the value the cue declares.
Clicking off the opening wall four seconds into a six-second fade-in was measured doing exactly
that: up to 0.2, then down from 0.2 to silence, with no jump to full gain first.

**A built slide marks its clicks as well as its arrival.** `Slide.sound` is what a slide says on
coming up; `stepCues` is what each click says after that, and the stack is the case it exists
for — `1-09` as the slide arrives over the opening band, then `1-09A` to `E` as each of the five
remaining bands lands. Five cues for six rows is right rather than one short: the opening band
is the slide arriving, not a click. A short list simply runs out, so a row can be added without
a cue having to be found for it, and `stepSound(step)` is there to override where the cue has to
be worked out rather than listed.

**The tree is the other case, and it is why a cue on the arrival is not always what is wanted.**
That slide opens on the city's own last frame — the same element, at the same size, in the same
place — and holds there, which is a cut built to be invisible. A cue on its arrival would mark
nothing anyone can see. `1-05` sits on click 1 instead, where the fan comes apart and the labels
are dealt down either side, so the sound arrives with the thing it is describing.

**`load` and the release must agree on what a slide's cues are**, and they did not at first — in
a way nothing reported. `load` gathered `slide.sound` and stopped there, so the stack's five
click marks reached `play` with no buffer to their name and did nothing at all. A step cue hangs
off a *method*, so a `mapNotNull` over the slides cannot see it. `cuesOf(slide)` is now the one
definition both use. The tell was the count in the startup line — 7 cues where there should have
been 12 — which is worth watching: a cue that fails to load is otherwise indistinguishable from
one that is playing too quietly.

Those marks are **forward only**, the same rule the chapter cards follow: clicking back through
a build is a correction, and re-firing the marks on the way would say something is being built
when it is being taken apart.

**The sheet's numbering is the running order**, which is the one thing that makes the cues
placeable at all: 1-02 is the map, 1-05 the tree, 1-07 the globe, 1-09 the stack and 1-10 the
figures — slides four to eight of the first chapter, in order. That is what settles which
`Swivel02Slide` takes 1-10, there being two of them in the show: the one in the first chapter,
not the one in the fourth. `cueGain` levels the whole sheet in one place; the ambience is set
separately, because a bed under people talking is a different job from a mark on a click.

**The fade is the sound's, not the picture's.** The opening scene arrives on a `Cut`, so there
is no handover to hang anything on — the wall is simply there, and the bed comes up underneath
it. That is why the fade lives on the cue rather than being derived from `stage.enter`.

**A fade is a function of the frame, not something integrated per tick.** `from + (to - from) *
ramp(frame - at, length)`, the same shape as everything else the deck animates, which buys the
same things: a dropped frame does not shorten a fade, pausing holds it where it stands, and the
draw loop running at 120 Hz against a 60 fps clock ticks the same frame twice with no effect,
because asking twice for frame 412 gives 412 both times.

**A bed spanning two slides is not dipped between them.** The driver releases the leaving
slide's loop only when the arriving slide is not standing on the same file, so a room that
carries across a slide boundary keeps playing rather than fading out and back in.

**A cue is a buffer, not a stream**, and that is the whole of
[`Speakers`](src/main/kotlin/slideshow/Speakers.kt). Each file is decoded whole at load into
one OpenAL buffer and triggered with one call — no thread, no streaming, nothing to keep in
step. The two obvious things to reach for are both the wrong shape:

- **`openrndr-openal` is already a dependency and is not a file player.** It has no decoder at
  all — `AudioData` takes raw PCM — and every `AudioQueueSource.play()` spawns a daemon thread
  whose loop never exits, one per call. It is built for streaming synthesis.
- **The discourse audio-player example** (and the `VorbisTrack` in `celest-telescope`) is a
  *streaming* player with seek, position and FFT. Right for scrubbing a soundtrack; 400 lines
  of thread-per-track to fire a five-second sting, and OGG-only besides.

**The JDK is the decoder.** `javax.sound.sampled` reads wav *and* converts as it goes, which
this cue sheet needs — it is 24-bit and OpenAL takes 16. Ask `getAudioInputStream` for 16-bit
little-endian and it inserts the converter. That is why there is no decoding library here.

**No new artifact was downloaded.** `openrndr-openal` already pulls `lwjgl-openal` and its
natives at runtime — but as its own `implementation` dependency, so the AL calls do not resolve
at *compile* time. `libs.lwjgl.openal` in `build.gradle.kts` names the very jar that was
already being resolved, and nothing else changed.

**Which arrivals announce, and which stay silent.** A card's cue fires only where it is
genuinely arriving — a section entered forward, or one replayed by `0`. Stepping **back** into
an earlier chapter is a retrace and lands the card already across, mid-chapter, so it is
silent: a cue there would announce a chapter the talk is leaving. Opening straight into a
chapter passes through neither branch, because nothing changed and the card was simply already
there, so the boot case is handled on its own.

**Nothing about it may stop the show.** No audio device, no file, an unreadable wav — every one
of them lands the deck in silence and leaves it running, the same way a missing sheet falls
back to plain type. A talk that will not start because it cannot find a wav is worse than a
talk with no sound in it.

`SLIDES_SOUND=false` mutes it, and it is a *load* switch as well as a mute: off, no device is
opened and nothing is decoded. A `SLIDES_STILLS` run is silent whatever it says — that run
jumps through every slide in the deck on a timer and would fire the whole cue sheet at it.

**A recorded run has no audio in the file.** `ScreenRecorder` writes video only, and under it
the draw loop is on video time while the sound plays on wall time, so the two drift exactly as
the note under demo01 describes. Audio goes in at the edit.

Three things about the assets. All four cards name the same file, so it is decoded **once** —
the cue count at startup is files, not cards. `clic.wav` is byte-identical to `1-09.wav`. And
the two sources are **not one format**: the `WN-0909` sheet is 24-bit 44.1k and the ambience is
32-bit float 48k, which is why the load report measures each file against its own rate rather
than one constant, and why the decoder asks for 16-bit at *the source's* sample rate rather
than a fixed one. `javax.sound.sampled` converts `PCM_FLOAT` as readily as 24-bit, so neither
needed a converter written by hand.

The bed alone is 57 MB decoded (five minutes of 48k stereo) against 31 MB for the whole cue
sheet, and it is still held whole rather than streamed: a buffer costs memory once, where a
stream costs a thread and a refill loop for as long as the wall is up.

[`SoundProbe.kt`](src/main/kotlin/SoundProbe.kt) plays one wav and quits, naming the device it
opened. Run it on the projector machine before a show: it is the one thing a silent deck cannot
tell you.

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

**A card stands on the left and stays there.** `ChapterPanel` declares one step, so it is
simply the left pane for as long as the show is in its section: no entrance across the frame,
and nothing for the show to click past before the first slide of a chapter can be seen. Only
the title fades up, over its own frame count.

**The engine still carries the other arrangement, and a card opts into it by declaring two
steps.** It then comes up *over the slide pane*, holding the whole right-hand frame with the
title on it, and the first click carries it across to the left, uncovering the slide as it
goes. It was the behaviour for a while and is worth keeping buildable, because the three
pieces it needs are each somewhere different and none of them are obvious:

- **The card declares the click** — `steps = 2` is the opt-in, and one step is what keeps
  every other panel drawer working unchanged.
- **`present` composites it, because no card can draw the move itself.** A pane is 1920 wide
  and the move crosses 3840, so where the card *is* cannot be a translation inside its own
  buffer. The driver reads `stage.on(1)` off the card and offsets the finished pane by
  `slideOffsetX * (1 - opened)`. That is also why **the slide pane is composited first and the
  card over it** — for half a click the card is in front of the slide, which is only possible
  in that order. At rest the two do not overlap and the order costs nothing.
- **The driver routes the click.** While a two-step card is still at step 0 the arrows belong
  to *it*: `→` opens the card instead of advancing the slide deck, `←` at the first click of a
  section closes it back over the slide, and `0` puts it back and replays its fade.

Two details that only matter for that arrangement, both decided rather than fallen into:
stepping *back* into a section lands mid-chapter, so the card arrives already across rather
than re-announcing itself (`atSectionStart()` is the test, and the same test closes a card left
standing when `up`/`down` cross whole slides inside a section); and stills hold every card
closed, since a card standing open would cover the first slide of every chapter.

#### The chapter card, set in the catalogue

`ObjectChapterPanel` draws the chapter title as a field of Willy Naessens elements: the words
are drawn into a black and white plate nobody sees, and a standing field of elements reads that
plate and stands wherever the words are. **The type is never drawn on the card at all**, and
neither is the plate. So a chapter is made of the same thing the rest of the talk is, and the
lettering still keeps every property of `setToFit` — set to the frame, broken where it likes,
restated by editing the show — without any of it having to be reworked into shapes.

**The picture moves; the field does not.** The plate is repainted every frame and the elements
read it every frame, so the type can do anything — be dealt to new places, drift, tile, mirror —
while the field itself is baked once and drawn in **one call**. That is what
[`mosaicField` and `MOSAIC_FIELD`](src/main/kotlin/ObjectMosaic.kt) are for: every cell of the
quadtree is in the buffer at every level, and each works out for itself, per frame, whether it
is the cell that stands here — *am I wholly inside the ink, and did every cell above me straddle
its edge* — which is exactly the rule `objectMosaic` walks on the CPU. Coverage comes from the
mip chain: a cell of `w` pane pixels is `w / TEXEL` texels of the plate, so one `textureLod` at
`log2` of that is the average of exactly that square, for nothing. It is why **the plate is
rendered on the cell's aspect rather than the pane's** — a cell is then square in texels and a
square mip average is the cell's own box.

Two things follow, and both matter:

- **The grain traces the letters as they move.** The packing re-decides itself every frame, so
  the field is not a screen the type shows through — it is the type, re-packed.
- **The ground is cut out by the words wherever they are.** A cell outside the ink stands in the
  ground colour and one inside it in the ink, off the same sample, so a letter always carries its
  own edge against the field rather than sitting on top of one packed without it.

**The size is what carries the letters, not the colour.** A mark's scale follows how much ink
covers its cell: full size wholly inside a stroke, `SLIDES_CARD_SHRINK` wholly outside it, and
smoothstepped between — so a word swells the field it crosses and lets it back down behind it,
and a cell the letter only clips is a half-grown mark rather than a hard yes or no. Setting the
ground to the ink colour hands the whole job to size; `SHRINK=1` hands it back to colour.

**Every element is one size unless a finer one is asked for.** `SLIDES_CARD_FINEST` defaults to
`COARSE`, so the card is a uniform field, the grey ground included — which reads as a set of
components, where a range of sizes reads as a picture made out of them. Set `FINEST` below
`COARSE` and the packing comes back: a stroke fills with big elements and its edge traces with
small ones, and the size of a mark tells you where in the letter it is. Every level is in the
buffer, so halving `FINEST` quadruples the cells.

**A mark can only be as big as the stroke it has to fill**, and that is the real limit of a
coarse grid rather than anything that can be tuned. Measured on this title at 1920x1080, whose
strokes run about 45 pixels: at a cell of 18 a stroke carries two or three marks across and the
words read; at 34 barely one and they come apart; past that no cell is ever wholly inside a
stroke and what is left is a scatter. `SLIDES_CARD_THRESHOLD` lights cells the letters only
clip, which puts weight back at the cost of the words growing into their own counters. Big marks
want big letters — a one-word chapter, or `lines` in the show — not a lower threshold.

**The cell is the shape of the catalogue, and it is read off the sheet.** `subset.svg` runs a
median of 1.85 wide to high and `objects-iso.svg` 1.06; an element fitted into a cell of another
proportion letterboxes, so naming a sheet without matching the cell to it quietly halves the ink.
`SLIDES_CARD_SHAPE` left empty takes the median of whichever sheet is named — the same move as
reading the object sheets' grid off the file rather than stating it. `SLIDES_CARD_UNIFORM` then
draws every element at one *height* rather than fitting each by its own proportion: fitted is the
honest thing when a mark stands for a component, but on a field of one cell size it means a cell
holding a 3.3:1 beam carries a third of the ink of one holding a panel, and that reads as holes.

**The words are set out a word at a time, and the arrangement is not this card's.** The sentence,
where each word stands and when it is there are [kinetic type](#kinetic-type)'s; the card paints
what that says into its plate and the field reads it. `SLIDES_CARD_BUILD` off gives the other
behaviour the card knows, which *is* its own: the arrangement holds and one word at a time eases
to somewhere new, the others standing still. **A move there is along one axis, never across** — a
round slides the words sideways and the next lifts them — because a diagonal reads as a thing
being carried from one place to another where an axis move reads as the composition re-setting
itself. It also makes the swept test exact rather than conservative: with no corner to cut, the
box that contains both ends *is* the path, so a word cannot pass through another even mid-move.

`SLIDES_CARD_BLEED`, `_BEAT`, `_MOVE` and `_HOLD` are handed straight to it and are described
under [kinetic type](#kinetic-type).

**`SLIDES_CARD_WINDOW` shows the type through a travelling box.** A band of that height crosses
from below the frame to above it and back over one period, and nothing outside it reaches the
plate — so a word is uncovered as the window passes and gone again behind it, and what moves
across the card is a band of type rising out of a still field. It is a clip on the *picture*, not
on the elements: they never move, they are simply reading a plate that has type on it for as long
as the window is over them.

**There are two views of the card, and `b` in the studio switches between them.**
`SLIDES_CARD_DEBUG` draws the plate itself instead of the field that reads it, with every box the
layout stands on over it: the buffer's own edge in red, the measure the type is set to in orange,
how far a word may hang off in yellow, the window's band in green and each word's box in blue.
They are the same card at two stages, which is the point — a fault in the picture and a fault in
the packing that reads it look alike until the picture can be seen on its own, and a word in the
wrong place looks exactly like a word correctly placed in a box that is itself wrong. The plate is
put back to the pane's proportion to draw it, since it is rendered on the *cell's* aspect and
would otherwise be stretched by that factor.

**The elements arrive one at a time, each growing from its own centre**, and the whole card is
still **one draw call** while they do. Every vertex carries its own element's centre, its size,
its depth and its place in the arrival, so the shade style works all of it out per vertex. An
element that has not started collapses to a point and reaches nothing, so there is no state to
keep and nothing to skip. The place in the arrival is a *value* rather than an index into the
list, and has to be: only a fraction of the cells stand at any moment, so an arrival counted in
indices would spend most of its length on cells nobody can see.

**Three traps in reading a plate this way, all found by measuring rather than looking.**

- **A colour buffer has one mip level unless you ask for more, and `textureLod` does not fail on
  one — it hands back level 0.** Every cell then reads a *point sample* at its own centre, which
  is always 0 or 1, so nothing ever straddles an edge, nothing subdivides, and the card comes out
  as blocks of the coarsest cell wherever a centre happened to land in ink. It renders, and it is
  unreadable.
- **A render target is drawn y-down and a texture is read y-up.** Taken raw the whole card comes
  out upside down, every letter with it, which looks like a font problem and is a `1.0 - v`.
- **The rule's uniforms have to be the sizes in force, not the ones the card was built with.**
  `j`/`k` in the studio rebuild the field at another mark size; a shader still holding the old
  `finest` decides every cell is too big to be the finest level, so nothing straddling a letter's
  edge may stand — and the words come out as a *hole* in a perfectly intact ground. That reads as
  a limit of the grid and is a stale uniform.

**All of it happens in `load`** — the field, the plate and the mip chain — because a card comes
up at the top of every chapter and none of it may land on the click. The plate is rendered before
anything knows how big a pane is, so the card **states the pane it composes for** and `draw` fits
that into whatever it gets, the same arrangement as `CityMapSlide`. And `STILL_HOLD` in `Show.kt`
had to go from 40 frames to 95: the reveal takes 1.4s and the contact sheet was catching every
card half built.

**The cards have a face of their own**, `SLIDES_CARD_FONT`, rather than the talk's family. A
card's title is never drawn as type, so what the face decides is the *shape of the strokes the
mosaic has to fill* — and a grotesque comes apart into parts better than a slab does: even
weights, flat terminals, no serifs to lose to a cell. Empty falls back to the deck's own bold,
so a machine without that face still runs the show.

`data/` is not committed, so a missing sheet falls back to setting the title as ordinary type
rather than failing. `ChapterPanel` is still there and still the plain version.

##### A picture instead of the type

`ObjectImageChapterPanel` is the same card reading a **png off disk** rather than type it sets
itself. Everything downstream of the plate is unchanged — the same field, the same coverage
rule, the same arrival — because the field never knew it was reading letters. So a title that
was *drawn* rather than set, or a mark that is not type at all, can still be made of
components. `SLIDES_CARD_IMAGE` names the file, and every other `SLIDES_CARD_*` key still
steers the field.

**Every chapter in the committed show is one of these**, and `chapterCard(section)` in
`Slideshow.kt` is the whole of the wiring: it takes the chapter's number off the running order
and reads `data/slides/chapter-<n>-title.png`. So the four titles are *drawn* — set full bleed
in a condensed grotesque, broken and packed the way the design asks rather than the way
`setToFit` would — and adding a chapter needs nothing in the code but the png beside the others.
A chapter with no picture falls back to `ObjectChapterPanel` setting its title as type, which
is what keeps a checkout without `data/` running rather than showing blank cards.

**These need no `levels`, and that is measured rather than assumed.** The earlier
`chapter0.png` set "DE" and "VAN" as grey outlines — its lit pixels averaged 106 — so a black
and white point had to be pulled in before anything read it. The four chapter titles are drawn
at one weight: 30–41% of each image is pure 251–255 white and under 1% of it falls in any
middle band, the outlined words included. There is nothing to correct, and correcting anyway
would only pull the antialiasing up into the ink. The rest of the recipe is `ImageCardStudio`'s
unchanged — coarse 32 down to finest 8, `solid` 0.62, `shrink` 1.0 — because the two-grain
problem is the same: these titles mix solid words with hairline outlined ones ("DE"/"VAN",
"EN", "EEN"), and no coarse cell is ever wholly inside a hairline.

**A still of one of these cards has to be taken late.** The reveal runs 2.8s and `STILL_HOLD`
is 95 frames, so the contact sheet catches every card mid-sweep — the ink half way down, the
words below it standing as holes in the ground. It looks like a fault in the packing and is a
fault in the timing. `CARD=1 CARD_AT=4.0` is how to see a finished one.

**The picture must be white on black**, which is what the mask means everywhere else here:
white is ink, black is ground. `SLIDES_CARD_IMAGE_INVERT` reads one that is the other way
round, and applies to the picture alone — the plate stays cleared to black, so a picture that
does not fill the pane leaves ground beside it rather than a band of marks. It is fitted into
the pane and centred, never cropped and never stretched.

**The plate is painted once, in `load`, and never again.** That is the one real difference from
the card that sets its own type: there the words move, so the plate and its mip chain are
rebuilt every frame; a picture off disk cannot move, so both happen once and a frame is one
`vertexBuffer` call. The elements still arrive over `REVEAL`, because that is baked into the
field rather than into the picture.

**What it costs is the stroke-width limit, and a drawn title hits it harder than a set one.**
A mark can only be as big as the stroke it has to fill — the same limit recorded above — and an
*outlined* letter is all edge and no stroke. Measured on `chapter0.png`, whose "WERELD BOUWEN"
is solid and whose "DE" and "VAN" are hairline outlines: at the committed `COARSE=30` with one
mark size the solid words read and the outlined ones come apart into scatter, 4288 cells. At
`SLIDES_CARD_FINEST=8` the whole title reads, outlines included, at 90 048 cells. So a picture
with fine strokes in it wants the packing back rather than a uniform field.

`CardStudio` drives it like any other card, through `MosaicCard` — the interface in
`ObjectMosaic.kt` carrying the three things the studio does to one (`b`, `j`/`k`, the readout).
The studio holds that rather than a particular class, so a new kind of card does not come up
with those keys silently dead. Its debug view is the plate and the pane's edge; there are no
word boxes to draw, because no words were placed.

If the sheet is missing it draws the picture itself, which is the honest equivalent of the
other card falling back to plain type. If the *picture* is missing it says so at load and draws
nothing rather than failing the run.

###### The card on its own

[`ImageCardStudio.kt`](src/main/kotlin/ImageCardStudio.kt) runs it at 1920x1080 with no show
around it:

```
./gradlew run -Popenrndr.application=ImageCardStudioKt
```

`j`/`k` step the mark size, `b` switches between the field and the plate it reads, `s` writes a
still, `r` replays the arrival. `CARD_STILLS=true` writes one png and quits.

**It states its own recipe rather than reading `SLIDES_CARD_*`,** under its own `CARD_*` prefix,
and that is not tidiness — the show's keys are the *typeset* card's and the committed `.env` is
tuned for it: a 1.85:1 cell off `subset.svg`, one mark size, a ground that barely shows. A
picture wants the opposite of all three, so reading them here would come up wrong every time and
read as a fault in the card rather than as the wrong settings.

**The marks may be a folder as well as a sheet.** `loadMarkTemplates` takes a directory of svgs,
one file to a mark, whole file as it stands — no grid to read off, no captions to drop, because
none of that was ever in the file. That is what a set of *drawn* marks looks like on disk when
it was not exported as a catalogue page, and `data/svg/3-shapes` is one: a solid square, a
notched near-square and a wide bar.

**Square marks and full cells pull against each other, and the sheet decides which you get.**
Measured on this picture: `subset.svg` at its own 1.85 cell fills its cells and the letterforms
are solid, but the marks are wide and there is no grain to speak of; the same sheet forced into a
square cell smears, because `UNIFORM` draws every element at one height and a 3.3:1 slab then
overflows a square cell by three times. `objects-iso.svg` is nearly square (median 1.06) and does
not smear, but its elements are thin diagonal slivers, so the letters carry almost no ink. The
three drawn shapes fit a square cell *and* fill it, which is why the studio opens on them.

**The limit that remains is the stroke width, and it is the picture's.** `chapter0.png` sets
"WERELD BOUWEN" solid and "DE"/"VAN" as hairline outlines, and no cell is ever wholly inside a
hairline. Lowering `THRESHOLD` to catch them catches the picture's own soft halo instead and the
letterforms go mushy — measured, at 0.14. More levels is the fix that works: an edge subdivides
and the outlines come back as smaller marks.

###### The packed wall

The studio opens on a **bin-packed** field rather than a grid of separate marks: every piece of
`subset.svg` fitted to fill its own cell, over a quadtree deep enough to carry several sizes —
big pieces through the middle of a stroke, small ones tracing its edge. Two decisions make it
that rather than a mosaic:

- **Nothing is shrunk.** At `SHRINK=1` every cell stands its piece full size inside the letters
  and out, so the words are carried by **colour alone** — white through them, grey around them.
  Size *and* colour both carrying it reads as a picture of type; colour alone reads as a wall
  that happens to spell something.
- **The clearance is absolute, not a fraction.** `FILL` is a fraction of the cell, so on a
  quadtree it opens a wide gap around a coarse mark and a hairline one around a fine mark —
  and the fine marks are exactly where two neighbours are closest. `SLIDES_CARD_GAP` (the
  studio's `CARD_GAP`) is subtracted from the cell in frame pixels before the mark is fitted,
  so a mark is inset half of it on each side and **any two neighbours stand that far apart
  whichever levels they are drawn at**. `mosaicField` defaults it to 0, which is the old
  behaviour, and the typeset card names its arguments from `seed` on rather than taking it.

**The grain is bounded at both ends and the picture sets both.** Too coarse and the words go:
at 104 against this picture's ~60px strokes the wall is handsome and illegible. Too fine and it
turns to mush — 72 down to 9 is 64 260 cells and reads as noise. 48 down to 12 is the range where
a stroke carries two marks and the wall still has several sizes in it.

**Why a word can be present and still not read, and it is not the threshold.** "VAN" came out
as a scatter while "BOUWEN" was solid, and there were two causes, both measured off the picture
rather than guessed:

- **The picture is not drawn at one weight.** "WERELD" and "BOUWEN" are solid white — p95 of 255
  — while "DE" and "VAN" are soft grey outlines whose lit pixels average 106. The field asks one
  question of the plate, *how much of this cell is ink*, so the same letters at a third of the
  signal stand in the ground colour. `SLIDES_CARD_LEVELS` sets a black and white point on the
  plate before anything reads it: pulling the white point down to where the grey type sits lifts
  it to full ink, and holding the black point just above the picture's blur skirt is what stops
  the halo coming with it — which a plain gain cannot do. Lighting VAN by dropping `THRESHOLD`
  instead was tried and fails: it tops out around 45% of VAN's cells (it is an *outline*, so
  only its stroke can ever light) and past 0.26 the empty ground starts lighting too.
- **A thin stroke is drawn in the smallest marks the field has.** No coarse cell is ever wholly
  inside a 23px stroke, so every cell over "VAN" subdivided to the finest level — the word stood
  in 12px marks beside a ground standing in 48px blocks, and the eye read the blocks. That is
  what `SLIDES_CARD_SOLID` is for: it is how much ink counts as *wholly inside*, 0.88 by default
  and strict, and lowering it lets a mostly-covered cell stand at its own size. Narrowing the
  coarse-to-finest range does the same thing from the other end.

The picture asks for two grains at once — 63px strokes want a coarse grid, 23px strokes want one
three times finer — so the settled recipe is coarse 32 down to finest 8 with `SOLID` at 0.62 and
levels 0.06–0.38. A version of the png with those words set solid would need none of it.

**The arrival is staged, and it has to happen in the shader.** `SLIDES_CARD_SWEEP=1` builds the
card in two passes: the ground sweeps **up** from the foot over `STAGE` of the reveal, then after
`DELAY` the ink sweeps **down** from the head over what is left — so the wall goes up first, with
the words standing in it as holes, and then the words land onto it. `SWEEP=0` is the typeset
card's behaviour, every cell arriving in the field's own baked random order.

**It cannot be baked into the buffer**, which is the whole reason it is a shader term: *which
pass a cell belongs to* is whether it stands in ink, and that is the mask's answer, read per
frame. A cell's `order` therefore has to be computed rather than stored — the buffer only carries
a random value, which is mixed back in as `JITTER` so the leading edge of a sweep is a ragged
front of marks rather than a ruled line crossing the card. It also means a card whose picture
moves would restage itself as it went.

Judging it needs frames rather than a still: `CARD_AT=0.5,1.1,1.7,2.2,2.7,3.2` writes a png at
each of those seconds and quits, which is how the order above was checked. A single still says
nothing about the order things arrived in. The reveal is 2.8s here rather than the typeset card's
1.2s, because two passes and a pause in 1.2s run into each other.

`CARD_RECORD=true` films it to `video/image-card.mp4` instead, sharing `CARD_FPS` and
`CARD_DURATION` with `CardStudio`. The recorder takes `contentScale = 1.0 / CARD_WINDOW`, so a
clip comes out at the canvas's own size and not the window's — the deck's own trick. For a gif,
the card is three tones plus antialiasing, so a small palette carries it:

```
ffmpeg -i video/image-card.mp4 -vf "fps=20,scale=960:-1:flags=lanczos,split[a][b];\
  [a]palettegen=max_colors=64:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3" \
  video/image-card.gif
```

An ordered dither rather than the default error diffusion, because the grain is already fine and
diffusion crawls between frames on a field like this.

**Both buffers run at `contentScale = 2.0`.** The plate gets twice the texels behind the same
coordinates, so nothing downstream changes — the shader samples in normalised uv and lays out in
pane pixels either way — while every mip level is averaged from four times the samples. Its mip
chain has to be **counted on the real size**, or the last level is not a single texel and a
coarse cell reads a blur rather than its own average. The studio's canvas is doubled the same
way, so a still comes out at 3840x2160; a field of this grain is all edge, which is the one thing
worth the memory.

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

**The whole talk is set in one family, in two weights**, and the family comes from outside
the project: `data/fonts` holds one weight of IBM Plex and no bold. `SLIDES_FONT` names the
file, `SLIDES_FONT_BOLD` and `SLIDES_FONT_TEXT` name the faces, and everything else is
derived — `boldFont` for headings, cards, quotes and the copy on the slabs, `textFont` for the
furniture. [`usableFont`](src/main/kotlin/FontPath.kt) stands the file up: a missing one falls
back to the bundled face rather than stopping the show, a path with a space in it is copied
somewhere plainer because `loadFont` parses its argument as a URI, and a `.ttc` has the wanted
face lifted out of it because stb_truetype will not open a collection at all.

`Type` — the furniture in `slide-drawers/` — takes its path from a `var` that `main()` sets
before the first slide loads. That is the default-package wall again: `usableFont` is in the
default package and `slideshow.drawers` cannot import from it, so the show hands the path in
rather than the object working it out.

**Two traps in lifting a face out of a collection, and both were live.**

- **A loose name match slides into the italic.** Rockwell's collection holds `Rockwell-Bold`
  and `Rockwell Bold Italic`, and "Rockwell Bold" is contained in the second before the first,
  so a plain `contains` hands back the italic. `pickFace` matches exactly first, then loosely
  but skipping italics unless the ask says italic.
- **The cache has to be checked by name, not by date.** The extracted file is named after the
  *wanted* string flattened for a filesystem, and two different requests flatten to the same
  name — "Rockwell Bold" and "Rockwell-Bold" both give `Rockwell-Bold.ttf` and resolve to
  different faces. On a date check the second request silently gets whatever the first left
  there. That is exactly how the deck came to be set in Bold Italic: a `build/fonts` file
  written by an earlier sketch. Comparing the face already written against the face now asked
  for costs one name table and cannot go wrong that way.

**Changing the family surfaced a bug in the slabs.** `Swivel02Slide` wrapped its copy at a
stated size, and wrapping can only break at a space: "Betonproductie" is one word, wider than
a slab in Rockwell, and hung off both edges — where, in a scene with depth, the slab in front
clipped the overhang. It read as the word having lost its first letter rather than as type
being too big. A line that will not fit is now set smaller instead.

`panelFont` is declared **above** `show` in `Slideshow.kt`, and has to be: top-level values
initialise in the order they are written, and the `panel { }` lambda is called *while*
`show` is being built, as the slides go in. Written underneath it is still null when the
cards are made.

## circle mosaic

[`CircleMosaic.kt`](src/main/kotlin/CircleMosaic.kt) is the chapter card's field with **nothing
behind it but a growing circle**:

```
./gradlew run -Popenrndr.application=CircleMosaicKt
```

`j`/`k` step the mark size, `b` switches between the field and the plate it reads, `p` holds the
clock and `.` `,` step it, `r` restarts, `s` writes a still.

**It is `ObjectImageChapterPanel` with the picture replaced by a render target**, and that is the
whole of it — the same `mosaicField`, the same coverage rule, the same arrival. Which is the
point of doing any of it through a plate: **the field never knew it was reading letters.** A png
off disk, type set to the frame and a circle drawn a frame at a time are all one thing to it, a
black and white mask with a mip chain on it.

**What comes out is a ring, not a disc, and that is the packing rather than anything asked for.**
Cells wholly inside the circle stand a big mark and cells wholly outside stand a small one; only
the cells the edge crosses subdivide. So the travelling edge is a band of fine marks moving
outward through a field of coarse ones, and at the top of the swing — every cell inside the ink —
the frame is one uniform field with no subdivision anywhere.

Three things it does differently from the card it came from:

- **The plate is repainted every frame and its mip chain rebuilt with it**, which is the *typeset*
  card's arrangement rather than the picture card's. A picture off disk cannot move; a circle does
  nothing else.
- **The ground has to stand.** A cell outside the circle is as settled as one inside it, so it
  stands its element too — `CIRCLE_SHRINK` of the size, in `CIRCLE_GROUND`. Left at the show's own
  values (a ground that barely shows) it reads as a disc on empty paper and the piece is a shape;
  at a grey that shows, the circle is passing *through* a standing field and swelling the marks it
  crosses, which is the thing worth looking at.
- **No sweep.** The staged arrival sorts cells by whether they stand in ink, and here that answer
  moves every frame — so a staged field restages itself under the circle for as long as the reveal
  lasts. The field arrives in its own baked random order and the circle starts from there.

`CIRCLE_RETURN` grows the circle and brings it back rather than cutting: at full size every cell
is inside the ink, so restarting from nothing throws the whole field down a size in one frame. A
cosine turns at either end instead and **closes the loop exactly** — measured, not judged: the
frames at 2s and 8s of the 6s period are byte-identical.

The keys are its own `CIRCLE_*` rather than `SLIDES_CARD_*`, for the reason `ImageCardStudio`
states its own: those are the typeset card's, tuned for a 1.85:1 cell off `subset.svg` and a
ground that barely shows, and two of the three are wrong here.
