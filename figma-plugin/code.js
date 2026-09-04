// Three commands, run in order from Plugins → Development:
//
//   1 · Copy Narratief → SHEETS   clones frames 1..52 onto SHEETS as a grid
//   2 · Widen to 3908             gives every 1920-wide frame a title panel
//   3 · Space chapters            regroups the grid, a block per chapter
//   4 · Tidy up                   renumbers, renames, aligns, reorders layers
//   5 · Left panel                fills the empty left half of every wide slide
//   6 · Adopt new slides          folds loose 'Slide 16:9 - n' frames into the grid
//   7 · Full cleanup              strays, duplicate cards, reflow, renumber, panels
//   8 · Speaker notes             boxes the loose notes and lays that page out like SHEETS
//
// None touches the source page, and all are safe to re-run.

const CONFIG = {
  sourcePage: 'Narratief',
  targetPage: 'SHEETS',
  first: 1,
  last: 52,

  // command 1
  perRow: 4,
  gap: 216,                 // the gap the existing s1/s2/s3 sit at
  centerInColumn: false,

  // command 2 — measured off the frames that are already 3908 wide
  targetWidth: 3908,
  contentX: 1988,           // 1920 panel + 68 gutter
  templateFrame: '52',      // its panel has live text rather than a screenshot
  panelName: 'Slide 16:9 - 142',
  titleReach: { x: 400, y: 200 },  // where a frame's own small title sits

  // command 3
  chapterGap: 648,                       // 3x the row gap, between chapters
  firstChapterName: 'De wereld van bouwen',  // frames 1-11 have raster titles
  mergeOnColon: true,                    // "CO2 impact van beton: Wereld" -> one chapter

  // command 4
  leftColumnMax: 4000,      // left of this is a chapter card, right of it a slide
  rowTolerance: 150,        // how far apart two frames can be and still be one row
  chapterPrefix: 'chapter-',
  startAt: 1,               // first slide number
  chapterMinWidth: 3000,    // wider than this is a chapter, narrower a sub-chapter
  numberScope: 'chapter',   // 'chapter' restarts at 1 per chapter, 'global' runs 1..n
  numberPrefix: true,       // 2-04 rather than 04, so a name stays unique document-wide
  pad: 2,                   // 04 rather than 4, so the layers panel sorts right

  // command 5 — the frame ground is #444444, so the panel sits just under it
  panelWidth: 1920,
  panelColor: '#2B2B2B',
  leftPanelName: 'left-panel',

  // command 6 — slides pasted in later, named by the panel convention rather
  // than by a bare number, so commands 2 and 3 skip them.
  adoptPattern: /^Slide 16:9 - (\d+)$/,
  adoptChapter: 'chapter-4',      // the chapter card they continue
  adoptCardFrom: 'chapter-3.4',   // sub-chapter card cloned for the split
  adoptSplitAt: 209,              // first slide of the sub-chapter, by its own number
  adoptSubTitle: 'ESG principes',
  adoptTitleBand: 200,            // a title sits in the top band, at any x
  adoptRemoveStrays: true,        // delete bare panels left in the chapter column

  // command 7 — the whole pass, in the order the steps depend on each other
  cleanupDemoteDuplicates: true,  // a repeated chapter title is a widened sub-chapter
  cleanupFillPanels: true,        // finish by running command 5
  subWidth: 1920,                 // what a demoted card is narrowed to

  // command 8 — the speaker-notes copy of the deck
  notesPage: 'SHEETS - SPEAKER NOTES',
  notesChapterStarts: ['2022', '12', '33', '52'],  // first frame of each chapter
  notesSortPattern: /^Slide 16:9 - (\d+)$/,  // runs of these are sorted numerically
  noteGap: 32,                    // between a slide and its own note box
  noteMinHeight: 360,
  notePad: 56,
  noteLineGap: 24,
  noteBoxFill: '#F4F4F4',         // the notes are near-black on a light canvas
  noteBoxStroke: '#CFCFCF',
  noteBoxPrefix: 'notes-',
}

const TAG_KEY = 'wnCopy'
const ADOPT_KEY = 'wnSheetNumber'   // the "Slide 16:9 - n" number, kept after renaming
const TAG = 'Narratief 1-52'

function findPage(name) {
  const wanted = name.trim().toLowerCase()
  return figma.root.children.find(p => p.name.trim().toLowerCase() === wanted) || null
}

function numberedFrames(page) {
  const byNumber = new Map()
  for (const node of page.children) {
    if (node.type !== 'FRAME') continue
    const m = /^\s*(\d+)\s*$/.exec(node.name)
    if (!m) continue
    const n = parseInt(m[1], 10)
    if (n < CONFIG.first || n > CONFIG.last) continue
    if (!byNumber.has(n)) byNumber.set(n, node)
  }
  return byNumber
}

// ---------------------------------------------------------------- command 1

function originFor(page) {
  const frames = page.children.filter(n => n.type === 'FRAME')
  const x = frames.length ? Math.min(...frames.map(n => n.x)) : 0
  const y = page.children.length
    ? Math.max(...page.children.map(n => n.y + n.height)) + CONFIG.gap * 2
    : 0
  return { x, y }
}

