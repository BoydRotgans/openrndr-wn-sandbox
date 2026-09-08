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
import slideshow.backdrops.NameTag
import slideshow.present
import slideshow.slideshow
import java.io.File
import slideshow.drawers.ChapterPanel
import slideshow.drawers.EsgFramework
import slideshow.drawers.GlobeSlide
import slideshow.drawers.LcaMark
import slideshow.drawers.LcaSource
import slideshow.drawers.LifeCycle
import slideshow.drawers.LifeCycleAnalysis
import slideshow.drawers.LifePhase
import slideshow.drawers.LifeStep
import slideshow.drawers.QuoteSlide
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
 * Where the evening's cues live. `WN-0909` is the sound design as delivered: numbered
 * one-shots, a `base-loop` bed and a `clic`.
 *
 * A path, so it is in `.env`; *which* cue is played where is content and is stated below —
 * see [chapterCue], [ambience] and [mapCue].
 *
 * The cues sit **above** [city] on purpose, and it is the same trap [boldFont] carries: top
 * level values initialise in the order they are written, and `city` takes [mapCue] as a
 * constructor argument. Declared underneath, it would be handed null and the map would run
 * silent — with nothing to say so.
 */
val soundFolder = File(Env["SLIDES_SOUNDS"] ?: "data/sounds/WN-0909")

/**
 * How loud the numbered cues are set. One value rather than seven, so the sheet is levelled
 * in one place — [ambience] is separate because a bed under people talking is a different
 * job from a mark on a click.
 */
val cueGain = Env["SLIDES_CUE_GAIN"]?.toDoubleOrNull() ?: 0.8

/**
 * A cue off the sheet, at the level they are all set to. Fades in seconds, for reading — the
 * value keeps them in frames, like everything the deck is timed in.
 *
 * **A fade out is what says a cue belongs to its slide** rather than ringing on into the next
 * one; see [slideshow.Sound.sustained]. The long ones need it — `1-07` runs 13s and `1-10`
 * nearly 12 — and the short marks do not, because cutting a two-second sting off at the click
 * would be more noticeable than letting it finish.
 */
fun cue(file: String, fadeIn: Double = 0.0, fadeOut: Double = 0.0) = slideshow.Sound(
    File(soundFolder, file), gain = cueGain,
    fadeIn = slideshow.frames(fadeIn), fadeOut = slideshow.frames(fadeOut)
)

/**
 * The cue a chapter opens on — the sting under its card, fired as the section arrives.
 *
 * **One cue, under every chapter.** A card is the same event each time it comes up: the
 * talk has changed subject and the wall says so. So the sound that marks it is the mark
 * itself rather than a label on which chapter this is — the same argument as the cards
 * cutting rather than crossfading, and as `Cut` being the deck's default transition.
 *
 * Naming it once here is also what keeps the four in step: a cue given out card by card
 * drifts the moment one of them is retuned, and this way there is one value to change.
 * A chapter that eventually wants its own is [chapterCard] taking a different `sound`,
 * keyed on `section.number` the way it already keys its picture.
 */
val chapterCue = cue("1-01.wav")

/**
 * What both life-cycle databases know about what they hold — the same five either way, which
 * is the point: the analysis can only add them up because they are asked the same questions.
 * One list handed in twice, so rewording it moves both fans.
 */
val lcaFields = listOf("Materiaal", "Leverancier", "Transportwijze", "Herkomst", "Co2 impact")

/**
 * The bed under the opening wall: the one cue in the show that **loops**.
 *
 * Everything else here is a transition — a sting, over when it is over — and looping is off by
 * default for exactly that reason. This is the exception, and it has to be: the opening scene
 * is up for the better part of an hour while the room comes in, so its sound is a room rather
 * than an event.
 *
 * **It fades, and the fade is the sound's own rather than the picture's.** The scene arrives on
 * a `Cut`, so there is no handover to hang anything on: the wall is simply there, and the
 * ambience comes up under it over [slideshow.Sound.fadeIn] and goes out over
 * [slideshow.Sound.fadeOut] as the talk steps off into the first chapter. Longer in than out —
 * a room fills slowly and is left briskly.
 *
 * At 0.6 rather than [cueGain]: it is a bed under a wall people are talking over, so it wants
 * to sit where you notice it having stopped rather than where you notice it playing.
 */
val ambience = slideshow.Sound(
    File(Env["SLIDES_AMBIENCE"] ?: "data/sounds/ambiencebackingtrackV2.wav"),
    gain = 0.6,
    loop = true,
    fadeIn = slideshow.frames(6.0),
    fadeOut = slideshow.frames(2.5)
)

