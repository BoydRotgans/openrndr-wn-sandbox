# Prompts

## One slide
```
Finish the slide `<module-id>` in the WN presentation.

Read first: style-guide/README.md, visual.md, motion.md, pacing.md, workflow.md, and the entry for
`<module-id>` in style-guide/completion-plan.md. Then its brief in show-modules.json, its reference frames
(export/references/<frame>-pane.png) and the CLAUDE.md sections of the drawers the plan reuses.

1. Write the states as a short list (state → on screen → what moved) and check them against pacing.md and
   motion.md. If a figure or asset is missing, stop and name it.
2. Build it following workflow.md: drawer in slide-drawers/, content as constructor lists, declared in
   Slideshow.kt with title and speaker notes, the module retired (show-modules.json → covered, the id
   replaced in show-order.json).
3. Verify: stills of every state, and a filmed pass with frames pulled from the middle of each click. Look
   at them and fix whatever breaks the style guide.
4. Add a short CLAUDE.md section with the decisions and traps.
5. Run the show with the organizer, refresh previews, and report with the stills. Don't commit.
```

## A phase
```
Finish phase <n> of style-guide/completion-plan.md: build any shared piece the phase names first, then its
slides one at a time in the listed order, each with the "One slide" prompt in style-guide/prompt.md. After
each slide show me the stills and wait for my go before starting the next.
```
