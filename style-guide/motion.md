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

## Emphasis
- By colour, never by size. A selected thing may pulse its colour slowly (`PixelMap`); nothing jumps or scales.
- A leader grows from the words toward the thing it points at, on the number the words fade up on.

## Don't
Typewriter text · bounce, elastic, overshoot · spinning for its own sake · fades between subjects · things popping
in when they have somewhere to come from · double easing · text reflowing while its box moves.
