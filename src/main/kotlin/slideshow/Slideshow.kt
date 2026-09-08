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

import org.openrndr.color.ColorRGBa
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
import slideshow.drawers.StackUp
import slideshow.drawers.row
import slideshow.drawers.Swivel01Slide
import slideshow.drawers.Swivel02Slide
import slideshow.drawers.SwivelBlock
import slideshow.drawers.Type
import slideshow.drawers.TreeSlide
import slideshow.drawers.nodes

/**
 * The talk is set in one family, in two weights.
 *
 * `data/fonts` holds one weight of IBM Plex and no bold, so the family comes from the
 * machine — and Rockwell lives in a `.ttc`, which `loadFont` will not open at all. See
 * [usableFont]: it lifts the named face out of the collection into a standalone font under
 * `build/`, and falls back to the bundled face rather than stopping the show when the file is
 * not there. The face is named exactly, because the collection holds *Rockwell-Bold* and
 * *Rockwell Bold Italic* and a loose match takes the italic.
 *
 * Declared **above** `show` on purpose: top-level values initialise in the order they are
 * written, and the `panel { }` lambda below is called *while* `show` is being built, as
 * the slides go in. Written underneath, this would still be null at the moment the cards
 * are made.
 */
private val family: String = Env["SLIDES_FONT"] ?: "/System/Library/Fonts/Supplemental/Rockwell.ttc"

/** Headings, chapter cards, quotes, the copy on the slabs — everything set large. */
val boldFont: String = usableFont(family, Env["SLIDES_FONT_BOLD"] ?: "Rockwell-Bold")

/** The small furniture: captions, slide numbers, the subchapter line. */
val textFont: String = usableFont(family, Env["SLIDES_FONT_TEXT"] ?: "Rockwell")

/**
 * The chapter cards' own face, where the talk's family is not what the card wants.
 *
 * A card's title is never drawn as type — it is drawn into a plate and rebuilt out of
 * catalogue elements — so what the face decides is the *shape of the strokes* the mosaic has
 * to fill. A grotesque comes apart into parts better than a slab does: even weights, flat
 * terminals, no serifs to lose to a cell.
 *
 * Empty falls back to [boldFont], so a machine without the face set still runs the show.
 */
val cardFont: String = Env["SLIDES_CARD_FONT"]?.takeIf { it.isNotBlank() }
    ?.let { usableFont(it, Env["SLIDES_CARD_FACE"], fallback = boldFont) } ?: boldFont