async function commandCopy() {
  const src = findPage(CONFIG.sourcePage)
  const dst = findPage(CONFIG.targetPage)
  if (!src) throw new Error(`No page named "${CONFIG.sourcePage}"`)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  const byNumber = numberedFrames(src)
  const numbers = [...byNumber.keys()].sort((a, b) => a - b)
  if (!numbers.length) throw new Error(`No frames named ${CONFIG.first}..${CONFIG.last} on "${src.name}"`)
  const missing = []
  for (let n = CONFIG.first; n <= CONFIG.last; n++) if (!byNumber.has(n)) missing.push(n)

  let cleared = 0
  for (const node of [...dst.children]) {
    if (node.getPluginData(TAG_KEY) === TAG) { node.remove(); cleared++ }
  }

  const origin = originFor(dst)
  const colW = Math.max(...numbers.map(n => byNumber.get(n).width))
  const rowH = Math.max(...numbers.map(n => byNumber.get(n).height))

  const placed = []
  numbers.forEach((n, i) => {
    const clone = byNumber.get(n).clone()
    dst.appendChild(clone)
    const cellX = origin.x + (i % CONFIG.perRow) * (colW + CONFIG.gap)
    clone.x = CONFIG.centerInColumn ? cellX + (colW - clone.width) / 2 : cellX
    clone.y = origin.y + Math.floor(i / CONFIG.perRow) * (rowH + CONFIG.gap)
    clone.setPluginData(TAG_KEY, TAG)
    placed.push(clone)
  })

  await figma.setCurrentPageAsync(dst)
  dst.selection = placed
  figma.viewport.scrollAndZoomIntoView(placed)

  const rows = Math.ceil(numbers.length / CONFIG.perRow)
  const notes = []
  if (cleared) notes.push(`replaced ${cleared}`)
  if (missing.length) notes.push(`missing: ${missing.join(', ')}`)
  return `Copied ${placed.length} frames — ${rows} rows of ${CONFIG.perRow}` +
    (notes.length ? ` (${notes.join('; ')})` : '')
}

// ---------------------------------------------------------------- command 2

async function setCharacters(node, chars) {
  const len = node.characters.length
  const fonts = len ? node.getRangeAllFontNames(0, len)
                    : (node.fontName === figma.mixed ? [] : [node.fontName])
  for (const f of fonts) await figma.loadFontAsync(f)
  if (!fonts.length) throw new Error('cannot resolve font on template title')
  node.characters = chars
}

// The frame's own small title: topmost text sitting in the top-left corner.
function ownTitle(frame) {
  const box = frame.absoluteBoundingBox
  if (!box) return null
  const texts = frame.findAll(n => n.type === 'TEXT')
  const near = []
  for (const t of texts) {
    const b = t.absoluteBoundingBox
    if (!b) continue
    const rx = b.x - box.x, ry = b.y - box.y
    if (rx < CONFIG.titleReach.x && ry < CONFIG.titleReach.y && rx > -1 && ry > -1) {
      near.push({ ry, chars: t.characters })
    }
  }
  if (!near.length) return null
  near.sort((a, b) => a.ry - b.ry)
  return near[0].chars.trim() || null
}

async function commandWiden() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  const byNumber = numberedFrames(dst)
  const numbers = [...byNumber.keys()].sort((a, b) => a - b)
  if (!numbers.length) throw new Error(`No frames named ${CONFIG.first}..${CONFIG.last} on ${dst.name} — run command 1 first`)

  const templateFrame = byNumber.get(parseInt(CONFIG.templateFrame, 10))
  if (!templateFrame) throw new Error(`No frame "${CONFIG.templateFrame}" to take the panel from`)
  const template = templateFrame.findChild(n => n.name === CONFIG.panelName)
  if (!template) throw new Error(`Frame ${CONFIG.templateFrame} has no "${CONFIG.panelName}" child`)

  let widened = 0, skipped = 0, inherited = 0
  const noTitle = []
  let lastTitle = null

  for (const n of numbers) {
    const frame = byNumber.get(n)
    if (Math.round(frame.width) >= CONFIG.targetWidth) {   // already wide — also the re-run guard
      skipped++
      const t = ownTitle(frame)
      if (t) lastTitle = t
      continue
    }

    let title = ownTitle(frame)
    if (title) lastTitle = title
    else if (lastTitle) { title = lastTitle; inherited++ }
    else { noTitle.push(n) }

    // widen, then slide the existing content into the right-hand slot
    frame.resizeWithoutConstraints(CONFIG.targetWidth, frame.height)
    for (const child of frame.children) child.x += CONFIG.contentX

    const panel = template.clone()
    frame.insertChild(0, panel)
    panel.x = 0
    panel.y = 0

    if (title) {
      const textNode = panel.findOne(n2 => n2.type === 'TEXT')
      if (textNode) await setCharacters(textNode, title)
    }
    widened++
  }

  await figma.setCurrentPageAsync(dst)
  const notes = []
  if (skipped) notes.push(`${skipped} already ${CONFIG.targetWidth}`)
  if (inherited) notes.push(`${inherited} inherited their title`)
  if (noTitle.length) notes.push(`no title found: ${noTitle.join(', ')}`)
  return `Widened ${widened} frames to ${CONFIG.targetWidth}` +
    (notes.length ? ` (${notes.join('; ')})` : '')
}

// ---------------------------------------------------------------- command 3

// The panel title, or null when the panel is a raster image with no live text.
function panelTitle(frame) {
  const panel = frame.findChild(n => n.name === CONFIG.panelName)
  if (!panel) return null
  const t = panel.findOne(n => n.type === 'TEXT')
  if (!t) return null
  return t.characters.replace(/\s+/g, ' ').trim() || null
}

function chapterKey(title) {
  if (!title) return null
  const s = title.replace(/\s+/g, ' ').trim()
  return CONFIG.mergeOnColon && s.includes(':') ? s.split(':')[0].trim() : s
}

async function commandChapters() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  const byNumber = numberedFrames(dst)
  const numbers = [...byNumber.keys()].sort((a, b) => a - b)
  if (!numbers.length) throw new Error(`No frames on ${dst.name} — run command 1 first`)

  // A frame whose panel has no live text continues the chapter before it.
  const chapters = []
  let last = null
  for (const n of numbers) {
    let key = chapterKey(panelTitle(byNumber.get(n))) || last || CONFIG.firstChapterName
    if (!chapters.length || key !== last) chapters.push({ key, frames: [n] })
    else chapters[chapters.length - 1].frames.push(n)
    last = key
  }

  // Re-lay in place: keep the grid where it already sits.
  const originX = Math.min(...numbers.map(n => byNumber.get(n).x))
  let y = Math.min(...numbers.map(n => byNumber.get(n).y))
  const colW = Math.max(...numbers.map(n => byNumber.get(n).width))
  const rowH = Math.max(...numbers.map(n => byNumber.get(n).height))

  for (const chapter of chapters) {
    chapter.frames.forEach((n, i) => {
      const frame = byNumber.get(n)
      frame.x = originX + (i % CONFIG.perRow) * (colW + CONFIG.gap)
      frame.y = y + Math.floor(i / CONFIG.perRow) * (rowH + CONFIG.gap)
    })
    const rows = Math.ceil(chapter.frames.length / CONFIG.perRow)
    y += rows * (rowH + CONFIG.gap) - CONFIG.gap + CONFIG.chapterGap
  }

  await figma.setCurrentPageAsync(dst)
  const summary = chapters
    .map(c => `${c.frames[0]}-${c.frames[c.frames.length - 1]}`)
    .join(', ')
  return `${chapters.length} chapters spaced: ${summary}`
}

