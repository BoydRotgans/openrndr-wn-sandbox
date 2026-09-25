# Motion

## The law
- A slide is a pure function of `stage.position` (clicks, already eased) and `stage.frame` (frames since it came up).
  No `program.seconds`, no state carried between frames. Clicking back is a smaller number, not an undo.
- `stage.on(n)` is click n's window; `between(a, b)` builds up and down in one expression.
- **Never ease `position` again.** Anything *counted* (items added or removed) undoes the ease: `linear(stage.on(n))`.
- Everything heavy happens in `load`; a click never waits.

## Between slides
- `Cut` by default: a new subject is a new picture.
- `Fade` only between two states of the same thing; `Push` only where things travel on.
- A seamless handover (last frame of one = first of the next) passes the shape with `Mark`; never recompute it.

## Opening a slide
- The first state builds on the slide's own clock (`stage.since(offset, length)`), only while `stage.step == 0`,
  so stepping back into the slide finds it built.
- Settled by ≈ 1.5 s — stills are taken at 95 frames. Declare `settle` so auto runs wait for it.

## House moves
| move | how | seen in |
|---|---|---|
| **Take its step** | arrives from one unit back (a run, a row) while fading up, on the same number | ladder rungs |
| **Grow from the baseline** | bars and bands grow out of their axis, eased by the deck | charts to come |
| **Re-form** | persistent slots travel to the next formation; newcomers wait for the travel (travel ≈ first 60%) | `Crowd`, ladder collapse |
| **Stagger** | a few items in reading order or middle-out, 0.15–0.35 of the click apart | tree fan, new rungs |
| **Dim the rest** | what is not being talked about goes grey or to outline; what is held stays solid | ladder |
| **Count** | one at a time, instant, linear in time | city cull, globe |
| **Crossfade text** | old text gone by ⅓ of the click, new text in from ⅔; unchanged text stays; titles sharing a prefix crossfade straight | ladder |
| **Loop** | whole cycles of `stage.loop`; the seam checked byte-identical | swivels, walls |
| **Breathe** | for what stands for minutes: a slow, bounded, never-repeating drift, at the edge of noticing (`Breathe`) | chapter card's light |
| **Snap** | one element to its next place along the grid, `snap` over `SNAP_SECONDS`, the next a stagger later — see below | `assemble`, `assemble-grid` |
| **Pop** | a change of state in place, in one frame: no fade, no scale | the reference's crosses turning square |
| **Calm** | for a wall that stands for minutes: `cubic-bezier(0.4, 0, 0.2, 1)` over ~0.8 s a leg, pieces ~0.3–0.45 s apart, grown in rather than popped — the snap there read as too fast | `assemble-row` |

- The house moves are classes in `slideshow/`: `Arrive` (with its stagger), `Grow`, `Slots`, `Swap`,
  `Count`, `Leader`, `Breathe`. Reach for them before writing a new expression.
- A click may have its own length: `stepLength(step)`. A long push and a short cull are not one click.

## The snap — measured off `input/motion-snap.mov`

A 5.4 s reference of motion graphics we want the walls to move like: white crosses sliding into place on a
black grid, then black crosses and dotted tiles over a light ground. Measured frame by frame (30 fps, three
repeated frames), not described.

| what | measured | rule |
|---|---|---|
| **The curve** | every move: ~15% over 3 frames, ~60% in **one** frame, the rest settling over 5–6 — 0 · 2 · 8 · 14 · 76 · 90 · 96 · 98 · 100% | `snap` in `Timing.kt` = `cubic-bezier(0.7, 0, 0, 1)`: residual 0.09 over four moves, vs ~0.53 for in-out quint/expo, 4.6 for a pure expo ease-out |
| **The duration** | 9–10 frames, **the same** for 50 px and 146 px | `SNAP_SECONDS` = 0.3 s whatever the distance — speed scales with distance, time does not |
| **The paths** | bars move straight along the grid, one axis at a time | move on the lattice's axes; a diagonal is two legs |
| **The stagger** | moves start 3–6 frames (0.1–0.2 s) apart in different places, overlapping | one after another, never in unison; a new move before the last has settled is fine |
| **The build** | ~0.5 s near-still to open, then sparse moves, then denser ones, then the camera | start in stillness; thicken; end on the widest move |
| **State changes** | a cross becomes a filled square, a tile changes pattern: **one frame**, no fade, no scale | a swap in place is a pop; motion is for things that go somewhere |
| **The camera** | a slow zoom out, linear, ~1 px/frame (~9%/s), never eased to rest; faster in the last frames | continuous things run linear; only discrete moves ease |
| **Scene changes** | two hard cuts, both in the middle of the camera's move | cut on action, never a transition; scenes are short (1.3–2.6 s) |
| **The eye** | one lime square rides the element being moved | one accent colour marks what is changing |