/**
 * Held in a value of its own because the slide after it opens on its last frame: the tree
 * asks the city for the element it closed on, so the cut between them is invisible. It has
 * to be declared before `show` for the same reason [boldFont] does, and it has to be
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
    title("openrndr")

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
    panel({ ObjectChapterPanel(it, cardFont) }, width = 1920, gap = 0)

    // --- the running order ------------------------------------------------- //
    //
    // Slides hang straight off their chapter here, with no subchapter over them.
    // A chapter can still hold `subchapter("...") { }` where a section needs
    // dividing — the card then carries its name and number at the foot.

    // The first chapter's card is the *drawn* title rather than a set one:
    // ObjectImageChapterPanel reads data/slides/chapter0.png and packs the same field of
    // components into it. Everything it is made of is stated here rather than left to
    // `.env`, and has to be — the SLIDES_CARD_* keys are the typeset card's and are tuned
    // for it, so a picture read through them comes up wrong in every particular: square-ish
    // marks shrunk away from a 1.85 cell, no levels on a picture that needs them, and a
    // strict `solid` that draws the thin lettering in the smallest marks the field has.
    //
    // The numbers are ImageCardStudio's, which is where they were arrived at. See the
    // chapter card notes in CLAUDE.md for what each of them is doing.
    chapter("De wereld van bouwen", panel = {
        ObjectImageChapterPanel(
            it,
            image = "data/slides/chapter0.png",
            coarse = 32.0, finest = 8.0,        // several sizes: big through a stroke, small on its edge
            fill = 1.0, gap = 2.0,              // pieces meet, held 2px apart at every level
            uniform = false,                    // each piece fitted to its own cell, so none overflows
            shrink = 1.0,                       // nothing shrunk: the words are carried by colour alone
            solid = 0.62,                       // let a mostly-covered cell stand, so thin strokes get big marks
            levels = 0.06 to 0.38,              // the png sets DE/VAN in grey; this brings them to full ink
            ground = ColorRGBa.fromHex("#2E2E2E"),
            reveal = slideshow.frames(2.8),   // qualified: bare `frames` is figma-rest's Node.Canvas.frames()               // two passes and a pause need longer than the typeset 1.2s
            sweep = 1.0, stage = 0.45, delay = 0.12   // ground up from the foot, then ink down from the head
        )
    }) {
        slide(
            QuoteSlide(
                "“Hoe bouw je een wereld die vandaag stevig overeind blijft, " +
                        "maar licht genoeg is om de toekomst niet te belasten?”",
                boldFont,
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
                fontPath = boldFont
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
                fontPath = boldFont
            ),
            title = "The globe",
            notes = "Builds off its own frame count rather than clicks, so it fills " +
                    "while you talk over it and keeps turning once it is full."
        )

        // A band a click, the newest arriving underneath and the ones already up
        // closing ranks above it — demo01's packBoxes as a slide. The rows are the
        // whole of the content: reword one, add one, split one in two, and the
        // stack re-proportions itself. A row given one label runs the full width
        // and stands taller than a row split in two, which is where the shape of
        // the drawing comes from without any of it being stated in pixels.
        slide(
            StackUp(
                title = "Willy Naessens Build",
                subtitle = "Verticale integratie",
                rows = listOf(
                    row("Eigen ontwerp- en engineeringafdeling", "Eigen prefabricatie"),
                    row("Eigen grond en- omgevingswerken", "Eigen funderingsploegen en paalmachines"),
                    row("Eigen transport", "Eigen Montageploegen"),
                    row("Eigen Dakdichtingsbedrijf", "Eigen AluSchrijn- en Glasbedrijf"),
                    row("Eigen technische afdeling"),
                    row("Eigen after sales", accent = true)
                ),
                fontPath = boldFont
            ),
            title = "Verticale integratie",
            notes = "Six clicks, a band each. The last is picked out in red."
        )
        // The same drawer as the slabs in chapter 4, carrying different figures in
        // a different palette — which is the whole point of the copy being a list
        // the show hands in rather than anything the drawer knows.
        slide(
            Swivel02Slide(
                blocks = listOf(
                    SwivelBlock("350.000 M\u00B3 Betonproductie"),
                    SwivelBlock("950.000 M\u00B2 Gewelven"),
                    SwivelBlock("210 miljoen omzet")
                ),
                front = ColorRGBa.fromHex("3D5AE0"),
                side = ColorRGBa.fromHex("3550C4"),
                fontPath = boldFont
            ),
            title = "De cijfers",
            notes = "A slab a figure, blue on black. Three blocks against the " +
                    "four-slab swing makes a twelve slab-width turn."
        )
    }

    // "verantwoor-delijkheid" is hyphenated so the card may break it there; written
    // whole it is one unbreakable word and the type shrinks to carry it on a line.
    chapter("Waardekader en verantwoor-delijkheid") {
        slide(
            QuoteSlide(
                "\u201CESG is geen checklist, maar de systemische manier waarop we " +
                        "elke dag opnieuw beslissen hoe we bouwen, leveren, investeren " +
                        "en verbeteren\u201D",
                boldFont,
                lines = 4
            ),
            title = "ESG is geen checklist",
            notes = "The chapter's own opening question, set the same way as the one " +
                    "that opens the talk."
        )
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
                fontPath = boldFont
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

fun main() {
    // The furniture picks up the family here, before any slide loads — see [Type.file].
    Type.file = textFont
    present(show.withEnv())
}

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
        cues = Env["${prefix}_CUES"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: settings.cues,
        record = Env.boolean("${prefix}_RECORD"),
        fps = Env["${prefix}_FPS"]?.toIntOrNull() ?: settings.fps,
        duration = Env["${prefix}_DURATION"]?.toDoubleOrNull() ?: settings.duration,
        stills = Env.boolean("${prefix}_STILLS")
    )
)