// ---------------------------------------------------------------- command 4

function modal(values) {
  const count = new Map()
  for (const v of values) count.set(v, (count.get(v) || 0) + 1)
  return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0][0]
}

async function commandTidy() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  const frames = dst.children.filter(n => n.type === 'FRAME')
  const cards = cleared.filter(n => n.x < CONFIG.leftColumnMax)
  const slides = frames.filter(n => n.x >= CONFIG.leftColumnMax)
  if (!slides.length) throw new Error('no slide frames right of the chapter column')
  if (!cards.length) throw new Error('no chapter cards left of the column')

  // Columns: snap to the grid the slides themselves describe, rather than to a
  // neighbour — a frame alone in its column has no neighbour to snap to.
  const pitch = Math.max(...slides.map(n => n.width)) + CONFIG.gap
  const firstCol = modal(slides.map(n => Math.round(n.x)))
  let movedX = 0
  for (const n of slides) {
    const x = firstCol + Math.round((n.x - firstCol) / pitch) * pitch
    if (Math.round(x) !== Math.round(n.x)) { n.x = x; movedX++ }
  }

  // Rows: the card in a row sets its height; without one, the majority wins.
  const rows = []
  for (const n of [...slides, ...cards].sort((a, b) => a.y - b.y)) {
    const row = rows.find(r => Math.abs(r.y - n.y) <= CONFIG.rowTolerance)
    if (row) row.nodes.push(n)
    else rows.push({ y: n.y, nodes: [n] })
  }
  let movedY = 0
  for (const row of rows) {
    const card = row.nodes.find(n => cards.indexOf(n) !== -1)
    const anchor = card ? card.y : modal(row.nodes.map(n => Math.round(n.y)))
    for (const n of row.nodes) {
      if (Math.round(n.y) !== Math.round(anchor)) { n.y = anchor; movedY++ }
    }
  }

  // Chapter and sub-chapter cards are different widths, so they line up on
  // their right edge — the edge that faces the grid.
  let movedCards = 0
  const edge = modal(cards.map(n => Math.round(n.x + n.width)))
  for (const n of cards) {
    const x = edge - n.width
    if (Math.round(x) !== Math.round(n.x)) { n.x = x; movedCards++ }
  }

  // Reading order: each card, then the slides between it and the next card.
  // Sub-chapter cards sort in by y, so they nest under their own chapter.
  const byY = [...cards].sort((a, b) => a.y - b.y)
  const order = []
  const claimed = new Set()
  byY.forEach((card, i) => {
    order.push(card)
    const next = byY[i + 1]
    slides
      .filter(s => s.y >= card.y - 1 && (!next || s.y < next.y - 1))
      .sort((a, b) => a.y - b.y || a.x - b.x)
      .forEach(s => { order.push(s); claimed.add(s) })
  })
  const orphans = slides.filter(s => !claimed.has(s)).sort((a, b) => a.y - b.y || a.x - b.x)
  order.unshift(...orphans)

  // A wide card starts a chapter; a narrow one is a sub-chapter of the chapter
  // above it, so the two never collide the way sub-chapter-0 did twice.
  // Sub-chapters carry on their chapter's count rather than restarting, which
  // is how the draaiboek numbers them.
  let chapterNo = 0, subNo = 0, slideNo = CONFIG.startAt, inChapter = 0
  const renamed = []
  for (const n of order) {
    let name
    if (cards.indexOf(n) === -1) {
      if (CONFIG.numberScope === 'chapter' && chapterNo > 0) {
        const num = String(++inChapter).padStart(CONFIG.pad, '0')
        name = CONFIG.numberPrefix ? `${chapterNo}-${num}` : num
        slideNo++
      } else {
        name = `${slideNo++}`
      }
    } else if (n.width >= CONFIG.chapterMinWidth || chapterNo === 0) {
      chapterNo++; subNo = 0; inChapter = 0
      name = `${CONFIG.chapterPrefix}${chapterNo}`
    } else {
      subNo++
      name = `${CONFIG.chapterPrefix}${chapterNo}.${subNo}`
    }
    if (n.name !== name) renamed.push(`${n.name} → ${name}`)
    n.name = name
  }

  // Figma lists the last child at the top of the layers panel, so append the
  // reading order backwards to make the panel read forwards. Everything the
  // deck does not use — the imported PNGs, the notes, the rules — goes behind.
  const rest = [...dst.children].filter(n => order.indexOf(n) === -1)
  for (const n of rest) dst.appendChild(n)
  for (let i = order.length - 1; i >= 0; i--) dst.appendChild(order[i])

  // Two chapter cards with the same title is what a duplicate looks like, and a
  // duplicate silently shifts every chapter number after it. Cheap to spot here,
  // and expensive to notice by eye once 59 frames have been renamed.
  const titles = new Map()
  for (const c of cards) {
    if (c.width < CONFIG.chapterMinWidth) continue
    const t = cardTitleNode(c)
    const key = t ? t.characters.replace(/\s+/g, ' ').trim() : null
    if (!key) continue
    titles.set(key, (titles.get(key) || 0) + 1)
  }
  const dupes = [...titles.entries()].filter(([, n]) => n > 1).map(([t]) => t)

  await figma.setCurrentPageAsync(dst)
  return (dupes.length
    ? `WARNING: ${dupes.length} chapter title used twice (${dupes.join('; ')}) — ` +
      `each copy counts as a chapter, so every number after it has shifted. ` +
      `Narrow the duplicate below ${CONFIG.chapterMinWidth} to make it a sub-chapter, ` +
      `or delete it, and run again. `
    : '') +
    `${chapterNo} chapters, ${cards.length - chapterNo} sub-chapters, ` +
    `${slideNo - CONFIG.startAt} slides; ${renamed.length} renamed; ` +
    `aligned ${movedX} x, ${movedY} y, ${movedCards} cards; ${rest.length} nodes to the back`
}

