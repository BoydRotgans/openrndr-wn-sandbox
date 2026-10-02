# Meeting 30 September 2026: tasks per slide

The walkthrough of 30 September, turned into work, slide by slide in running order
(`show-order.json`). Under each slide: what was said, with the time in the recording (see
[the transcript](meeting-30-sep.md)), and then what it comes to.

Each task is tagged with the kind of work it is:

- **Wall**: a change to what is drawn or how the slide behaves. Boyd.
- **Script**: Erik's words. Once he has written them, the subtitles and voice lines on that slide
  follow (`show-subtitles.json`, both tracks).
- **Check**: a fact or a piece of content to get from Willy Naessens. Mostly through Roma, from Jos.
- **Sound**: for the sound designers.
- **Event**: production. Roma.

Where a task reads more into the meeting than was literally said, it is marked *Reading:*. Those
are the ones to confirm with Erik before building.

---

## The evening as a whole

Four things said in the meeting change the shape of the evening rather than one slide.

1. **The aperitif happens before the room.** Guests get a drink and the amuse in the reception
   room, 17:30–18:00, and come in afterwards (05:23–13:32). So the Aperitif moment no longer has a
   course in it.
   - [ ] **Wall:** take the aperitif out of the Aperitif moment and out of the programme list (see
     `het-programma` and `sketch-flow-v2…`).
2. **There is a fourth course, after chapter 4.** Roma (1:06:08, 1:16:04): after The Circle comes a
   short summary, then "nog een gerecht, in de vorm van een cirkel", then the cases, then an
   introduction to dessert. The order goes straight from chapter 4 to the cases, so this course has
   no wall.
   - [ ] **Wall:** add a moment between `kernboodschap-4` and `case-studies`, with a course wall and
     a playlist.
   - [ ] **Event:** get the course names from StudioBuik's menu so the programme, the moments and
     the menu use the same words. The room lost count at 1:05:33.
3. **Every course opens with Els, then the dinner.** Els (StudioBuik) introduces each course over
   its wall. Roma wanted the music to come up only once she has finished; Boyd said on a click
   (34:55–35:09).
   - [ ] **Wall:** give every course wall a quiet first state for Els, and bring the dinner music
     up on the click.
   - *Reading:* one mechanism on the course moments, rather than a fix per wall.
4. **Erik's text leads, and the additions get written into it** (23:53). Most Script tasks below
   are his. Then Roma plans a timing rehearsal (23:35).

---

## Arrival

### `opening-scene`

Said: guests find their seats to this wall and its music, about a quarter of an hour (13:35–14:18).

- No change to the wall.
- **Event:** times as said. Guests in the reception room 17:30–18:00, seated at 18:00, a quarter of
  an hour to walk through; hosts there at 17:00 (09:08–11:19).

## Opening

### `who-is-speaking`

No notes.

### `het-programma`: the programme, and Els's introduction

Said: after Erik has named the four chapters, Els introduces herself and StudioBuik. She explains
the question they were given, "breng de toegevoegde waarde tot uitdrukking in een gerecht", and
each course then links back to Erik's text (00:19–04:57, 14:43–15:11). Erik's line for her close:
"…in de beleving van de toegevoegde waarde van Willy Naessens" (15:06). It should be short.

