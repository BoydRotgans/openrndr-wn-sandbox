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
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import slideshow.Settings
import slideshow.Show
import slideshow.Scene
import slideshow.backdrops.BlockCity
import slideshow.backdrops.CityBlock02
import slideshow.backdrops.NameTag
import slideshow.backdrops.PlainScene
import slideshow.backdrops.Programme
import slideshow.backdrops.EndingScene
import slideshow.playlist
import slideshow.backdrops.Entry
import slideshow.backdrops.ShadowFacade
import slideshow.backdrops.Skew3D
import slideshow.arrangedFromFile
import slideshow.present
import slideshow.slideshow
import slideshow.withModules
import java.io.File
import slideshow.drawers.Band
import slideshow.drawers.Callout
import slideshow.drawers.LongShadowChapterPanel
import slideshow.drawers.LongShadowV3ChapterPanel
import slideshow.drawers.CircleBuilding
import slideshow.drawers.BarChart
import slideshow.drawers.CarbonCharts
import slideshow.drawers.ConcreteLevers
import slideshow.drawers.Exchange
import slideshow.drawers.Lever
import slideshow.drawers.Population
import slideshow.drawers.ReductionProcess
import slideshow.drawers.Station
import slideshow.drawers.CarbonLadder
import slideshow.drawers.Measure
import slideshow.drawers.RealReduction
import slideshow.drawers.Co2Column
import slideshow.drawers.ColumnState
import slideshow.drawers.ChapterPanel
import slideshow.drawers.ShadowChapterPanel
import slideshow.drawers.Crowd
import slideshow.drawers.EsgFramework
import slideshow.drawers.LcaMark
import slideshow.drawers.LcaSource
import slideshow.drawers.LifeCycle
import slideshow.drawers.LifeCycleAnalysis
import slideshow.drawers.LifePhase
import slideshow.drawers.LifeStep
import slideshow.drawers.QuoteSlide
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
 * The loop under the slides of the talk, off `SLIDES_BASE_LOOP` — `base-loop.wav` from the sound
 * design. It plays on every slide beside a chapter card and fades out while a backdrop or a
 * whole-wall scene is up, since those carry the evening's other sound. Quiet, because it sits
 * under someone speaking; `none` for no bed.
 */
val slideLoop: slideshow.Sound? = (Env["SLIDES_BASE_LOOP"] ?: File(soundFolder, "base-loop-low.wav").path)
    .takeUnless { it.trim().equals("none", ignoreCase = true) }
    ?.let { path ->
        slideshow.Sound(
            File(path),
            gain = Env["SLIDES_BASE_LOOP_GAIN"]?.toDoubleOrNull() ?: 1.0,
            loop = true,
            fadeIn = slideshow.frames(3.0),
            fadeOut = slideshow.frames(2.0),
            // Not levelled: the loop is far quieter than the cues on purpose, and levelling would
            // boost it right back up (+20 dB). Its volume is the file's own and the gain's.
            levelled = false
        )
    }

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
 * The sound design delivered **named against the show**, a folder a chapter: `data/sounds/P1`
 * is chapter 1, one wav per state of one slide, the file saying which state it belongs to.
 * Comma separated, so `P2` stands beside `P1` when it arrives rather than replacing the folder.
 *
 * Nothing about *where* these go is stated here, and that is the point — it is already in the
 * file name, which is the [slideshow.Nameplate]'s own label for the state. See
 * [slideshow.CueSheet] for how a name becomes a slide, and why a sheet owns the slides it names
 * rather than filling in around what is declared below.
 *
 * `hold` and `fadeOut` are the rule the cues above arrived at one at a time: a cue that runs
 * longer than the state it marks is taken away with its slide, a shorter one rings out. The
 * first sheet splits on it cleanly — its cues run 2.4 to 5.1 seconds or 9.0 to 19.2, and
 * nothing in between.
 */
val cueSheet = slideshow.CueSheet.read(
    folders = (Env["SLIDES_CUE_SHEETS"] ?: "data/sounds/P1,data/sounds/P2,data/sounds/P3,data/sounds/P4")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        .map { File(it) },
    gain = cueGain,
    hold = Env["SLIDES_CUE_HOLD"]?.toDoubleOrNull() ?: 6.0,
    fadeOut = Env["SLIDES_CUE_FADE"]?.toDoubleOrNull() ?: 1.5
)

/**
 * What both life-cycle databases know about what they hold — the same five either way, which
 * is the point: the analysis can only add them up because they are asked the same questions.
 * One list handed in twice, so rewording it moves both fans.
 */
val lcaFields = listOf("Materiaal", "Leverancier", "Transportwijze", "Herkomst", "CO₂-impact")

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
 * The dinner music, a bed a course, off the playlists in `SLIDES_MUSIC` — one folder a course,
 * joined into one looping wav each (see [playlist]). The track order is the tracklist's, not the
 * folder's, which sorts by file name. Quieter than the opening's ambience: the meeting asked for
 * the opening loud and present and the evening moving from mysterious to uplifting, and these
 * sit under people eating.
 */
val musicFolder = File(Env["SLIDES_MUSIC"] ?: "input/diner_music/option1")
val musicGain = Env["SLIDES_MUSIC_GAIN"]?.toDoubleOrNull() ?: 0.45
val aperitifBed = playlist("aperitif", File(musicFolder, "Aperitif"), listOf("Backspace", "Memories Collide", "Wind And Unwind"), gain = musicGain)
val starterBed = playlist("starter", File(musicFolder, "Starter"), listOf("SRY", "Hazy Haze", "First Wave"), gain = musicGain)
val mainBed = playlist("main", File(musicFolder, "Main"), listOf("You Go", "Float", "Lost & Found"), gain = musicGain)
val dessertBed = playlist("dessert", File(musicFolder, "Dessert"), listOf("Port Ella", "Groovier Things", "Red Crescent"), gain = musicGain)

/**
 * The cues the talk itself is marked with, and **the sheet's numbering is the running order**:
 * 1-02 is the map, 1-05 the tree, 1-07 the globe, 1-09 the stack and 1-10 the figures — slides
 * four to eight of the first chapter, in order. That is what settles which `Swivel02Slide` gets
 * 1-10, there being two of them in the show: the one in this chapter, not the one in the fourth.
 *
 * The long ones fade out so they do not play on under the slide after them. `1-02` runs 25s
 * against a 12-second click, which is the case that made [slideshow.Sound.sustained] necessary.
 *
 * **These are now the fallback for chapter 1 rather than what it plays.** [cueSheet] speaks for
 * every slide of that chapter and a sheet owns the slides it names, so none of the five below is
 * heard while `data/sounds/P1` is there. They are kept rather than deleted because `data/` is not
 * committed and `SLIDES_CUE_SHEETS=none` is one line: without them a checkout with no sounds
 * folder, or a run with the sheet off, would be a chapter with no sound at all rather than the
 * chapter as it was before the sheet arrived. Chapters 2 to 4 still play them for real.
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
 * The click mark for a counted build in chapters 2 to 4 — a column landing, a step arriving, an
 * illustration appearing — so those chapters have marks on meaningful events where before only
 * chapter 1 had any. `clic.wav` is the sheet's own click (byte-identical to 1-09). A sting per
 * chapter would need four files that do not exist yet.
 */
val markCue = cue("clic.wav")

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
/** The white the brand's graphics stand on — not the paper grey above, which reads as a wall. */
val wnWhite = ColorRGBa.fromHex("F5F7FA")

/**
 * The playful set, for the collage. The house red and navy open it and the other four are
 * picked to stand beside them — a warm amber, a green, a coral and the Figma export's lighter
 * blue. They are a proposal rather than a brand palette: nothing in the exports names them.
 */
val wnAmber = ColorRGBa.fromHex("F2B705")
val wnTeal = ColorRGBa.fromHex("2E9E5B")
val wnCoral = ColorRGBa.fromHex("FF6B4A")
val wnSky = ColorRGBa.fromHex("4674D6")

/**
 * The sheet the backdrops stand their elements off. A path, so it is in `.env`; which
 * elements stand on which wall is content and is stated in the show below.
 */
val backdropSheet = File(Env["SLIDES_BACKDROP_SHEET"] ?: "data/svg/subset.svg")

/** The subset as separate files, one svg to a piece, for the walls that stack them. */
val patternFolder = File(Env["SLIDES_PATTERN_FOLDER"] ?: "data/svg/subset_svg")

/** The picture the shadow facade sets as type: dark ink on a light ground, fitted to the wall. */
val shadowMask = File(Env["SLIDES_SHADOW_MASK"] ?: "data/plain/title01.png")

/** The titles the fold wall circles through, one a turn, in order. */
val skewMasks = (Env["SLIDES_SKEW_MASKS"] ?: "data/plain/title01.png,data/plain/title02.png")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }

/**
 * The pictures the shadow wall reveals, one a turn, in order: a list of pngs, or `shapes` for
 * the abstract forms drawn by [abstractShapes] — a circle, a square and a triangle.
 */
val shadowMasks = (Env["SLIDES_SHADOW_MASKS"] ?: "shapes").let { value ->
    if (value.trim().equals("shapes", ignoreCase = true)) abstractShapes()
    else value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
}

/**
 * A circle, a square and a triangle as masks for the shadow wall, written once to
 * `build/shadow-shapes`: dark ink on a light ground, at the wall's own 3840x1080 so where a
 * form stands is where it is revealed. Each stands at its own place along the wall — left,
 * middle, right — so, revealed one a turn, the forms read as passing along it rather than
 * as one spot changing shape. They are drawn at the same height, and the triangle a little
 * larger, so the three carry about the same weight.
 */