// ---------------------------------------------------------------- command 5

function hexToRgb(hex) {
  const h = hex.replace('#', '')
  return {
    r: parseInt(h.slice(0, 2), 16) / 255,
    g: parseInt(h.slice(2, 4), 16) / 255,
    b: parseInt(h.slice(4, 6), 16) / 255,
  }
}

async function commandLeftPanel() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  // Chapter cards are 3908 wide too, so width alone is not enough to tell a
  // slide from a card — the slides are the frames right of the chapter column.
  const slides = dst.children.filter(n =>
    n.type === 'FRAME' &&
    n.x >= CONFIG.leftColumnMax &&
    Math.round(n.width) >= CONFIG.targetWidth)
  if (!slides.length) throw new Error(`no ${CONFIG.targetWidth}-wide slide frames found`)

  const color = hexToRgb(CONFIG.panelColor)
  let added = 0, skipped = 0
  for (const frame of slides) {
    if (frame.findChild(n => n.name === CONFIG.leftPanelName)) { skipped++; continue }
    const rect = figma.createRectangle()
    rect.name = CONFIG.leftPanelName
    rect.resize(CONFIG.panelWidth, frame.height)
    frame.insertChild(0, rect)      // index 0 = behind everything already there
    rect.x = 0
    rect.y = 0
    rect.fills = [{ type: 'SOLID', color }]
    added++
  }

  await figma.setCurrentPageAsync(dst)
  return `${added} left panels added at ${CONFIG.panelColor}` +
    (skipped ? `, ${skipped} already had one` : '')
}

// ---------------------------------------------------------------- command 6

// Loose slides carry a centred title rather than the small top-left one that
// `ownTitle` looks for, so the band runs the full width. Kept separate rather
// than widening `titleReach`, which would change what command 2 reads off the
// frames it already widened.
function bandTitle(frame) {
  const box = frame.absoluteBoundingBox
  if (!box) return null
  const near = []
  for (const t of frame.findAll(n => n.type === 'TEXT')) {
    const b = t.absoluteBoundingBox
    if (!b) continue
    const ry = b.y - box.y
    if (ry > -1 && ry < CONFIG.adoptTitleBand) near.push({ ry, chars: t.characters })
  }
  if (!near.length) return null
  near.sort((a, b) => a.ry - b.ry)
  return near[0].chars.replace(/\s+/g, ' ').trim() || null
}

// The chapter number a card carries, derived exactly the way command 4 derives
// it: wide cards counted down the page. Shared so the two cannot disagree.
function chapterIndexOf(card, cards) {
  let ch = 0
  for (const c of [...cards].sort((a, b) => a.y - b.y)) {
    if (c.width >= CONFIG.chapterMinWidth || ch === 0) ch++
    if (c === card) return ch
  }
  return ch
}

function slideName(chapterNo, i) {
  const num = String(i).padStart(CONFIG.pad, '0')
  return CONFIG.numberPrefix ? `${chapterNo}-${num}` : num
}

// A card wraps a panel; a bare panel is what a careless duplicate leaves behind.
// Both sit in the chapter column at the same size, so position and width cannot
// tell them apart — the nesting can.
//
// This identifies a stray positively rather than by failing to recognise it. The
// first version deleted anything in the column that was not a card, which is far
// too broad a rule to hang a delete on: the s1/s2/s3 frames the deck does not use
// would have gone the same way. A stray has to *be* a loose copy of the panel —
// carrying the panel's own name, at the panel's own size.
function isStrayCard(node, panelSize) {
  if (node.x >= CONFIG.leftColumnMax) return false
  if (node.name.indexOf(CONFIG.chapterPrefix) === 0) return false
  if (node.name !== CONFIG.panelName) return false
  if (node.findChild && node.findChild(n => n.name === CONFIG.panelName)) return false
  if (!panelSize) return false
  return Math.round(node.width) === Math.round(panelSize.width) &&
         Math.round(node.height) === Math.round(panelSize.height)
}

function cardTitleNode(card) {
  const panel = card.findChild(n => n.name === CONFIG.panelName) || card
  return panel.findOne(n => n.type === 'TEXT')
}

