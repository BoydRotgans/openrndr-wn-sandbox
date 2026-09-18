# Style guide — the WN talk

What the finished slides have in common, written down so the unfinished ones come out the same.

| file | settles |
|---|---|
| [visual.md](visual.md) | frame, ground, colour, type, imagery, layout |
| [motion.md](motion.md) | how things arrive, change and leave |
| [pacing.md](pacing.md) | clicks, durations, holds, text load, sound |
| [workflow.md](workflow.md) | one slide from pink module to finished, and how it is checked |
| [completion-plan.md](completion-plan.md) | every unfinished slide: states, reuse, blockers, order |
| [prompt.md](prompt.md) | paste-ready prompts for one slide or a whole phase |
| [review-2026-09-15.md](review-2026-09-15.md) | the filmed export read against this guide: findings, local and global improvements, to do |
| [improvements.md](improvements.md) | the full list of improvements to make, as intent — walls, slides, content, sound, type |
| [principles.md](principles.md) | the twelve reusable principles the refactor works to, each meant to become one class |
| [refactor-plan.md](refactor-plan.md) | the phases of the refactor, with the briefs, the review and the general feedback folded in |

## Reference slides — when in doubt, match these

| slide id | drawer | shows |
|---|---|---|
| `co2-prestatieladder` | `CarbonLadder` | states as slots, diagram + explanatory paragraph, text crossfades |
| `esg-social-governance` | `Crowd` | formations that re-form, travel before arrival |
| `levenscyclus-van-betonproducten` | `LifeCycle` | blocks and labels in the house pair |
| `verticale-integratie` | `StackUp` | layout as a pure function of a count |
| `hoe-bouw-je-een-wereld` | `QuoteSlide` | the quote |

## The rules on one screen

1. Two walls of 12 m, 1920×1080 each, the room 8 to 10 m away: anything that has to be read stays in one pane;
   only a picture backdrop runs across both. Size is hierarchy, never legibility (`Scale.arcminutes`).
2. Black pane, white type, red and blue as flat fills — nothing else unless the content brings it.
3. Rockwell: bold for titles, labels and figures, regular for text; sizes are shares of the pane height.
4. One legible change per click; a slide opens already building and is settled by 1.5 s.
5. Arrivals take their step, bars grow from their baseline, changing text fades out before new text fades in.
6. A drawer is a pure function of `stage.position` and `stage.frame` — no clock, no state.
7. Nothing is done until it has been looked at: stills of every state and a filmed pass read frame by frame.
