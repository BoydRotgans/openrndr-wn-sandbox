// ============================================================================ //
//  The slideshow. This is the one file to open: everything about the show is
//  here, and everything about how a slide draws is in its own file under
//  slide-drawers/.
//
//      ./gradlew run -Popenrndr.application=SlideshowKt
//
//  Add a slide by writing a class in slide-drawers/ and naming it below. There
//  is no registry and no manifest to keep in step — `slide(TitleSlide())` is the
//  constructor, so the compiler resolves it and a slide that does not exist will
//  not build.
//
//  No `package` declaration, deliberately: Env lives in the default package and
//  Kotlin cannot import from the default package, so the file that reads .env has
//  to sit in it too. Everything else in this folder is `package slideshow`.
// ============================================================================ //

import slideshow.Settings
import slideshow.Show
import slideshow.present
import slideshow.slideshow
import slideshow.drawers.BuildSlide
import slideshow.drawers.ChapterPanel
import slideshow.drawers.GlobeSlide
import slideshow.drawers.HardCutSlide
import slideshow.drawers.LoopSlide
import slideshow.drawers.QuoteSlide
import slideshow.drawers.RevealSlide
import slideshow.drawers.Swivel01Slide
import slideshow.drawers.Swivel02Slide
import slideshow.drawers.SwivelBlock
import slideshow.drawers.TitleSlide
import slideshow.drawers.TreeSlide
import slideshow.drawers.nodes

/**
 * The chapter card's face — see [usableFont] for what standing up a system font involves.
 *
 * Declared **above** `show` on purpose: top-level values initialise in the order they are
 * written, and the `panel { }` lambda below is called *while* `show` is being built, as
 * the slides go in. Written underneath, this would still be null at the moment the cards
 * are made.
 */
val panelFont: String = usableFont(
    Env["SLIDES_PANEL_FONT"] ?: "/System/Library/Fonts/Supplemental/Georgia Bold.ttf"
)

/** The face the swivel slabs carry their copy in — a bold sans, against the card's serif. */
val blockFont: String = usableFont(
    Env["SLIDES_BLOCK_FONT"] ?: "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
)

/**
 * Held in a value of its own because the slide after it opens on its last frame: the tree
 * asks the city for the element it closed on, so the cut between them is invisible. It has
 * to be declared before `show` for the same reason [panelFont] does, and it has to be
 * declared before the tree *in the running order* too — the handover is read in the tree's
 * `load`, and slides load in the order they are declared.
 */
val city = CityMapSlide(pace = 12.0)

/**
 * Everything a build answers to, declared once and used twice: the tree hangs them off the
 * element the city closes on, and the globe then turns them up one at a time. Reword a line
 * here and both slides follow — in each of them the layout is a function of the list rather
 * than a set of numbers that happen to suit its length.
 */
val leftFactors = listOf(
    "Locatie", "Bodemgesteldheid", "Topografie", "Bereikbaarheid",
    "Transport", "Logistiek", "Materiaalbeschikbaarheid", "Sourcing",
    "Levertermijnen", "Arbeidsmarkt", "Vakmanschap", "Onderaannemers",
    "Bouwkosten", "Inflatie", "Rentevoeten", "Financiering",
    "Vergunningen", "Bestemmingsplan", "Bouwvoorschriften",
    "Veiligheidsnormen", "Milieuregels", "Stikstofregels"
)

val rightFactors = listOf(
    "Waterbeheer", "Nutsvoorzieningen", "Netcongestie", "Riolering",
    "Fundering", "Constructiekeuzes", "Architectonisch ontwerp",
    "Technische installaties", "Digitalisering/BIM", "Planning",
    "Risicobeheer", "Kwaliteitscontrole", "Veiligheidsbeheer",
    "Omwonenden", "Participatie", "Politieke besluitvorming",
    "Juridische procedures", "Grondprijs", "Eigendomsrechten",
    "Archeologie", "Verontreinigde grond", "Duurzaamheidsdoelen"
)