async function commandAdopt() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)

  const frames = dst.children.filter(n => n.type === 'FRAME')
  const loose = frames
    .map(n => ({ n, m: CONFIG.adoptPattern.exec(n.name) }))
    .filter(r => r.m)
    .map(r => ({ frame: r.n, num: parseInt(r.m[1], 10) }))
    .sort((a, b) => a.num - b.num)          // numeric order, not canvas order
  // A bare panel in the chapter column reads as a sub-chapter card to command 4,
  // which then names it and pushes the real card's number along. Clear it first,
  // and do it whether or not there is anything left to adopt — after a run the
  // slides are named 4-03 and up, so `loose` is empty on every pass after the
  // first and an early return here would leave the strays standing for good.
  // Measured off the template rather than hardcoded, so the test tracks the deck.
  const sizeSource = frames.find(n => n.name === CONFIG.adoptCardFrom)
  const panelSize = sizeSource
    ? sizeSource.findChild(n => n.name === CONFIG.panelName)
    : null
  const strays = CONFIG.adoptRemoveStrays
    ? frames.filter(n => isStrayCard(n, panelSize))
    : []
  const removed = strays.map(n => `${n.name} at ${Math.round(n.x)},${Math.round(n.y)}`)
  for (const n of strays) n.remove()
  const cleared = frames.filter(n => strays.indexOf(n) === -1)

  if (!loose.length) {
    await figma.setCurrentPageAsync(dst)
    return removed.length
      ? `Nothing left to adopt; removed ${removed.length} stray ` +
        `${removed.length === 1 ? 'panel' : 'panels'} from the chapter column ` +
        `(${removed.join('; ')}) — now run 4, then 5`
      : 'nothing to adopt and nothing to repair — the page is already in order'
  }

  const chapter = cleared.find(n => n.name === CONFIG.adoptChapter)
  if (!chapter) throw new Error(`no card named "${CONFIG.adoptChapter}"`)
  const cardTemplate = cleared.find(n => n.name === CONFIG.adoptCardFrom)
  if (!cardTemplate) throw new Error(`no card named "${CONFIG.adoptCardFrom}" to clone`)
  const panelTemplate = cardTemplate.findChild(n => n.name === CONFIG.panelName)
  if (!panelTemplate) throw new Error(`"${CONFIG.adoptCardFrom}" has no "${CONFIG.panelName}" child`)

  // The chapter's own slides: between its card and the next one down, the same
  // claim rule command 4 uses. Unbounded, this would swallow later chapters.
  const cards = cleared.filter(n => n.x < CONFIG.leftColumnMax)
  const below = [...cards].sort((a, b) => a.y - b.y).find(c => c.y > chapter.y + 1)
  const kept = cleared.filter(n =>
    n.x >= CONFIG.leftColumnMax &&
    n.y >= chapter.y - 1 &&
    (!below || n.y < below.y - 1) &&
    loose.every(l => l.frame !== n))
  const pitch = CONFIG.targetWidth + CONFIG.gap
  const rowH = chapter.height + CONFIG.gap
  const firstCol = kept.length ? modal(kept.map(n => Math.round(n.x)))
                               : Math.round(chapter.x + chapter.width) + CONFIG.gap
  let y = kept.length ? Math.max(...kept.map(n => n.y + n.height)) + CONFIG.gap
                      : chapter.y

  // Two readings of which chapter this is: where the card sits, and what its
  // existing slides are already called. They disagree when a stray wide card
  // above shifts the count — the duplicate chapter-2 does exactly that. The
  // slides on the page are the better authority, so follow them and say so.
  const derivedNo = chapterIndexOf(chapter, cards)
  const carried = kept
    .map(n => /^(\d+)-\d+$/.exec(n.name))
    .filter(Boolean)
    .map(m => parseInt(m[1], 10))
  const chapterNo = carried.length ? modal(carried) : derivedNo
  const disagree = carried.length && chapterNo !== derivedNo

  // --- widen, so every adopted slide has the deck's shape
  let widened = 0, inherited = 0
  let lastTitle = null
  for (const { frame } of loose) {
    let title = bandTitle(frame)
    if (title) lastTitle = title
    else if (lastTitle) { title = lastTitle; inherited++ }

    if (Math.round(frame.width) < CONFIG.targetWidth) {   // also the re-run guard
      frame.resizeWithoutConstraints(CONFIG.targetWidth, frame.height)
      for (const child of frame.children) child.x += CONFIG.contentX
      const panel = panelTemplate.clone()
      frame.insertChild(0, panel)
      panel.x = 0
      panel.y = 0
      if (title) {
        const textNode = panel.findOne(n => n.type === 'TEXT')
        if (textNode) await setCharacters(textNode, title)
      }
      widened++
    }
  }

  // --- the sub-chapter card, cloned rather than built so it matches the others
  const splitIndex = loose.findIndex(l => l.num >= CONFIG.adoptSplitAt)
  let card = null
  if (splitIndex > 0) {
    // By name, not by "any card below this one" — command 4 derives the same
    // name from the position, so the guard holds across a tidy.
    const subNo = cards.filter(n =>
      n.y > chapter.y && n.width < CONFIG.chapterMinWidth &&
      (!below || n.y >= below.y)).length + 1
    const subName = `${CONFIG.chapterPrefix}${chapterNo}.${subNo}`
    const existing = cleared.find(n => n.name === subName && n !== chapter)
    card = existing || cardTemplate.clone()
    if (!existing) {
      dst.appendChild(card)
      card.name = subName
      const textNode = cardTitleNode(card)
      if (textNode) await setCharacters(textNode, CONFIG.adoptSubTitle)
    }
    // Cards come in two widths and line up on the edge facing the grid.
    card.x = Math.round(chapter.x + chapter.width) - card.width
  }

  // --- lay out: 4 per row, the sub-chapter card sharing its first slide's row
  const groups = splitIndex > 0
    ? [loose.slice(0, splitIndex), loose.slice(splitIndex)]
    : [loose]
  // A sub-chapter carries on its chapter's count rather than restarting, so the
  // number runs unbroken across the split — as command 4 numbers it.
  let slideNo = kept.length
  const renamed = []
  groups.forEach((group, g) => {
    if (g > 0) y += CONFIG.chapterGap - CONFIG.gap
    if (g > 0 && card) card.y = y
    group.forEach(({ frame, num }, i) => {
      frame.x = firstCol + (i % CONFIG.perRow) * pitch
      frame.y = y + Math.floor(i / CONFIG.perRow) * rowH
      const name = slideName(chapterNo, ++slideNo)
      if (frame.name !== name) {
        // The sheet number is the only record of where a slide came from, and
        // that 206 and 207 were never there. Keep it once the name is gone.
        if (!frame.getPluginData(ADOPT_KEY)) frame.setPluginData(ADOPT_KEY, String(num))
        renamed.push(`${num} → ${name}`)
        frame.name = name
      }
    })
    y += Math.ceil(group.length / CONFIG.perRow) * rowH
  })

  await figma.setCurrentPageAsync(dst)
  const notes = []
  if (removed.length) notes.push(`removed ${removed.length} stray: ${removed.join('; ')}`)
  if (widened) notes.push(`${widened} widened to ${CONFIG.targetWidth}`)
  if (inherited) notes.push(`${inherited} inherited their title`)
  if (card) notes.push(`${card.name} "${CONFIG.adoptSubTitle}" at ${CONFIG.adoptSplitAt}`)
  if (disagree) {
    notes.push(
      `WARNING: named ${chapterNo}- to match the slides already here, but the card ` +
      `sits ${derivedNo} cards down — a stray card wider than ${CONFIG.chapterMinWidth} ` +
      `above it. Command 4 will renumber to ${derivedNo}- until that is fixed`)
  }
  const span = renamed.length
    ? `${renamed[0].split(' → ')[1]}..${renamed[renamed.length - 1].split(' → ')[1]}`
    : 'no rename needed'
  return `Adopted ${loose.length} slides as ${span}` +
    (notes.length ? ` (${notes.join('; ')})` : '') + ' — now run 4, then 5'
}

