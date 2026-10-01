import type { Placed } from './audio'

/**
 * What the audio page plays: the film's own soundtrack; the soundtrack rebuilt here from its files,
 * at the gains being reviewed; or the selected sound on its own.
 */
export type Listen = 'film' | 'remix' | 'solo'

interface Voice {
  el: HTMLAudioElement
  src: MediaElementAudioSourceNode
  gain: GainNode
  clip: Placed
  seekedAt: number
}

/**
 * The level a clip is at, `t` seconds into the film, as the show's soundtrack renders it
 * (Soundtrack.render): a one-shot plays flat; a sustained cue ramps up over its fade in and, once
 * its slide lets go of it, down over its fade out — both linear, in frames of the film.
 */
export function envelope(c: Placed, t: number, fps: number): number {
  if (!c.loop && c.fadeOut <= 0) return 1
  let g = 1
  if (c.fadeIn > 0) g *= Math.min(1, Math.max(0, ((t - c.start) * fps) / c.fadeIn))
  if (c.released != null && c.fadeOut > 0 && t >= c.released) g *= Math.max(0, 1 - ((t - c.released) * fps) / c.fadeOut)
  return g
}

/**
 * The soundtrack rebuilt from its files, in step with the picture, so a gain changed in the review is
 * heard where it will be heard — the film's own track has every cue mixed into it and cannot.
 *
 * Every clip near the playhead gets an audio element of its own through a gain node — a node, since an
 * element's own volume stops at 1 and a reviewed gain may lift a cue — opened a moment before it is
 * due and closed once it is past. Each follows the film's clock and is pulled back when it drifts;
 * a pull-back is held off while the element is still loading, or the seek would chase its own tail.
 */
export class Remix {
  private ctx: AudioContext | null = null
  private voices = new Map<string, Voice>()

  constructor(private urlOf: (c: Placed) => string | null, private fps: number) {}

  /** A browser only lets sound start from a click or a key: call this from one. */
  wake() {
    if (!this.ctx) this.ctx = new AudioContext()
    if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {})
  }

  /** Brings every clip in [clips] to where it should be at `t`, at `level(clip)` times its envelope. */
  update(t: number, playing: boolean, clips: Placed[], level: (c: Placed) => number) {
    const ctx = this.ctx
    if (!ctx) return
    const near = new Set<string>()
    for (const c of clips) {
      if (t < c.start - 2 || t > c.end + 0.5) continue
      const url = this.urlOf(c)
      if (!url) continue
      near.add(c.id)
      let v = this.voices.get(c.id)
      if (!v) {
        v = this.open(ctx, c, url)
        this.voices.set(c.id, v)
      }
      this.follow(ctx, v, t, playing, level(c))
    }
    for (const [id, v] of this.voices) if (!near.has(id)) this.close(id, v)
  }

  stop() {
    for (const [id, v] of this.voices) this.close(id, v)
  }

  /** What is sounding, for a check from the console or a test: which files, where, how loud. */
  snapshot() {
    return [...this.voices.values()].map((v) => ({
      file: v.clip.info.name, playing: !v.el.paused, at: +v.el.currentTime.toFixed(2), gain: +v.gain.gain.value.toFixed(3),
    }))
  }

  private open(ctx: AudioContext, clip: Placed, url: string): Voice {
    const el = new Audio()
    el.preload = 'auto'
    el.src = url
    const src = ctx.createMediaElementSource(el)
    const gain = ctx.createGain()
    gain.gain.value = 0
    src.connect(gain).connect(ctx.destination)
    return { el, src, gain, clip, seekedAt: -1 }
  }

  private close(id: string, v: Voice) {
    v.el.pause()
    v.src.disconnect()
    v.gain.disconnect()
    v.el.removeAttribute('src')
    v.el.load()
    this.voices.delete(id)
  }

  private follow(ctx: AudioContext, v: Voice, t: number, playing: boolean, level: number) {
    const c = v.clip
    const len = c.info.duration ?? Infinity
    let at = (c.offset ?? 0) + t - c.start
    if (c.loop && Number.isFinite(len) && len > 0) at %= len
    const sounding = playing && t >= c.start && t < c.end && at >= 0 && at < len
    if (!sounding) {
      if (!v.el.paused) v.el.pause()
      v.gain.gain.setTargetAtTime(0, ctx.currentTime, 0.01)
      return
    }
    const now = performance.now()
    if (v.el.paused) {
      v.el.currentTime = at
      v.seekedAt = now
      v.el.play().catch(() => {})
    } else if (Math.abs(v.el.currentTime - at) > 0.12 && !v.el.seeking && v.el.readyState >= 3 && now - v.seekedAt > 400) {
      v.el.currentTime = at
      v.seekedAt = now
    }
    v.gain.gain.setTargetAtTime(level * envelope(c, t, this.fps), ctx.currentTime, 0.01)
  }
}
