import { useEffect, useRef, useState } from 'react'
import Bubble, { when } from './Bubble'
import CopyKey from './CopyKey'
import { AUDIO_TEAM, colourOf } from '../lib/team'
import { GAIN_MAX, GAIN_MIN, STATUSES, clock, fmtDb, layerName, seconds, slotName, statusLabel, type GainEntry, type Lane, type Placed, type Slot, type StatusEntry } from '../lib/audio'
import type { ClipStatus, Comment, Release } from '../lib/types'

interface Props {
  release: Release
  releases: Release[]
  slot: Slot
  clip: Placed | null
  status: StatusEntry | undefined
  /** Notes on this slot in every release, oldest first, and the replies under them. */
  notes: Comment[]
  repliesOf(id: string): Comment[]
  /** Every status this slot has been given, newest first. */
  history: Comment[]
  /** Audio notes written in the review on the same state, not tied to a file. */
  stateNotes: Comment[]
  name: string
  onName(): void
  onStatus(s: ClipStatus): void
  onNote(body: string, assignees: string[]): void
  onReply(parentId: string, body: string): void
  onDone(id: string, done: boolean): void
  onEdit(id: string, body: string): void
  onDelete(id: string): void
  onGoTo(t: number): void
  onPickClip(c: Placed): void
  onPrev(): void
  onNext(): void
  onClose(): void
  /**
   * A sound-design cue's gain: the one being set or reviewed, what the film was made at, the row that
   * set it and whether it is still being dragged. Null on the other tracks and on a gap.
   */
  gain: { db: number; film: number; set: GainEntry | null; draft: boolean } | null
  /** The voice line on the same state, at the level the film plays it: what the cue is balanced against. */
  voiceLufs: number | null
  onGainDraft(db: number): void
  onGain(db: number): void
  onExportGains(): void
}