// ---------------------------------------------------------------- command 7

function cardTitleText(card) {
  const t = cardTitleNode(card)
  return t ? t.characters.replace(/\s+/g, ' ').trim() : null
}

// A wide card repeating an earlier wide card's title is a sub-chapter that was
// widened by accident, not a second chapter. The deck says so itself: the only
// sub-chapter under chapter 2 is named `chapter-2.2`, which is a name that can
// only have been assigned when a `chapter-2.1` sat above it. Narrowing the
// duplicate puts that card back and every other card keeps the name it has.
function demoteDuplicateCards(cards) {
  const seen = {}
  const done = []
  for (const card of [...cards].sort((a, b) => a.y - b.y)) {
    if (card.width < CONFIG.chapterMinWidth) continue
    const title = cardTitleText(card)
    if (!title) continue
    if (seen[title]) {
      card.resizeWithoutConstraints(CONFIG.subWidth, card.height)
      done.push(`${card.name} "${title}"`)
    } else {
      seen[title] = card
    }
  }
  return done
}

// Pack every chapter into full rows from its own card down. Command 4 aligns
// what is already roughly right; this decides where things go, which is what
// closes a hole rather than shuffling it along.
function reflowDeck(dst, perRow) {
  const frames = dst.children.filter(n => n.type === 'FRAME')
  const cards = frames.filter(n => n.x < CONFIG.leftColumnMax).sort((a, b) => a.y - b.y)
  const slides = frames.filter(n => n.x >= CONFIG.leftColumnMax)
  if (!cards.length) throw new Error('no chapter cards left of the column')
  if (!slides.length) throw new Error('no slide frames right of the column')

  const pitch = Math.max(...slides.map(n => n.width)) + CONFIG.gap
  const rowH = Math.max(...slides.map(n => n.height)) + CONFIG.gap
  const col0 = modal(slides.map(n => Math.round(n.x)))
  const edge = modal(cards.map(n => Math.round(n.x + n.width)))

  // Claim every group before moving anything: the claim rule reads card.y, so
  // repositioning as we go would have each card claim against moved neighbours.
  const groups = cards.map((card, i) => {
    const next = cards[i + 1]
    return {
      card,
      slides: slides
        .filter(s => s.y >= card.y - 1 && (!next || s.y < next.y - 1))
        .sort((a, b) => a.y - b.y || a.x - b.x),
    }
  })
  const orphans = slides.filter(s => !groups.some(g => g.slides.indexOf(s) !== -1))

  let y = cards[0].y
  let rows = 0, moved = 0
  const place = (node, x, ny) => {
    if (Math.round(node.x) !== Math.round(x) || Math.round(node.y) !== Math.round(ny)) moved++
    node.x = x
    node.y = ny
  }
  groups.forEach((g, i) => {
    if (i > 0) y += CONFIG.chapterGap - CONFIG.gap
    place(g.card, edge - g.card.width, y)
    g.slides.forEach((s, k) => {
      place(s, col0 + (k % perRow) * pitch, y + Math.floor(k / perRow) * rowH)
    })
    const r = Math.max(1, Math.ceil(g.slides.length / perRow))
    rows += r
    y += r * rowH
  })

  return { rows, moved, groups: groups.length, orphans: orphans.length, height: y - cards[0].y }
}

async function commandCleanup() {
  const dst = findPage(CONFIG.targetPage)
  if (!dst) throw new Error(`No page named "${CONFIG.targetPage}"`)
  const perRow = CONFIG.perRow

  // 1 — strays first: a bare panel in the chapter column would otherwise be
  // reflowed and renamed as though it were a real card.
  const frames = dst.children.filter(n => n.type === 'FRAME')
  const sizeSource = frames.find(n => n.name === CONFIG.adoptCardFrom)
  const panelSize = sizeSource
    ? sizeSource.findChild(n => n.name === CONFIG.panelName)
    : null
  const strays = CONFIG.adoptRemoveStrays
    ? frames.filter(n => isStrayCard(n, panelSize))
    : []
  const removed = strays.map(n => `${n.name} at ${Math.round(n.x)},${Math.round(n.y)}`)
  for (const n of strays) n.remove()

  // 2 — then duplicates, because a widened sub-chapter changes every chapter
  // number after it and so has to be settled before anything is named.
  const cards = dst.children.filter(n =>
    n.type === 'FRAME' && n.x < CONFIG.leftColumnMax)
  const demoted = CONFIG.cleanupDemoteDuplicates ? demoteDuplicateCards(cards) : []

  // 3 — reflow, 4 — renumber and reorder, 5 — fill the empty left halves.
  const flow = reflowDeck(dst, perRow)
  const tidy = await commandTidy()
  const panels = CONFIG.cleanupFillPanels ? await commandLeftPanel() : null

  await figma.setCurrentPageAsync(dst)
  const notes = []
  if (removed.length) notes.push(`removed ${removed.length} stray (${removed.join('; ')})`)
  if (demoted.length) notes.push(`demoted ${demoted.length} duplicate card to sub-chapter: ${demoted.join('; ')}`)
  if (flow.orphans) notes.push(`${flow.orphans} slides above the first card, left alone`)
  return `Cleanup: ${flow.groups} groups reflowed to ${flow.rows} rows of ${perRow}, ` +
    `${flow.moved} frames moved, ${flow.height} tall` +
    (notes.length ? `; ${notes.join('; ')}` : '') +
    `. Tidy: ${tidy}` + (panels ? `. Panels: ${panels}` : '')
}

