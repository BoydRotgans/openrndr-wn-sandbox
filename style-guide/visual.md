# Visual

## Frame
- **The installation is two walls, each 12 m wide and 1920×1080, left and right, with the room 8 to
  10 m away.** In the show they are the two panes of one 3840 canvas meeting at x = 1920: a talk
  slide is the **right pane** beside the chapter card in the left, and the card stays up for the whole
  chapter. Lay out against `stage.bounds`: sizes as a share of the pane **height**, positions as a
  share of the width. Whether the two surfaces are continuous is not yet known; the four backdrops
  that draw one picture across both depend on it.
- **A pixel is 6.25 mm on the wall.** `Scale.arcminutes(share)` says what a size becomes from the
  room; the smallest type here is about three times the comfortable reading threshold, so a size is
  always a question of hierarchy, never of legibility. Do not enlarge type because it looks small in
  the combined preview.
- **The lines every layout stands on are `Frame`'s** — margin 0.02 of the width, title at 0.06 of the
  height, foot at 0.94 — and `g` in the show rules them over the wall.
- **A wide moment that carries type or a diagram is still two panes.** Nothing that has to be read
  straddles the seam — no line of type, no list, no chart runs from one projector into the other.
  Split the content: one thing on the left, one on the right (`Programme`: the list left, the key
  message right).
- **A backdrop scene — a picture with no type to read — may run across the whole 3840 wall.** The
  yard, the gallery, the block city and the shadow walls do; the seam is theirs to cross.
- The client's frames are 16:9 slides; in the show they become the pane. Only take the wall when the plan says so.

## Ground
| use | colour |
|---|---|
| content slides | black `#000000` |
| quotes | dark grey `#3C3C3C` (`QuoteSlide`) |
| maps, light walls | `#F5F5F5`, `wnWhite #F5F7FA`, `wnPaper #E8E8E8` |
| placeholders | pink `#FF3F9E` — never in a finished slide |

## Colour
- **White** `#FFFFFF` for type and neutral shapes (rungs, boxes, labels).
- **Red** `#FF0000` (`wnRed`) is the accent: WN's own share, the point being made, the highlight. One red idea per state.
- **Blue** on slides is the bright Figma pair — `#3D5AE0` (slabs, frames) and `#4674D6` (`wnSky`: people, discs).
  Walls use the navy `wnBlue #1E3A72`. Prefer one blue per slide.
- **Grey** `#D9D9D9` for secondary text and dimmed things; `#2E2E2E` for a ground that must still read as ground.
- The playful set (`wnAmber`, `wnTeal`, `wnCoral`) belongs to the sectors collage only.
- Flat fills. No gradients, no drop shadows on slides (walls have their own light), no transparency as
  decoration — opacity only while something arrives or leaves, or to dim what is not being talked about.
- Build colours with `ColorRGBa.fromHex`; the plain constructor gets sRGB-lifted in shaders.

## Type
Rockwell — `boldFont` for titles, labels, quotes, figures; `textFont` for body, notes, captions. Paths are
handed to the drawer, never looked up in it.

| role | × pane height | px |
|---|---|---|
| slide title | 0.036–0.041 | 39–44 |
| label on a shape | 0.024–0.030 | 26–32 |
| body, paragraph | 0.0287, leading 0.035 | 31 / 38 |
| quote | `setToFit` to the measure, ranged left | — |

- **Title**: centred at the top (≈ 0.06–0.075) on diagrams; top-left (x 0.02, baseline 0.055) when an
  explanatory paragraph stands under it, as on the ladder. It may change between states only by crossfading.
- Sentence case, Dutch, the client's wording. Figures in Dutch notation: `350.000`, `1,9%`, `€ 1,4 miljard`.
- CO₂, M², M³ go through `setLine`. A character outside `TYPE_CHARACTERS` draws nothing and advances nothing — add it.
- A list of things of one kind is set at one size, never fitted item by item.
- An explanatory paragraph is ≤ 35 words and ≤ 6 lines, wrapped to a fixed measure (≈ 0.29 of the width).

## Imagery
- **Everything is a precast component seen one of three ways**: as a catalogue specimen (ordered,
  numbered, named), as a technical drawing (plans, leaders, a share in red), or as sculpture (in the
  round, lit from one side, a real shadow). A slide says which, and that settles its ground, its
  movement and whether its type is a caption or a label. See `principles.md`.
- The vocabulary is the catalogue: svg silhouettes (`data/svg`), meshes in isometric (`IsoPieces`, `data/objects`),
  names and sizes from the register (`data/csv/objects-115-details.csv`).
- People are the low-poly silhouettes (`Crowd`); maps are pixel grids (`PixelMap`); buildings are blocks (`BlockCity`).
- Photographs only where a real project is the content, inside a shape and duotoned in its colour (`CaseStudy`).
- Diagrams are rectangles and type: bands, columns, rungs, bars. Lines are 2 px and only lead or connect.

## Layout
- Edge margin ≈ 0.02 of the width; content may fill the pane.
- A label sits inside its shape or at the end of a leader; text never lies over a shape it does not belong to.
- One composition that re-forms between states, not a new layout per click.