/** One sound: which file, where it plays, what it measures, where it stands, and what is said about it. */
export default function ClipPanel(p: Props) {
  const { slot, clip } = p
  const c = clip ?? slot.clips[0] ?? null
  const f = c?.info
  const status = p.status?.status ?? 'undone'
  const releaseName = (id: string) => p.releases.find((r) => r.id === id)?.name ?? ''
  const [draft, setDraft] = useState('')
  const [draftFor, setDraftFor] = useState<string[]>([])
  const [showState, setShowState] = useState(false)
  useEffect(() => { setDraft(''); setDraftFor([]) }, [slot.key])

  const send = () => {
    const body = draft.trim()
    if (!body) return
    p.onNote(body, draftFor)
    setDraft('')
    setDraftFor([])
  }

  return (
    <div className="clip-panel">
      <div className="cp-top">
        <span className={`cp-layer lane-${slot.layer}`}>{layerName(slot.layer)}</span>
        <span className="spacer" />
        <button className="cp-nav" onClick={p.onPrev} title="Previous sound on this track (←)">‹</button>
        <button className="cp-nav" onClick={p.onNext} title="Next sound on this track (→)">›</button>
        <button className="link" onClick={p.onClose} title="Back to the overview (Esc)">close</button>
      </div>

      <div className="cp-file">
        {slot.missing ? (
          <>
            <div className={`cp-name missing${status === 'done' ? ' silent' : ''}`}>{status === 'done' ? 'Silent on purpose' : `No ${slot.layer === 'voice' ? 'voice line' : 'sound design'} here`}</div>
            <div className="cp-dir">the sheet would name it <code>{slotName(slot)}</code></div>
          </>
        ) : (
          <>
            <div className="cp-name">
              <CopyKey value={f!.name} big title="Copy the file name" />
            </div>
            <div className="cp-dir">{f!.dir || '—'}{f!.absent && <span className="cp-flag bad">not on disk when built</span>}</div>
          </>
        )}
      </div>

      <div className="cp-where">
        <CopyKey value={slot.state.key} />
        <span className="muted">{slot.state.title}{slot.state.chapter ? ` · ${slot.state.chapter}` : ''}</span>
      </div>

      {c && f && (
        <div className="cp-facts">
          <span><b>plays</b> {clock(c.start)} – {clock(c.end)} <em>({seconds(c.end - c.start)})</em></span>
          {f.duration != null && <span><b>file</b> {seconds(f.duration)}</span>}
          {f.peakDb != null && <span><b>peak</b> {f.silent ? 'silent' : `${f.peakDb.toFixed(1)} dB`}</span>}
          {f.lufs != null && <span><b>loudness</b> {f.lufs.toFixed(1)} LUFS</span>}
          {!f.silent && f.head != null && f.head > 0.05 && <span className={f.head > 0.5 ? 'warn' : ''}><b>silent head</b> {f.head.toFixed(2)} s</span>}
          {c.released != null && <span><b>fades out</b> from {clock(c.released)}</span>}
          {c.loop && <span><b>loops</b></span>}
          {f.silent && <span className="cp-flag bad">the file is silent end to end</span>}
        </div>
      )}
      {slot.missing && (
        <div className="cp-facts">
          <span><b>state</b> {clock(slot.missing.start)} – {clock(slot.missing.end)} <em>({seconds(slot.missing.end - slot.missing.start)})</em></span>
          <span className="muted">Mark it done if the state should stay silent.</span>
        </div>
      )}
      {slot.clips.length > 1 && (
        <div className="cp-plays">
          <span className="assign-label">Plays {slot.clips.length}×</span>
          {slot.clips.map((x) => (
            <button key={x.id} className={`cp-play${x.id === c?.id ? ' on' : ''}`} onClick={() => p.onPickClip(x)}>{clock(x.start)}</button>
          ))}
        </div>
      )}

      <div className="cp-status" role="group" aria-label="Status">
        {STATUSES.map((s) => (
          <button key={s.key} className={`cp-st s-${s.key}${status === s.key ? ' on' : ''}`} onClick={() => status !== s.key && p.onStatus(s.key)}>
            <i className="aclip-dot" />
            {slot.missing && s.key === 'done' ? 'Silent: done' : s.label}
          </button>
        ))}
      </div>
      {p.gain && f && <GainControl g={p.gain} lufs={f.lufs ?? null} peak={f.silent ? null : f.peakDb ?? null} voiceLufs={p.voiceLufs}
        onDraft={p.onGainDraft} onCommit={p.onGain} onExport={p.onExportGains} />}

      {p.status?.row && (
        <div className="cp-status-by">
          set by <b>{p.status.row.author}</b> · {when(p.status.row.createdAt)}
          {p.status.row.releaseId !== p.release.id && <> · on {releaseName(p.status.row.releaseId)}</>}
        </div>
      )}

      <div className="cp-notes">
        {p.notes.length === 0 && <div className="empty">No notes on this sound yet.</div>}
        {p.notes.map((n) => (
          <div key={n.id}>
            {n.releaseId !== p.release.id && <div className="cp-release-tag">{releaseName(n.releaseId)}</div>}
            <Bubble
              c={n}
              mine={n.author === p.name}
              onDone={(d) => p.onDone(n.id, d)}
              onEdit={(b) => p.onEdit(n.id, b)}
              onDelete={() => p.onDelete(n.id)}
              pinLabel={n.at != null ? clock(n.at) : undefined}
              onPin={() => n.at != null && p.onGoTo(n.at)}
              replies={p.repliesOf(n.id)}
              onReply={(b) => p.onReply(n.id, b)}
              onEditReply={p.onEdit}
              onDeleteReply={p.onDelete}
              compact
            />
          </div>
        ))}
      </div>

      <div className="chat-composer cp-composer">
        <div className="chat-as">
          <span className="avatar" aria-hidden="true">{(p.name || '?').trim().charAt(0).toUpperCase()}</span>
          {p.name ? (
            <span>writing as <b>{p.name}</b> <button className="link" onClick={p.onName}>change</button></span>
          ) : (
            <button className="link" onClick={p.onName}>set your name</button>
          )}
          <span className="spacer" />
          <span className="assign-label">For</span>
          {AUDIO_TEAM.map((who) => (
            <button
              key={who}
              className={`assign-chip small${draftFor.includes(who) ? ' on' : ''}`}
              style={draftFor.includes(who) ? { background: colourOf(who), borderColor: colourOf(who) } : undefined}
              onClick={() => setDraftFor((a) => (a.includes(who) ? a.filter((x) => x !== who) : [...a, who]))}
            >
              {who}
            </button>
          ))}
        </div>
        <div className="chat-row">
          <textarea
            value={draft}
            rows={2}
            placeholder={`Feedback on ${slot.missing ? slot.state.key : f?.name ?? 'this sound'}…`}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
          />
          <button className="primary" disabled={!draft.trim()} onClick={send}>Send</button>
        </div>
      </div>

      {p.stateNotes.length > 0 && (
        <div className="cp-more">
          <button className="link" onClick={() => setShowState((v) => !v)}>
            {showState ? '▾' : '▸'} {p.stateNotes.length} audio {p.stateNotes.length === 1 ? 'note' : 'notes'} on {slot.state.key} from the review
          </button>
          {showState && p.stateNotes.map((n) => (
            <Bubble key={n.id} c={n} mine={n.author === p.name} onDone={(d) => p.onDone(n.id, d)} replies={p.repliesOf(n.id)} compact />
          ))}
        </div>
      )}

      {p.history.length > 0 && (
        <details className="cp-history">
          <summary>History ({p.history.length})</summary>
          {p.history.map((h) => (
            <div key={h.id} className="cp-history-row">
              {h.kind === 'audio_gain'
                ? <><i className="aclip-dot" /> <b>{h.author}</b> set the gain to {fmtDb(Number(h.body))}</>
                : <><i className={`aclip-dot s-${h.body}`} /> <b>{h.author}</b> → {statusLabel(h.body as ClipStatus)}</>}
              <span className="muted"> · {when(h.createdAt)}{h.releaseId !== p.release.id ? ` · ${releaseName(h.releaseId)}` : ''}{h.clipFile ? ` · ${h.clipFile}` : ''}</span>
            </div>
          ))}
        </details>
      )}
    </div>
  )
}