// ---------------------------------------------------------------- command 8

const NOTE_BOX_KEY = 'wnNoteBox'    // on a slide: the id of its note box
const NOTE_OF_KEY = 'wnNoteFor'     // on a box: the id of its slide
const SRC_NAME_KEY = 'wnDraaiboek'  // the name a frame arrived with

// The chapter splits are named after draaiboek frames — '12', '33', '52' — and
// this command renames those very frames to 1-01 and up. So the name it splits
// on has to be the one the frame arrived with, kept the first time it is seen,
// or a second run would find no starts at all and fold the deck into one chapter.
function sourceName(node) {
  const kept = node.getPluginData(SRC_NAME_KEY)
  if (kept) return kept
  node.setPluginData(SRC_NAME_KEY, node.name)
  return node.name
}

async function loadFontsOf(node) {
  const len = node.characters.length
  const fonts = len ? node.getRangeAllFontNames(0, len)
                    : (node.fontName === figma.mixed ? [] : [node.fontName])
  for (const f of fonts) await figma.loadFontAsync(f)
}

// Rows are read off the page rather than assumed: this layout is five bands of
// frames, and the running order is those bands top to bottom, each left to right.
function readingOrder(frames, tolerance) {
  const rows = []
  for (const f of [...frames].sort((a, b) => a.y - b.y)) {
    const row = rows.find(r => Math.abs(r.y - f.y) <= tolerance)
    if (row) row.nodes.push(f)
    else rows.push({ y: f.y, nodes: [f] })
  }
  const out = []
  for (const row of rows) out.push(...row.nodes.sort((a, b) => a.x - b.x))
  return out
}

// A contiguous run of sheet-numbered frames goes back into numeric order, which
// is the same correction command 6 makes on SHEETS. Everything else keeps the
// order the page already has — only the run is touched.
function sortSheetRuns(order, pattern, nameOf) {
  const out = order.slice()
  let i = 0, fixed = 0
  while (i < out.length) {
    if (!pattern.exec(nameOf(out[i]))) { i++; continue }
    let j = i
    while (j < out.length && pattern.exec(nameOf(out[j]))) j++
    const run = out.slice(i, j)
    const sorted = run.slice().sort((a, b) =>
      parseInt(pattern.exec(nameOf(a))[1], 10) - parseInt(pattern.exec(nameOf(b))[1], 10))
    for (let k = 0; k < run.length; k++) {
      if (run[k] !== sorted[k]) fixed++
      out[i + k] = sorted[k]
    }
    i = j
  }
  return { order: out, fixed }
}

// Notes are loose text sitting above the frame they belong to. Pair them while
// that is still true — once anything moves, the only record is gone.
function pairNotes(frames, texts, tolerance) {
  const rowY = []
  for (const f of frames) {
    if (!rowY.some(y => Math.abs(y - f.y) <= tolerance)) rowY.push(f.y)
  }
  rowY.sort((a, b) => a - b)
  const byFrame = new Map()
  const orphans = []
  for (const t of texts) {
    const y = rowY.find(r => r >= t.y - 1)
    const inRow = y === undefined ? [] : frames.filter(f => Math.abs(f.y - y) <= tolerance)
    const over = inRow.filter(f => t.x >= f.x - 60 && t.x <= f.x + f.width + 60)
    const pick = (over.length ? over : inRow)
      .sort((a, b) => Math.abs(a.x - t.x) - Math.abs(b.x - t.x))[0]
    if (!pick) { orphans.push(t); continue }
    if (!byFrame.has(pick)) byFrame.set(pick, [])
    byFrame.get(pick).push(t)
  }
  for (const list of byFrame.values()) list.sort((a, b) => a.y - b.y)
  return { byFrame, orphans }
}

