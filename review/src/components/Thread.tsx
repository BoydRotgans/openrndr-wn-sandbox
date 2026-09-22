import { useEffect, useRef, useState } from 'react'
import Bubble, { when } from './Bubble'
import CopyKey from './CopyKey'
import { formatTime } from '../lib/csv'
import type { Comment, CommentTopic, Release, StateInfo } from '../lib/types'
import { colourOf, teamFor } from '../lib/team'

interface Props {
  release: Release
  state: StateInfo | null
  comments: Comment[]
  /** Open comments on the same slide in other releases, to tick off as this one is checked. */
  earlier: { release: Release; comments: Comment[] }[]
  name: string
  thumbUrl(s: StateInfo): string
  onPost(body: string, kind?: 'comment' | 'voiceover'): void
  /** A note written in the chat rather than dropped on the picture: at the current frame, no spot. */
  onWrite(body: string, topic: CommentTopic, assignees: string[]): void
  onName(): void
  onReply(parentId: string, body: string): void
  onDone(id: string, done: boolean): void
  onEdit(id: string, body: string): void
  onDelete(id: string): void
  fps: number
  onGoTo(at: number, id: string): void
}

/** The chat on one state: what has been said about it, and a line to add. */
export default function Thread({ release, state, comments, earlier, name, thumbUrl, onPost, onDone, onEdit, onDelete, fps, onGoTo, onReply, onWrite, onName }: Props) {
  const [showEarlier, setShowEarlier] = useState(false)
  const [topic, setTopic] = useState<CommentTopic>('visual')
  const [draft, setDraft] = useState('')
  const [draftFor, setDraftFor] = useState<string[]>([])
  const write = () => {
    const body = draft.trim()
    if (!body) return
    onWrite(body, topic, draftFor.filter((a) => teamFor(topic).includes(a)))
    setDraft('')
    setDraftFor([])
  }
  const list = useRef<HTMLDivElement>(null)
  const allRemarks = comments.filter((c) => c.kind !== 'voiceover')
  const remarks = allRemarks.filter((c) => !c.parentId && c.topic === topic)
  const repliesOf = (id: string) => allRemarks.filter((c) => c.parentId === id)
  const openOn = (t: CommentTopic) => allRemarks.filter((c) => !c.parentId && c.topic === t && !c.done).length

  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight })
  }, [remarks.length, state?.key, topic])

  const earlierOnTopic = earlier.map((e) => ({ ...e, comments: e.comments.filter((c) => c.topic === topic) })).filter((e) => e.comments.length)
  const earlierCount = earlierOnTopic.reduce((n, e) => n + e.comments.length, 0)

  return (
    <aside className="thread">
      <div className="thread-comments">
      {state && (
        <div className="commenting-on">
          <img src={thumbUrl(state)} alt="" />
          <CopyKey value={state.key} />
          <span className="co-meta">{state.title} · {formatTime(state.start)} – {formatTime(state.end)}</span>
          <span className="spacer" />
          <span className="co-hint" title="Click on the picture to add a note at that spot and frame">
            <svg width="14" height="14" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"><path d="M3 4.5A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v6a1.5 1.5 0 0 1-1.5 1.5H8l-4 3.5V12H4.5A1.5 1.5 0 0 1 3 10.5z" /></svg>
            click the picture to add a note
          </span>
        </div>
      )}
      <div className="topics">
        {(['visual', 'audio'] as CommentTopic[]).map((t) => (
          <button key={t} className={`topic${topic === t ? ' on' : ''}`} onClick={() => setTopic(t)}>
            {t === 'visual' ? 'Visual' : 'Audio'}
            {openOn(t) > 0 && <span className="topic-count">{openOn(t)}</span>}
          </button>
        ))}
      </div>
      {(<>
      <div className="thread-list" ref={list}>
        {remarks.length === 0 && <div className="empty">No {topic} notes yet.</div>}
        {remarks.map((c) => (
          <Bubble
            key={c.id}
            c={c}
            mine={c.author === name}
            onDone={(d) => onDone(c.id, d)}
            onEdit={(b) => onEdit(c.id, b)}
            onDelete={() => onDelete(c.id)}
            pinLabel={c.at != null && state ? `frame ${Math.round((c.at - state.start) * fps)}` : undefined}
            onPin={() => c.at != null && onGoTo(c.at, c.id)}
            replies={repliesOf(c.id)}
            onReply={(body) => onReply(c.id, body)}
            onEditReply={onEdit}
            onDeleteReply={onDelete}
            compact
          />
        ))}

        {earlierCount > 0 && (
          <div className="earlier">
            <button className="link" onClick={() => setShowEarlier((v) => !v)}>
              {showEarlier ? '▾' : '▸'} {earlierCount} open from earlier releases on this slide
            </button>
            {showEarlier &&
              earlierOnTopic.map((e) => (
                <div key={e.release.id} className="earlier-release">
                  <div className="earlier-name">{e.release.name}</div>
                  {e.comments.map((c) => (
                    <Bubble key={c.id} c={c} mine={c.author === name} onDone={(d) => onDone(c.id, d)} />
                  ))}
                </div>
              ))}
          </div>
        )}
      </div>
      <div className="chat-composer">
        <div className="chat-as">
          <span className="avatar" aria-hidden="true">{(name || '?').trim().charAt(0).toUpperCase()}</span>
          {name ? (
            <span>writing as <b>{name}</b> <button className="link" onClick={onName}>change</button></span>
          ) : (
            <button className="link" onClick={onName}>set your name</button>
          )}
          <span className="spacer" />
          <span className="assign-label">For</span>
          {teamFor(topic).map((p) => (
            <button
              key={p}
              className={`assign-chip small${draftFor.includes(p) ? ' on' : ''}`}
              style={draftFor.includes(p) ? { background: colourOf(p), borderColor: colourOf(p) } : undefined}
              onClick={() => setDraftFor((a) => (a.includes(p) ? a.filter((x) => x !== p) : [...a, p]))}
            >
              {p}
            </button>
          ))}
        </div>
        <div className="chat-row">
          <textarea
            value={draft}
            rows={1}
            placeholder={`Write a ${topic} note on this frame… (or click the picture to pin one to a spot)`}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); write() }
            }}
          />
          <button className="primary" disabled={!draft.trim()} onClick={write}>Send</button>
        </div>
      </div>
      </>)}
      </div>
    </aside>
  )
}