- [ ] **Wall:** fill in Els's name and role on the guest name tag on the last click. It still reads
  `[Naam]` / `[Functie]` ([Slideshow.kt:1176](../src/main/kotlin/slideshow/Slideshow.kt#L1176)).
- [ ] **Wall:** bring the programme list in line with the new evening
  ([Slideshow.kt:1157](../src/main/kotlin/slideshow/Slideshow.kt#L1157)):
  - drop "Aperitief" after "Opening";
  - add the course after chapter 4;
  - make chapter 4 "…een nieuwe manier van denken en doen". Erik wants that phrase "al vanaf het
    begin", meaning in this introduction too (1:09:40).
- [ ] **Script:** line E ("een nieuwe manier van denken") gets "en doen", and line B gets
  "nauwelijks nog" (see the next slide).
- [ ] **Script:** line F promises "ruimte voor uw vragen". Erik said there is no interactive part at
  the cases (1:15:33). Decide which is true.
- [ ] **Event:** brief Els. Who writes her text is open (36:48).

### `sketch-flow-v2-out-of-a-concrete-block` (Aperitif moment)

Said: with the aperitif served beforehand, "deze gaat er dus uit". Roma likes it, and Boyd suggested
moving it to the voorgerecht (15:17–15:35). Erik: these elements are a mix of what all the factories
make, so it shouldn't be presented as The Circle (15:40–16:13).

- [ ] **Wall:** take it out of the Aperitif moment. Either it becomes the opening course's wall
  (before, or instead of, `shadow-mosaic`), or it goes on the shelf.
- [ ] **Wall:** with the wall gone, the moment holds only the transition into chapter 1. Rename it
  to suit, and rename `music("Aperitif", …)` with it, since the music is keyed on the moment's name
  ([Slideshow.kt:1048](../src/main/kotlin/slideshow/Slideshow.kt#L1048)).

### `course-transition-to-chapter-1`

Said: on the click, the music stops, the lights go down, and the wall waits for Erik (17:01–17:27).
On the click into chapter 1: "gaat het niet goed… ik zie dat er wat dingen misgaan" (17:28–17:45).

- [ ] **Wall:** reproduce the click from the transition into the chapter 1 opening (film it with
  `SLIDES_START`/`SLIDES_UNTIL` around the two slides) and fix what goes wrong. The rest of the
  transition was received fine.

---

## Chapter 1: De wereld van bouwen

### `hoe-bouw-je-een-wereld`: the opening and its quote

Said: on "licht genoeg om de toekomst niet te belasten", Anouk: "Belasten doe je altijd." Erik
agreed and read it as "nauwelijks nog te belasten"; Roma repeated it (19:07–19:31). Boyd is still
adjusting the audio (19:02).

- [ ] **Wall + Script:** change the opening question to "…maar licht genoeg is om de toekomst
  nauwelijks nog te belasten?". It is `chapterMessages[0]`
  ([Slideshow.kt:869](../src/main/kotlin/slideshow/Slideshow.kt#L869)), which the programme and this
  wall both read. Then update the subtitle lines that say it (`het-programma` B,
  `hoe-bouw-je-een-wereld` B, on both tracks), render the voice lines again, and check the line
  breaks on the wall.
  - *Reading:* agreed in the room, but confirm the exact words with Erik.
- [ ] **Sound:** finish the audio on this opening.

### `the-catalogue-city`

Said: Boyd: the zoom ends on one element "uit een catalogus van honderd. Dit refereert misschien al te
veel naar The Circle." Erik will look at it (19:51–20:19).

- [ ] **Script:** decide with Erik whether chapter 1 already mentions the catalogue of a hundred.
  - *Reading:* say "één element" here, and keep "100 elementen" for chapter 4, where The Circle is
    introduced.

### `everything-a-build-answers-to`

Said: Erik read his text over it. Roma would like to time it, "maar daar zijn we nog te vroeg voor"
(21:06–22:21).

- No change to the wall.
- **Event:** a pre-rehearsal from the link to the presentation (22:21).

### `the-globe`

Not discussed.

### `verticale-integratie`

Said: Erik's text: design and engineering, 13 factories, own groundworks, foundations, transport and
assembly, "koop je rechtstreeks in bij de fabriek" (22:40). Add "eigen beheer en after sales" (24:13).
"Volgens mij hebben we ook eigen grond", to check with Jos (24:26).

- [ ] **Wall:** change the last box, "Eigen after sales", to "Eigen beheer en after sales"
  ([Slideshow.kt:1285](../src/main/kotlin/slideshow/Slideshow.kt#L1285)). It's a rename, so the
  states and sound cues stay the same.
- [ ] **Check (Jos):** does the group have "eigen grond"?
  - *Reading:* land the group owns to build on, which would be an eleventh box. A box is a click,
    so the sound design's `verticale-integratie` A–J would then need a cue for the new state.
  - If Jos says the existing "Eigen grond- en omgevingswerken" already covers it, nothing changes.

### `de-cijfers`

Said:
- Netherlands: 25 to 30 projects a year, ± 150 million turnover, "de andere keer veel meer"
  (25:28–25:43).
- "Werven" is the Belgian word for projects. Erik explains it himself, with the containers that read
  "nog een werf" (26:00–26:32).
- On 9.000: "een teken meer dan, groter dan". Erik floated 10.000; Roma says 9.000 is checked; Erik
  settled on "meer dan 9.000" (26:44–27:16).

- [ ] **Wall:** change "9.000 referenties" to "> 9.000 referenties"
  ([Slideshow.kt:1316](../src/main/kotlin/slideshow/Slideshow.kt#L1316)). The deck's typeface has
  the ">"; write "Meer dan 9.000" if the sign reads poorly on the slab.
- [ ] **Script:** Erik explains "werf" (a building project, in Belgian) when "250 werven" comes up.
- [ ] **Check:** "30 projecten per jaar" against the 25–30 said in the room. Keep it, or make it
  "± 30"?
- [ ] **Check (Jeroen):** only if Erik still wants 10.000. Roma considers 9.000 settled.

### `de-fabrieken`

Said:
- "Voor toekomstige presentaties is dit fantastisch." Seveton and Transwinaton were read apart
  (27:22–28:14).
- Erik: after the factories, light up the six countries Willy Naessens builds in: Netherlands,
  Belgium, France, Luxembourg, Denmark and Sweden (28:17–28:44).
- Denmark is supplied by ship, but a ship doesn't fit here (Anouk, 29:14). Boyd: "dan highlight ik
  de landen", as the last state.

- [ ] **Wall:** add a last state with the six countries lit, the factory dots still on them.
  - *Reading:* the overview frames about 1050 km around the Belgian cluster, which doesn't reach
    Sweden, so this state needs a wider camera.
  - The map can tint countries by their share of factories. This wants a fixed list instead:
    `NLD BEL FRA LUX DNK SWE`.
  - A new state means a new cue letter (I) in the sound design.
- [ ] **Script:** Erik names the six countries over it.

### `projecthighlight-1`: Van Cranenbroek

Said: every chapter has one project (29:42). Erik: "Waar lag de klant hier wakker van, en waar
hebben we hem mee geholpen?", from Jos (30:05). Anouk: the question on the first photo, the solution
on the second (31:29). Erik's example: "Ik lig wakker van dat ik mijn organisatie continuïteit wil
bieden, en dat ik wil groeien" (30:58).

**This applies to all four highlights.**

- [ ] **Check (Jos):** for each project, what the client lay awake over and how Willy Naessens
  solved it. "Dat moet Jos wel echt zeker weten" (31:10).
- [ ] **Wall:** give each highlight a line per state: the question on the first photo, the solution
  on the next. Right now it shows the project name and one fixed line ("Budel").
  - *Reading:* the line under the name changes on the click, rather than more text on the photo.
  - Van Cranenbroek and Kivits have two photos each, so that maps directly. VGP and Intervest have
    three, so decide which photo carries what.
- [ ] **Script:** Erik tells the question and then the solution. The subtitle lines follow, one a
  photo.
- Draft for Van Cranenbroek: continuity and growth for the organisation, Erik's own example. Confirm
  it with Jos.

### `kernboodschap-1`

Said: Boyd proposed a question to take to the table, something to talk about over the voorgerecht.
"Waar lig jij wakker van?" won everyone over, "compacter" if possible. Then StudioBuik: "een
fantastische overgang" (31:45–32:28). No photo of the dish: food photographs badly, and keep the
transition (32:39–33:08).

- [ ] **Wall + Script:** turn the takeaway into the bridge to the course, ending on the question.
  It now reads "We hebben gekeken naar de organisatie achter het bouwen. Na deze gang stellen we de
  volgende vraag: hoe beoordelen we de keuzes die we maken?"
  ([Slideshow.kt:1338](../src/main/kotlin/slideshow/Slideshow.kt#L1338)).
  - *Reading:* shorter, ending on "Een vraag voor aan tafel: waar ligt u wakker van?". The talk
    addresses the room as "u", while the room said "jij"; that's Erik's call.
  - It echoes the highlight just before it, which is why it lands.
- *Reading, not asked:* the same device could close every chapter, with a question for each course.
  Worth proposing.

## Opening course: the voorgerecht

### `shadow-mosaic`

Said:
- The music: "Ik vind de muziek fantastisch… niet meer zo zwaar. Je wordt hier blij van" (33:20–33:26).
- "Bouwen we hier omhoog?" Anouk: it's abstract, and that's fine (33:32–34:43).
- Els introduces the voorgerecht over this wall, and the music comes up on a click once she's done
  (34:48–35:09). She explains what's on the plate in light of what Erik said, and how they arrived
  at it, "nothing more, nothing less" (35:14).
- Course 1 is "gieten": tomato bouillon poured at the table, with a bavette (35:42–37:19).

- [ ] **Wall:** a quiet state for Els, then the music on a click (see "The evening as a whole", 3).
- [ ] **Script:** Erik puts "gieten" into his chapter 1 text so the dish answers it (35:56). The
  alternative is that Els tells how concrete is made (36:37).
- **Event:** meat only in this course (37:05). Wine: "niet moeilijk doen, moet gewoon goed zijn"
  (38:58).

---

## Chapter 2: Waardekader en verantwoordelijkheid

### `esg-is-geen-checklist`

Said: Erik: ESG has always been anchored in the organisation; what's new is the name (39:15).

- [ ] **Script:** that line.

### `esg-beoordelingskader`

Not discussed.

### `co2-prestatieladder`

Said: Boyd shows the 2025 ladder, then the new one (39:57). Erik needs to have the story with him;
Boyd will check it's in the text (41:14–41:19).

- [ ] **Script:** check that Erik's own text carries the ladder: trede 3 in 2025, the three treden
  merged in 2026 so WN is on trede 1, and working towards trede 2. The extended track has it; Erik's
  text must too.

### `co2-behaald`

Said: Erik: since 2020 the group has bought companies, so emissions rose in absolute terms and fell
relative to turnover (40:36). The CO₂ reduction figures to be fact-checked by Roma and Erik
(42:08–42:44).

- [ ] **Check (Erik and Roma):** the figures. They are placeholders read off the client's chart: CO₂
  per indexed turnover 2020–24 and its target, and green power 2024–26
  ([Slideshow.kt:1392](../src/main/kotlin/slideshow/Slideshow.kt#L1392)).
- [ ] **Script:** the absolute-against-relative point. It's why the chart is per indexed turnover.

### `geen-compensatie`

Said: Erik: offsetting costs money, "een boete die we onszelf opleggen" that we want to be rid of,
so we reduce (41:40).

- [ ] **Script:** that line.

### `domino-effect`

"Dit is zo sterk, Boyd" (43:44). No change.

### `esg-social-governance`

Said:
- The family business. Willy's own line, which Erik wants in: "Niet het vastgoed, niet de machines,
  maar de mensen zijn mijn grootste kapitaal." And his birthday on 14 February, when every employee
  got a present (44:01–44:53).
- "Governance" stands over the slide while the words are still about social (45:15–46:34). Roma:
  keep it on social, and the next click is governance, the third pillar (46:41).
- Erik's governance line (47:03).

- [ ] **Wall:** the per-state titles are Social, Social, Governance, Governance, Governance
  ([Crowd.kt:103](../src/main/kotlin/slideshow/slide-drawers/Crowd.kt#L103)), but the third state,
  the lines between the people, is still social in the voice-over. Make them Social, Social,
  Social, Governance, Governance, so governance arrives with the arrow and the one out in front.
- [ ] **Wall or Script:** Willy's quote.
  - *Reading:* as text on the second state, the group around the one, attributed to Willy Naessens.
    Or only spoken. Ask Erik.
- [ ] **Script:**
  - on the arrow: "Ons bestuur is er altijd op gericht dat de mens centraal staat…";
  - on the last state, the globe of people: "in verbondenheid met het totaal" (44:53);
  - the 14 February story Erik tells himself.

### `projecthighlight-2`: VGP Park Nijmegen

Said: VGP owns the park and is always looking for users, so it was built in phases for five
different tenants (47:55–48:23). Check the number of tenants (48:35, 49:16). "Ga je die vraag ook
niet vergeten?" (47:46).

- [ ] **Check (Roma):** the number of tenants and phases.
- [ ] **Wall + Script:** the question and the solution, as for highlight 1.
  - *Reading:* the question is a park that can be let to whoever comes; the solution is five
    buildings for five users.

### `kernboodschap-2`

Read as it stands. No change.

### Course 2 (StudioBuik)

"Ontkisten": opened at the table, with cheese grated over (50:10). Nothing for the wall.

## First course

### `sketch-climb`

Said: Erik: "Zou je dat in de originele Willy Naessens-kleuren willen?" Boyd: "Ik heb het blauw hier
iets lichter gezet", and he doesn't think it's a good effect (50:46–50:53). Anouk: people with
epilepsy can't take this (51:01). With the room lights on it's less intense (51:28–51:32).

- [ ] **Wall:** the house blue is set to `4297FF` in `show-colours.json`, a lighter blue than Willy
  Naessens' `023F88`. That one setting colours every blue in the show, not just this wall. Erik asked
  for the original, so set it back to `023F88` in the organizer's colours.
  - *Reading:* if the lighter blue was chosen because the navy goes dark on the projectors, raise
    that with Erik rather than keeping it quietly.
- [ ] **Wall:** check the wall for flashing. The usual line is no more than three large changes in
  brightness a second (WCAG 2.3.1). Measure the clip; slow it down or lower the contrast if it
  crosses the line, or note that it's been checked.

### `course-transition-to-chapter-3`

The playlist of ten tracks, checked by Roma and liked (51:48–52:06). No change.

---

## Chapter 3: Beton: ruggengraat en transitie

### `we-gieten-kennis`

Said: "Ruggengraat" was questioned and stays (52:56). "Transitie" means improving the product
(53:45). The quote ("We gieten niet alleen beton…") calls back to the pouring in the voorgerecht
(53:08–53:40).

- No change to the wall.
- [ ] **Script:** Erik says what "transitie" means, and makes the callback: "zoals u in het
  voorgerecht zag…".

Not for the evening: Erik's aside on recovering cement from old concrete, which is still in
development and confidential (54:03).

### `co2-impact-van-beton`

Said: no "reality check" that undercuts us: "Dan halen we onszelf onderuit" (55:16–55:34). Erik:
producing concrete causes 9% of global CO₂; the slide says 7, so fact-check it (56:21–56:33). His
order: the fact, the need (we can't build without concrete), and the answer (as sustainably as
possible, with some walls 30% lower) (56:40).

- [ ] **Check:** 9% against 7%, with a source. The slide carries three figures that must agree
  ([Slideshow.kt:1630](../src/main/kotlin/slideshow/Slideshow.kt#L1630)): the first state's caption
  says "cementindustrie 5 tot 8%", and the second "Wereldwijd: 7%".
  - *Reading:* the commonly cited figures are about 7–8% for cement and 8–9% for concrete as a
    whole. Say which one the slide shows.
- [ ] **Wall:** the second state's band and label become the checked figure.
- [ ] **Script:** Erik's order rather than a confession. The extended track opens with "Laten we
  eerlijk zijn over het materiaal" and says "zeven procent"; both follow the decision.

### `levenscyclus-van-betonproducten`

Said: Erik: say briefly what A, B and C stand for (57:21). Roma: "dit vind ik zo moeilijk" (57:29).

- [ ] **Script:** one sentence where the codes first appear: these are the phases a life cycle
  analysis uses. A is the product and its construction, B its use, C its end of life.
- [ ] **Wall (option):**
  - *Reading:* the codes (A1-A3 and so on) are what make it look hard. Let the phase names lead and
    set the codes smaller, or add that key under the columns.

### `levenscyclusanalyse`

Said: Erik: this is what Willy Naessens delivers with a building, the information on materials and
mixes (58:48).

- [ ] **Script:** that line.

### `verduurzamen-van-beton`

Read through. No notes.

### `verborgen-verhaal`

Said: on the wall: 30% less CO₂, through a different binder and different aggregates, "ten opzichte
van vorig jaar". That's an important addition, but it mustn't sound like 30% a year
(59:34–1:00:48). The mix was developed over several years with concrete technologists and has been
used since last year.

- [ ] **Wall:** give the −30% its baseline: since when, and against what
  ([Slideshow.kt:1709](../src/main/kotlin/slideshow/Slideshow.kt#L1709)).
  - *Reading:* "–30% CO₂ in gevelelementen", with "sinds 2025, t.o.v. het vorige mengsel" under
    it. Not "per jaar".
- [ ] **Script:** Erik's sentence (1:00:14): smart mixes, a different binder and different
  aggregates, the same quality, developed over years and used since last year. Extended track line
  B follows.
- Erik also says the 30% on `co2-impact-van-beton` (56:40). Say it in one place only.

### `recyclage`

Said: how to pronounce it (1:01:06–1:01:37). Erik: we also do this with our own waste, "staat dat
ook in het script?" (1:01:46).

- [ ] **Script:** add that Willy Naessens recycles its own waste too.
  - *Reading:* the word after "eigen afval van de" is unclear in the recording, probably the
    factory. Confirm what Erik means.
- *Reading, not asked:* "recyclage" is Flemish; a Dutch room says "recycling". Erik's call for the
  title.

### `projecthighlight-3`: Kivits Ridderkerk

Said:
- WDP must be on it, the developer and investor that lets the building (1:02:02–1:02:24).
- Erik: cold storage is made for concrete. Its thermal mass keeps the temperature stable, and the
  same goes for pharma and defence (1:02:30).
- A study ran a steel building and a concrete one for two years: the energy costs with concrete were
  lower and steadier (1:03:18–1:04:19).
- Climate-adaptive building goes after this (1:08:58).
- Roma, at the cases: the client needed it fast, and many buildings were delivered within a year,
  only possible through vertical integration (1:16:51).

- [ ] **Wall:** add WDP: "WDP · Kivits", or the line "Ridderkerk · koelopslag voor WDP"
  ([Slideshow.kt:1754](../src/main/kotlin/slideshow/Slideshow.kt#L1754)). The case studies already
  name WDP as client.
- [ ] **Script:**
  - thermal mass and the sectors it suits;
  - the energy study;
  - climate-adaptive, heat-stress-resistant building;
  - the question and the solution. The question: it had to be fast. The solution: delivered within
    a year (52 weeks) through vertical integration.
- [ ] **Check (Jos):** find the steel-against-concrete energy study, the factsheet that was left
  out earlier (1:03:59), and the other points Jos raised.

### `kernboodschap-3`

Said: Erik: "We hebben nagedacht over het verduurzamen van ons product, maar ook over hoe we het
circulair maken. Nog duurzamer dus." (1:04:53)

- [ ] **Wall + Script:** replace the takeaway's sentence
  ([Slideshow.kt:1759](../src/main/kotlin/slideshow/Slideshow.kt#L1759)) with that link into The
  Circle.
  - *Reading, draft:* "We hebben ons product duurzamer gemaakt. Nu maken we het circulair: nog
    duurzamer." Confirm the wording with Erik.

## Second course

### `sketch-kit-two-screens-quiet`

Course 3, "drogen en uitharden": a different plate for each guest, and together they form a circle
on the table (1:06:19). Nothing for the wall beyond the Els state.

---

## Chapter 4: The Circle

### `we-bouwen-vandaag`

Said:
- The title becomes "The Circle: een nieuwe manier van denken en doen", from the programme on
  (1:09:30–1:09:50).
- The quote is long but good (1:09:56–1:10:15).
- The music should sound toward the future: "future proof… zodat het geluid opengaat, zodat iets in
  elkaar valt". Boyd: "het geluid van de opbouw" (1:07:30–1:08:12).

- [ ] **Wall:** rename chapter 4 in
  [Slideshow.kt:1770](../src/main/kotlin/slideshow/Slideshow.kt#L1770), the programme entry, and
  **`show-order.json`**, which finds chapters by title (renamed in only one place, the chapter loses
  its card). Check that the longer title still sets well on the opening wall.
- [ ] **Sound:** a brief for chapter 4's opening cue (`we-bouwen-vandaag-A`), and perhaps the
  transition into it: forward-looking, opening up, things clicking into place. The sound of
  assembly.
- The quote stays.

### `the-circle-in-elementen` and `100-elementen`

Said: "In het Willy Naessens-logo, fantastisch" (1:10:26). Boyd: there are still a few round
elements in it; Roma noticed them too (1:10:32–1:10:35).

- [ ] **Wall:** decide on the round pieces.
  - *Reading:* they are fixings and inserts rather than building elements, and they read as discs.
    Take them out of the ring and grid (`circleCatalogue`), or keep them.
  - Check that the count still fits the title "100 elementen".

### `gebouw-uit-de-webtool`

Said: "Start a new circle", as in Willy Naessens' online demo (1:11:01–1:11:15). Erik: the story is
a design within four hours with a cost indication, which saves time (1:11:26). Erik on "demontabel
en herbruikbaar bouwen" (1:11:51; unclear).

- [ ] **Script:** the four-hour design and the cost indication.
- [ ] **Wall:**
  - *Reading:* the label "Demontabel bouwen" becomes "Demontabel en herbruikbaar bouwen". That's a
    rename. Merging it with "Bouwstenen hergebruikt" instead would drop a state and shift the cue
    letters. Confirm with Erik.
- *Option:* "Ontwerp en kostenindicatie binnen 4 uur" as the first label.

### `reductie-carbon-footprint`

Said: "En dat je daarmee in de toekomst een bouwproces overslaat"; it's in the text (1:12:34–1:12:44).

- No change.

### `the-circle-en-esg`

Said: Boyd: governance, for him, is about how a company is run, and this one is about building
(1:13:06). Erik: relate it to responsible governance, the choices CEOs make; "er zitten CEO's in de
zaal". The words: "verantwoord bouwen" and "toekomst" (1:13:20–1:14:23).

- [ ] **Wall:** governance's notes read "Systematiek / Meetbaarheid / Herhaalbaarheid"
  ([Slideshow.kt:1994](../src/main/kotlin/slideshow/Slideshow.kt#L1994)).
  - *Reading, draft:* "Verantwoord bestuur", "Verantwoord bouwen voor de toekomst", "Meetbaar en
    herhaalbaar".
- [ ] **Script:** line C: as the board of a large organisation you take ESG into your choices, and
  with The Circle you build responsibly for the future.

### `projecthighlight-4`: Intervest

Said, at the cases: the first Circle sold, with the concept launched in 2024 (1:15:12–1:15:21).

- [ ] **Check (Jos) + Wall + Script:** the question and the solution, as for highlight 1.

### `kernboodschap-4`

Said: after The Circle comes a short summary of the story, then the course (1:14:32, 1:16:04). Erik
wants no interactive part (1:15:33).

- [ ] **Wall + Script:** the takeaway asks "Welke vragen roept dit op voor uw eigen praktijk?"
  ([Slideshow.kt:2011](../src/main/kotlin/slideshow/Slideshow.kt#L2011)), which invites questions
  that aren't planned.
  - *Reading:* make it the short summary, the four added values in a sentence, and the handover to
    the course. A draft for Erik.

## The course after chapter 4 (new)

Course 4, served "in de vorm van een cirkel" (1:06:08, 1:16:04). See "The evening as a whole", 2.

---

## Questions

### `case-studies`

Said: Erik wants to close on the Panattoni showcase (1:15:35–1:15:43). The cases refer back to the
four added values: "binnen planning, binnen budget, met kwaliteit" (1:16:27). Ridderkerk carries the
speed story (1:16:51). No interactive part (1:15:33).

- [ ] **Wall:** put Panattoni Almelo last. It is now first
  ([Slideshow.kt:2491](../src/main/kotlin/slideshow/Slideshow.kt#L2491)).
- [ ] **Wall:** the wall turns over on its own every 6 s (`cycle = 6.0`).
  - *Reading:* if Erik talks through the cases it won't wait for him. Go back to a click per view
    while he speaks, or slow the cycle.
- [ ] **Script:** Erik's close: "Met de verticale integratie bouwen wij altijd binnen planning,
  binnen budget, met kwaliteit", tying each case back to the added values.
- [ ] **Wall:** the moment is called "Questions" and the programme says "Vragen". Rename both if
  there are none.

## Dessert

Said: after the cases, an introduction to dessert, and perhaps time to network (1:16:04).

- [ ] **Wall:** the Dessert moment is empty in the order. It needs a wall, with its quiet state for
  Els. Its playlist already exists.

## Exit

### `ending`

Said:
- Not "Ontdek The Circle" (1:18:03), and not "ontdek" or "beleef" either: they have done that all
  evening. "Spread the word" (1:19:14–1:19:26).
- Candidates: "Laten we in gesprek gaan" (1:18:51) and "Zullen we samen verder bouwen aan de
  toekomst?" (1:21:22). Anouk noted the second pairs with "We bouwen vandaag, met het oog op wie na
  ons komt" (1:21:25).
- The QR code: a summary of the presentation to download, and a landing page with a form (Flexmail)
  to leave details and book a meeting (1:18:23–1:20:59).
- A gift box with a personal card (1:21:36–1:22:36). Decide fast: "liever nu dan gisteren"
  (1:23:15).

- [ ] **Wall:** keep the sentence. Set the action once it's decided (`SLIDES_ENDING_ACTION`, or
  [Slideshow.kt:2559](../src/main/kotlin/slideshow/Slideshow.kt#L2559)).
  - *Reading:* "Zullen we samen verder bouwen aan de toekomst?", the question answering the
    sentence above it.
- [ ] **Wall:** point the QR code at the landing page once it exists (`SLIDES_ENDING_URL`). It now
  points at willynaessens.nl.
- [ ] **Event (Roma and Willy Naessens):** the landing page with its form and download, a brochure
  of the presentation, and the gift box. All three were left "om over na te denken", with a decision
  wanted tomorrow.

---

## Not tied to a slide

- **Event:** the timing rehearsal once Erik's text is settled (23:35). The general rehearsal in the
  hall is already in the run sheet twice, once dry and once with audio (07:39).
- **Event:** StudioBuik's printed cards for the four bitterballen (cement, water, sand, gravel), to
  go with the amuse in the reception room (08:35–13:32).
- **Event:** Els's brief. She introduces herself, says what StudioBuik was asked, and for each
  course gives the plate in light of Erik's words and how they chose it. Who writes her text is open.