val show = slideshow {

    // --- how it is shown --------------------------------------------------- //

    canvas(3840, 1080)      // the frame everything is composed at: two 1920x1080
                            // sides, flush against each other
    window(1.0)             // how much of the screen the window takes
    title("slideshow")

    // The left pane: the chapter, set as large as it will go, white on black. One
    // card per section, standing for as long as the show is in it — see
    // ChapterPanel in slide-drawers/ for the drawing itself. 1920 + 1920 is the
    // 3840 canvas above: two keynote-sized sides, meeting with no gutter, so the
    // two grounds are what divide the frame rather than a bar between them.
    //
    // A lambda rather than `::ChapterPanel` so the card can be handed the face:
    // `data/fonts` holds one weight of IBM Plex and no bold, so a bold serif has
    // to come from outside the project. SLIDES_PANEL_FONT names another, and an
    // unusable one falls back to the bundled face rather than stopping the show.
    panel({ ChapterPanel(it, panelFont) }, width = 1920, gap = 0)

    // --- the running order ------------------------------------------------- //
    //
    // Slides hang straight off their chapter here, with no subchapter over them.
    // A chapter can still hold `subchapter("...") { }` where a section needs
    // dividing — the card then carries its name and number at the foot.

    // Three lines rather than the two that would let the type be bigger: the break
    // is the design, so it is stated here. See ChapterPanel's `lines`.
    chapter("De wereld van bouwen", panel = { ChapterPanel(it, panelFont, lines = 3) }) {
        slide(
            QuoteSlide(
                "“Hoe bouw je een wereld die vandaag stevig overeind blijft, " +
                        "maar licht genoeg is om de toekomst niet te belasten?”",
                panelFont,
                lines = 4
            ),
            title = "Hoe bouw je een wereld",
            notes = "The opening question, set to a measure with air around it rather " +
                    "than filling the frame the way a chapter card does."
        )
        // Two clicks, both long: the first pushes the camera in and gathers the
        // elements it lands on into a grid, the second empties that grid down to
        // one component. `pace` is what makes it a backdrop you talk over rather
        // than a move — see CityMapSlide for what the sketch's clock-driven beats
        // became.
        slide(
            city,
            title = "The catalogue city",
            notes = "Click 1 pushes in from the whole town; click 2 closes the grid " +
                    "down onto a single element. Everything else about the drawing is " +
                    "still the CITY_* keys, so it looks like the film it came from."
        )

        // Opens on the city's own last frame — the same element, the same size, the
        // same place — so the cut into it cannot be seen. One click then fans the
        // tree out of it. The two lists are the whole of the content: add, remove or
        // reword a line and the type resizes and the fan re-spaces to suit.
        slide(
            TreeSlide(
                left = nodes(leftFactors),
                right = nodes(rightFactors),
                opening = { city.closingMark },
                fontPath = panelFont
            ),
            title = "Everything a build answers to",
            notes = "The element the city closed on becomes the root. Nesting a label " +
                    "with node(\"...\", node(\"...\")) adds a level and costs a click."
        )

        // No clicks: it arrives and fills itself while you talk over it. The globe
        // turns one label's worth of angle between one label and the next, so the
        // ring closes exactly as the list runs out — `pace` is the only dial, and it
        // sets the spin and the arrivals together because they are the same number.
        slide(
            GlobeSlide(
                labels = leftFactors + rightFactors,
                fontPath = panelFont
            ),
            title = "The globe",
            notes = "Builds off its own frame count rather than clicks, so it fills " +
                    "while you talk over it and keeps turning once it is full."
        )
        slide(
            TitleSlide(),
            notes = "Held on a cut. The rule draws itself in over 0.9s off the " +
                    "slide's own frame count, not off a click."
        )
    }

    // "verantwoor-delijkheid" is hyphenated so the card may break it there; written
    // whole it is one unbreakable word and the type shrinks to carry it on a line.
    chapter("Waardekader en verantwoor-delijkheid") {
        slide(
            RevealSlide(),
            title = "Circle, then square",
            notes = "Three clicks: circle, square, on. Both shapes are stage.on(n), " +
                    "so clicking back plays them out again."
        )
    }

    chapter("Beton: ruggengraat en transitie") {
        slide(
            Swivel01Slide(),
            title = "Panels turning",
            notes = "A wave crosses the row once on arrival and settles — 7.5s, off the " +
                    "slide's own frame count rather than a clock."
        )
        slide(
            LoopSlide(),
            notes = "Five seconds a turn, running whether or not anyone clicks. The " +
                    "second click adds a mark half a turn behind."
        )
    }

    chapter("The circle: Een nieuwe manier van denken") {
        slide(
            Swivel02Slide(
                blocks = listOf(
                    SwivelBlock("Actief in 6 landen"),
                    SwivelBlock(
                        items = listOf(
                            "LUXEMBURG", "NEDERLAND", "BELGIE",
                            "LUXEMBURG", "DENEMARKEN", "ZWEDEN"
                        )
                    ),
                    SwivelBlock("350 werven per jaar", note = "+ 250 zwembaden"),
                    SwivelBlock("950 medewerkers"),
                    SwivelBlock("19 bedrijven")
                ),
                fontPath = blockFont
            ),
            title = "Slabs travelling",
            notes = "One block to a slab, taken in turn and repeated. The turn is as long " +
                    "as it has to be for the copy, the swing and the four-slab pattern to " +
                    "close together — five blocks against four makes twenty slab-widths."
        )
        slide(
            BuildSlide(),
            notes = "Five clicks. The last is the build down and runs at 0.70s " +
                    "against the deck's 0.45s."
        )
        slide(HardCutSlide(), notes = "No handover at all — the slide is simply there.")
    }
}

