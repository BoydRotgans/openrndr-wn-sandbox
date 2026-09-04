# WN — Narratief to SHEETS

A Figma plugin that copies the numbered frames (`1`..`52`) from the **Narratief**
page onto **SHEETS**, laid out as a grid of 4 per row.

It exists because nothing outside Figma can write to a Figma document. The Dev Mode
MCP server on `127.0.0.1:3845` is read-only — six getters, no create or move — and
the REST API's write endpoints cover comments, dev resources and webhooks, not
canvas nodes. Placing nodes is Plugin API only, which means code running inside the
editor.

## Running it

1. Figma desktop → **Plugins → Development → Import plugin from manifest…**
2. Pick `figma-plugin/manifest.json` in this repo.
3. Open the WN Concept Phase file → **Plugins → Development → WN — Narratief to SHEETS**,
   then pick a command.

Both run headless and close with a summary line. If only one command shows up, the
manifest changed since you imported it — re-import it.

## 1 · Copy Narratief → SHEETS

- Copies. The originals on Narratief are never touched.
- Takes only top-level frames whose name is a bare number in range. Anything else on
  Narratief — the 1710 other frames, the sections, the loose text — is ignored.
- Starts below whatever is already on SHEETS, aligned to the leftmost existing frame,
  so `s1`/`s2`/`s3` are left alone.
- Columns are sized to the widest frame (3908), so left edges line up down each column.
  The 33 frames that are 1920 wide leave a gap at their right — see `centerInColumn`.
- Tags every frame it places, and a re-run removes the previous run's frames first.
  So it is safe to run repeatedly while tuning the layout.

## 2 · Widen to 3908 with title panel

Gives every 1920-wide frame on SHEETS the same shape as the ones that are already
3908: a title panel on the left, the content slide on the right.

- Widens the frame to 3908 and slides its existing content to x=1988 — a 1920 panel
  plus the 68px gutter the existing wide frames use.
- Clones `Slide 16:9 - 142` off frame 52 into the empty left slot and retitles it.
- The title is the frame's own small top-left title, promoted. A frame with none
  inherits the last one seen, which is what carries 28–32 through on
  "ESG Environmental".
- The small title stays on the content slide.
- Frames already 3908 are left alone, which is also what makes a re-run a no-op.
  To redo it, delete the copies and run command 1 again first.

Frames `s1`/`s2`/`s3` are not touched — they are 3840x1080 and not part of the deck.

## 3 · Space chapters

Regroups the grid so each chapter is its own block, with a wider gap between blocks.
A chapter starts wherever the panel title changes; frames still fill 4 per row inside it.

The title is read from the panel, not from anywhere in the frame — reading the whole
frame picks up body text and mis-splits the deck. Frames 1–11 have their title baked
into a raster image with no live text, so they carry the chapter before them forward,
and `firstChapterName` seeds the first one.

`mergeOnColon` folds "CO2 impact van beton: Wereld" and ": Nederland" into one chapter.
It also shortens "Beton: ruggengraat en transitie" to "Beton" and "The Circle: ..." to
"The Circle" — only the grouping key, never the frame.

Re-running is a no-op on an already-spaced grid, and safe after re-running 1 and 2.

## 4 · Tidy up

Numbering, naming, layer order and alignment. It moves and renames; it never
changes a frame's size or its contents.

Anything left of `leftColumnMax` is a chapter card, anything right of it a slide.

- **Alignment.** Slides snap to the column grid the slides themselves describe
  (`firstCol + n * pitch`) rather than to a neighbour — a frame alone in its column
  has no neighbour to snap to. Rows snap to the chapter card sitting in that row,
  or to the majority where there is none. Chapter cards come in two widths, so they
  line up on their **right** edge, which is the edge that faces the grid.
- **Numbering.** Slides restart at 1 in each chapter, the way the draaiboek numbers
  them, and a sub-chapter carries on its chapter's count rather than restarting again:
  `2-01 2-02 2-03` then `chapter-2.1` then `2-04 2-05`. The chapter prefix keeps every
  name unique across the page. `numberScope: 'global'` gives one run of `1..n` instead.
- **Naming.** Cards come in two levels, told apart by width: wider than
  `chapterMinWidth` starts a chapter (`chapter-1`), narrower is a sub-chapter of the
  chapter above it (`chapter-2.1`). That is what stops two cards both being called
  `sub-chapter-0`.
- **Layer order.** Figma lists the last child at the top of the layers panel, so the
  reading order is appended backwards and the panel then reads forwards: each chapter
  card followed by its own slides.

Re-running is a no-op once everything is aligned and named.

## 5 · Left panel

