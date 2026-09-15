# Completion plan

State on 15 September 2026, evening: **all sixteen slides are built, and no module stands.** Decisions 1 to 4 were
taken (the block city joined, the building rendered from the IFC in `input/`, an ending with a QR code, the dinner
beds off `input/diner_music`). Still open: the revisions from the meeting, and every `PLACEHOLDER` figure in
`Slideshow.kt` — the chart values, the blue bands, the bar values, the WN mark as svg (`SLIDES_MARK`), and the
ending's address and action (`SLIDES_ENDING_URL`, `SLIDES_ENDING_ACTION`).

Earlier that day: the four chapters held 32 entries, **15 of them pink modules**, plus **het-programma**
in the Opening — 16 slides to build. On top of that: revisions the meeting of 9 September asked for on finished
slides, and an ending nobody has briefed yet.

## Decide first
1. **Title placement** — centred at the top (most slides) or top-left with a paragraph (the new ladder). See `visual.md`.
2. **Blue on slides** — the bright Figma pair (`#3D5AE0`, `#4674D6`) as now, with navy kept for walls?
3. **Block city in chapter 4** — does `demontabel-in-de-stad` replace the two block-city backdrops there, or join them?
4. **The ending** — what is the call to action: website, QR code, contact, an invitation?

## Waiting on WN
| input | blocks |
|---|---|
| figures behind the two charts: CO₂ per geïndexeerde omzet 2020–24 and target, groene stroom 2024–26 | `co2-behaald` |
| sizes of the four blue bands in concrete's footprint | `co2-impact-van-beton` |
| NL + BE factory list; two factories with an anecdote | `de-fabrieken` (revision) |
| a project and photo per sector | `de-sectoren` (revision) |
| revenue (slide: 210 miljoen; meeting: € 1,4 miljard), "6 landen" (Luxemburg listed twice) | `de-cijfers`, `slabs-travelling` |
| ladder levels (meeting: 3 → 1, Figma: 5 → 3) and why the ladder changed | `co2-prestatieladder` (built; copy to confirm) |
| a real project photo under DUURZAAM, or none | `domino-effect` |
| the WN ring as svg | `the-circle-in-elementen`, `geen-compensatie` |
| The Circle's placements, or a chosen representative view | `gebouw-uit-de-webtool` |

## Order
By reuse — shared pieces before the slides that need them — and within a phase in running order.
Size: S an hour, M a session, L a long session, XL needs a decision or data first.

### Phase 1 — no new drawer
| slide | build | states | size |
|---|---|---|---|
| `we-gieten-kennis` | `QuoteSlide` with the quote | 1 | S |
| `we-bouwen-vandaag` | `QuoteSlide`, `lines ≈ 6` (two sentences) | 1 | S |
| `the-circle-en-esg` | `EsgFramework` gains labels per piece; the others dim while one is named; then the close. Chapter 2's colours so the callback reads | 5 | M |

### Phase 2 — a chart kit, then the chart slides
**Kit** (`ChartKit.kt`): bar growing from its baseline; stacked column of (label, value, colour) bands; a target line
drawing across; labels arriving with their band; axis ticks. Pure functions of values and one progress number.

| slide | states | reuse | size |
|---|---|---|---|
| `co2-impact-van-beton` | footprint column, cement ≈ 80% → world 7% → NL 1,9%: one column re-read, bands re-forming | kit | M |
| `co2-behaald` | left chart: bars fall, target draws → right chart: bars rise → bullets under both. Type as `CarbonLadder`, which it follows | kit | M, blocked on figures |
| `reductie-carbon-footprint` | linear column builds → circular column copies it → 80% turns red, disassembly slides in, brackets 80/20 | kit, `LifeCycle` columns | M |
| `verduurzamen-van-beton` | scatter separating out of one cloud → lime cycle, arrows travelling round → descending bars; one caption row | kit, new cycle | L |

### Phase 3 — the catalogue in the round
| slide | states | reuse | size |
|---|---|---|---|
| `verborgen-verhaal` | TANDBALK with three leaders → −30% share in red → WAND −15% → X-BALK −20%; pulls back between pieces | `IsoPieces`, register csv, `BlueprintLabel`, clip plane | L |
| `recyclage` | WAND_27 stands → floor slab lies → shatters → sieved to points → new white panel; stages travel left | `IsoPieces`, `ObjMesh`, Voronoi shatter | L |
| `the-circle-in-elementen` | pieces land along the WN ring in order, each turning slowly, the ring turning as one | `IsoPieces`, gallery turning, ring svg path | M |
| `100-elementen` | every piece packed at one height, red on black → reshuffle (→ the list of eight, optional) | `loadObjectSheet`, `packTrain` | M |

### Phase 4 — composites
| slide | states | reuse | size |
|---|---|---|---|
| `geen-compensatie` | certificate alone → measures either side → certificate leaves, measures close up | catalogue silhouettes for machines (or sourced svgs), ring svg | M |
| `domino-effect` | one tree → 4 → 16, type scaling with the cell → DUURZAAM | `TreeSlide` per cell, `setToFit` | M |
| `demontabel-in-de-stad` | pieces on the city's roofs lifted and set down elsewhere; two states or a loop | `BlockCity` camera and depth map, `IsoPieces` | L |
| `gebouw-uit-de-webtool` | The Circle in red lines on a dotted ground; label set swaps on the click | `ObjMesh`, `data/unique_objects`, line shader | XL |

### Phase 5 — the evening
| slide | build | size |
|---|---|---|
| `het-programma` | wall: moments and chapters in one line; a click a chapter with its key message | M |
| ending (new module) | call-to-action wall before the closing scene: one sentence, one action, positive | M, blocked on decision 4 |
| sound | a bed per moment; the opening louder; mysterious → uplifting across the evening | engine M, music external |

### Revisions from the meeting
| slide | change |
|---|---|
| `de-fabrieken` | NL + BE only; two visits with an anecdote line in the bar |
| `de-cijfers`, `slabs-travelling` | fact-checked figures |
| `de-sectoren` | real case studies and photos |

## Running it
- One slide per session with `prompt.md`, in this order, each reviewed in the organizer before the next.
- Phase 1 fits in one session. Phase 2 starts with the kit on its own, reviewed with `co2-impact-van-beton`.
- Drawers are separate files, so two sessions *can* run in parallel worktrees — but every slide edits `Slideshow.kt`,
  `show-modules.json` and `show-order.json`: merge one at a time.
- Slides blocked on WN get built as far as the data allows, with the missing figures as visible placeholders in the
  show's lists, not guessed.