/**
 * The cues the talk itself is marked with, and **the sheet's numbering is the running order**:
 * 1-02 is the map, 1-05 the tree, 1-07 the globe, 1-09 the stack and 1-10 the figures — slides
 * four to eight of the first chapter, in order. That is what settles which `Swivel02Slide` gets
 * 1-10, there being two of them in the show: the one in this chapter, not the one in the fourth.
 *
 * The long ones fade out so they do not play on under the slide after them. `1-02` runs 25s
 * against a 12-second click, which is the case that made [slideshow.Sound.sustained] necessary.
 */
val mapCue = cue("1-02.wav", fadeIn = 1.5, fadeOut = 2.0)

/**
 * The tree's, and it hangs off the **click** rather than the arrival — see the slide below.
 * It opens on the city's own last frame and holds there, so a cue on the arrival would mark a
 * cut that was built to be invisible; what it marks instead is the fan coming apart.
 */
val treeCue = cue("1-05.wav", fadeOut = 1.0)
val globeCue = cue("1-07.wav", fadeOut = 2.0)
val swivelCue = cue("1-10.wav", fadeOut = 2.0)

/** The stack's own arrival, over the opening band. */
val stackCue = cue("1-09.wav", fadeOut = 1.5)

/**
 * A mark a band, from the second on — the first arrives with the slide and is [stackCue].
 *
 * Five of them for six rows, which is exactly right rather than one short: the opening band is
 * the slide coming up, not a click. They are stings and take no fade — the stack's clicks are
 * 0.55s apart and these run about two seconds, so they overlap by design, which is what the
 * eight-voice pool is for.
 */
val stackSteps = listOf("A", "B", "C", "D", "E").map { cue("1-09$it.wav") }

/**
 * Held in a value of its own because the slide after it opens on its last frame: the tree
 * asks the city for the element it closed on, so the cut between them is invisible. It has
 * to be declared before `show` for the same reason [boldFont] does, and it has to be
 * declared before the tree *in the running order* too — the handover is read in the tree's
 * `load`, and slides load in the order they are declared.
 */
val city = CityMapSlide(pace = 12.0, sound = mapCue)

/**
 * The house colours, as the draaiboek draws them: navy and red on a light ground. Named
 * here so a backdrop says `wnBlue` and the value lives in one place. The Figma export
 * carries `#FF0000` and a lighter `#4674D6` for the same pair; the navy is the draaiboek's.
 */
val wnBlue = ColorRGBa.fromHex("1E3A72")
val wnRed = ColorRGBa.fromHex("FF0000")
val wnPaper = ColorRGBa.fromHex("E8E8E8")

/**
 * The sheet the backdrops stand their elements off. A path, so it is in `.env`; which
 * elements stand on which wall is content and is stated in the show below.
 */
val backdropSheet = File(Env["SLIDES_BACKDROP_SHEET"] ?: "data/svg/subset.svg")

/**
 * The opening wall reads a sheet of its own, and needs to: it draws the catalogue *entire*,
 * one piece after another, so it wants the full front sheet rather than the picked subset
 * the rest of the deck stands on.
 */
val openingSheet = File(Env["SLIDES_OPENING_SHEET"] ?: "data/svg/objects-front.svg")

/**
 * The sheet the conveyor walls run: the **elevations**, because a piece on a belt is seen
 * square on. The iso sheet is drawn at an angle and would not sit on one.
 */
val beltSheet = File(Env["SLIDES_BELT_SHEET"] ?: "data/svg/objects-front.svg")

/**
 * The catalogue as meshes — the same 115 pieces the sheets hold flat, as real geometry — which
 * is what the yard wall stacks. A path, so it is in `.env`.
 */
val yardObjects = File(Env["SLIDES_YARD_OBJECTS"] ?: "data/objects")

/** The concrete the opening wall's pieces are cut out of. Empty fills them flat. */
val openingTexture = Env["SLIDES_OPENING_TEXTURE"]?.let { File(it) }

/**
 * The catalogue's register, which is what lets the wall name a piece and quote its box.
 * Paired with the sheet by index, and only when the two are the same length — see
 * [OpeningScene], which drops it rather than shifting every name along by one.
 */
val openingDetails = Env["SLIDES_OPENING_DETAILS"]?.let { File(it) }