Puts a 1920x1080 rectangle in the empty left half of every 3908-wide slide, in a
soft black-grey. The frame ground is `#444444`, so `#2B2B2B` reads as a panel rather
than as a hole.

It goes in at index 0, behind whatever the frame already holds, so it never covers
content — on the few frames that still have an old title panel it simply sits behind it.

Width alone cannot tell a slide from a chapter card, since the four chapter cards are
also 3908 wide. So it takes only the frames right of `leftColumnMax`; the cards keep
their own titles untouched.

Re-running skips any frame that already has a `left-panel`, so it only ever fills gaps.
To recolour, change `panelColor`, delete the old panels and run again.

## 6 · Adopt new slides

Folds slides pasted onto SHEETS later into the grid. They arrive named by the panel
convention (`Slide 16:9 - 200`) rather than as a bare number, which is exactly what
commands 2 and 3 filter on, so **both skip them silently** — this command is the way in.

- **Order comes from the number, not from the canvas.** They were dropped left to right
  as `200 201 202 208 203 204 205 209 210 …`; sorting on the name puts 208 and 209 back
  where they belong. Command 4 numbers by position, so getting this right first is what
  stops the misplacement being baked into the names.
- **Widens like command 2**, but reads the title from a band across the whole top of the
  frame (`adoptTitleBand`) rather than the top-left corner. These slides centre their
  title, so `ownTitle`'s 400px corner misses it. Kept as a separate function because
  widening `titleReach` would change what command 2 reads off the frames it already did.
- **Splits at `adoptSplitAt`.** Slides from that number on go under a sub-chapter card
  cloned from `adoptCardFrom` and retitled `adoptSubTitle`, so it matches the other
  cards rather than being built from scratch. Below the split it is one flat run.
- **Lays out on the grid the chapter already describes** — 4 per row from the modal
  column of the chapter's existing slides, starting a row below them, `chapterGap`
  before the sub-chapter card, which shares its first slide's row like every other card.
- **Names them itself**, continuing the chapter's own count: `4-03`…`4-15`, with the
  sub-chapter carrying on rather than restarting, and its card named from the same
  number (`chapter-4.1`). Nothing here is hardcoded — the chapter number, the
  sub-chapter index and the first slide number are all read off the page.

### Which chapter number, when the page disagrees with itself

Command 4 derives a chapter's number from **where its card sits** — wide cards counted
down the page. That is fragile: one stray wide card above shifts every number after it.
So command 6 takes the other reading as well, the number the chapter's **existing slides
already carry** (`4-01`, `4-02` → 4), and follows that, because the slides on the page
are the better authority about what the chapter is called.

With the duplicate `chapter-2` still present the two readings differ — position says 5,
the slides say 4 — and command 6 names them `4-03`…`4-15` and **says so in its summary**
rather than silently picking one. Verified both ways against the geometry: the names come
out identical with the stray card present and removed.

The number is still only half-safe until that card is fixed, because command 4 renumbers
positionally and would then rewrite 59 frames. Command 6 gets *its* slides right either
way; command 4 is what the stray card breaks.

`slideName` and `chapterIndexOf` are shared with command 4 rather than reimplemented, so
`numberPrefix`, `pad` and `chapterMinWidth` cannot mean one thing in one command and
something else in the other.

The sheet number each slide arrived with (`200`, `208`…) is kept in plugin data under
`wnSheetNumber` before the name is overwritten — it is the only record of where a slide
came from, and that 206 and 207 were never there.

Command 5 still fills the panels afterwards, so the order is **6, then 4, then 5**.
Command 4 remains worth running: it aligns rows, fixes the layer order, and covers the
frames command 6 does not touch.

### The repair pass

Before adopting anything it clears **stray panels from the chapter column**, and it does
this whether or not there is anything left to adopt. That second part matters: after one
run the slides are named `4-03` and up, so `adoptPattern` matches nothing on every later
pass, and an early return would leave a stray standing for good.

A card *wraps* a panel; duplicating one and getting the inner panel instead leaves a node
that is the right width in the right column and one level short. Position and width cannot
tell the two apart — the nesting can.

`isStrayCard` identifies one **positively**: it carries the panel's own name, it does not
wrap a panel of its own, and it matches the template panel's size, measured off
`adoptCardFrom` rather than hardcoded. The first version of this test was the negative —
anything in the column that was not a card — which is far too broad a rule to hang a
delete on, since the `s1`/`s2`/`s3` frames the deck does not use would have gone the same
way. On the live page both versions matched exactly one node; only the second is safe to
leave switched on.

