import type { Comment, Manifest, NewReleaseInput, Progress, Release, Store } from './types'

/**
 * The site with no Supabase behind it: releases are read off `public/releases/index.json`
 * (what tools/release_build.py writes), and comments live in this browser's localStorage,
 * so they are yours alone until a Supabase project is configured.
 */
const COMMENTS = 'wn-review.local-comments.v1'
const RELEASES = 'wn-review.local-releases.v1'

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}
function write(key: string, value: unknown) {
  localStorage.setItem(key, JSON.stringify(value))
}

const base = import.meta.env.BASE_URL.replace(/\/$/, '')

export function releaseFromManifest(id: string, manifest: Manifest, folder: string, videoUrl?: string): Release {
  const video = videoUrl || (/^https?:\/\//.test(manifest.video) ? manifest.video : `${folder}/${manifest.video}`)
  return {
    id,
    slug: manifest.slug,
    name: manifest.name,
    created: manifest.created,
    notes: manifest.notes ?? '',
    videoUrl: video,
    thumbBase: `${folder}/`,
    manifest,
  }
}

export class LocalStore implements Store {
  readonly kind = 'local' as const

  async listReleases(): Promise<Release[]> {
    const out: Release[] = []
    try {
      const index = await fetch(`${base}/releases/index.json`, { cache: 'no-cache' }).then((r) => (r.ok ? r.json() : { releases: [] }))
      for (const entry of index.releases ?? []) {
        const folder = `${base}/releases/${entry.slug}`
        const manifest = (await fetch(`${folder}/manifest.json`, { cache: 'no-cache' }).then((r) => r.json())) as Manifest
        out.push(releaseFromManifest(entry.slug, manifest, folder))
      }
    } catch (e) {
      console.warn('no releases folder', e)
    }
    // releases added through the page in local mode: manifest kept here, video by URL only
    for (const r of read<Release[]>(RELEASES, [])) out.push(r)
    return out.sort((a, b) => b.created.localeCompare(a.created))
  }

  async addRelease(input: NewReleaseInput, progress: Progress): Promise<Release> {
    progress('saving', 0.5)
    const id = `local-${input.manifest.slug}-${Date.now().toString(36)}`
    const manifest: Manifest = { ...input.manifest, name: input.name, notes: input.notes }
    const video = input.videoUrl || (input.videoFile ? URL.createObjectURL(input.videoFile) : manifest.video)
    const release: Release = {
      id, slug: manifest.slug, name: input.name, created: manifest.created, notes: input.notes,
      videoUrl: video, thumbBase: '', manifest,
    }
    if (input.thumbFiles?.length) {
      // an object URL a thumb, for this session; local mode cannot keep files
      const byName = new Map(input.thumbFiles.map((f) => [f.webkitRelativePath.split('/').slice(1).join('/') || f.name, f]))
      release.manifest = {
        ...manifest,
        states: manifest.states.map((s) => {
          const f = byName.get(s.thumb) ?? byName.get(s.thumb.replace(/^thumbs\//, ''))
          return f ? { ...s, thumb: URL.createObjectURL(f) } : s
        }),
      }
    }
    write(RELEASES, [...read<Release[]>(RELEASES, []), release])
    progress('saved', 1)
    return release
  }

  async listComments(): Promise<Comment[]> {
    return read<Comment[]>(COMMENTS, []).map((c) => ({ ...c, kind: c.kind ?? 'comment', topic: c.topic ?? 'visual', assignees: c.assignees ?? [], parentId: c.parentId ?? null }))
  }

  async addComment(c: Omit<Comment, 'id' | 'createdAt' | 'done' | 'doneBy' | 'doneAt'>): Promise<Comment> {
    const full: Comment = {
      ...c, id: crypto.randomUUID(), createdAt: new Date().toISOString(), done: false, doneBy: null, doneAt: null,
    }
    write(COMMENTS, [...read<Comment[]>(COMMENTS, []), full])
    return full
  }

  async setDone(id: string, done: boolean, by: string): Promise<void> {
    const all = read<Comment[]>(COMMENTS, []).map((c) =>
      c.id === id ? { ...c, done, doneBy: done ? by : null, doneAt: done ? new Date().toISOString() : null } : c,
    )
    write(COMMENTS, all)
  }

  async editComment(id: string, body: string): Promise<void> {
    write(COMMENTS, read<Comment[]>(COMMENTS, []).map((c) => (c.id === id ? { ...c, body, editedAt: new Date().toISOString() } : c)))
  }

  async deleteComment(id: string): Promise<void> {
    write(COMMENTS, read<Comment[]>(COMMENTS, []).filter((c) => c.id !== id))
  }

  subscribe(onChange: () => void): () => void {
    const handler = (e: StorageEvent) => {
      if (!e.key || e.key === COMMENTS || e.key === RELEASES) onChange()
    }
    window.addEventListener('storage', handler)
    return () => window.removeEventListener('storage', handler)
  }
}