/** The page with nothing selected: how far each track has got, and what still wants attention. */
export function AudioOverview({ lanes, statusOf, attention, onPick, gains, onExportGains }: {
  lanes: Lane[]
  statusOf(key: string): ClipStatus
  attention: { slot: Slot; why: string }[]
  onPick(slot: Slot): void
  /** How many cues have a gain off 0 dB, and how many of those the film has not been made at yet. */
  gains: { set: number; waiting: number }
  onExportGains(): void
}) {
  return (
    <div className="clip-panel overview">
      <div className="cp-top"><span className="cp-layer">Overview</span></div>
      {lanes.map((lane) => {
        const n = { done: 0, 'needs-work': 0, undone: 0, missing: 0 }
        for (const s of lane.slots) {
          const st = statusOf(s.key)
          if (s.missing && st !== 'done') n.missing++
          else n[st]++
        }
        const total = lane.slots.length || 1
        return (
          <div key={lane.layer} className="ov-lane">
            <div className="ov-head"><b>{lane.track}</b> {lane.name} <span className="muted">{lane.slots.length} sounds</span></div>
            <div className="ov-bar">
              {(['done', 'needs-work', 'undone', 'missing'] as const).map((k) => n[k] > 0 && (
                <i key={k} className={`s-${k}`} style={{ width: `${(n[k] / total) * 100}%` }} title={`${n[k]} ${k === 'missing' ? 'missing' : statusLabel(k)}`} />
              ))}
            </div>
            <div className="ov-counts">
              <span><i className="aclip-dot s-done" />{n.done} done</span>
              <span><i className="aclip-dot s-needs-work" />{n['needs-work']} needs work</span>
              <span><i className="aclip-dot s-undone" />{n.undone} undone</span>
              {n.missing > 0 && <span><i className="aclip-dot s-missing" />{n.missing} missing</span>}
            </div>
            {lane.layer === 'design' && (
              <div className="ov-gains">
                <span>{gains.set} {gains.set === 1 ? 'gain' : 'gains'} set{gains.waiting > 0 && <em> · {gains.waiting} not in a film yet</em>}</span>
                <button className="link" onClick={onExportGains} title="The file the show reads the gains from: commit it at the project root before the next release">export show-gains.json</button>
              </div>
            )}
          </div>
        )
      })}
      <div className="assign-label ov-att-head">Needs attention</div>
      <div className="ov-attention">
        {attention.length === 0 && <div className="empty">Nothing open. Click a clip on the timeline to review it.</div>}
        {attention.map(({ slot, why }) => (
          <button key={slot.key} className="ov-item" onClick={() => onPick(slot)}>
            <span className={`aclip-dot s-${slot.missing && statusOf(slot.key) !== 'done' ? 'missing' : statusOf(slot.key)}`} />
            <span className="ov-name">{slotName(slot)}</span>
            <span className="ov-why">{why}</span>
            <span className="ov-at">{clock(slot.start)}</span>
          </button>
        ))}
      </div>
      <div className="ov-help">
        Click a clip to play its part of the film. <kbd>space</kbd> play · <kbd>←</kbd><kbd>→</kbd> sounds on a track ·
        <kbd>↑</kbd><kbd>↓</kbd> tracks · <kbd>M</kbd> remix · <kbd>S</kbd> solo · <kbd>L</kbd> loop · <kbd>1</kbd><kbd>2</kbd><kbd>3</kbd> undone / needs work / done ·
        <kbd>[</kbd><kbd>]</kbd> gain −/+1 dB (⇧ half) ·
        ⌘ + scroll to zoom, <kbd>\</kbd> to fit
      </div>
    </div>
  )
}

