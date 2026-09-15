# Workflow — from pink module to finished slide

1. **Read** the module's entry in `completion-plan.md`, its brief in `show-modules.json`, its reference frames
   (`export/references/<frame>-pane.png`) and the CLAUDE.md section of each drawer it reuses.
2. **Write the states** before code: state → what is on screen → what moved. Check against `pacing.md` and `motion.md`.
   Missing figure or asset → stop and name it.
3. **Drawer** in `src/main/kotlin/slideshow/slide-drawers/<Name>.kt`, package `slideshow.drawers` (default package only
   when it stands on default-package code). Copy, figures and lists are constructor parameters.
4. **Declare** it in `Slideshow.kt` at its place in the chapter, with `title` and speaker `notes`.
5. **Retire the module**: delete it from `show-modules.json`, move its frames into `covered` under the new slide's id,
   and replace the module id in `show-order.json` with the slide's id (printed at startup as `#id`).
   Titles with `₂`, `%` or `&` slug differently from the module id — check the print.
6. **Stills**: `SLIDE=<Name> SLIDE_STILLS=true ./gradlew run -Popenrndr.application=SlideStudioKt` → one png per state
   in `screenshots/`. Look at every one.
7. **Motion**: `SLIDE=<Name> SLIDE_RECORD=true SLIDE_AUTOSTEP=2.5 SLIDE_DURATION=<s> SLIDE_FPS=30 ./gradlew run
   -Popenrndr.application=SlideStudioKt` → `video/`; pull frames from the middle of each click with ffmpeg and look.
   Stills cannot show a transition.
8. **Measure** what can be measured: text inside its box, overlaps, colour shares, loop seams byte-identical.
9. **Document**: a short CLAUDE.md section — the decisions and the traps, not a tour of the code.
10. **Review**: run the show with `SLIDES_ORGANIZER=true`, refresh previews, report with the stills. Don't commit.

## Done means
- Every state follows `visual.md`; no pink; the first state settled by 1.5 s.
- The filmed pass shows no blank title, no overlapping paragraphs, no jumps; clicking back plays it undone.
- The organizer shows the slide covering its frames; the startup print has no warning for it.

## Traps already paid for
- Don't run SlideStudio or CardStudio while the show runs under Gradle — the second run hangs.
- `setLine` for CO₂; `advanceOf`, never `characterWidth`; `FontImageMap.size` is not the point size.
- Inside `drawer.isolated {}` a bare `width`/`height` is the render target's — hoist to locals first.
- A slide reading another slide's result (`Mark`) is declared after it.
- `steps` must be known before `load`: the running order prints first.
