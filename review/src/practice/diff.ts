/**
 * What changed between two texts, a word at a time: the words both share, those taken out of the first, and those
 * put into the second, in reading order — so a change reads as the sentence it was, struck through where words went
 * and marked where words came. The longest common run of words, worked out the plain way: a script's line is a few
 * dozen words, so the table is small.
 */
export interface Piece {
  text: string
  kind: 'same' | 'out' | 'in'
}

export function wordDiff(before: string, after: string): Piece[] {
  const a = before.split(/\s+/).filter(Boolean)
  const b = after.split(/\s+/).filter(Boolean)
  // how many words the rest of each text still shares, from i in a and j in b
  const shared = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      shared[i][j] = a[i] === b[j] ? shared[i + 1][j + 1] + 1 : Math.max(shared[i + 1][j], shared[i][j + 1])
  const out: Piece[] = []
  const push = (text: string, kind: Piece['kind']) => {
    const last = out[out.length - 1]
    if (last && last.kind === kind) last.text += ' ' + text
    else out.push({ text, kind })
  }
  let i = 0, j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { push(a[i], 'same'); i++; j++ }
    else if (shared[i + 1][j] >= shared[i][j + 1]) push(a[i++], 'out')
    else push(b[j++], 'in')
  }
  while (i < a.length) push(a[i++], 'out')
  while (j < b.length) push(b[j++], 'in')
  return out
}
