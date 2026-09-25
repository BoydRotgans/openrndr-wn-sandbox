import type { Release } from './types'

export interface Waveform {
  rate: number
  peaks: Uint8Array
}

/**
 * The peaks of the soundtrack playing: [track] is `film` for the film's own mix, or the key of one
 * of the release's other tracks, which carries its own. A track with none falls back to the film's.
 * Null when there is nothing to draw or it cannot be read.
 */
export async function loadWaveform(release: Release, track = 'film'): Promise<Waveform | null> {
  const other = release.manifest.audio?.find((a) => a.key === track)
  const name = other?.waveform || release.manifest.waveform
  if (!name) return null
  const url = /^(https?:|blob:)/.test(name) ? name : `${release.thumbBase}${name}`
  try {
    // revalidated on every load: a release's waveform can be rebuilt under the same name
    const r = await fetch(url, { cache: 'no-cache' })
    if (!r.ok) return null
    const j = (await r.json()) as { rate: number; peaks: number[] }
    return { rate: j.rate, peaks: Uint8Array.from(j.peaks) }
  } catch {
    return null
  }
}