Deleting is on by default (`adoptRemoveStrays`) and is reported by id and position in the
summary, so an unwanted removal is one undo away.

Re-running is otherwise a no-op — it says the page is already in order.

### The duplicate chapter card

`chapter-2` exists **twice** — `687:9` at (-615, 9950) and `699:177` at (-724, 13364),
the second carrying an extra `CO₂-prestatieladder` line and sitting 109px left of the
column every other card lines up on. Command 4 reads any card wider than
`chapterMinWidth` as a chapter start, so it counts both and **shifts every chapter from
2 down by one**: chapter 4 comes out `chapter-5` and its slides `5-01`…`5-15`.

This is still open on the page, and it is the one thing standing between the deck and
correct numbering. Narrowing `699:177` to 1920 makes it a sub-chapter (`chapter-2.3`),
which is what its content reads like; deleting it is the other way out.

Command 4 now **warns when two chapter cards share a title**, which is what a duplicate
looks like and what this one is — both read "Waardekader en verantwoor-delijkheid". It
warns rather than acts: which copy is the real chapter is a judgement about the deck, not
something width and position can settle. Cheap to spot up front, expensive to notice by
eye once 59 frames have been renamed.

Separately, `chapter-2.2` is the only sub-chapter card under chapter 2, so command 4 will
rename it `chapter-2.1`. Stale from an earlier edit, harmless, and mentioned only so the
rename is not a surprise.

## 7 · Full cleanup

One command for the whole pass. It runs the steps in the order they depend on each other,
which is the part that is easy to get wrong by hand:

1. **Strays** — a bare panel in the chapter column would otherwise be reflowed and renamed
   as though it were a real card, so it goes first.
2. **Duplicate cards** — a wide card repeating an earlier wide card's title is demoted to a
   sub-chapter by narrowing it to `subWidth`. This has to happen before anything is named,
   because a spurious chapter shifts every chapter number after it.
3. **Reflow** — every chapter packed into full rows from its own card down.
4. **Renumber, realign, reorder** — command 4, unchanged.
5. **Left panels** — command 5, unchanged.

### Why demoting the duplicate is right, and not a guess

The deck says so itself. The only sub-chapter under chapter 2 is named `chapter-2.2` — a
name command 4 can only have assigned when a `chapter-2.1` sat above it. `699:177` is that
card, widened to 3908 at some point and so counted as a chapter ever since. Narrowing it
puts it back as `chapter-2.1`, and **every other card on the page then keeps exactly the
name it already has**. A rule that changed nothing except the one thing that was wrong is
the rule that was already in force.

### Reflow, not snap

Command 4 aligns what is already roughly right: it snaps each slide to the nearest column
and each row to its card. That cannot close a hole — an empty slot stays empty, because
every slide is already on the grid. Nor can it even out rows: the page had rows of 7, 6, 5,
4, 3, 2 and 1 against a `perRow` of 4, and one row with a gap in the middle of it.

So the reflow decides positions rather than correcting them. Groups are claimed **before**
anything moves — the claim rule reads `card.y`, so repositioning as it went would have each
card claim against neighbours that had already shifted.

Measured on the live page: 11 groups into 20 rows, 13 of 65 slides renamed, every chapter
numbering 1..n with no holes, and the deck 30 240 tall against 45 022 before — the ragged
rows were costing a third of the page's height.

`cleanupDemoteDuplicates` and `cleanupFillPanels` turn steps 2 and 5 off. Re-running is a
no-op once the page is in order.

## 8 · Speaker notes

Lays `SHEETS - SPEAKER NOTES` out the way SHEETS is laid out, and puts each loose note
into a box under the slide it belongs to.

**The grid is measured off SHEETS, not restated.** The column origin and the card edge are
read from that page at run time, so "the same way" stays true if SHEETS ever moves rather
than being a second set of constants that drifts.

### Pairing has to happen before anything moves

The notes are 52 top-level text nodes; **none is a child of a frame**. The only thing
linking a note to its slide is that it sits above it — and the pairing is loose, with
x-offsets from 77 to 2139 and one note overlapping the frame below it by 656px. Move a
frame and that link is gone for good, so the command pairs first and lays out second.

Once paired the link is made real: the note text is **moved into** the box rather than
copied, and slide and box hold each other's ids in plugin data. Moving the text also means
no font has to be created or matched — the note keeps its own type exactly.

### One height for every box

Notes are re-flowed from their current 846–1920px width to 3796, so they get *shorter*, not
taller: measured content comes out around 240px median and 593 worst. Every box is then cut
to the same height — the tallest note plus padding, floored at `noteMinHeight` — because
unequal boxes under a grid of equal slides read as a fault rather than as information.