/**
 * The card for one section: the chapter's own drawn title, packed into components.
 *
 * **Every chapter is a picture rather than type the card sets itself.** `data/slides` holds
 * one png a chapter — `chapter-1-title.png` and so on, white on black, at the pane's own
 * 1920x1080 — and [ObjectImageChapterPanel] reads it as the mask a field of catalogue
 * elements stands in. So the titles are *drawn*: set full-bleed in a condensed grotesque,
 * broken and packed the way the design asks rather than the way [setToFit] would, with
 * "DE" and "VAN" outlined and the rest solid. Nothing downstream knows the difference —
 * the field never knew it was reading letters — so all of that survives being made of
 * components.
 *
 * The number comes off the running order (`chapter { }` numbers itself), so adding a
 * chapter needs nothing here: drop `chapter-5-title.png` in and it is read. A section with
 * subchapters carries "1.2", and the chapter's own picture is what stands behind all of
 * them, so only the part before the dot is used.
 *
 * A chapter with no picture falls back to setting its title as type, which is the honest
 * equivalent of the picture card's own fallback — `data/` is not committed, so a checkout
 * without it still runs the show rather than showing four blank cards.
 *
 * The recipe is [ImageCardStudio]'s, with one difference from the version tuned for
 * `chapter0.png`: **no `levels`.** That picture set "DE" and "VAN" in grey — its lit pixels
 * averaged 106 — so a black and white point had to be pulled in before anything read it.
 * These are drawn at one weight: measured, 30–41% of each is pure 251–255 white and under
 * 1% falls in any middle band, outlined words included. There is nothing to correct, and
 * correcting it anyway would only pull the antialiasing up into the ink.
 */