fun main() = present(show.withEnv())

// ---------------------------------------------------------------------------- //

/**
 * `.env` overriding what is declared above, for the switches you flip for one run without
 * editing the show: `SLIDES_START` to open on a slide you are working on, `SLIDES_DEBUG`,
 * `SLIDES_RECORD` and `SLIDES_AUTOSTEP` to film it, `SLIDES_STILLS` to write a png per
 * click of every slide.
 *
 * So the show is declared in one place and the run is steered from another, which is the
 * split that matters: nothing you set for one afternoon's filming ends up committed as
 * part of the talk.
 *
 * This has to be `settings.copy(...)`, not a fresh `Settings(...)`: the panel geometry
 * (`panelWidth`, `panelGap`, `gutter`) is set in the show above and has no `.env` key of
 * its own, so a constructor call naming every *other* field would silently reset those
 * three to the class defaults — the panel working in the running program and vanishing
 * the moment this ran, which is exactly what happened here before `.copy()` replaced it.
 */
fun Show.withEnv(prefix: String = "SLIDES"): Show = copy(
    settings = settings.copy(
        width = Env["${prefix}_WIDTH"]?.toIntOrNull() ?: settings.width,
        height = Env["${prefix}_HEIGHT"]?.toIntOrNull() ?: settings.height,
        windowScale = Env["${prefix}_WINDOW_SCALE"]?.toDoubleOrNull() ?: settings.windowScale,
        fullscreen = Env["${prefix}_FULLSCREEN"]?.let { Env.boolean("${prefix}_FULLSCREEN") } ?: settings.fullscreen,
        title = Env["${prefix}_TITLE"] ?: settings.title,
        start = Env["${prefix}_START"] ?: settings.start,
        debug = Env["${prefix}_DEBUG"]?.let { Env.boolean("${prefix}_DEBUG") } ?: settings.debug,
        autoStep = Env["${prefix}_AUTOSTEP"]?.toDoubleOrNull() ?: settings.autoStep,
        record = Env.boolean("${prefix}_RECORD"),
        fps = Env["${prefix}_FPS"]?.toIntOrNull() ?: settings.fps,
        duration = Env["${prefix}_DURATION"]?.toDoubleOrNull() ?: settings.duration,
        stills = Env.boolean("${prefix}_STILLS")
    )
)
