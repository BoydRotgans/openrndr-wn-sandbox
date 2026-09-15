# Pacing

## A click
- One click is one legible change the speaker can talk over. If what just moved needs explaining, it is two states.
- A slide is 1 state (quote, loop) or 3–6 states. More than 6: split the slide or merge states.

| click kind | `stepFrames` | in the deck |
|---|---|---|
| reveal, small change | 0.45–0.55 s | default, stack |
| layout change, bars, crossfading text | 0.7–0.9 s | ESG framework 0.7, life cycle 0.8, ladder 0.9 |
| several things travelling, re-forming | 1.2–1.5 s | tree 1.2, crowd 1.5 |
| camera move | 2 s | map |
| a long push to talk over | up to 12 s | catalogue city |

- A build-down is slower than the reveal it undoes (0.7 against 0.45) — things leaving fast read as missing.
- A long click may finish its move in a share of the click and hold for the rest.

## Holds
- Reading time after a state settles: `SLIDES_HOLD` 3.5 s for a slide, `SLIDES_HOLD_WIDE` 12 s for a wall.
  A state with a paragraph wants ≥ 6 s; set it per state in `SLIDES_CUES`.
- Walls around the talk loop slowly (12 s to 4 min periods) and never start or stop.

## Length
- The draaiboek first gave the talk 30 minutes. Split over four chapters between courses that is ≈ 7 minutes a
  chapter — about 8–10 slides at 40–60 s each. Count a chapter's states when a slide is added.

## Text load
- On screen: title, labels, figures, at most one short paragraph. The argument lives in the speaker notes (`notes`).
- Figures and copy go into the show as lists, never into a drawer's layout.

## Sound
- The chapter sting lives on the card; a slide's own cue marks its arrival (`sound`); click marks are
  `stepCues`, forward only. Silence is the default for a new slide.
- A cue that belongs to its slide declares a `fadeOut`; stings don't fade.
- Beds on walls loop, fade in ≈ 6 s and out ≈ 2.5 s.
- Meeting 9/9: the opening loud and present; the evening moving from mysterious to uplifting; dinner music under
  the course walls (Valentina, Jurre).
