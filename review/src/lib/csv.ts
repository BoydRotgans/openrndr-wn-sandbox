import type { Comment, Release } from './types'

function cell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Every comment across every release, one row each, in the order they were written. */
export function commentsCsv(comments: Comment[], releases: Release[]): string {
  const byId = new Map(releases.map((r) => [r.id, r]))
  const head = ['release', 'release_date', 'chapter', 'slide', 'state', 'title', 'time_in_timeline',
    'kind', 'topic', 'pinned_at', 'pinned_frame', 'pin_x', 'pin_y', 'assigned_to', 'reply_to', 'author', 'comment', 'written', 'done', 'done_by', 'done_at']
  const rows = [...comments]
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map((c) => {
      const r = byId.get(c.releaseId)
      const s = r?.manifest.states.find((x) => x.key === c.stateKey)
      return [
        r?.name ?? c.releaseId, r?.created ?? '', s?.chapter ?? '', c.slideId, c.stateKey, s?.title ?? (c.stateKey === 'general' ? 'General' : ''),
        s ? formatTime(s.start) : '', c.kind, c.topic,
        c.at != null ? c.at.toFixed(3) : '', c.at != null && r ? Math.round(c.at * r.manifest.fps) : '',
        c.x != null ? c.x.toFixed(3) : '', c.y != null ? c.y.toFixed(3) : '',
        (c.assignees ?? []).join('; '), c.parentId ?? '', c.author, c.body, c.createdAt,
        c.done ? 'yes' : 'no', c.doneBy ?? '', c.doneAt ?? '',
      ].map(cell).join(',')
    })
  return '﻿' + [head.join(','), ...rows].join('\r\n') + '\r\n'
}

export function download(name: string, text: string, type = 'text/csv;charset=utf-8') {
  const blob = new Blob([text], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}