/**
 * The voice-over for the state: the line as it stands — the latest mutation, else the script as
 * released — solid, with a button to edit it. Saving records the new text as a mutation, and the
 * mutations stand under it as comments of their own kind: edited, deleted or ticked done like any
 * other. The script itself is only ever changed by hand from there.
 */
export function VoiceOver({ state, updates, name, onSave, onDone, onEdit, onDelete }: {
  state: StateInfo
  updates: Comment[]
  name: string
  onSave(text: string): void
  onDone(id: string, done: boolean): void
  onEdit(id: string, body: string): void
  onDelete(id: string): void
}) {
  const original = state.voiceover?.trim() ?? ''
  // the latest mutation stands whether or not it is ticked: the tick says it has been carried
  // into the script, and a deleted one is gone from the list altogether
  const latest = updates.length ? updates[updates.length - 1] : null
  const effective = latest ? latest.body : original
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(effective)
  const box = useRef<HTMLTextAreaElement>(null)
  useEffect(() => { if (editing) box.current?.focus() }, [editing])

  const save = () => {
    const t = text.trim()
    if (t && t !== effective.trim()) onSave(t)
    setEditing(false)
  }

  return (
    <div className="voiceover">
      <div className="voiceover-head">
        <span>Voice-over</span>
        {!effective && <span className="voiceover-missing">missing</span>}
        {latest && <span className="voiceover-by">changed by {latest.author}</span>}
        {!editing && (
          <button className="voiceover-edit" onClick={() => { setText(effective); setEditing(true) }}>
            {effective ? 'Edit' : 'Add voice-over'}
          </button>
        )}
      </div>
      {editing ? (
        <>
          <textarea
            ref={box}
            value={text}
            rows={4}
            placeholder="Write the voice-over…"
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); save() }
              if (e.key === 'Escape') setEditing(false)
            }}
          />
          <div className="composer-row">
            <button onClick={() => setEditing(false)}>Cancel</button>
            <button className="primary" disabled={!text.trim() || text.trim() === effective.trim()} onClick={save}>Save</button>
          </div>
        </>
      ) : (
        <div className={`voiceover-text${effective ? '' : ' empty'}`}>{effective || 'No voice-over for this state yet.'}</div>
      )}
      {latest && original && original !== latest.body && (
        <details className="voiceover-original">
          <summary>The script as released</summary>
          <p>{original}</p>
        </details>
      )}
      {updates.length > 0 && (
        <div className="voiceover-history">
          <div className="assign-label">Mutations</div>
          {[...updates].reverse().map((u) => (
            <Bubble key={u.id} c={u} mine={u.author === name} onDone={(d) => onDone(u.id, d)} onEdit={(b) => onEdit(u.id, b)} onDelete={() => onDelete(u.id)} />
          ))}
        </div>
      )}
    </div>
  )
}