fun chapterCard(section: slideshow.Section): slideshow.Slide {
    val image = File("data/slides/chapter-${section.number.substringBefore('.')}-title.png")
    if (!image.isFile) return ObjectChapterPanel(section, cardFont, sound = chapterCue)

    return ObjectImageChapterPanel(
        section,
        image = image.path,
        coarse = 32.0, finest = 8.0,        // several sizes: big through a stroke, small on its edge
        fill = 1.0, gap = 2.0,              // pieces meet, held 2px apart at every level
        uniform = false,                    // each piece fitted to its own cell, so none overflows
        shrink = 1.0,                       // nothing shrunk: the words are carried by colour alone
        solid = 0.62,                       // let a mostly-covered cell stand, so thin strokes get big marks
        ground = ColorRGBa.fromHex("#2E2E2E"),
        reveal = slideshow.frames(2.8),     // two passes and a pause need longer than the typeset 1.2s
        sweep = 1.0, stage = 0.45, delay = 0.12,  // ground up from the foot, then ink down from the head
        sound = chapterCue                        // the sting every chapter opens on
    )
}

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
    // Every chapter's card is its own drawn title off data/slides, packed into
    // catalogue elements — see chapterCard above, which picks the picture off the
    // section number and falls back to setting the title as type where there is
    // none. So adding a chapter needs nothing here but the png.
    panel(::chapterCard, width = 1920, gap = 0)

    // --- the running order ------------------------------------------------- //
    //
    // The talk is one part of the evening's draaiboek, and the wall carries
    // pictures around it: backdrops, taking both projectors with no chapter card
    // beside them. They are in the same deck as the slides, so `->` off the
    // opening scene is the first slide and `->` off the last slide is the closing
    // scene. See ObjectScene in backdrop-drawers/ for the drawing.
    //
    // 18:30, Aanvang: a black wall on which the catalogue draws itself. A piece to
    // the left half and one to the right, each drawn as a single travelling white
    // line and flooded solid the moment its outline closes; the pair holds, slides
    // up, and the next two are drawn — right through objects-front.svg, so every
    // variant has its turn while the room comes in.
    //
    // Its own drawer rather than the plain ObjectScene below: this wall is up for
    // the better part of an hour and is the first thing anyone sees, so it is the
    // one that grew an arrangement of its own. The two share no code but the sheet
    // loader, so work here cannot land on the closing wall.
    backdrop(
        // The annotation is white — one ink on the wall. `label = wnRed` marks it up in
        // the house red instead, and `label = null` drops it entirely.
        OpeningScene(
            sheet = openingSheet,
            // The regular weight, not the deck's bold: the annotation is a caption on a
            // drawing, and the bold read as a heading beside a piece rather than under it.
            fontPath = textFont,
            concrete = openingTexture,
            details = openingDetails,
            // the room the wall stands in, fading up under it and out as the talk starts
            sound = ambience
        ),
        title = "Opening scene",
        notes = "Aanvang. Draws the whole catalogue, two pieces a turn, and repeats; " +
                "-> goes to the first chapter whenever the talk starts."
    )

    // 19:30, Opening/Welcome: the belt, after the black wall the room arrived to. The
    // catalogue goes by flat and named, two rows running against each other. See
    // ConveyorScene in backdrop-drawers/.
    //
    // One of these for now. The drawer is a *kind* rather than an occasion, so a second
    // moment in the evening — Eerste gang — is another backdrop(...) with the palette
    // reversed and `reversed = true, move = 2.2, rest = 1.2` to run it the other way and
    // a touch slower, rather than anything new in the drawer.
    backdrop(
        ConveyorScene(
            // The register is what makes the belt draw the pieces to scale against one
            // another; `labels = true` puts their names back on them.
            "Welcome", beltSheet, details = openingDetails, concrete = openingTexture,
            // The house pair and nothing else, on black.
            palette = listOf(wnRed, wnBlue),
            fontPath = boldFont
        ),
        title = "Welcome",
        notes = "Introduction of objects. The catalogue on a belt, running left."
    )

    // Slides hang straight off their chapter here, with no subchapter over them.
    // A chapter can still hold `subchapter("...") { }` where a section needs
    // dividing — the card then carries its name and number at the foot.

    // Who is speaking. It stands **before** the first chapter, which is what makes it a
    // backdrop rather than a slide: a slide belongs to a chapter and would be announced by
    // that chapter's card, and there is no chapter yet — the talk has not started. A
    // backdrop takes the whole wall with no card beside it, which is exactly the frame a
    // title card wants. See NameTag in backdrop-drawers/.
    backdrop(
        NameTag(
            presenter = "Erik Koremans",
            organisation = "Willy Naessens NEDERLAND",
            role = "Chief Commercial and Sustainability Officer (CCSO)",
            fontPath = boldFont,
            accent = wnRed
        ),
        title = "Who is speaking",
        notes = "Opens the talk, ahead of the first chapter. Builds on its own frame count " +
                "— name, rule, company, title — so it finishes while it is being said " +
                "rather than on a click."
    )

    chapter("De wereld van bouwen") {
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
                fontPath = boldFont,
                // On the click, not the arrival: this slide opens on the city's own last
                // frame and holds there, so what the cue marks is the fan coming apart.
                stepCues = listOf(treeCue)
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
                fontPath = boldFont,
                sound = globeCue
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
                fontPath = boldFont,
                sound = stackCue,          // the slide coming up, over the opening band
                stepCues = stackSteps      // then a mark as each of the five lands
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
                fontPath = boldFont,
                sound = swivelCue
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
        // The three pillars as three cut pieces: one a click, and a fourth click that
        // closes the joint so their teeth mesh. Where they sit when closed is read off the
        // svgs' own teeth rather than tuned — see EsgFramework.
        slide(
            EsgFramework(fontPath = boldFont, ink = wnRed),
            title = "ESG Beoordelingskader",
            notes = "Environmental, then Social, then Governance, then they connect. " +
                    "Drie pijlers \u2014 niet als modewoorden, maar als kompas."
        )
        // The linear life cycle drawn in full, a phase a click, and then contradicted: the
        // end-of-life column is taken away and The Circle put in its place. Read off
        // data/ref/Levenscyclus van betonproducten.pdf, whose six states are these six
        // clicks. See LifeCycle for the layout, which is a function of the column count.
        slide(
            LifeCycle(
                phases = listOf(
                    LifePhase("A1-A3", "Productfase", listOf(
                        LifeStep("A1", "Grondstof winning"),
                        LifeStep("A2", "Transport naar fabricage plek"),
                        LifeStep("A3", "Fabricage")
                    )),
                    LifePhase("A4-A5", "Constructiefase", listOf(
                        LifeStep("A4", "Transport naar constructie plek"),
                        LifeStep("A5", "Installatie")
                    )),
                    LifePhase("B4-B5", "Gebruiksfase", listOf(
                        LifeStep("B1", "Gebruik"),
                        LifeStep("B2", "Onderhoud"),
                        LifeStep("B3", "Reparatie"),
                        LifeStep("B4", "Vervanging"),
                        LifeStep("B5", "Verbouwing")
                    )),
                    LifePhase("C1-C4", "End-of-life fase", listOf(
                        LifeStep("C1", "Demonteren en sloop"),
                        LifeStep("C2", "Transport"),
                        LifeStep("C3", "Afvalverwerking"),
                        LifeStep("C4", "Ontdoen van")
                    ))
                ),
                // No code on these: they are not a phase of the linear cycle, they are what
                // replaces its last one.
                closing = LifePhase("", "The Circle", listOf(
                    LifeStep("", "Elementen demonteren"),
                    LifeStep("", "Transport"),
                    LifeStep("", "Opslag"),
                    LifeStep("", "Opnieuw gebruiken")
                )),
                boldPath = boldFont,
                textPath = textFont
            ),
            title = "Levenscyclus van betonproducten",
            notes = "Four phases, a click each, then the end-of-life column is replaced by " +
                    "The Circle and its four steps arrive. Zonder sloop: demonteren, " +
                    "transport, opslag, opnieuw gebruiken."
        )
        // The B half of the same reference: the two databases feeding one calculation, and
        // what that calculation is for. Off data/ref/Levenscyclus van betonproducten-2.pdf,
        // whose two states are the first and the last of these five clicks.
        slide(
            LifeCycleAnalysis(
                sources = listOf(
                    LcaSource("Database van aangekocht materialen", lcaFields, LcaMark.ELEMENT),
                    LcaSource("Database betonmengsels", lcaFields, LcaMark.MIX)
                ),
                boldPath = boldFont,
                textPath = textFont,
                ink = wnRed
            ),
            title = "Levenscyclus analyse",
            notes = "The two databases arrive, are gathered into the analysis, and then what " +
                    "the analysis makes possible \u2014 materiaalgebonden CO\u2082-uitstoot."
        )
    }

    chapter("Beton: ruggengraat en transitie") {
        slide(
            Swivel01Slide(),
            title = "Panels turning",
            notes = "A wave crosses the row once on arrival and settles — 7.5s, off the " +
                    "slide's own frame count rather than a clock."
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
    }

    // The yard: the catalogue's pieces in the round, side by side at one height on a
    // white ground, each turning slowly on the spot under a low sun as the row drifts
    // across the wall. The second course wall, after the belt — the same catalogue the
    // belt shows flat, now as things. See YardScene in backdrop-drawers/.
    backdrop(
        YardScene(objects = yardObjects, ink = wnRed, shadow = wnBlue),   // the house pair: red pieces, navy shadow
        title = "Yard",
        notes = "The pieces laid end to end at one height, one colour with a long sharp " +
                "shadow, passing slowly and each turning on its own axis."
    )

    // The gallery: the whole catalogue on a dense grid, every piece once, all turning slowly
    // with the phase rippling across the field — calm, because nothing starts or stops.
    // See GalleryMode in backdrop-drawers/.
    backdrop(
        GalleryMode(objects = yardObjects, ink = wnRed, shadow = wnBlue),   // no picks: the whole catalogue
        title = "Gallery",
        notes = "All 115 pieces once each on a 23x5 grid on the white ground, every one " +
                "turning slowly, the phase rippling across the field as a sine."
    )

    // 22:00, Uitloop: the opening scene with the colours the other way round — the
    // same two elements in the same places, red then navy — which is how the
    // draaiboek draws the exit against the arrival.
    backdrop(
        ObjectScene(
            "Closing", backdropSheet,
            objects = listOf(7, 3),
            palette = listOf(wnRed, wnBlue),
            paper = wnPaper
        ),
        title = "Closing scene",
        notes = "Uitloop. The opening scene, colours swapped. The last -> lands here."
    )
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
        undecorated = Env["${prefix}_UNDECORATED"]?.let { Env.boolean("${prefix}_UNDECORATED") } ?: settings.undecorated,
        windowX = Env["${prefix}_WINDOW_X"]?.toIntOrNull() ?: settings.windowX,
        windowY = Env["${prefix}_WINDOW_Y"]?.toIntOrNull() ?: settings.windowY,
        title = Env["${prefix}_TITLE"] ?: settings.title,
        start = Env["${prefix}_START"] ?: settings.start,
        debug = Env["${prefix}_DEBUG"]?.let { Env.boolean("${prefix}_DEBUG") } ?: settings.debug,
        autoStep = Env["${prefix}_AUTOSTEP"]?.toDoubleOrNull() ?: settings.autoStep,
        cues = Env["${prefix}_CUES"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: settings.cues,
        record = Env.boolean("${prefix}_RECORD"),
        fps = Env["${prefix}_FPS"]?.toIntOrNull() ?: settings.fps,
        duration = Env["${prefix}_DURATION"]?.toDoubleOrNull() ?: settings.duration,
        stills = Env.boolean("${prefix}_STILLS"),
        sound = Env["${prefix}_SOUND"]?.let { Env.boolean("${prefix}_SOUND") } ?: settings.sound
    )
)