async function commandNotes() {
  const page = findPage(CONFIG.notesPage)
  if (!page) throw new Error(`No page named "${CONFIG.notesPage}"`)
  const sheets = findPage(CONFIG.targetPage)
  if (!sheets) throw new Error(`No page named "${CONFIG.targetPage}" to take the grid from`)

  const boxPrefix = CONFIG.noteBoxPrefix
  const frames = page.children.filter(n =>
    n.type === 'FRAME' && n.name.indexOf(boxPrefix) !== 0)
  const texts = page.children.filter(n => n.type === 'TEXT')
  if (!frames.length) throw new Error(`no frames on "${page.name}"`)

  // The grid is measured off SHEETS, so "the same way" is literal rather than
  // a second set of constants that can drift from it.
  const sheetSlides = sheets.children.filter(n =>
    n.type === 'FRAME' && n.x >= CONFIG.leftColumnMax)
  const sheetCards = sheets.children.filter(n =>
    n.type === 'FRAME' && n.x < CONFIG.leftColumnMax && n.width >= CONFIG.chapterMinWidth)
  if (!sheetSlides.length) throw new Error(`no slides on "${sheets.name}" to measure the grid from`)
  if (!sheetCards.length) throw new Error(`no chapter cards on "${sheets.name}" to clone`)
  const col0 = modal(sheetSlides.map(n => Math.round(n.x)))
  const edge = modal(sheets.children
    .filter(n => n.type === 'FRAME' && n.x < CONFIG.leftColumnMax)
    .map(n => Math.round(n.x + n.width)))
  const cardsByName = {}
  for (const c of sheetCards) cardsByName[c.name] = c

  // --- pair, before anything moves
  const { byFrame, orphans } = pairNotes(frames, texts, CONFIG.rowTolerance)

  // --- running order, with the sheet-numbered run put back in sequence
  const read = readingOrder(frames, CONFIG.rowTolerance)
  const sorted = sortSheetRuns(read, CONFIG.notesSortPattern, sourceName)
  const order = sorted.order

  // --- split into chapters at the named frames
  const starts = CONFIG.notesChapterStarts
  const groups = []
  for (const f of order) {
    const at = starts.indexOf(sourceName(f))
    if (at !== -1 || !groups.length) groups.push({ slides: [] })
    groups[groups.length - 1].slides.push(f)
  }

  // --- measure the notes at their new width, so every box is one height
  const slideW = Math.max(...frames.map(f => f.width))
  const innerW = slideW - CONFIG.notePad * 2
  let needed = CONFIG.noteMinHeight
  for (const list of byFrame.values()) {
    let h = CONFIG.notePad * 2
    for (const t of list) {
      await loadFontsOf(t)
      t.textAutoResize = 'HEIGHT'
      t.resize(innerW, t.height)
      h += t.height + CONFIG.noteLineGap
    }
    needed = Math.max(needed, h - CONFIG.noteLineGap)
  }
  const boxH = Math.ceil(needed)

  // --- one box per slide, empty where no note was written
  const fill = [{ type: 'SOLID', color: hexToRgb(CONFIG.noteBoxFill) }]
  const stroke = [{ type: 'SOLID', color: hexToRgb(CONFIG.noteBoxStroke) }]
  let boxes = 0, filled = 0
  for (const slide of order) {
    let box = page.children.find(n =>
      n.type === 'FRAME' && n.getPluginData(NOTE_OF_KEY) === slide.id)
    if (!box) {
      box = figma.createFrame()
      page.appendChild(box)
      box.setPluginData(NOTE_OF_KEY, slide.id)
      slide.setPluginData(NOTE_BOX_KEY, box.id)
      boxes++
    }
    box.name = boxPrefix + slide.name
    box.resizeWithoutConstraints(slideW, boxH)
    box.fills = fill
    box.strokes = stroke
    box.strokeWeight = 2
    box.clipsContent = true
    const mine = byFrame.get(slide) || []
    let ty = CONFIG.notePad
    for (const t of mine) {
      box.appendChild(t)
      t.x = CONFIG.notePad
      t.y = ty
      ty += t.height + CONFIG.noteLineGap
    }
    if (mine.length) filled++
  }

  // --- lay out: same columns as SHEETS, each cell a slide with its box under it
  const pitch = slideW + CONFIG.gap
  const cellH = Math.max(...frames.map(f => f.height)) + CONFIG.noteGap + boxH
  const rowH = cellH + CONFIG.gap
  let y = 0, rows = 0, made = 0
  groups.forEach((g, i) => {
    if (i > 0) y += CONFIG.chapterGap - CONFIG.gap
    const wanted = `${CONFIG.chapterPrefix}${i + 1}`
    let card = page.children.find(n => n.type === 'FRAME' && n.name === wanted)
    if (!card && cardsByName[wanted]) {
      card = cardsByName[wanted].clone()
      page.appendChild(card)
      card.name = wanted
      made++
    }
    if (card) { card.x = edge - card.width; card.y = y }
    g.slides.forEach((slide, k) => {
      const x = col0 + (k % CONFIG.perRow) * pitch
      const ry = y + Math.floor(k / CONFIG.perRow) * rowH
      slide.x = x
      slide.y = ry
      const box = page.children.find(n =>
        n.type === 'FRAME' && n.getPluginData(NOTE_OF_KEY) === slide.id)
      if (box) { box.x = x; box.y = ry + slide.height + CONFIG.noteGap }
      slide.name = `${i + 1}-${String(k + 1).padStart(CONFIG.pad, '0')}`
      if (box) box.name = boxPrefix + slide.name
    })
    const r = Math.max(1, Math.ceil(g.slides.length / CONFIG.perRow))
    rows += r
    y += r * rowH
  })

  // Reading order: card, then each slide followed by its own box.
  const flat = []
  groups.forEach((g, i) => {
    const card = page.children.find(n => n.type === 'FRAME' && n.name === `${CONFIG.chapterPrefix}${i + 1}`)
    if (card) flat.push(card)
    for (const slide of g.slides) {
      flat.push(slide)
      const box = page.children.find(n =>
        n.type === 'FRAME' && n.getPluginData(NOTE_OF_KEY) === slide.id)
      if (box) flat.push(box)
    }
  })
  const rest = page.children.filter(n => flat.indexOf(n) === -1)
  for (const n of rest) page.appendChild(n)
  for (let i = flat.length - 1; i >= 0; i--) page.appendChild(flat[i])

  await figma.setCurrentPageAsync(page)
  const notes = []
  if (sorted.fixed) notes.push(`${sorted.fixed} sheet-numbered frames put back in order`)
  if (made) notes.push(`${made} chapter cards cloned from ${sheets.name}`)
  if (orphans.length) notes.push(`${orphans.length} notes matched no frame, left where they are`)
  return `${groups.length} chapters, ${order.length} slides in ${rows} rows of ${CONFIG.perRow}; ` +
    `${boxes} note boxes at ${boxH}px, ${filled} carrying text, ${order.length - filled} empty` +
    (notes.length ? ` (${notes.join('; ')})` : '')
}

// ----------------------------------------------------------------------

async function main() {
  await figma.loadAllPagesAsync()
  const commands = {
    copy: commandCopy, widen: commandWiden,
    chapters: commandChapters, tidy: commandTidy,
    panel: commandLeftPanel, adopt: commandAdopt,
    cleanup: commandCleanup, notes: commandNotes,
  }
  const run = commands[figma.command]
  if (!run) {
    throw new Error(
      'no command — Figma is still running the single-command version of this ' +
      'plugin. Re-import figma-plugin/manifest.json and the two commands appear.'
    )
  }
  figma.closePlugin(await run())
}

main().catch(err => figma.closePlugin(`Failed: ${err.message}`))
