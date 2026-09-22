//  No `package` declaration: it reads the show out of Slideshow.kt, which is in the default
//  package, and Kotlin cannot import from the default package.

import slideshow.arrangedFromFile
import slideshow.runningOrder
import slideshow.withModules

/**
 * Prints the running order — the tree `present` prints at startup, with every slide's id and
 * state count — without opening a window or loading a slide:
 *
 *     ./gradlew run -Popenrndr.application=RunningOrderKt
 *
 * It is what a script written against the states needs — the subtitles, the sound design, the
 * cue list — since the state counts are the slides' own and change as the drawers do.
 */
fun main() {
    System.setProperty("java.awt.headless", "true")
    println(show.withEnv().withModules().arrangedFromFile().runningOrder())
}