fun abstractShapes(): List<File> {
    // **AWT must not claim the macOS main thread before GLFW does.** This is a top-level val,
    // so it runs at class init — before `configure {}` — and touching Graphics2D there starts
    // AppKit on thread 0, after which `glfwCreateWindow` blocks for ever with no message at
    // all. Headless AWT draws and writes pngs exactly the same and never starts AppKit. The
    // run task sets this too; it is set here as well because the trap belongs to this function
    // rather than to a launcher, and a second main that forgets the flag would hang the same way.
    System.setProperty("java.awt.headless", "true")

    val dir = File("build/shadow-shapes").apply { mkdirs() }
    val w = 3840
    val h = 1080
    val size = 640
    val cy = h / 2
    val forms = listOf<(java.awt.Graphics2D, Int) -> Unit>(
        { g, cx -> g.fillOval(cx - size / 2, cy - size / 2, size, size) },
        { g, cx -> g.fillRect(cx - size / 2, cy - size / 2, size, size) },
        { g, cx ->
            val s = (size * 1.15).toInt()
            val top = cy - s * 0.55
            val base = cy + s * 0.45
            g.fillPolygon(intArrayOf(cx, cx + s / 2, cx - s / 2), intArrayOf(top.toInt(), base.toInt(), base.toInt()), 3)
        }
    )
    val places = listOf(w * 0.22, w * 0.5, w * 0.78).map { it.toInt() }
    return forms.mapIndexed { i, draw ->
        File(dir, "shape-$i.png").also { file ->
            val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            g.color = java.awt.Color.BLACK
            draw(g, places[i])
            g.dispose()
            javax.imageio.ImageIO.write(image, "png", file)
        }
    }
}

/**
 * The opening wall reads a sheet of its own, and needs to: it draws the catalogue *entire*,
 * one piece after another, so it wants the full front sheet rather than the picked subset
 * the rest of the deck stands on.
 */
val openingSheet = File(Env["SLIDES_OPENING_SHEET"] ?: "data/svg/objects-front.svg")

/**
 * The sheet the field of a hundred elements packs: the iso sheet, every piece once. The front
 * sheet was tried, as the brief asked, and is mostly hairlines and plain slabs in elevation —
 * the note under demo02 — so the field came out as a stack of bars; drawn in isometric the same
 * catalogue reads as pieces.
 */
val hundredSheet = File(Env["SLIDES_HUNDRED_SHEET"] ?: "data/svg/objects-iso.svg")

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


/**
 * The catalogue's register, which is what lets the wall name a piece and quote its box.
 * Paired with the sheet by index, and only when the two are the same length — see
 * [OpeningScene], which drops it rather than shifting every name along by one.
 */
val openingDetails = Env["SLIDES_OPENING_DETAILS"]?.let { File(it) }

/**
 * The chapter title pictures, one a chapter: `{n}` is the chapter's number in the running order.
 * `data/titles` holds three drawn versions — `v1` the condensed full-bleed set, `v2` one wide bold
 * at one size filling the pane, `v3` the same face small — all white on black at 1920x1080.
 */
val cardTitles = Env["SLIDES_CARD_TITLES"] ?: "data/titles/v2/title0{n}.png"

/**
 * How a chapter card shows its title: `shadows` cuts it into the facade, `mosaic` packs it into
 * elements, and `longshadow` sets the chapter's own words in Rockwell as towers rising out of the
 * floor under a setting sun — `LongShadowType`'s effect, tuned by the same `LONGSHADOW_*` keys.
 */
val cardStyle = Env["SLIDES_CARD_STYLE"] ?: "longshadow"

/**
 * The chapters whose card is long shadow type v3 instead of [cardStyle], by number — the drawn
 * stencil title coming up through a field of elements. `none` gives every chapter [cardStyle].
 */
val cardV3Chapters = (Env["SLIDES_CARD_V3_CHAPTERS"] ?: "1").split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "none" }.toSet()

/**
 * The card for one section: the chapter's own drawn title, cut into the Shadows facade.
 *
 * **Every chapter is a picture rather than type the card sets itself**, read off [cardTitles] by
 * the chapter's number — the number comes off the running order, so a fifth chapter needs only its
 * png. A section with subchapters carries "1.2", and the chapter's picture stands behind all of
 * them, so only the part before the dot is used.
 *
 * **The title is read out of the wall by its shadows.** The facade is the Shadows wall's own —
 * a shallow grid, the letters cut deep into it, one sun — and [ShadowChapterPanel] only stands it
 * in the pane beside the slides. The wall opens as a plain grid with the sun square on, where
 * letter and ground are identical to the pixel; as the chapter's sting plays the sun leans away
 * over `reveal`, the deep letters fill with shadow and go dark while the ground stays lit.
 * Then it stays low and keeps going round, once a `period`, so the shadows turn slowly in every
 * cell for as long as the chapter lasts and the title never sinks back into the grid.
 *
 * **The cells are sized to the title's strokes, not to the wall's.** The backdrop runs 27px cells
 * under titles set at twice this size; a letter here is built of whole cells, so v2's strokes of
 * about 20–40px want cells half that. The pictures are white on black, hence `invert`.
 *
 * [cardStyle] `mosaic` gives back the card made of catalogue elements, [ObjectImageChapterPanel]
 * with the recipe tuned for v1; a chapter with no picture at all falls back to setting its title
 * as type, so a checkout without `data/` still runs the show rather than four blank cards.
 */
