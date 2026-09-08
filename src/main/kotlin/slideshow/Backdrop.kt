package slideshow

/**
 * A slide that takes the whole wall.
 *
 * The presentation is one part of a longer programme — the draaiboek — and around it the
 * wall carries other pictures: a scene to arrive to, one to leave by, the courses in
 * between. Those are backdrops. A backdrop is clicked through exactly like a slide and sits
 * in the same deck, so `->` off the last one lands on the first slide and `->` off the last
 * slide lands on the next backdrop; what differs is the frame it composes for. A slide gets
 * the pane beside its chapter card, a backdrop gets the **whole canvas** — both projectors,
 * 3840x1080 on the committed show — and no card is drawn beside it.
 *
 * It is a class rather than a flag because the show reads differently with it: a running
 * order says `backdrop(OpeningScene())` outside the chapters, and the engine can ask
 * `slide is Backdrop` at the one place the two compose differently. Everything else a slide
 * has — [steps], [stepFrames], [loop], [transition], [load], `Stage` — a backdrop has too.
 *
 * Backdrops live in `backdrop-drawers/`, a file to a scene, the way slides live in
 * `slide-drawers/`.
 */
abstract class Backdrop : Slide()
