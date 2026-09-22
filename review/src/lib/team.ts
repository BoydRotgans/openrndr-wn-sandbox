import type { CommentTopic } from './types'

const list = (env: string | undefined, fallback: string) =>
  (env || fallback).split(',').map((s) => s.trim()).filter(Boolean)

/** Who notes can be assigned to. `VITE_VISUAL_TEAM` and `VITE_AUDIO_TEAM` override. */
export const VISUAL_TEAM: string[] = list(import.meta.env.VITE_VISUAL_TEAM as string | undefined, 'Boyd,Jeroen')
export const AUDIO_TEAM: string[] = list(import.meta.env.VITE_AUDIO_TEAM as string | undefined, 'Valentina,Jurre')
export const TEAM: string[] = [...VISUAL_TEAM, ...AUDIO_TEAM.filter((p) => !VISUAL_TEAM.includes(p))]

export const teamFor = (topic: CommentTopic) => (topic === 'audio' ? AUDIO_TEAM : VISUAL_TEAM)

/** One colour a person, across both teams; a name outside them gets grey. */
const COLOURS = ['#e10000', '#0a7cff', '#5e00ff', '#ff7a00', '#00a3a3', '#c400c4']
export function colourOf(name: string): string {
  const i = TEAM.indexOf(name)
  return i >= 0 ? COLOURS[i % COLOURS.length] : '#8a8a8a'
}
