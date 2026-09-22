import type { Release } from './types'

export interface Waveform {
  rate: number
  peaks: Uint8Array
}

/** The release's waveform, once; null when it has none or it cannot be read. */
export async function loadWaveform(release: Release): Promise<Waveform | null> {
  const name = release.manifest.waveform
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
