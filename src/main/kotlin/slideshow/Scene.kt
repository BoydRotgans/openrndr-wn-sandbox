package slideshow

/**
 * A slide that takes the whole wall from inside a chapter.
 *
 * The third kind. A [Slide] composes for the pane beside its chapter card; a [Backdrop] takes
 * the whole wall and is a picture *around* the talk; a scene takes the whole wall and is *part
 * of* the talk. It is what a full-bleed moment is: the subject fills the room and the furniture
 * gets out of the way.
 *
 * Declared with `scene(...)` **in a chapter**, it appears under that chapter in the running
 * order and the chapter's card is simply not drawn while it is up, arriving again with the next
 * slide. Declared at the top level it stands beside the backdrops, where it composes exactly as
 * one does — whole wall, no card — and what the two still say differently is what the thing is.
 * The running order prints which, so the distinction stays visible where the behaviour matches.
 *
 * **Adding a kind costs one property now**, which it did not before. The driver used to ask
 * `slide is Backdrop` in fourteen places — the buffers to allocate, the bounds to draw into,
 * whether a card can be seen, whether one must replay, how a handover with something narrower
 * is composed — and every one of them was really asking *does this take the whole wall*. That
 * is [Slide.wide], and both wide kinds set it; a fourth would set it too and edit nothing.
 *
 * Scenes live in `scene-drawers/`, a file to a scene, the way slides live in `slide-drawers/`
 * and backdrops in `backdrop-drawers/`.
 */
abstract class Scene : Slide() {
    override val wide get() = true
    override val kind get() = "scene"
}
