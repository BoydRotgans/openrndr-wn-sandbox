/**
 * A click's line as the show puts it on the wall: cut into cards of at most two lines of 42 characters,
 * each standing as long as it takes to read, the words arriving one by one as they would be said.
 *
 * A port of `Subtitles.cards` and `Subtitles.wordTimes` in the show (`slideshow/Subtitles.kt`), so the
 * rehearsal film's subtitle reads like the review film's: cut at sentences, then clauses packed as evenly
 * as they go, then evenly between words; a card stands `chars / 15` seconds and never under 1.6, a
 * quarter second between cards, the first 0.4 s after the click; a card's last word lands 80% of the
 * way through it. Times are seconds from the click's start. Without the voice's own word times the
 * reading rule is all there is, which is the pace the show falls back on too.
 */
export const LINE_CHARS = 42
const CARD_CHARS = LINE_CHARS * 2
const CPS = 15
const MIN_SECONDS = 1.6
const GAP_SECONDS = 0.25
const LEAD_SECONDS = 0.4
const SPOKEN = 0.8

export interface Card {
  /** One line, or two broken nearest equal. */
  lines: string[]
  /** Seconds from the click's start the card comes up, and how long it stands. */
  at: number
  length: number
  /** Seconds from the click's start each word of the card arrives, in reading order across its lines. */
  words: number[]
}

export function cards(text: string): Card[] {
  let at = LEAD_SECONDS
  return chunks(text).map((chunk) => {
    const lines = wrap(chunk)
    const length = Math.max(MIN_SECONDS, chunk.length / CPS)
    const words = lines.flatMap((l) => l.split(' ')).filter(Boolean)
    const total = Math.max(1, words.reduce((n, w) => n + w.length + 1, 0))
    let before = 0
    const card: Card = {
      lines, at, length,
      words: words.map((w) => { const t = at + (length * SPOKEN * before) / total; before += w.length + 1; return t }),
    }
    at += length + GAP_SECONDS
    return card
  })
}

/**
 * The card to show [t] seconds into the click: the one standing then, none between two, and the last
 * one from its coming up on — a click held still keeps its last card rather than an empty film.
 */
export function cardAt(all: Card[], t: number): Card | null {
  for (let i = 0; i < all.length; i++) {
    const c = all[i]
    if (t < c.at) return null
    if (i === all.length - 1 || t < c.at + c.length) return c
  }
  return null
}

export function chunks(text: string): string[] {
  const flat = text.replace(/\s+/g, ' ').trim()
  if (!flat) return []
  return sentences(flat).flatMap((sentence) =>
    sentence.length <= CARD_CHARS ? [sentence]
      : packed(clauses(sentence)).flatMap((p) => (p.length <= CARD_CHARS ? [p] : evenly(p, CARD_CHARS))))
}

/** At a full stop, question or exclamation mark followed by a space — so 1,2 and 2.750 stay whole. */
function sentences(text: string): string[] {
  return text.split(/(?<=[.!?…])\s+/).map((s) => s.trim()).filter(Boolean)
}

/** At a comma, colon, semicolon or a spaced dash, the mark kept on the piece before it. */
function clauses(sentence: string): string[] {
  return sentence.split(/(?<=[,:;])\s+|\s+(?=[–—]\s)/).map((s) => s.trim()).filter(Boolean)
}

/** Clauses run together into as few cards as fit, as even in length as they will go. */
function packed(parts: string[]): string[] {
  const n = parts.length
  const joined = (from: number, until: number) => parts.slice(from, until).join(' ')
  const count = new Array<number>(n + 1).fill(Infinity)
  const spread = new Array<number>(n + 1).fill(Infinity)
  const cut = new Array<number>(n + 1).fill(0)
  count[0] = 0
  spread[0] = 0
  const length = parts.reduce((s, p) => s + p.length + 1, 0) - 1
  const mean = length / Math.max(1, Math.ceil(length / CARD_CHARS))
  for (let i = 1; i <= n; i++) for (let k = 0; k < i; k++) {
    if (count[k] === Infinity) continue
    const piece = joined(k, i)
    if (piece.length > CARD_CHARS && i - k > 1) continue
    const c = count[k] + 1
    const d = spread[k] + (piece.length - mean) ** 2
    if (c < count[i] || (c === count[i] && d < spread[i])) { count[i] = c; spread[i] = d; cut[i] = k }
  }
  const out: string[] = []
  for (let i = n; i > 0; i = cut[i]) out.unshift(joined(cut[i], i))
  return out
}

/** [text] cut between words into the fewest pieces under [limit], as even as they go. */
function evenly(text: string, limit: number): string[] {
  const count = Math.ceil(text.length / limit)
  const target = text.length / count
  const out: string[] = []
  let line = ''
  for (const word of text.split(' ')) {
    const next = line ? `${line} ${word}` : word
    if (line && (next.length > limit || (out.length < count - 1 && next.length > target + 4))) { out.push(line); line = word } else line = next
  }
  if (line) out.push(line)
  return out
}

/** A card as one line, or two broken where they come out nearest equal. */
export function wrap(card: string): string[] {
  if (card.length <= LINE_CHARS) return [card]
  let best = -1
  for (let i = 0; i < card.length; i++) {
    if (card[i] !== ' ') continue
    if (best < 0 || Math.max(i, card.length - i - 1) < Math.max(best, card.length - best - 1)) best = i
  }
  return best < 0 ? [card] : [card.slice(0, best), card.slice(best + 1)]
}
