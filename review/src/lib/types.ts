/** One state of one slide in a filmed release: the nameplate's `slide-LETTER`, and where it is in the film. */
export interface StateInfo {
  key: string
  index: number
  slide: string
  step: number
  letter: string
  title: string
  chapter: string
  chapterKind: 'chapter' | 'moment' | ''
  slideKind: string
  start: number
  end: number
  thumb: string
  /** What is said over this state, off show-subtitles.json when the release was built. */
  voiceover?: string
}

/** What tools/release_build.py writes beside a release's thumbnails and video. */
export interface Manifest {
  name: string
  slug: string
  notes?: string
  created: string
  source?: string
  fps: number
  frames: number
  duration: number
  width: number
  height: number
  /** A path relative to the release folder, or an absolute URL. */
  video: string
  /** Peaks of the mixed track, `{ rate, peaks: 0..255[] }`, relative to the release folder. */
  waveform?: string | null
  states: StateInfo[]
}

export interface Release {
  id: string
  slug: string
  name: string
  created: string
  notes: string
  videoUrl: string
  /** Prefix a state's `thumb` is resolved against. */
  thumbBase: string
  manifest: Manifest
}

/** A remark, or a proposed voice-over text for the state (`body` is the whole new text). */
export type CommentKind = 'comment' | 'voiceover'
/** What a comment is about: the picture, or the sound and voice under it. */
export type CommentTopic = 'visual' | 'audio'

export interface Comment {
  id: string
  kind: CommentKind
  topic: CommentTopic
  releaseId: string
  slideId: string
  stateKey: string
  author: string
  body: string
  createdAt: string
  done: boolean
  doneBy: string | null
  doneAt: string | null
  editedAt?: string | null
  /** Pinned to a moment: seconds into the timeline, and optionally a spot on the picture (0..1). */
  at?: number | null
  x?: number | null
  y?: number | null
  /** Who an audio comment is for, by name; empty when it is for nobody in particular. */
  assignees?: string[]
  /** A reply: the note it answers. */
  parentId?: string | null
}

export interface NewReleaseInput {
  name: string
  notes: string
  manifest: Manifest
  /** A film to upload, or the URL of one already hosted. */
  videoFile?: File | null
  videoUrl?: string
  /** The release's thumbnails, named as the manifest's `thumb` paths (a folder picked in the browser). */
  thumbFiles?: File[]
}

export type Progress = (label: string, fraction: number) => void

export interface Store {
  readonly kind: 'local' | 'supabase'
  listReleases(): Promise<Release[]>
  addRelease(input: NewReleaseInput, progress: Progress): Promise<Release>
  listComments(): Promise<Comment[]>
  addComment(c: Omit<Comment, 'id' | 'createdAt' | 'done' | 'doneBy' | 'doneAt'>): Promise<Comment>
  setDone(id: string, done: boolean, by: string): Promise<void>
  editComment(id: string, body: string): Promise<void>
  deleteComment(id: string): Promise<void>
  /** Called whenever comments or releases may have changed elsewhere. Returns an unsubscribe. */
  subscribe(onChange: () => void): () => void
}