**Every slide gets a box, empty where no note was written.** 42 of the 74 carry text; the
other 32 stand as visible gaps, which is honest — nearly half the deck has no speaker note.

### What it can and cannot infer

- **Chapters come from `notesChapterStarts`** — the draaiboek frames `2022`, `12`, `33`,
  `52`. That gives 12, 22, 25 and 15 slides; chapters 1 and 4 match SHEETS' counts exactly
  and the nine extra frames fall in 2 and 3, which is where they already sat.
- **Only the four top-level cards are cloned.** SHEETS also has seven sub-chapters, and
  there is no reliable way to place them here: matching the two decks by text content gives
  28 clean matches out of 74, because the build-up sequences share their wording. Add
  sub-chapter cards by hand and command 7 picks them up from position.
- **A run of `Slide 16:9 - n` frames is put back in numeric order** — the same correction
  command 6 makes on SHEETS, and 4 frames move. Nothing else is reordered.

The name a frame arrived with is kept in plugin data under `wnDraaiboek` the first time it
is seen, because this command renames those very frames to `1-01` and up — without it a
second run would find no chapter starts and fold the deck into one chapter.

Verified against the live page: 20 rows, 152 nodes, no overlaps, 2 notes matching no frame
and left where they are.

## Tuning

`CONFIG` at the top of `code.js`:

| key | default | |
|---|---|---|
| `perRow` | `4` | frames per row |
| `gap` | `216` | matches the gap between the existing `s1`/`s2`/`s3` |
| `centerInColumn` | `false` | `true` centres a 1920 frame in its 3908 column |
| `first` / `last` | `1` / `52` | range of frame names to take |
| `targetWidth` | `3908` | width command 2 widens to |
| `contentX` | `1988` | where the content slide lands |
| `templateFrame` | `'52'` | frame whose panel is cloned as the title panel |
| `titleReach` | `400 x 200` | corner a frame's own title is looked for in |
| `chapterGap` | `648` | space between chapters, 3x the row gap |
| `firstChapterName` | `'De wereld van bouwen'` | chapter for the raster-titled frames 1–11 |
| `mergeOnColon` | `true` | group on the text before a colon |
| `leftColumnMax` | `4000` | x below which a frame is a chapter card |
| `rowTolerance` | `150` | how far apart two frames can be and still be one row |
| `chapterPrefix` | `'chapter-'` | naming for the cards |
| `startAt` | `1` | first slide number |
| `chapterMinWidth` | `3000` | wider is a chapter, narrower a sub-chapter |
| `numberScope` | `'chapter'` | restart per chapter, or `'global'` for one run |
| `numberPrefix` | `true` | `2-04` rather than `04` |
| `pad` | `2` | `04` rather than `4`, so names sort right |
| `panelWidth` | `1920` | left panel width |
| `panelColor` | `'#2B2B2B'` | soft black-grey against the `#444444` ground |
| `leftPanelName` | `'left-panel'` | layer name, and the re-run guard |
| `sourcePage` / `targetPage` | `Narratief` / `SHEETS` | matched case-insensitively |
| `adoptPattern` | `/^Slide 16:9 - (\d+)$/` | which loose frames command 6 takes |
| `adoptChapter` | `'chapter-4'` | the chapter card they continue |
| `adoptCardFrom` | `'chapter-3.4'` | sub-chapter card cloned for the split |
| `adoptSplitAt` | `209` | first slide of the sub-chapter, by its own number |
| `adoptSubTitle` | `'ESG principes'` | title set on the cloned card |
| `adoptTitleBand` | `200` | top band a centred title is looked for in |
| `adoptRemoveStrays` | `true` | delete bare panels left in the chapter column |
| `cleanupDemoteDuplicates` | `true` | narrow a repeated chapter card to a sub-chapter |
| `cleanupFillPanels` | `true` | finish the cleanup by running command 5 |
| `subWidth` | `1920` | what a demoted card is narrowed to |
| `notesPage` | `SHEETS - SPEAKER NOTES` | page command 8 works on |
| `notesChapterStarts` | `2022, 12, 33, 52` | first frame of each chapter |
| `noteGap` / `noteMinHeight` | `32` / `360` | slide-to-box gap, empty box height |
| `notePad` / `noteLineGap` | `56` / `24` | inside a box |
| `noteBoxFill` / `noteBoxStroke` | `#F4F4F4` / `#CFCFCF` | the notes are dark on light |

## Expected result

52 frames, 13 rows of 4, occupying 16280 x 16632 starting at (-151, 4591) — verified
against the geometry read off the document, with no overlaps and no clash with the
existing SHEETS content.
