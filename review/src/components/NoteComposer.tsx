import { useEffect, useRef, useState } from 'react'
import { colourOf, teamFor } from '../lib/team'
import type { CommentTopic } from '../lib/types'

interface Props {
  frame: number
  onAdd(body: string, topic: CommentTopic, assignees: string[]): void
  onCancel(): void
}

/** The box that opens at a dropped note: what it is about, who it is for, the text. */
export default function NoteComposer({ frame, onAdd, onCancel }: Props) {
  const [topic, setTopic] = useState<CommentTopic>('visual')
  const [assignees, setAssignees] = useState<string[]>([])
  const [text, setText] = useState('')
  const box = useRef<HTMLTextAreaElement>(null)
  useEffect(() => box.current?.focus(), [])

  const add = () => {
    const body = text.trim()
    if (body) onAdd(body, topic, assignees.filter((a) => teamFor(topic).includes(a)))
  }

  return (
    <div className="note" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()}>
      <div className="note-head">
        <div className="note-topics">
          {(['visual', 'audio'] as CommentTopic[]).map((t) => (
            <button key={t} className={`note-topic${topic === t ? ' on' : ''}`} onClick={() => setTopic(t)}>
              {t === 'visual' ? 'Visual' : 'Audio'}
            </button>
          ))}
        </div>
        <span className="note-frame">frame {frame}</span>
      </div>
      {(
        <div className="assign-row">
          <span className="assign-label">For</span>
          {teamFor(topic).map((p) => (
            <button
              key={p}
              className={`assign-chip${assignees.includes(p) ? ' on' : ''}`}
              style={assignees.includes(p) ? { background: colourOf(p), borderColor: colourOf(p) } : undefined}
              onClick={() => setAssignees((a) => (a.includes(p) ? a.filter((x) => x !== p) : [...a, p]))}
            >
              {p}
            </button>
          ))}
        </div>
      )}
      <textarea
        ref={box}
        value={text}
        rows={3}
        placeholder="Write a note…"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); add() }
          if (e.key === 'Escape') onCancel()
        }}
      />
      <div className="composer-row">
        <button onClick={onCancel}>Cancel</button>
        <button className="primary" disabled={!text.trim()} onClick={add}>Add comment</button>
      </div>
    </div>
  )
}
