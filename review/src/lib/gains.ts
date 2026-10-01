import type { Comment } from './types'

/**
 * `show-gains.json`: the gain the audio timeline review gave each sound-design cue, keyed by state,
 * which the show reads (`SLIDES_GAINS`, slideshow/ReviewGains.kt) and applies as each cue is fired.
 * One definition for the page's export and for `pnpm pull-gains`, so the two cannot write different
 * files. Only type imports, so the script can load this file as it stands.
 */
export interface GainsFile {
  about: string
  written: string
  design: Record<string, { db: number; file: string | null; by: string; at: string }>
}

export function gainsFile(comments: Comment[]): GainsFile {
  const latest = new Map<string, Comment>()
  for (const c of [...comments].sort((a, b) => a.createdAt.localeCompare(b.createdAt))) {
    if (c.kind === 'audio_gain' && c.clip?.startsWith('design:') && Number.isFinite(Number(c.body))) latest.set(c.clip, c)
  }
  const design: GainsFile['design'] = {}
  for (const key of [...latest.keys()].sort()) {
    const c = latest.get(key)!
    design[key.slice('design:'.length)] = { db: Math.round(Number(c.body) * 10) / 10, file: c.clipFile ?? null, by: c.author, at: c.createdAt }
  }
  return {
    about:
      'The gain the audio timeline review gave each sound design cue, per state (<slide-id>-<LETTER>), in dB against the file as delivered. ' +
      'Written by the review site; read by the show through SLIDES_GAINS and applied as each cue is fired.',
    written: new Date().toISOString(),
    design,
  }
}

export const gainsJson = (comments: Comment[]) => JSON.stringify(gainsFile(comments), null, 1) + '\n'