/**
 * A cue's gain: the fader, heard in the remix as it moves and written when it is let go; what the
 * gain does to the file's loudness and peak; and how far under the voice on the same state it sits,
 * which is the balance the gain is set for.
 */
function GainControl({ g, lufs, peak, voiceLufs, onDraft, onCommit, onExport }: {
  g: { db: number; film: number; set: GainEntry | null; draft: boolean }
  lufs: number | null
  peak: number | null
  voiceLufs: number | null
  onDraft(db: number): void
  onCommit(db: number): void
  onExport(): void
}) {
  const pending = Math.abs(g.db - g.film) >= 0.05
  const clips = peak != null && peak + g.db > 0
  const commit = (raw: string) => {
    const v = Number(raw)
    if (raw.trim() !== '' && Number.isFinite(v)) onCommit(v)
  }
  // Arrow keys on the fader step it half a dB a press: written once they stop, not once a press.
  const settle = useRef(0)
  useEffect(() => () => window.clearTimeout(settle.current), [])
  const commitSoon = (raw: string) => {
    window.clearTimeout(settle.current)
    settle.current = window.setTimeout(() => commit(raw), 600)
  }
  return (
    <div className={`cp-gain${pending ? ' pending' : ''}`}>
      <div className="cp-gain-head">
        <span className="assign-label">Gain</span>
        <b className="cp-gain-value">{fmtDb(g.db)}</b>
        <span className="cp-gain-note">
          {g.draft ? 'setting…' : pending ? `the film has ${fmtDb(g.film)} · goes into the next release` : Math.abs(g.db) < 0.05 ? 'as delivered' : 'in the film'}
        </span>
        {Math.abs(g.db) >= 0.05 && <button className="link" onClick={() => onCommit(0)}>reset to 0 dB</button>}
      </div>
      <div className="cp-gain-row">
        <button onClick={() => onCommit(g.db - 1)} title="Down 1 dB ([)">−1</button>
        <input
          type="range" min={GAIN_MIN} max={GAIN_MAX} step={0.5} value={g.db}
          onChange={(e) => onDraft(Number(e.target.value))}
          onPointerUp={(e) => commit(e.currentTarget.value)}
          onKeyUp={(e) => commitSoon(e.currentTarget.value)}
          aria-label="Gain in dB"
        />
        <button onClick={() => onCommit(g.db + 1)} title="Up 1 dB (])">+1</button>
        <input
          key={g.db.toFixed(1)} className="cp-gain-num" type="number" step={0.5} min={GAIN_MIN} max={GAIN_MAX} defaultValue={g.db.toFixed(1)}
          onKeyDown={(e) => { if (e.key === 'Enter') commit(e.currentTarget.value) }}
          onBlur={(e) => commit(e.currentTarget.value)}
          aria-label="Gain in dB"
        />
        <span className="muted">dB</span>
      </div>
      <div className="cp-gain-meter">
        {lufs != null && <span><b>loudness</b> {(lufs + g.db).toFixed(1)} LUFS</span>}
        {lufs != null && voiceLufs != null && <span><b>under the voice</b> {(voiceLufs - (lufs + g.db)).toFixed(1)} LU</span>}
        {peak != null && <span className={clips ? 'bad' : ''}><b>peak</b> {(peak + g.db).toFixed(1)} dB{clips ? ' — clips' : ''}</span>}
      </div>
      <div className="cp-gain-hint">
        Heard in <b>remix</b> as you set it{g.set ? <> · set by {g.set.row.author}, {when(g.set.row.createdAt)}</> : null} ·{' '}
        <button className="link" onClick={onExport}>export show-gains.json</button>
      </div>
    </div>
  )
}
