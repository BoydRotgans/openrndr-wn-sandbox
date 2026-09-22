import { createClient, type SupabaseClient } from '@supabase/supabase-js'
import * as tus from 'tus-js-client'
import type { Comment, Manifest, NewReleaseInput, Progress, Release, Store } from './types'

/**
 * The site on Supabase: two tables and one public bucket, made by supabase/schema.sql.
 * Swapping projects is the two VITE_SUPABASE_* keys and running that file once.
 */
const BUCKET = 'releases'

interface ReleaseRow {
  id: string
  slug: string
  name: string
  notes: string | null
  created_at: string
  video_url: string
  thumb_base: string | null
  manifest: Manifest
}

interface CommentRow {
  id: string
  release_id: string
  kind: string | null
  topic: string | null
  slide_id: string
  state_key: string
  author: string
  body: string
  created_at: string
  done: boolean
  done_by: string | null
  done_at: string | null
  edited_at?: string | null
  at?: number | null
  x?: number | null
  y?: number | null
  assignees?: string[] | null
  parent_id?: string | null
}

/** The kinds that belong to the voice-over panel rather than to the thread. */
const VOICE_KINDS = ['voiceover', 'voiceover_extended', 'voiceover_note', 'voiceover_extended_note'] as const

const toRelease = (r: ReleaseRow): Release => ({
  id: r.id,
  slug: r.slug,
  name: r.name,
  created: r.manifest?.created ?? r.created_at.slice(0, 10),
  notes: r.notes ?? '',
  videoUrl: r.video_url,
  thumbBase: r.thumb_base ?? '',
  manifest: r.manifest,
})

const toComment = (c: CommentRow): Comment => ({
  id: c.id,
  kind: (VOICE_KINDS as readonly string[]).includes(c.kind ?? '') ? (c.kind as Comment['kind']) : 'comment',
  topic: c.topic === 'audio' ? 'audio' : 'visual',
  releaseId: c.release_id,
  slideId: c.slide_id,
  stateKey: c.state_key,
  author: c.author,
  body: c.body,
  createdAt: c.created_at,
  done: c.done,
  doneBy: c.done_by,
  doneAt: c.done_at,
  editedAt: c.edited_at ?? null,
  at: c.at ?? null,
  x: c.x ?? null,
  y: c.y ?? null,
  assignees: c.assignees ?? [],
  parentId: c.parent_id ?? null,
})

export class SupabaseStore implements Store {
  readonly kind = 'supabase' as const
  private client: SupabaseClient

  constructor(private url: string, private anonKey: string) {
    this.client = createClient(url, anonKey)
  }

  async listReleases(): Promise<Release[]> {
    const { data, error } = await this.client.from('releases').select('*').order('created_at', { ascending: false })
    if (error) throw error
    return (data as ReleaseRow[]).map(toRelease)
  }

  private publicUrl(path: string) {
    return this.client.storage.from(BUCKET).getPublicUrl(path).data.publicUrl
  }

  /** Resumable, in chunks, with progress — a film is hundreds of megabytes and a plain upload has no progress. */
  private uploadResumable(file: File, path: string, progress: (f: number) => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const upload = new tus.Upload(file, {
        endpoint: `${this.url}/storage/v1/upload/resumable`,
        retryDelays: [0, 3000, 5000, 10000, 20000],
        headers: { authorization: `Bearer ${this.anonKey}`, apikey: this.anonKey, 'x-upsert': 'true' },
        uploadDataDuringCreation: true,
        removeFingerprintOnSuccess: true,
        metadata: { bucketName: BUCKET, objectName: path, contentType: file.type || 'video/mp4', cacheControl: '3600' },
        chunkSize: 6 * 1024 * 1024,
        onError: reject,
        onProgress: (sent, total) => progress(total ? sent / total : 0),
        onSuccess: () => resolve(),
      })
      upload.findPreviousUploads().then((previous) => {
        if (previous.length) upload.resumeFromPreviousUpload(previous[0])
        upload.start()
      })
    })
  }

  async addRelease(input: NewReleaseInput, progress: Progress): Promise<Release> {
    const slug = input.manifest.slug
    const folder = `${slug}`
    let manifest: Manifest = { ...input.manifest, name: input.name, notes: input.notes }

    if (input.thumbFiles?.length) {
      const files = input.thumbFiles
      for (let i = 0; i < files.length; i++) {
        const f = files[i]
        const rel = f.webkitRelativePath ? f.webkitRelativePath.split('/').slice(1).join('/') : f.name
        const path = `${folder}/${rel.startsWith('thumbs/') ? rel : `thumbs/${rel}`}`
        progress(`thumbnails ${i + 1}/${files.length}`, i / files.length)
        const { error } = await this.client.storage.from(BUCKET).upload(path, f, { upsert: true, contentType: f.type || 'image/jpeg' })
        if (error) throw error
      }
    }

    let videoUrl = input.videoUrl?.trim() || ''
    if (input.videoFile) {
      const path = `${folder}/video.mp4`
      await this.uploadResumable(input.videoFile, path, (f) => progress(`video ${(f * 100).toFixed(0)}%`, f))
      videoUrl = this.publicUrl(path)
    }
    if (!videoUrl) videoUrl = /^https?:\/\//.test(manifest.video) ? manifest.video : this.publicUrl(`${folder}/${manifest.video}`)
    manifest = { ...manifest, video: videoUrl }

    progress('registering', 0.98)
    const row = {
      slug, name: input.name, notes: input.notes, video_url: videoUrl,
      thumb_base: this.publicUrl(`${folder}/`), manifest,
    }
    const { data, error } = await this.client.from('releases').upsert(row, { onConflict: 'slug' }).select().single()
    if (error) throw error
    progress('done', 1)
    return toRelease(data as ReleaseRow)
  }

  async listComments(): Promise<Comment[]> {
    const { data, error } = await this.client.from('comments').select('*').order('created_at')
    if (error) throw error
    return (data as CommentRow[]).map(toComment)
  }

  async addComment(c: Omit<Comment, 'id' | 'createdAt' | 'done' | 'doneBy' | 'doneAt'>): Promise<Comment> {
    const { data, error } = await this.client
      .from('comments')
      .insert({ release_id: c.releaseId, kind: c.kind, topic: c.topic, slide_id: c.slideId, state_key: c.stateKey, author: c.author, body: c.body, at: c.at ?? null, x: c.x ?? null, y: c.y ?? null, assignees: c.assignees ?? [], parent_id: c.parentId ?? null })
      .select()
      .single()
    if (error) throw error
    return toComment(data as CommentRow)
  }

  async setDone(id: string, done: boolean, by: string): Promise<void> {
    const { error } = await this.client
      .from('comments')
      .update({ done, done_by: done ? by : null, done_at: done ? new Date().toISOString() : null })
      .eq('id', id)
    if (error) throw error
  }

  async editComment(id: string, body: string): Promise<void> {
    const { error } = await this.client.from('comments').update({ body, edited_at: new Date().toISOString() }).eq('id', id)
    if (error) throw error
  }

  async deleteComment(id: string): Promise<void> {
    const { error } = await this.client.from('comments').delete().eq('id', id)
    if (error) throw error
  }

  subscribe(onChange: () => void): () => void {
    const channel = this.client
      .channel('wn-review')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'comments' }, onChange)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'releases' }, onChange)
      .subscribe()
    // and a slow poll, for a project where realtime is not enabled on the tables
    const timer = window.setInterval(onChange, 20000)
    return () => {
      window.clearInterval(timer)
      this.client.removeChannel(channel)
    }
  }
}