fun chapterCard(section: slideshow.Section): slideshow.Slide {
    // Set as type from the chapter's own title, so it needs no picture. No concrete of its own:
    // the show lays one over every frame (SLIDES_CONCRETE), and it keeps white white.
    // Long shadow type v3 on the chapters that ask for it: the field already standing as the chapter
    // opens (FILL 0), the title coming through after AT seconds. The sketch's own keys steer the rest.
    val chapterNumber = section.number.substringBefore('.')
    if (chapterNumber in cardV3Chapters) return LongShadowV3ChapterPanel(
        section,
        longShadowV3FromEnv(
            revealFill = Env["SLIDES_CARD_V3_FILL"]?.toDoubleOrNull() ?: 0.0,
            revealAt = Env["SLIDES_CARD_V3_AT"]?.toDoubleOrNull() ?: 1.0
        ),
        svg = (Env["SLIDES_CARD_V3_SVG"] ?: Env["LONGSHADOW_V3_SVG"] ?: "data/titles/v5/title0{n}-test3.svg")
            .takeIf { it != "none" }?.let { File(it.replace("{n}", chapterNumber)) },
        sound = chapterCue
    )
    if (cardStyle == "longshadow") return LongShadowChapterPanel(section, longShadowFromEnv(), sound = chapterCue)

    val image = File(cardTitles.replace("{n}", section.number.substringBefore('.')))
    if (!image.isFile) return ObjectChapterPanel(section, cardFont, sound = chapterCue)

    if (cardStyle == "mosaic") return ObjectImageChapterPanel(
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

    return ShadowChapterPanel(
        section,
        ShadowFacade(
            name = section.chapter,
            mask = image, invert = true,            // white letters on black
            // Letters cut deep into a shallow ground, so the words fill with shadow while the
            // ground stays lit — and then the whole picture inverted, so the letters stand white
            // in a dark wall, which the feedback of 16 September asked for. Cutting it the other
            // way round (ground deep, letters shallow) was tried first and left the ground a mid
            // grey: at 10px cells the lit frames are half of every cell whatever its depth.
            depth = 0.06, inkDepth = 0.6,
            inverted = true,
            hollow = 0.22,                          // every floor darker than the face: the grid before any shadow
            ramps = 0.0, blades = 0.0, stepShare = 0.0, wedges = 0.0,   // every cell a plain recess
            // 13px cells, 7px wide: v4's strokes are 29px, so a stroke carries two cells up and
            // four across. The grid follows the drawing — v2's 50px strokes ran 60 rows and v3's
            // 22px ran 108 — because a letter is built of whole cells and has to be several
            // across to read at all.
            rows = 80, aspect = 0.5,
            mullion = 0.08, ledge = 0.08,
            orbit = true, low = 40.0, high = 90.0, hold = 2.0,
            reveal = 3.0,                           // square on, then leaning away as the chapter opens: 3s, so less bare wall at each opening than the 4s it had
            period = 45.0,                          // once round in three quarters of a minute afterwards
            // The light keeps moving while the card stands, slow but *seen*, as light falling
            // across a wall: the sun climbs eighteen degrees and settles back every forty seconds,
            // so the shadow filling the ground lengthens and shortens visibly, and a lamp wanders
            // over the wall so the light moves at the scale of the wall. The first setting (ten
            // degrees, a 0.3 lamp) was at the edge of noticing and was asked to be visible.
            breathe = 18.0, breathePeriod = 40.0,
            glow = 0.4, glowSize = 0.8, glowPeriod = 60.0,
            jitter = 0.8, depthJitter = 0.35
            // No concrete of its own: the show lays one over the whole frame instead (SLIDES_CONCRETE).
        ),
        sound = chapterCue
    )
}

/**
 * A chapter's takeaway: the one sentence it resolves on before the room goes to a course, set as
 * the quote slide and nothing else. Every chapter ends on one since 16 September — none did
 * before, each closing on whatever slide happened to be last — which is the re-entry and
 * resolution the narrative plan asks of a chapter. It carried "Om mee te nemen" over it for a
 * day; that label went with the other lines over the quotes, and what it said is the speaker's
 * to say. The sentences are DRAFTS from the script plan's bridges, for WN to approve; the
 * slide's id is `kernboodschap-<n>`.
 */
fun slideshow.ChapterBuilder.takeaway(n: Int, sentence: String) = slide(
    QuoteSlide(sentence, boldFont, lines = 4),
    title = "Kernboodschap $n",
    notes = "De kernboodschap van hoofdstuk $n, vóór de gang: één zin, dan de vraag " +
            "die het volgende hoofdstuk beantwoordt. DRAFT wording from the script plan."
)

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

/**
 * The four chapters' key messages, one wording each, read by the programme wall and by the
 * quote that opens each chapter — so the two cannot say the same sentence differently, which
 * they did until 16 September ("checklijst… de manier" against "checklist… de systemische
 * manier"). Indexed by chapter number less one. The wording of the second is a proposal:
 * "systematische" for what the earlier copy had as "systemische".
 */
val chapterMessages = listOf(
    "Hoe bouw je een wereld die vandaag stevig overeind blijft, maar licht genoeg is om de toekomst niet te belasten?",
    "ESG is geen checklist, maar de systematische manier waarop we elke dag opnieuw beslissen hoe we bouwen, leveren, investeren en verbeteren.",
    "We gieten niet alleen beton. We gieten kennis, onderzoek en verantwoordelijkheid in elke kubieke meter.",
    "We bouwen vandaag, met het oog op wie na ons komt."
)

/**
 * The group's production sites, as the address list gives them. A pixel each on the map, and the
 * order here is the order [FactoryVisit] counts in. The `B-` postcode prefixes are dropped so
 * every address line reads the same way; the geocoder strips them anyway.
 *
 * Two pairs share an address — Megaton/Structo Prefab Systems and Megaton in Ninove,
 * Intershipping and Interton in Bornem — so each of those takes the free cell beside the other.
 */
val factories = listOf(
    Factory("Megaton/Structo Prefab Systems", "Industriezone II, Nederwijk-Oost 279", "9400", "Ninove", "België"),
    Factory("Concreton", "Diebeke 37", "9500", "Geraardsbergen", "België"),
    Factory("Megaton", "Industriezone II, Nederwijk-Oost 279", "9400", "Ninove", "België"),
    Factory("Tripan", "Pannenhuisstraat 44", "3650", "Dilsen", "België"),
    Factory("FB Groupe France", "69 Avenue Foch", "54001", "Nancy", "Frankrijk"),
    Factory("Alpreco", "Victor Dumonlaan 26", "2830", "Willebroek", "België"),
    Factory("Intershipping", "Oude Sluisweg 30", "2880", "Bornem", "België"),
    Factory("Megaton", "Bedrijvenpark Coupure 7", "9700", "Oudenaarde", "België"),
    Factory("Altaan", "Industriezone Lanklaar, Siemenslaan 7", "3650", "Dilsen-Stokkem", "België"),
    Factory("Interton", "Oude Sluisweg 30", "2880", "Bornem", "België"),
    Factory("Seveton", "Meersbloem Leupegem 58", "9700", "Oudenaarde", "België"),
    Factory("Structo", "Steenkaai 107", "8000", "Brugge", "België"),
    // The plants the contact page (willynaessens.be/en/contact, read 16 September) lists that the
    // client's sheet did not: the head office and works, two more Belgian works, two in France
    // and the Luxembourg plant. Appended, so the visits above keep their numbers.
    Factory("Willy Naessens Industriebouw", "Kouter 3", "9790", "Wortegem-Petegem", "België"),
    Factory("Willy Naessens Industriebouw", "Industrieweg 116", "3980", "Tessenderlo", "België"),
    Factory("Snoeck", "Hooiemeersstraat 10", "8710", "Wielsbeke", "België"),
    Factory("Willy Naessens Bâtiments Industriels", "Zone du Moulin", "62450", "Bapaume", "Frankrijk"),
    Factory("Thibault Bâtiment Industriel", "Route Nationale", "76340", "Foucarmont", "Frankrijk"),
    Factory("Concrelux", "Z.I. Um Monkeler", "L-4149", "Schifflange", "Luxemburg"),
    Factory("Megaton/Structo Prefab Systems Luxembourg", "Z.I. Um Monkeler", "L-4149", "Schifflange", "Luxemburg")
)

/**
 * Where the group is without a plant: offices, showrooms and depots off the same contact page,
 * one entry an address — the seven companies at Kouter 3 are the head office above, and the
 * transport arm shares its plants' addresses. Drawn as small blue dots under the factories. It
 * answers the question the meeting left open: the Netherlands has a Willy Naessens office, at
 * Nieuwkuijk, and no factory.
 */
val offices = listOf(
    Factory("Willy Naessens Industriebouw NL", "Vimmerik 18", "5253 CB", "Nieuwkuijk", "Nederland"),
    Factory("Willy Naessens Construct", "Bedrijvenpark de Coupure 15-17", "9700", "Oudenaarde", "België"),
    Factory("Willy Naessens Bâtiments Industriels LU", "65 Rue Romain Fandel", "L-4149", "Schifflange", "Luxemburg"),
    Factory("Willy Naessens Industrial Buildings DK", "Galoche Alle 6", "4600", "Køge", "Denemarken"),
    Factory("Willy Naessens Swimming Pools", "Westerring 37", "9700", "Oudenaarde", "België"),
    Factory("Willy Naessens Swimming Pools", "Hoeilaartsesteenweg 252", "3090", "Overijse", "België"),
    Factory("Willy Naessens Swimming Pools", "Industriepark Brechtsebaan 16", "2900", "Schoten", "België"),
    Factory("Willy Naessens Swimming Pools", "Kortrijksesteenweg 130", "9830", "Sint-Martens-Latem", "België"),
    Factory("Pool Conception", "Rue des Français 36", "7538", "Vezon", "België"),
    Factory("Mutec", "Eugène Bekaertlaan 55", "8790", "Waregem", "België"),
    Factory("Transwinaton", "Bedrijfsstraat 15", "3990", "Peer", "België"),
    Factory("M-Construct", "Meerstraat 128", "9220", "Hamme", "België"),
    Factory("D-Glas", "Heerweg 7", "8400", "Oostende", "België"),
    Factory("Huppa", "Waregemseweg 28", "9790", "Wortegem-Petegem", "België"),
    Factory("Huppa", "Gustave Demeurslaan 106", "1654", "Huizingen", "België"),
    Factory("Huppa", "Zandbergen 108", "2480", "Dessel", "België"),
    Factory("Huppa", "Zandvoordestraat 370", "8400", "Oostende", "België"),
    Factory("Huppa", "Fortsesteenweg 34", "2860", "Sint-Katelijne-Waver", "België"),
    Factory("Huppa", "Parc Artisanal de Blegny 15", "4671", "Blegny", "België"),
    // No postcode: the contact page gives none for this one, and a guessed one is worse than
    // none — it sends the geocoder somewhere confidently wrong rather than to the town.
    Factory("Huppa Cash & Carry", "Rue du Marché Couvert", "", "Jupille-sur-Meuse", "België")
)

/** The overview the factory map opens and closes on. */
val europe = FactoryCluster("Europa", span = 1050.0)

/**
 * The arrival wall, a named value because two slides draw it: the opening backdrop itself, and
 * the name tag, which draws it into its right pane so the catalogue goes on drawing itself
 * while the speaker is introduced. Declared as one object, loaded once by the deck.
 */
val openingWall = OpeningScene(
    sheet = openingSheet,
    // The regular weight, not the deck's bold: the annotation is a caption on a
    // drawing, and the bold read as a heading beside a piece rather than under it.
    fontPath = textFont,
    // No concrete of its own: the show lays one over every frame (SLIDES_CONCRETE).
    details = openingDetails,
    // the room the wall stands in, fading up under it and out as the talk starts
    sound = ambience
)

val show = slideshow {

    // --- how it is shown --------------------------------------------------- //

    canvas(3840, 1080)      // the frame everything is composed at: two 1920x1080
                            // sides, flush against each other
    window(1.0)             // how much of the screen the window takes
    title("openrndr")
    slideBed(slideLoop)     // the base loop under the slides; walls and scenes play their own
    cueSheet(cueSheet)      // the sound design read off its folder, placed by the state each file names

    // The left pane: the chapter, set as large as it will go, white on black. One
    // card per section, standing for as long as the show is in it — see
    // ChapterPanel in slide-drawers/ for the drawing itself. 1920 + 1920 is the
    // 3840 canvas above: two keynote-sized sides, meeting with no gutter, so the
    // two grounds are what divide the frame rather than a bar between them.
    //
    // Every chapter's card is its own drawn title off data/titles, cut into the
    // Shadows facade and read out by a sun leaning away as the chapter opens — see
    // chapterCard above, which picks the picture off the section number and falls
    // back to setting the title as type where there is none. So adding a chapter
    // needs nothing here but the png.
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
        openingWall,
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
            // No concrete for now: projected over the belt it darkened the whole wall.
            "Welcome", beltSheet, details = openingDetails,
            // The house pair and nothing else, on black.
            palette = listOf(wnRed, wnBlue),
            fontPath = boldFont,
            sound = aperitifBed                     // the aperitif's bed: the belt is the Aperitif wall
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

    // The programme of the evening: the moments and the four chapters in one line across the
    // wall, a click a chapter with its key message. The entries are the order file's moments
    // and the chapters' titles, stated here so a renamed moment is one change. See Programme.
    backdrop(
        Programme(
            entries = listOf(
                Entry("Opening"),
                Entry("Aperitief"),
                Entry("De wereld van bouwen", chapter = true, message = chapterMessages[0]),
                Entry("Voorgerecht"),
                Entry("Waardekader en verantwoordelijkheid", chapter = true, message = chapterMessages[1]),
                Entry("Eerste gang"),
                Entry("Beton: ruggengraat en transitie", chapter = true, message = chapterMessages[2]),
                Entry("Tweede gang"),
                Entry("The Circle: een nieuwe manier van denken", chapter = true, message = chapterMessages[3]),
                Entry("Vragen"),
                Entry("Dessert"),
                Entry("Uitloop")
            ),
            boldPath = boldFont,
            textPath = textFont,
            accent = wnRed
        ),
        title = "Het programma",
        notes = "De avond in vier delen, geserveerd tussen de gangen. Een click per hoofdstuk " +
                "brengt het naar voren met zijn kernboodschap; de laatste click toont het geheel."
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
                    "than filling the frame the way a chapter card does. The lead line above it " +
                    "is the evening's opening sentence; DRAFT."
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
                // Two factors named first, one a click, then the fan. They stand beside the root
                // while they are the only thing on the pane and travel out to their rows when it
                // opens, so one is read on the left, then one on the right, then all of them.
                // **One from each list, in that order** — Locatie is the left branch and
                // Constructiekeuzes the right, and the slide reads left-then-right because of it.
                // PROPOSAL: which two is the speaker's call.
                highlights = listOf("Locatie", "Constructiekeuzes"),
                // On the fan's click, not the arrival: this slide opens on the city's own
                // last frame and holds there, so what the cue marks is the fan coming apart.
                fanCue = treeCue
            ),
            title = "Everything a build answers to",
            notes = "The element the city closed on becomes the root. Click 1 names Locatie, " +
                    "click 2 Constructiekeuzes, click 3 fans out everything a build answers to. " +
                    "Nesting a label with node(\"...\", node(\"...\")) adds a level and costs a click."
        )

        // No clicks: it arrives and fills itself while you talk over it. The globe
        // turns one label's worth of angle between one label and the next, so the
        // ring closes exactly as the list runs out — `pace` is the only dial, and it
        // sets the spin and the arrivals together because they are the same number.
        slide(
            GlobeSlide(
                labels = leftFactors + rightFactors,
                fontPath = boldFont,
                // The globe is the catalogue on a grid: the Crowd slide's meridians and
                // parallels with components in place of its people, white on the body and the
                // strong blue where a line runs through — the same pair that slide uses.
                sheet = hundredSheet,
                piece = ColorRGBa.WHITE,
                grid = ColorRGBa.fromHex("2E5BFF"),
                sound = globeCue
            ),
            title = "The globe",
            notes = "Builds off its own frame count rather than clicks, so it fills " +
                    "while you talk over it and keeps turning once it is full. The globe " +
                    "itself is the catalogue on the Crowd slide's grid."
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
                // Five rows of two, all the same shape: the last two stood alone across the full
                // width until 16 September and are now a pair like the ones above them. A click
                // is one box, so this is ten states.
                rows = listOf(
                    row("Eigen ontwerp- en engineeringafdeling", "Eigen prefabricatie"),
                    row("Eigen grond- en omgevingswerken", "Eigen funderingsploegen en paalmachines"),
                    row("Eigen transport", "Eigen montageploegen"),
                    row("Eigen dakdichtingsbedrijf", "Eigen alu-schrijn- en glasbedrijf"),
                    row("Eigen technische afdeling", "Eigen after sales")
                ),
                sheet = backdropSheet,                       // the bars take the subset piece's contour
                fontPath = boldFont,
                sound = stackCue,          // the slide coming up, over the opening band
                stepCues = stackSteps      // then a mark as each of the five lands
            ),
            title = "Verticale integratie",
            notes = "Six clicks, a band each. The last is picked out in red."
        )
        // The company's figures, a slab a figure and a figure a click — one slide since 16
        // September, where it was two. The same drawer as the train it replaced, carrying
        // a different palette — which is the whole point of the copy being a list
        // the show hands in rather than anything the drawer knows.
        slide(
            Swivel02Slide(
                blocks = listOf(
                    SwivelBlock("350.000 M\u00B3 Betonproductie"),
                    SwivelBlock("950.000 M\u00B2 Gewelven"),
                    SwivelBlock("210 miljoen omzet"),        // TO CONFIRM: Dutch company or group (meeting: € 1,4 miljard)
                    // The travelling slabs that stood in chapter 4 — the six countries, the sites,
                    // the people and the companies — are the later clicks here since 16 September.
                    SwivelBlock("Actief in 6 landen"),
                    SwivelBlock(
                        items = listOf(
                            "BELGIË", "NEDERLAND", "LUXEMBURG",
                            "FRANKRIJK", "DENEMARKEN", "ZWEDEN"   // six: Frankrijk in place of a second Luxemburg
                        )
                    ),
                    SwivelBlock("350 werven per jaar", note = "+ 250 zwembaden"),
                    SwivelBlock("950 medewerkers"),
                    SwivelBlock("19 bedrijven")
                ),
                clicked = true,                              // a figure a click, centred and close up
                across = 700.0,                              // three slabs across the pane: the centred one is the picture
                front = ColorRGBa.fromHex("3D5AE0"),
                side = ColorRGBa.fromHex("3550C4"),
                fontPath = boldFont,
                sound = swivelCue
            ),
            title = "De cijfers",
            notes = "Een cijfer per click, blauw op zwart: productie, gewelven, omzet, dan de zes " +
                    "landen, de werven, de mensen en de bedrijven. TO CONFIRM with WN: which entity " +
                    "the omzet is, and the six countries."
        )
        takeaway(1, "We hebben gekeken naar de organisatie achter het bouwen. Na deze gang stellen " +
                "we de volgende vraag: hoe beoordelen we de keuzes die we maken?")
    }

    // "verantwoor-delijkheid" is hyphenated so the card may break it there; written
    // whole it is one unbreakable word and the type shrinks to carry it on a line.
    chapter("Waardekader en verantwoor-delijkheid") {
        slide(
            QuoteSlide(
                // One wording, shared with the programme wall (see `chapterMessages`).
                "\u201C" + chapterMessages[1] + "\u201D",
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
        // The CO₂-prestatieladder, both versions: the five rungs until 2025 with the group on the
        // third, the first three closing up into the first rung of the 2026 ladder, and the group
        // on that one — a paragraph at the top left saying what each state means. Read off the
        // Figma frames of the new version, six states. See CarbonLadder.
        slide(
            CarbonLadder(boldPath = boldFont, textPath = textFont),
            title = "CO\u2082-prestatieladder",
            notes = "Zes stappen: (0) de ladder tot 2025, vijf treden; (1) WN op trede 3; " +
                    "(2) in 2026 van vijf naar drie treden, de eerste drie gaan samen; (3) samen de " +
                    "nieuwe trede 1; (4) trede 2 keten, trede 3 naar nul in 2050; (5) WN op trede 1 " +
                    "\u2014 geen stap terug, dezelfde positie. Te checken door WN: de treden (meeting " +
                    "9/9: 2025 trede 3, 2026 trede 1; het Figma-ontwerp zei trede 5 en 3), en de " +
                    "reden van de wijziging \u2014 de tekst beschrijft nu alleen wat er veranderde. " +
                    "Eventueel te noemen: het gunningsvoordeel bij aanbestedingen. " +
                    "Onder Environment wordt verteld hoe CO\u2082-uitstoot systematisch in kaart " +
                    "wordt gebracht, reductie geen eenmalig project is maar een continue opdracht, " +
                    "en groene stroom en eigen energieproductie geen bijzaak zijn maar structurele " +
                    "keuzes. CO\u2082-uitstoot is voor WN niet alleen een getal, maar een instelling: " +
                    "elke liter brandstof, elke kilowattuur, elke gereden kilometer vertaalt zich " +
                    "in impact \u2014 en dus ook in kansen om te verbeteren."
        )
        // The numbers behind the ladder: two bar charts, a click each, and the measures under
        // them. See CarbonCharts.
        slide(
            CarbonCharts(
                title = "ESG Environmental \u2014 CO\u2082-prestatieladder",
                left = BarChart(
                    heading = "26% minder CO\u2082 behaald (ge\u00EFndexeerde omzet)",
                    years = listOf("2020", "2021", "2022", "2023", "2024"),
                    // PLACEHOLDER: read off the client's chart (frame 2-08), which carries no data
                    // table — WN is asked for the figures behind it, and for the target.
                    values = listOf(27.0, 28.0, 20.0, 19.2, 19.6),
                    max = 30.0, step = 5.0,
                    target = listOf(26.8, 25.9, 25.2, 24.5, 23.6),
                    targetLabel = listOf("Doelstelling CO\u2082 per", "ge\u00EFndexeerde omzet"),
                    reachedLabel = listOf("Behaalde CO\u2082-reductie", "per ge\u00EFndexeerde omzet"),
                    // Three a chart at most; the rest is in the notes for the speaker.
                    bullets = listOf(
                        "Alle betonfabrieken hebben het CSC Silver-statuut.",
                        "Fabrieken draaien op opgevangen regenwater.",
                        "Zand en grind uit het spoelwater worden gerecupereerd."
                    )
                ),
                right = BarChart(
                    heading = "Eigen productie groene stroom",
                    years = listOf("2024", "2025", "2026"),
                    // PLACEHOLDER: 6,2 GWh is the client's figure for 2026; 2024 and 2025 are read off the chart.
                    values = listOf(5.2, 5.6, 6.2),
                    max = 6.0, step = 1.0,
                    reachedLabel = listOf("6,2 GWh eigen productie", "van groene stroom"),
                    bullets = listOf(
                        "1 GWh meer eigen productie voorzien voor 2026",
                        "Sinds 2022 groene stroom voor wat we niet zelf produceren",
                        "Elektrische aansluitingen op de werf, met batterij voor pieken"
                    )
                ),
                boldPath = boldFont,
                textPath = textFont,
                bar = wnRed,
                line = wnSky
            ),
            title = "CO2 behaald",
            notes = "De cijfers achter de ladder. Click 1: de doelstelling erover; click 2: eigen " +
                    "groene stroom; click 3: de maatregelen. PLACEHOLDER figures, read off the " +
                    "client's chart \u2014 the real values (CO\u2082 per ge\u00EFndexeerde omzet 2020\u201324 " +
                    "and the target, groene stroom 2024\u201326) are still to come from WN. " +
                    "Voor de spreker, niet op de muur: het CSC Silver-statuut staat voor zorgvuldige " +
                    "bedrijfsvoering op management, milieu en sociale aspecten; water met fijn " +
                    "materiaal wordt in suspensie gehouden en hergebruikt; uitgebreide monitoring van " +
                    "het stroomgebruik; een 100% elektrische kraan."
        )
        // Not compensation but reduction: the certificate alone, the measures either side of
        // it, then the certificate gone and the measures closing up. The machines are drawn as
        // outline pictograms, the language the reference draws its panel and battery in; the
        // mark on the certificate is set as type until the svg arrives. See RealReduction.
        slide(
            RealReduction(
                title = "Geen compensatie, maar reductie",
                farewell = "Niet compenseren. Reduceren.",     // the line the certificate leaves on
                measures = listOf(
                    Measure(Measure.Kind.CRANE, "Elektrische kranen", 0),
                    Measure(Measure.Kind.TRUCK, "Elektrische vrachtwagens", 0),
                    Measure(Measure.Kind.VAN, "Elektrische bussen", 1),
                    Measure(Measure.Kind.CAR, "Elektrisch woon-werkverkeer", 1),
                    Measure(Measure.Kind.PANEL, "Opwekken eigen groene stroom", 2),
                    Measure(Measure.Kind.BATTERY, "Opslaan groene stroom in batterij", 2)
                ),
                illustrations = File(Env["SLIDES_ILLUSTRATIONS"] ?: "data/illustrations"),
                boldPath = boldFont,
                textPath = textFont,
                accent = wnRed,
                blue = ColorRGBa.fromHex("3D5AE0"),
                stepCues = List(7) { markCue }      // the certificate going, then a mark an illustration
            ),
            title = "Geen compensatie",
            notes = "In plaats van ons te richten op CO\u2082-neutrale certificeringen op basis van " +
                    "compensatie van emissies, legt Willy Naessens de nadruk op daadwerkelijke " +
                    "emissiereducties door maatregelen zoals de elektrificatie van transport en " +
                    "apparatuur. PLACEHOLDER: the WN mark on the certificate is set as type."
        )
        // The domino effect: the decision tree, then four, then sixteen, then one word. Each
        // tree round one of the subset's elements in its own colour. See DominoEffect.
        slide(
            DominoEffect(
                title = "Het domino-effect van duurzame keuzes",   // its own title: the ladder's named a ladder over a tree
                left = leftFactors,
                right = rightFactors,
                sheet = backdropSheet,
                centres = listOf(
                    Centre(0, ColorRGBa.fromHex("3D5AE0")),
                    Centre(5, ColorRGBa.WHITE),
                    Centre(8, wnRed),
                    Centre(12, wnSky)
                ),
                boldPath = boldFont,
                textPath = textFont
            ),
            title = "Domino-effect",
            notes = "Duurzaamheid heeft bovendien een domino-effect: het gebruik van duurzame " +
                    "materialen maakt het gemakkelijker om complete gebouwen als duurzaam te " +
                    "classificeren. One tree, nine, twenty-five, then DUURZAAM — the camera " +
                    "pulling back about the first tree rather than re-dealing them. No " +
                    "photograph under the word: the client's was a placeholder."
        )
        // Social and governance as a crowd of people: one, the group around them, the lines
        // between them, the group as an arrow with one out ahead, and the whole with a share
        // marked out. Read off data/ref/social.pdf, a click a frame. See Crowd.
        slide(
            Crowd(fontPath = boldFont, many = wnSky, share = ColorRGBa.fromHex("2E5BFF")),   // the globe grid in a strong blue, so a line a figure wide still reads against the white
            title = "ESG Social & Governance",
            notes = "Opleiding, veiligheid en werkzekerheid vormen de basis, en hoe de groep ook " +
                    "nadenkt over levenskwaliteit. Then governance: transparante rapportering, " +
                    "externe audits, certificering en lange-termijnrelaties met partners als " +
                    "de structuur achter de waarden."
        )
        // The linear life cycle drawn in full, a phase a click, and then contradicted: the
        // end-of-life column is taken away and The Circle put in its place. Read off
        // data/ref/Levenscyclus van betonproducten.pdf, whose six states are these six
        // clicks. See LifeCycle for the layout, which is a function of the column count.
        slide(
            LifeCycle(
                phases = listOf(
                    // One vocabulary with the reduction slide in chapter 4: the same five steps
                    // in the same Dutch words, compounds closed.
                    LifePhase("A1-A3", "Productfase", listOf(
                        LifeStep("A1", "Grondstofwinning"),
                        LifeStep("A2", "Transport naar de fabriek"),
                        LifeStep("A3", "Productie")
                    )),
                    LifePhase("A4-A5", "Bouwfase", listOf(
                        LifeStep("A4", "Transport naar de bouwplaats"),
                        LifeStep("A5", "Montage")
                    )),
                    LifePhase("B1-B5", "Gebruiksfase", listOf(
                        LifeStep("B1", "Gebruik"),
                        LifeStep("B2", "Onderhoud"),
                        LifeStep("B3", "Reparatie"),
                        LifeStep("B4", "Vervanging"),
                        LifeStep("B5", "Verbouwing")
                    )),
                    LifePhase("C1-C4", "Einde levensduur", listOf(
                        LifeStep("C1", "Sloop"),
                        LifeStep("C2", "Transport"),
                        LifeStep("C3", "Afvalverwerking"),
                        LifeStep("C4", "Verwijdering")
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
                textPath = textFont,
                stepCues = List(5) { markCue }      // a mark as each column lands: a counted build
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
                    LcaSource("Database van aangekochte materialen", lcaFields, LcaMark.ELEMENT),
                    LcaSource("Database betonmengsels", lcaFields, LcaMark.MIX)
                ),
                boldPath = boldFont,
                textPath = textFont,
                ink = wnRed
            ),
            title = "Levenscyclusanalyse",
            notes = "The two databases arrive, are gathered into the analysis, and then what " +
                    "the analysis makes possible \u2014 materiaalgebonden CO\u2082-uitstoot."
        )
        takeaway(2, "We hebben besproken hoe we keuzes beoordelen. Na deze gang kijken we waar die " +
                "keuzes concreet worden: in het materiaal en de levenscyclus van beton.")
    }

    chapter("Beton: ruggengraat en transitie") {
        // The chapter's own opening line, set the way the first two chapters open. The
        // draaiboek questions "ruggengraat" in the chapter title; the quote stands either way.
        slide(
            QuoteSlide(
                "\u201C" + chapterMessages[2] + "\u201D",
                boldFont,
                lines = 4
            ),
            title = "We gieten kennis",
            notes = "Opens the third chapter. Frame 3-01 of the client's deck."
        )
        // One column read three times: what concrete's footprint is made of, then concrete's
        // share of the world's emissions, then of the Netherlands'. A band is a slot keyed by
        // name, so the red band is the same band shrinking from 80 to 7 to 1,9 across the clicks
        // and the blues close over it. See Co2Column.
        slide(
            Co2Column(
                states = listOf(
                    ColumnState(
                        "CO\u2082 impact van beton",
                        // PLACEHOLDER: 80 for cement is the client's figure; the four blue bands were
                        // drawn rather than measured and are read off frame 3-02 to the nearest half
                        // — WN is asked for their sizes.
                        bands = listOf(
                            // Cement is the accent; the other four are one structure blue at four
                            // values, not four hues. Reinforcement is not a binder and is not named here.
                            Band("share", "Cement (bindmiddel)", 81.5, wnRed),
                            Band("toeslag", "Toeslagstoffen", 4.0, ColorRGBa.fromHex("B7C6F2")),
                            Band("aanvoer", "Aanvoer grondstoffen", 8.5, ColorRGBa.fromHex("8AA0E6")),
                            Band("energie", "Energieproductie", 1.0, ColorRGBa.fromHex("5E7BDB")),
                            Band("transport", "Transport naar de bouwplaats", 5.0, ColorRGBa.fromHex("3D5AE0"))
                        ),
                        caption = "In beton is cement veruit het meest vervuilende bestanddeel: ongeveer 80% " +
                                "van de totale CO\u2082-uitstoot van het materiaal. Wereldwijd is de " +
                                "cementindustrie verantwoordelijk voor zo\u2019n 5 tot 8% van alle door de " +
                                "mens veroorzaakte CO\u2082-emissies."
                    ),
                    ColumnState(
                        "CO\u2082 impact van beton: wereld",
                        bands = listOf(
                            Band("share", "Wereldwijd: 7% aandeel CO\u2082-uitstoot door beton", 7.0, wnRed),
                            Band("rest", "", 93.0, ColorRGBa.fromHex("4370CE"))
                        )
                    ),
                    ColumnState(
                        "CO\u2082 impact van beton: Nederland",
                        bands = listOf(
                            Band("share", "Nederland: 1,9% aandeel CO\u2082-uitstoot door beton", 1.9, wnRed),
                            Band("rest", "", 98.1, ColorRGBa.fromHex("4370CE"))
                        )
                    )
                ),
                boldPath = boldFont,
                textPath = textFont
            ),
            title = "CO2 impact van beton",
            notes = "Zonder beton geen logistieke hubs, geen productiehallen, geen voedselverwerking " +
                    "op schaal. Click 1: wereldwijd 7% van de CO\u2082-uitstoot door beton; click 2: " +
                    "Nederland 1,9%. The four blue bands are read off the client's drawing, not " +
                    "measured \u2014 to confirm with WN."
        )
        // Three ways to make concrete cleaner, a panel a click: the mix as a scatter, production
        // as the lime cycle, measurement as bars. See ConcreteLevers.
        slide(
            ConcreteLevers(
                populations = listOf(
                    Population(30, wnSky, Vector2(-0.13, -0.02), 0.07),
                    Population(180, wnRed, Vector2(0.0, 0.02), 0.045),
                    Population(90, ColorRGBa.WHITE, Vector2(0.13, 0.0), 0.04)
                ),
                stations = listOf(
                    Station("Kalksteen", "CaCO\u2083", "branden bij 1000 \u00B0C"),
                    Station("Gebrande kalk", "CaO", "blussen met water"),
                    Station("Luchtkalk", "Ca(OH)\u2082", "mengen met zand en water"),
                    Station("Kalkmortel", "", "verharden door carbonatatie")
                ),
                // Burning drives CO₂ off, slaking takes water in, carbonation takes CO₂ in and gives water back.
                exchanges = listOf(
                    Exchange("CO\u2082", 0, entering = false),
                    Exchange("H\u2082O", 1, entering = true),
                    Exchange("CO\u2082", 3, entering = true),
                    Exchange("H\u2082O", 3, entering = false)
                ),
                // PLACEHOLDER: eight descending values read off frame 3-15; the LCA's own data would give real ones.
                bars = listOf(100.0, 81.0, 72.0, 63.0, 58.0, 56.0, 47.0, 43.0),
                levers = listOf(
                    Lever("1. Door samenstelling", "Minder cement, lagere klinker, nieuwe bindmiddelen, zelfs beton zonder cement."),
                    Lever("2. Door productie", "Aandacht voor water, energie en grondstoffen, nul-loos processen, hergebruik van granulaten."),
                    Lever("3. Door meting", "Via de levenscyclusanalyse wordt de CO\u2082-voetafdruk van betonproducten precies in kaart gebracht, per product.")
                ),
                boldPath = boldFont,
                textPath = textFont,
                accent = wnRed,
                blue = wnSky
            ),
            title = "Verduurzamen van beton",
            notes = "Drie niveaus: samenstelling (de wolk scheidt zich vanzelf), productie (de " +
                    "kalkkringloop, click 1), meting (de staven, click 2). The bar values are " +
                    "placeholders read off the client's frame."
        )
        // One element in the round with its register, and the share of it research took out:
        // the beam with the three levers round it, then −30% on it, then the wall at −15%,
        // then the long beam at −20%, the camera pulling back between pieces. See HiddenStory.
        slide(
            HiddenStory(
                title = "Onderzoek om CO\u2082 van betonsamenstellingen te reduceren",
                objects = yardObjects,
                details = openingDetails,
                levers = listOf("Samenstelling", "Productie", "Meting"),
                // Each piece matches its claim: a wall panel for the facade elements, a floor
                // plate for the gewelven, a beam for the reinforced concrete. Before 16 September
                // a beam carried the facade figure and a wall the floor figure.
                reductions = listOf(
                    Reduction("WAND_27", 0.30, "\u201330%", "CO\u2082 in gevelelementen", listOf(
                        "Reductie cementgebruik",
                        "Overstap van CEM I naar CEM II",
                        "Gebruik CEM III A",
                        "Alternatieve samenstellingen"
                    )),
                    // The TT floor plate is the gewelf product; it is modelled standing, so it is
                    // seen edge on. WERKVLOER_2 was tried lying flat and read as a bracket.
                    Reduction("TT-590-2400-120", 0.15, "\u201315%", "CO\u2082 in gewelven"),
                    Reduction("TANDBALK", 0.20, "\u201320%", "CO\u2082 in gewapend beton")
                ),
                boldPath = boldFont,
                textPath = textFont,
                ink = ColorRGBa.fromHex("3D5AE0"),
                accent = wnRed
            ),
            title = "Verborgen verhaal",
            notes = "Elk element \u2013 van gevelpaneel tot gewelf \u2013 heeft een verborgen verhaal: " +
                    "hoeveel CO\u2082 belichaamd is, welke mengsels gebruikt zijn, welke verbeteringen " +
                    "mogelijk zijn. Reducties tot 30% in gevelelementen, 15% in gewelven, 20% in " +
                    "gewapend beton, gevolgd via LCA (frame 4-15's note)."
        )
        // One element's second life across the pane: stand, lie down, break, sieve, stand
        // again — a stage a click, the story travelling left as it goes. See SecondLife.
        slide(
            SecondLife(
                objects = yardObjects,
                panel = "WAND_27",          // the doorway is the ear-clipping proof
                slab = "WERKVLOER_2",       // a floor that is modelled lying flat; PREDAL stands upright in the export
                boldPath = boldFont,
                textPath = textFont,
                ink = ColorRGBa.fromHex("3D5AE0"),
                accent = wnRed
            ),
            title = "Recyclage",
            notes = "Reststromen kunnen weer grondstof worden: puin dat ter plaatse wordt gebroken, " +
                    "gezeefd en opnieuw ingezet, waardoor zowel de herkomst als de kwaliteit " +
                    "controleerbaar blijven en extra transport wordt vermeden."
        )
        slide(
            Swivel01Slide(),
            title = "Panels turning",
            notes = "A wave crosses the row once on arrival and settles — 7.5s, off the " +
                    "slide's own frame count rather than a clock."
        )
        takeaway(3, "We hebben gekeken naar beton en zijn onderdelen. Straks brengen we die " +
                "onderdelen samen in het verhaal van The Circle.")
    }

    chapter("The Circle: een nieuwe manier van denken") {
        // Two sentences, so six lines rather than the four the shorter quotes take.
        slide(
            QuoteSlide(
                "\u201CWe bouwen vandaag, met het oog op wie na ons komt. Elke kolom, elke " +
                        "balk, elke plaat is niet alleen een drager van lasten, maar ook een " +
                        "drager van verantwoordelijkheid, kansen en toekomst.\u201D",
                boldFont,
                lines = 6
            ),
            title = "We bouwen vandaag",
            notes = "Opens the fourth chapter. Frame 4-01 of the client's deck."
        )
        // The mark drawn as a loop of the catalogue's pieces, landing along it one after
        // another and turning. The mark's own path is the one thing it waits on: until the
        // svg arrives a plain ring open at the top stands in. See RingOfPieces.
        slide(
            RingOfPieces(
                objects = yardObjects,
                path = Env["SLIDES_MARK"]?.takeIf { it.isNotBlank() }?.let { File(it) },   // PLACEHOLDER until the WN ring arrives as svg
                boldPath = boldFont
            ),
            title = "The Circle in elementen",
            notes = "The Circle is een concept dat laat zien hoe een gebouw m\u00E9\u00E9r kan zijn dan " +
                    "een optelsom van materialen. No clicks: the pieces land along the ring on the " +
                    "slide's own clock. Waiting on the WN ring as svg (SLIDES_MARK)."
        )
        // Every silhouette of the front sheet once, packed at one height in the red, and dealt
        // out again on the click. See HundredElements.
        slide(
            HundredElements(sheet = hundredSheet, boldPath = boldFont, ink = wnRed),
            title = "100 elementen",
            notes = "The Circle gaat uit van vaste betonelementen, een modulair stramien en een " +
                    "digitale ontwerptool. Click 1 reshuffles the field. The list of eight " +
                    "(vaste afmetingen, stramien 12 op 24 m, webtool, plannen, CO\u2082, demontabel, " +
                    "hergebruik, voorraad) is the speaker's."
        )
        // The travelling slabs that stood here — the six countries, the sites, the people and
        // the companies — are the later clicks of "De cijfers" in chapter 1 since 16 September.

        // DRAFT. The group's factories on a pixel map of Benelux, a pixel each, and a click in
        // on one after another with its name and address beside it. Borders are Natural Earth
        // and the addresses are geocoded, both collected on first load — see PixelMap.
        slide(
            PixelMap(
                // Europe around the factories: 1050 km of ground high, centred on the middle of
                // the cluster rather than on a stated point, so the list decides where it sits.
                shots = listOf(europe, FactoryVisit(0), FactoryVisit(1), FactoryVisit(2), europe),
                factories = factories,
                offices = offices,         // the rest of the group as small blue dots: the Netherlands is an office, not a plant
                background = ColorRGBa.fromHex("BFD2EE"),   // the sea blue, so it reads as sea against the grey land
                dot = wnRed,
                picked = wnBlue,           // the selected factory's dot turns blue...
                pickedPulse = wnSky,       // ...and pulses toward the lighter blue
                // For reference, the way Google Maps names a city. Placed on their market squares.
                cities = listOf(
                    City("Brugge", 3.2242, 51.2089),
                    City("Antwerpen", 4.3997, 51.2213),
                    City("Brussel", 4.3525, 50.8467),
                    City("Namen", 4.8675, 50.4669),
                    City("Nancy", 6.1833, 48.6936),
                    City("Den Bosch", 5.3036, 51.6886)
                ),
                boldPath = boldFont,
                textPath = textFont
            ),
            title = "De fabrieken",
            notes = "Every factory a red dot on a grey map. Then in on the first three " +
                    "in the list, a click each, and out again."
        )
        // The Circle itself, out of the IFC: the building as red lines on a dotted ground, the
        // three labels swapping on the click. tools/ifc_to_tri.py makes data/circle from the
        // IFC in input/. See CircleBuilding.
        slide(
            CircleBuilding(
                title = "Het gebouw uit de webtool",
                callouts = listOf(
                    listOf(
                        // Two or three words a callout: a label, not a sentence.
                        Callout("3D-model uit de webtool", Vector3(0.5, 1.0, 0.5)),
                        Callout("Plan, automatisch", Vector3(0.9, 0.5, 0.5)),
                        Callout("CO\u2082 per gebouw, automatisch", Vector3(0.8, 0.0, 0.9))
                    ),
                    listOf(
                        Callout("Demontabel bouwen", Vector3(0.5, 1.0, 0.5)),
                        Callout("Bouwstenen hergebruikt", Vector3(0.9, 0.5, 0.5)),
                        Callout("Bouwstenen op voorraad", Vector3(0.8, 0.0, 0.9))
                    )
                ),
                boldPath = boldFont,
                textPath = textFont,
                line = wnRed,
                dots = ColorRGBa.fromHex("3D5AE0")
            ),
            title = "Gebouw uit de webtool",
            notes = "In plaats van lineair bouwen wordt een circulaire gedachte geschetst: bouwen met " +
                    "demontabele elementen, voor de volgende gebruiker. Click 1 swaps the label set."
        )
        // The ordinary process against the process with reuse: two columns of steps, the copy
        // taking its steps across, the removed steps turning red, disassembly sliding in. See
        // ReductionProcess.
        slide(
            ReductionProcess(
                // The same five steps, in the same words, as the life cycle in chapter 3.
                stages = listOf(
                    "Grondstofwinning",
                    "Transport naar de fabriek",
                    "Productie",
                    "Transport naar de bouwplaats",
                    "Montage"
                ),
                reused = 3,
                disassembly = "Demonteren van de bouwblokken",
                shares = "80%" to "20%",     // the client's split
                boldPath = boldFont,
                textPath = textFont,
                blue = ColorRGBa.fromHex("3D5AE0"),
                red = wnRed,
                stepCues = List(3) { markCue }      // a mark a step
            ),
            title = "Reductie carbon footprint",
            notes = "In plaats van lineair bouwen (ontwerpen \u2013 produceren \u2013 bouwen \u2013 " +
                    "afbreken), wordt een circulaire gedachte geschetst. Click 1 copies the process; " +
                    "click 2 marks the 80% reuse does away with; click 3 puts disassembly in their place."
        )
        // Demountable building in the block city: catalogue pieces on the roofs, lifted off on
        // the click and set down on other roofs. The city is the backdrop's own renderer held
        // inside the slide, so the pieces cast into its shadows. See DisassemblyCity.
        slide(
            DisassemblyCity(
                objects = yardObjects,
                picks = listOf("WAND_27", "TT-590-2400-120", "KOLOM", "TANDBALK", "PREDAL", "BALK")
            ),
            title = "Demontabel in de stad",
            notes = "Bouwen met demontabele elementen; bouwen met het idee dat onderdelen later " +
                    "hergebruikt kunnen worden; bouwen voor de volgende gebruiker, niet alleen " +
                    "voor de eerste. Click 1 lifts every piece to another roof."
        )
        // The framework of chapter 2 again, this time saying what each piece stands for in The
        // Circle: a piece a click with its notes beside it and the others dimmed, then all three
        // in full, then the joint closed. The same drawer and the same colours, so the callback
        // reads as one — the notes are the only thing new, and they are content stated here.
        slide(
            EsgFramework(
                title = "The Circle & ESG principes",
                notes = listOf(
                    listOf("Minder materiaal", "Hergebruik", "Lagere CO\u2082-voetafdruk"),
                    listOf("Toekomstbestendig", "Nieuwe functies, nieuwe noden, nieuwe generaties"),
                    listOf("Systematiek", "Meetbaarheid", "Herhaalbaarheid")
                ),
                fontPath = boldFont,
                ink = wnRed
            ),
            title = "The Circle en ESG",
            notes = "Environment: minder materiaal, lagere CO\u2082-voetafdruk, hergebruik. " +
                    "Social: toekomstbestendige gebouwen die zich kunnen aanpassen aan nieuwe " +
                    "functies, noden en generaties. Governance: systematiek, meetbaarheid en " +
                    "herhaalbaarheid in ontwerp en uitvoering. Then all three, then the joint closes."
        )
        takeaway(4, "Laten we dit nu naast concrete projecten leggen. Welke vragen roept dit op " +
                "voor uw eigen praktijk?")
    }

    // The yard: the catalogue's pieces in the round, side by side at one height on a
    // white ground, each turning slowly on the spot under a low sun as the row drifts
    // across the wall. The second course wall, after the belt — the same catalogue the
    // belt shows flat, now as things. See YardScene in backdrop-drawers/.
    backdrop(
        // The house pair on black: red pieces, a navy shadow that reads against the black —
        // the deeper navy for the overlap was invisible on it — and the row tightened so three
        // or four pieces stand in view rather than one adrift. Measured on the export of 15
        // September the wall was 97% black. The second course's bed.
        YardScene(objects = yardObjects, ink = wnRed, paper = ColorRGBa.BLACK,
            shadow = ColorRGBa.fromHex("2E4A8A"), shadowDeep = wnBlue, gap = 0.012, sound = mainBed),
        title = "Yard",
        notes = "The pieces laid end to end at one height, one colour with a long sharp " +
                "shadow, passing slowly and each turning on its own axis."
    )

    // The gallery: the whole catalogue on a dense grid, every piece once, all turning slowly
    // with the phase rippling across the field — calm, because nothing starts or stops.
    // See GalleryMode in backdrop-drawers/.
    backdrop(
        GalleryMode(
            objects = yardObjects, sound = starterBed,              // no picks: the whole catalogue
            // Inverted: white pieces on black, the shadow in the house red. Where two shadows
            // lap, a deeper red — black would drop the overlap into the ground.
            ink = ColorRGBa.WHITE, paper = ColorRGBa.BLACK,
            shadow = wnRed, shadowDeep = ColorRGBa.fromHex("A00000")
        ),
        title = "Gallery",
        notes = "All 115 pieces once each on a 23x5 grid on the white ground, every one " +
                "turning slowly, the phase rippling across the field as a sine."
    )

    // The block city: a field of cubic buildings in isometric, drawn as an architectural
    // model — thin outlines, ruled faces, four flat greys and one hard shadow across it all —
    // panning slowly and swaying a little either side of the isometric angle. A course wall
    // like the yard and the gallery, and the only one with nothing of the catalogue in it.
    // See BlockCity in backdrop-drawers/.
    backdrop(
        BlockCity(
            // One day a period: the sun goes right round, low at first — long shadows lying
            // across half the city — and high by the middle. `sun = 0` holds the light still
            // at `light`, which is the reference's picture.
            sun = 1
            // The isometric: the drawer's own defaults, and the reference's picture. A lens
            // was tried here — `pitch = 25.0, lens = 14.0, unit = 150.0, horizon = 0.55` —
            // and it does make the near blocks sweep past the far ones, which the parallel
            // projection cannot; but it reads as a camera in a city rather than as a drawing
            // of one, and the drawing is what this wall is for. The option stands in the
            // drawer for a wall that wants it.
        ),
        title = "Block city",
        notes = "An isometric city of blocks under one hard light, drifting across the wall " +
                "and swaying, the sun going once round; loops every four minutes."
    )

    // The same city with the grid off: a massing model rather than a drawing, standing on
    // BlockCity with only what the plain wall needs stated — a shallower day and a wider step
    // between roof and wall. See CityBlock02 in backdrop-drawers/.
    backdrop(
        CityBlock02(
            // The house pair on white, as the brand draws it: navy for the walls and for the
            // shadows alike, so the two merge into one mass; white for the ground and the
            // roofs alike, so a low block is a white shape bounded by navy; one block in
            // twenty-five in the red. No outlines — the picture is flat shapes — and the
            // walls away from the light a shade deeper than the navy, so a corner still
            // reads when the sun is behind the camera.
            paper = wnWhite, top = wnWhite,
            lit = wnBlue, shade = wnBlue,
            unlit = wnBlue.shade(0.82), unlitAcross = wnBlue.shade(0.91),
            line = 0.0,
            accent = wnRed, accents = 0.04
        ),
        title = "Block city, plain",
        notes = "The block city with every face plain, in the house colours: navy masses on " +
                "white with a red block here and there. Loops every four minutes."
    )

    // DRAFT. A pictogram chart on a wall going from the house red to the navy and back: a
    // column per value, each a stack of one subset piece repeated. The values are
    // placeholders. See PlainScene in backdrop-drawers/.
    backdrop(
        // The ground goes red to navy and back at a wall's pace — it took eight seconds and
        // crossed a muddy purple twice a cycle on the export of 15 September.
        PlainScene(folder = patternFolder, from = wnRed, to = wnBlue, period = 90.0, sound = dessertBed),
        title = "Plain",
        notes = "Draft backdrop. Stacks of subset pieces as a bar chart, on red to navy and back."
    )

    // TRYOUT. A wall of identical recessed cells with the chapter title in it, under a sun going
    // round: the letters are cut shallow and everything else deep. The sun opens square on, where
    // nothing throws a shadow and letter and ground are the same to the pixel — a plain grid drawn
    // by the dark of its holes; as it leans away the deep cells fill with shadow and the words are
    // left standing in the light, and as it comes back square they sink into the grid again.
    // Nothing turns and nothing fades — the shadows turning inside every cell are the only thing
    // that moves. The texts circle, one a turn. M skips one, P switches to soft shadows, T hides the concrete,
    // I inverts the wall, Q/A and W/S change the grid. See ShadowFacade in backdrop-drawers/.
    backdrop(
        ShadowFacade(
            masks = shadowMasks,                    // one form a turn — circle, square, triangle — swapped while the grid is plain; M skips one
            drift = 0.3,                            // each form passes along the wall as it is revealed, a cell at a time
            depth = 0.6, inkDepth = 0.06,           // deep holes, letters barely cut
            hollow = 0.22,                          // every floor darker than the face: the grid, before any shadow
            ramps = 0.0, blades = 0.0, stepShare = 0.0, wedges = 0.0,   // every cell a plain recess
            rows = 40, aspect = 0.5,                // 27px cells: the titles are set large, their strokes some 40px
            mullion = 0.07, ledge = 0.07,           // a pixel of frame a side at this cell size
            // One sun. `lights = 3, lead = 6.0, contrast = 0.6, depth = 0.45` sends three past instead.
            orbit = true, low = 40.0, high = 90.0, hold = 1.3, period = 12.0,   // opens square on, a shorter bare seam than the 2.0 it had; twelve seconds a text
            jitter = 0.8, depthJitter = 0.35,       // every slot a little different, so the reveal ripples
            sound = mainBed                         // the first course's bed
        ),
        title = "Shadows",
        notes = "Tryout. A relief facade in black and white under a swinging light, with a circle, a square and a triangle cut into it one a turn, each further along the wall, read in its shadows."
    )

    // TRYOUT. The shadow wall's reveal with the light taken out and a fold put in, in black and white:
    // a grid of panels folded down their middle, the ground folding while the letters stay flat and
    // grow solid. Plain at the seam, where the text is swapped. See Skew3D in
    // backdrop-drawers/. M skips a text, I inverts, Q/A and W/S change the grid.
    backdrop(
        Skew3D(
            masks = skewMasks,
            rows = 28                               // the titles are set large, their strokes some 40px: panels big enough to read as folds
        ),
        title = "Skew 3D",
        notes = "Tryout. Folded panels in black and white; the raised letters are read in long shadows sweeping across the title."
    )

    // TRYOUT. Skew 3D kept to three rules: one shape repeated, one light laid along its fold, one
    // movement — the shadows reaching out and back, once across the text. No wave down the rows, no
    // swinging sun. See Skew3D in backdrop-drawers/.
    backdrop(
        Skew3D(
            name = "Skew 3D, simple",
            masks = skewMasks,
            rows = 28,
            ripple = 0.0,                           // one shape, repeated
            alignLight = true,                      // one light, along the fold
            shadowLength = 4.0, spread = 1.0, hold = 1.0   // one movement, once across the text
        ),
        title = "Skew 3D, simple",
        notes = "Tryout. One shape, one light along its fold, one sweep of shadow across the title."
    )

    // TRYOUT. The same facade setting type: every cell a tilted panel, turned the other way
    // where the mask has ink, so the words appear with the light to one side, sink into the
    // wall as it passes overhead and come back inverted.
    backdrop(
        ShadowFacade(
            name = "Shadow type",
            mask = shadowMask,
            rows = 36, aspect = 0.5,   // small cells, so a letter is several across
            depth = 0.3, mullion = 0.05, ledge = 0.05,
            sound = mainBed            // the second course's bed, the same as the first's
        ),
        title = "Shadow type",
        notes = "Tryout. The chapter title set in the facade by which way its panels turn."
    )

    // TRYOUT. The type wall with soft edges: tones grade and a cell half over a letter gets a
    // panel half turned, so the words fade in and out. The shadows stay hard.
    backdrop(
        ShadowFacade(
            name = "Shadow type, soft",
            mask = shadowMask,
            rows = 36, aspect = 0.5,
            depth = 0.3, mullion = 0.05, ledge = 0.05,
            edge = 1.0
        ),
        title = "Shadow type, soft",
        notes = "Tryout. The shadow type wall with soft tones and soft letter edges, hard shadows."
    )

    // The sectors, last before the exit: the whole wall, no card, the set laid out at once
    // and then each case taken full frame in turn. A scene rather than a backdrop because
    // it is part of the talk rather than a wall around it — see CaseStudy in scene-drawers/,
    // and Scene for what a third kind of slide costs the engine.
    scene(
        CaseStudy(
            objects = yardObjects,
            // **A piece stands for a sector, not for itself**: the title is what is
            // built, the note is the element that builds it, and the image is the project
            // itself, blended in as the camera closes. Six of these sectors are the
            // draaiboek's own words — the notes to slides 4, 39 and 40 name scholen,
            // logistieke centra, voedingsbedrijven, zwembaden, distributiehubs and
            // productiehallen — and the rest are a proposal to be corrected. The three
            // placeholders are dealt round until there is a photograph a sector.
            cases = listOf(
                Case("WAND_27", "Industriebouw", "Wandpaneel met sparing",
                    "data/case-studies/placeholder01.jpg"),
                Case("TC-BALK", "Logistiek & distributie", "TC-balk, 23,8 m overspanning",
                    "data/case-studies/placeholder02.png"),
                Case("TT-590-2400-120", "Voedingsindustrie", "TT-plaat, 2,4 m breed",
                    "data/case-studies/placeholder03.png"),
                Case("KOLOM", "Agrarische bouw", "Kolom",
                    "data/case-studies/placeholder01.jpg"),
                Case("PREDAL", "Zwembaden", "Breedplaatvloer",
                    "data/case-studies/placeholder02.png"),
                Case("KONZOLE", "Scholen & onderwijs", "Konsole",
                    "data/case-studies/placeholder03.png"),
                Case("TANDBALK", "Utiliteitsbouw", "Tandbalk",
                    "data/case-studies/placeholder01.jpg"),
                Case("FUND", "Woningbouw", "Prefab poer",
                    "data/case-studies/placeholder02.png"),
                Case("PAAL", "Sport & recreatie", "Geheide fundering",
                    "data/case-studies/placeholder03.png"),
                Case("RT-BALK", "Retail", "Randbalk",
                    "data/case-studies/placeholder01.jpg"),
                Case("X-BALK", "Datacenters", "Zware overspanning",
                    "data/case-studies/placeholder02.png"),
                Case("KANTA", "Kantoren", "Randafwerking",
                    "data/case-studies/placeholder03.png"),
            ),
            columns = 4, rows = 3,   // more, smaller cells: six on this wall left it 89% white, and a full-height
                                     // overview would make the zoom a zoom out
            fill = 1.25,             // the pieces fill their cells and lap a little; the width cap allows for the scatter's largest
            // A quieter scatter than the 0.45 it had: the floating pieces read as chaotic on the
            // export of 15 September, and at 0.22 they keep their float and find a grouping.
            fills = 0.88, collage = 0.22,
            fontPath = boldFont,
            // A collage rather than one ink. The navy is deliberately *not* in it: it is
            // what the shadows are drawn in, and a navy piece beside a navy shadow reads
            // as a hole rather than as a colour — measured, it was 10% of the wall and
            // the eye took it for one thing.
            palette = listOf(wnRed, wnAmber, wnTeal, wnCoral, wnSky),
            // On black: the navy shadow still reads against it, and where two lap a deeper navy.
            paper = ColorRGBa.BLACK, lettering = ColorRGBa.WHITE,
            shadow = wnBlue, shadowDeep = ColorRGBa.fromHex("12244A"),
            highlights = 3           // only the first three are taken full frame
        ),
        title = "De sectoren",
        notes = "A collage of the sectors, each stood for by a precast element; a click " +
                "takes each full frame in turn, the camera pulling back between them."
    )
    // The case studies as blueprints, after the sectors: a project a click, its plan on the left
    // projector and a second drawing on the right, the facts in the corners of both. Drawings cut
    // out of data/blue-prints/blue-prints.pdf into SLIDES_BLUEPRINTS. PLACEHOLDER: the function,
    // location and realisation are ??? in the blueprints for most; Kievits II's are the sketch's.
    scene(
        slideshow.drawers.CaseBlueprints(
            cases = listOf(
                slideshow.drawers.BlueprintCase("van Cranenbroek", "van-cranenbroek-a.png", "van-cranenbroek-b.png"),
                slideshow.drawers.BlueprintCase("VGP Nijmegen", "vgp-nijmegen-a.png", "vgp-nijmegen-b.png",
                    location = "Nijmegen"),
                slideshow.drawers.BlueprintCase("Panattoni Waalwijk", "panattoni-waalwijk-a.png", "panattoni-waalwijk-b.png",
                    location = "Waalwijk"),
                slideshow.drawers.BlueprintCase("Kievits II", "kvitis-ridderkerk-a.png", "kvitis-ridderkerk-b.png",
                    function = listOf("10.000 m² magazijn", "24 laaddocks"),     // a builder's words, not a department store
                    location = "Ridderkerk", realisation = "30 weken",
                    // The composed views after the overview. PLACEHOLDER regions: the mechanism
                    // is in place, the facade details worth showing are WN's to name.
                    details = listOf(
                        slideshow.drawers.BlueprintDetail('a', 0.05, 0.05, 0.4, 0.4, "Detail: de hoek van de plattegrond (PLACEHOLDER)"),
                        slideshow.drawers.BlueprintDetail('b', 0.55, 0.3, 0.4, 0.4, "Detail: de gewelven (PLACEHOLDER)")
                    )),
                slideshow.drawers.BlueprintCase("Panattoni Sas van Gent", "panattoni-sas-van-gent-a.png", "panattoni-sas-van-gent-b.png",
                    location = "Sas van Gent"),
                slideshow.drawers.BlueprintCase("Prohuis", "prohuis-a.png", "prohuis-b.png"),
            ),
            folder = File(Env["SLIDES_BLUEPRINTS"] ?: "data/case-blueprints"),
            boldPath = boldFont,
            textPath = textFont
        ),
        title = "Case studies",
        notes = "The case studies as blueprints: a project a click, plan on the left projector and a second " +
                "drawing on the right, name, function, location and realisation in the corners. " +
                "PLACEHOLDER: most facts are still ??? in the blueprints."
    )
    // The ending: one sentence, one action, and a code to scan. PROPOSAL: the sentence is the
    // fourth chapter's own line, the action and the address are placeholders until WN says
    // what the call to action is. See EndingScene.
    backdrop(
        EndingScene(
            sentence = "We bouwen vandaag, met het oog op wie na ons komt.",
            // "Ontdek The Circle": The Circle is not a place that can be visited, so the earlier
            // "plan uw bezoek" was a false invitation. What the code reaches is still to be
            // confirmed, and the supporting line with it.
            action = Env["SLIDES_ENDING_ACTION"] ?: "Ontdek The Circle.",
            url = Env["SLIDES_ENDING_URL"] ?: "https://www.willynaessens.nl",
            boldPath = boldFont,
            textPath = textFont,
            accent = wnRed,
            // The exit has sound: the arrival's own room, back under the last thing on the
            // wall. The export of 15 September went silent at the ending.
            sound = ambience
        ),
        title = "Ending",
        notes = "The call to action, before the exit wall. Sentence, action and address are " +
                "proposals: SLIDES_ENDING_ACTION and SLIDES_ENDING_URL set the last two."
    )

    // 22:00, Uitloop: the opening scene with the colours the other way round — the
    // same two elements in the same places, red then navy — which is how the
    // draaiboek draws the exit against the arrival.
    backdrop(
        ObjectScene(
            "Closing", backdropSheet,
            objects = listOf(7, 3),
            palette = listOf(wnRed, wnBlue),
            // On the opening's black rather than on paper, so the two ends of the evening rhyme:
            // the same two elements in the same places, the colours swapped, on the same ground,
            // under the same room — the bed carries over from the ending without a dip.
            paper = ColorRGBa.BLACK,
            sound = ambience
        ),
        title = "Closing scene",
        notes = "Uitloop. The opening scene, colours swapped. The last -> lands here."
    )
}

/**
 * The show: the Willy Naessens evening as one deck on the two-projector wall — backdrops, chapter cards and slides, clicked through, with the organizer (SLIDES_ORGANIZER=true) to arrange it.
 */
fun main() {
    // The furniture picks up the family here, before any slide loads — see [Type.file].
    Type.file = textFont
    // The order file, where there is one, says which slides play and in what order; the
    // Kotlin above still says what each slide *is*. See Order.kt.
    // The modules — the slides still to be built — stand in the deck as pink placeholders
    // showing the frames of the client's deck they stand for, so the talk can be clicked
    // through whole and the gaps are visible from the organizer. See Modules.kt.
    present(show.withEnv().withModules().arrangedFromFile())
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
        until = Env["${prefix}_UNTIL"] ?: settings.until,
        debug = Env["${prefix}_DEBUG"]?.let { Env.boolean("${prefix}_DEBUG") } ?: settings.debug,
        nameplate = Env["${prefix}_NAMEPLATE"]?.let { Env.boolean("${prefix}_NAMEPLATE") } ?: settings.nameplate,
        autoStep = Env["${prefix}_AUTOSTEP"]?.toDoubleOrNull() ?: settings.autoStep,
        cues = Env["${prefix}_CUES"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: settings.cues,
        cuesAuto = Env["${prefix}_CUES"]?.trim().equals("auto", ignoreCase = true),
        hold = Env["${prefix}_HOLD"]?.toDoubleOrNull() ?: settings.hold,
        holdWide = Env["${prefix}_HOLD_WIDE"]?.toDoubleOrNull() ?: settings.holdWide,
        record = Env.boolean("${prefix}_RECORD"),
        fps = Env["${prefix}_FPS"]?.toIntOrNull() ?: settings.fps,
        duration = Env["${prefix}_DURATION"]?.toDoubleOrNull() ?: settings.duration,
        video = Env["${prefix}_VIDEO"]?.takeIf { it.isNotBlank() } ?: settings.video,
        mix = Env["${prefix}_MIX"]?.let { Env.boolean("${prefix}_MIX") } ?: settings.mix,
        stills = Env.boolean("${prefix}_STILLS"),
        sound = Env["${prefix}_SOUND"]?.let { Env.boolean("${prefix}_SOUND") } ?: settings.sound,
        organizer = Env["${prefix}_ORGANIZER"]?.let { Env.boolean("${prefix}_ORGANIZER") } ?: settings.organizer,
        organizerPort = Env["${prefix}_ORGANIZER_PORT"]?.toIntOrNull() ?: settings.organizerPort,
        order = Env["${prefix}_ORDER"]?.takeIf { it.isNotBlank() } ?: settings.order,
        modules = Env["${prefix}_MODULES"]?.takeIf { it.isNotBlank() } ?: settings.modules,
        intents = Env["${prefix}_INTENTS"]?.takeIf { it.isNotBlank() } ?: settings.intents,
        feedback = Env["${prefix}_FEEDBACK"]?.takeIf { it.isNotBlank() } ?: settings.feedback,
        midi = Env["${prefix}_MIDI"]?.takeIf { it.isNotBlank() } ?: settings.midi,
        references = Env["${prefix}_REFERENCES"]?.takeIf { it.isNotBlank() } ?: settings.references,
        concrete = Env["${prefix}_CONCRETE"]?.takeIf { it.isNotBlank() } ?: settings.concrete,
        concreteOn = Env["${prefix}_CONCRETE_ON"]?.let { Env.boolean("${prefix}_CONCRETE_ON") } ?: settings.concreteOn,
        concreteMix = Env["${prefix}_CONCRETE_MIX"]?.toDoubleOrNull() ?: settings.concreteMix,
        concreteScale = Env["${prefix}_CONCRETE_SCALE"]?.toDoubleOrNull() ?: settings.concreteScale
    )
)
