import org.openrndr.Program
import org.openrndr.draw.BufferMultisample
import org.openrndr.extensions.Screenshots
import java.io.File

/**
 * A sketch's thumbnail for the organizer's Sketches tab, on request.
 *
 * Every sketch calls this first thing in `program { }`. Normally it does nothing; run with
 * `SKETCH_PREVIEW=true` it saves the window at [at] seconds — or `SKETCH_PREVIEW_AT` — to
 * `sketch-previews/<name>.png` and quits, so `tools/sketch_previews.sh` can walk every sketch and
 * leave a picture of each without anyone clicking through them.
 *
 * It has to be installed before the sketch's own drawing: OPENRNDR's `Screenshots` binds its target
 * in `beforeDraw`, and extensions run in the order they were added.
 */
fun Program.sketchPreview(name: String, at: Double = 3.0) {
    if (!Env.boolean("SKETCH_PREVIEW")) return
    val seconds = Env["SKETCH_PREVIEW_AT"]?.toDoubleOrNull() ?: at
    File(PREVIEW_DIR).mkdirs()
    val shot = Screenshots().apply {
        this.name = "$PREVIEW_DIR/$name.png"
        async = false
        quitAfterScreenshot = true
        listenToKeyDownEvent = false
        contentScale = 1.0
        multisample = BufferMultisample.Disabled
    }
    var fired = false
    // Timed off the program's own clock: a preview is not a recording, so there is no video time to
    // keep in step with.
    extend {
        if (!fired && this@sketchPreview.seconds >= seconds) {
            fired = true
            shot.trigger()
        }
    }
    extend(shot)
}

/** Where the thumbnails go; the organizer serves them from here. */
const val PREVIEW_DIR = "sketch-previews"