Checked on the `assemble` draft filmed at 30 fps with one beam moving alone: 0 · 2 · 12 · 41 · 80 · 92 · 96 ·
98 · 100% against the reference's 0 · 2 · 8 · 14 · 76 · 90 · 96 · 98 · 100 — the wind-up and the settle agree
frame for frame; the steep part falls across two samples rather than one, which is only where 30 fps lands on it.

## Extended practice — what the design systems add (sources in the table)

The reference and the published systems agree more than they differ; where they are UI numbers, the wall
changes them, and that is said.

| topic | practice | source | for the wall |
|---|---|---|---|
| **Few curves** | a handful of custom curves, used consistently; the same meaning always gets the same motion | Val Head, Carbon | `snap` for discrete moves, linear for continuous ones, nothing else without a reason |
| **Enter / exit** | entering decelerates, leaving accelerates and is shorter (drawer 250 / 200 ms, fade 150 / 75 ms) | Material, Carbon | a thing leaving for good may go on an accelerate, ~20% quicker than its arrival |
| **No bounce** | no bounce, stretch or sudden stop (Carbon); springs stay near-critically damped (M3 damping 0.9 ≈ 0.15% overshoot) | Carbon, M3 | the house rule already: no overshoot |
| **Linear** | only for constant rhythm — progress, loops, a drifting camera | Carbon, Figma | the reference's zoom is linear too |
| **Duration and distance** | duration grows far slower than distance: 14× the travel is only ~2× the time (IBM), a snap is constant (reference) | IBM Motion, reference | `SNAP_SECONDS` for everything a click's gesture moves; up to ~0.5 s for a move across a whole projector |
| **Following with the eye** | the eye tracks only up to ~30°/s, and only after 90–150 ms | smooth pursuit | at 9 m a degree is ~25 px, so ~750 px/s: **a snap is a jump and a landing, never followed** — right for building, wrong for "watch where this goes"; a move meant to be followed takes T ≥ k·D / 750 (k the curve's peak/average speed: 3 for in-out cubic) |
| **Stagger** | UI: 20 ms per item, whole cascade within 500 ms; start the next before the last ends | Carbon, Material | 20–60 ms for a group read as one gesture; 100–200 ms (the reference) for moves meant to be seen one by one |
| **Order** | one direction — reading order, or out from the focal point; stable content first, the important last; paths never cross | Material, Carbon | take apart top-down, build bottom-up, fill in reading order |
| **Paths** | trace the grid, never diagonally | Carbon, reference | a diagonal is two legs |
| **Grouping** | things that belong together move as one: separate movements compete | Material | stagger parts, not wholes that should read as one |
| **Undo is reverse** | forward in the entrance direction affirms, reversing cancels; don't dismiss the way something didn't come | Carbon, Apple | the deck's click-back is already the build played backwards |
| **Holds** | text: ~0.3 s a word, never under 5/6 s | BBC, Netflix | `pacing.md`; after a build the picture holds long enough to be read before the next move |
| **Attention** | fast motion pulls the eye, slow motion recedes; motion seen again and again becomes a roadblock | NN/g | keep the long-standing walls slow (`Breathe`); save the snaps for the moments |
| **Cuts** | cut on action: a cut during movement hides itself | film editing, reference | scene changes cut mid-camera-move, not on a still |
| **Comfort** | no more than 3 flashes a second; no sustained oscillation near 0.2 Hz; whole-surround motion can make people unwell | WCAG 2.2, Apple HIG | **a pop that swaps a large area light↔dark counts as a flash on a 12 m wall**; camera moves stay slow and linear |

Where they disagree: Material makes exits shorter where Comeau makes a hover-out slower; Carbon bans bounce
where Apple allows up to ~0.4; UI ceilings of 300–500 ms sit beside Material 3 tokens up to 1000 ms. The wall
takes the reference's side on all three — short, no bounce, 0.3 s — and gives the eye time only where a move
is meant to be followed.

Links: Material 3 motion tokens (github.com/material-components/material-web, `_md-sys-motion.scss`) ·
Carbon (carbondesignsystem.com/elements/motion) · IBM Motion `getDuration.js` (github.com/IBM/motion) ·
Material choreography (m1.material.io/motion/choreography, m2.material.io/design/motion/choreography) ·
NN/g (nngroup.com/articles/animation-duration, /animation-usability) · Apple HIG motion and WWDC23 "Animate
with springs" · easings.net · BBC subtitle guidelines · Netflix timed text · WCAG 2.2 three flashes ·
smooth pursuit and cutting on action (Wikipedia) · Val Head (smashingmagazine.com, css-tricks.com).

## Emphasis
- By colour, never by size. A selected thing may pulse its colour slowly (`PixelMap`); nothing jumps or scales.
- A leader grows from the words toward the thing it points at, on the number the words fade up on.

## Don't
Typewriter text · bounce, elastic, overshoot · spinning for its own sake · fades between subjects · things popping
in when they have somewhere to come from · double easing · text reflowing while its box moves · diagonal or
crossing paths · a group moving in unison where its parts could go one by one · a snap where the audience has to
follow the move · large light↔dark pops faster than three a second · a camera that eases to a stop mid-scene.
