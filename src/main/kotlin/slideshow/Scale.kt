package slideshow

import kotlin.math.atan

/**
 * Type as a role, with the room in the arithmetic — principle 2 of `style-guide/principles.md`.
 *
 * Five sizes for the whole evening, each a share of the pane's height as the guide gives them:
 * [DISPLAY] for a figure or a name that is the subject, [TITLE] for a slide's title, [LABEL] for
 * a label on a shape, [BODY] for a paragraph, [CAPTION] for a caption. A drawer asks
 * `Scale.title(stage.height)` and states no number.
 *
 * **The projection is in here too**, so a size can be asked what it becomes in the room: each
 * wall is [WALL_METRES] wide and [PIXELS_ACROSS] pixels across, so a pixel is 6.25 mm, and the
 * room sits about [DISTANCE_METRES] away. That settled an argument the review of 15 September
 * got wrong: the smallest type in the show subtends about three times the comfortable reading
 * threshold of 15 to 20 minutes of arc, so nothing here is too small to read, and a size is
 * always a question of hierarchy. [arcminutes] is the check.
 */
object Scale {
    const val DISPLAY = 0.095
    const val TITLE = 0.038
    const val LABEL = 0.027
    const val BODY = 0.0287
    const val BODY_LEADING = 1.22
    const val CAPTION = 0.024

    fun display(paneHeight: Double) = paneHeight * DISPLAY
    fun title(paneHeight: Double) = paneHeight * TITLE
    fun label(paneHeight: Double) = paneHeight * LABEL
    fun body(paneHeight: Double) = paneHeight * BODY
    fun caption(paneHeight: Double) = paneHeight * CAPTION

    /** The projection: one wall, and where the room sits. */
    const val WALL_METRES = 12.0
    const val PIXELS_ACROSS = 1920.0
    const val DISTANCE_METRES = 9.0
    const val METRES_PER_PIXEL = WALL_METRES / PIXELS_ACROSS

    /** Rockwell's cap height against its em, near enough. */
    private const val CAP = 0.7

    /** How tall a cap of type set at [share] of a [paneHeight]-pixel pane stands on the wall, in centimetres. */
    fun centimetres(share: Double, paneHeight: Double = 1080.0): Double =
        share * paneHeight * CAP * METRES_PER_PIXEL * 100.0

    /** The same cap as seen from the room, in minutes of arc. Comfortable reading starts around 15 to 20. */
    fun arcminutes(share: Double, paneHeight: Double = 1080.0): Double =
        Math.toDegrees(atan(centimetres(share, paneHeight) / 100.0 / DISTANCE_METRES)) * 60.0
}
