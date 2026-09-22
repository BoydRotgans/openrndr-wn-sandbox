import { useState } from 'react'
import type { Manifest, NewReleaseInput } from '../lib/types'

export function NameDialog({ initial, onSave, onCancel }: { initial: string; onSave(name: string): void; onCancel?(): void }) {
  const [name, setName] = useState(initial)
  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Who is writing?</h2>
        <p>Your name goes on every comment you post, so the team knows who said what.</p>
        <input
          autoFocus
          value={name}
          placeholder="Your name"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && name.trim() && onSave(name.trim())}
        />
        <div className="modal-row">
          {onCancel && <button onClick={onCancel}>Cancel</button>}
          <button className="primary" disabled={!name.trim()} onClick={() => onSave(name.trim())}>Save</button>
        </div>
      </div>
    </div>
  )
}

interface NewReleaseProps {
  local: boolean
  onSubmit(input: NewReleaseInput, progress: (label: string, f: number) => void): Promise<void>
  onClose(): void
}

export function NewReleaseDialog({ local, onSubmit, onClose }: NewReleaseProps) {
  const [name, setName] = useState('')
  const [notes, setNotes] = useState('')
  const [manifest, setManifest] = useState<Manifest | null>(null)
  const [manifestError, setManifestError] = useState('')
  const [videoFile, setVideoFile] = useState<File | null>(null)
  const [videoUrl, setVideoUrl] = useState('')
  const [thumbs, setThumbs] = useState<File[]>([])
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<{ label: string; f: number } | null>(null)
  const [error, setError] = useState('')

  const readManifest = async (f: File | undefined) => {
    setManifestError('')
    setManifest(null)
    if (!f) return
    try {
      const m = JSON.parse(await f.text()) as Manifest
      if (!Array.isArray(m.states) || !m.slug) throw new Error('not a release manifest')
      setManifest(m)
      if (!name) setName(m.name)
      if (!notes && m.notes) setNotes(m.notes)
    } catch (e) {
      setManifestError(`Could not read manifest: ${(e as Error).message}`)
    }
  }

  const submit = async () => {
    if (!manifest) return
    setBusy(true)
    setError('')
    try {
      await onSubmit(
        { name: name.trim() || manifest.name, notes, manifest, videoFile, videoUrl, thumbFiles: thumbs },
        (label, f) => setProgress({ label, f }),
      )
      onClose()
    } catch (e) {
      setError((e as Error).message || String(e))
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>New release</h2>
        <p className="muted">
          Build the folder first: <code>python3 tools/release_build.py --video … --name …</code> writes a
          manifest, a thumbnail per state and a web-sized video into <code>review/public/releases/&lt;slug&gt;/</code>.
          {local
            ? ' In local mode that folder is listed by itself on the next reload; this form only registers a release whose video is already online.'
            : ' Pick that manifest and its thumbs folder here; a video under the storage limit can be uploaded, a bigger one goes to any host and its URL below.'}
        </p>

        <label className="field">
          <span>Manifest (manifest.json)</span>
          <input type="file" accept="application/json,.json" onChange={(e) => readManifest(e.target.files?.[0])} />
          {manifest && <span className="ok">{manifest.states.length} states, {(manifest.duration / 60).toFixed(1)} min</span>}
          {manifestError && <span className="err">{manifestError}</span>}
        </label>

        <label className="field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Release 21 September" />
        </label>

        <label className="field">
          <span>Notes</span>
          <textarea value={notes} rows={2} onChange={(e) => setNotes(e.target.value)} placeholder="What changed in this release" />
        </label>

        <label className="field">
          <span>Thumbnails (the thumbs folder)</span>
          <input
            type="file"
            accept="image/*"
            multiple
            // @ts-expect-error non-standard but universal: pick a folder
            webkitdirectory=""
            onChange={(e) => setThumbs(Array.from(e.target.files ?? []))}
          />
          {thumbs.length > 0 && <span className="ok">{thumbs.length} files</span>}
        </label>

        <label className="field">
          <span>Video — upload a file</span>
          <input type="file" accept="video/mp4,video/*" onChange={(e) => setVideoFile(e.target.files?.[0] ?? null)} disabled={local} />
          {videoFile && <span className="ok">{(videoFile.size / 1e6).toFixed(0)} MB</span>}
        </label>
        <label className="field">
          <span>… or the URL of a video already online</span>
          <input value={videoUrl} onChange={(e) => setVideoUrl(e.target.value)} placeholder="https://…/video.mp4" />
        </label>

        {progress && (
          <div className="progress-bar" title={progress.label}>
            <span style={{ width: `${progress.f * 100}%` }} />
            <em>{progress.label}</em>
          </div>
        )}
        {error && <div className="err">{error}</div>}

        <div className="modal-row">
          <button onClick={onClose} disabled={busy}>Cancel</button>
          <button className="primary" disabled={!manifest || busy} onClick={submit}>
            {busy ? 'Adding…' : 'Add release'}
          </button>
        </div>
      </div>
    </div>
  )
}
