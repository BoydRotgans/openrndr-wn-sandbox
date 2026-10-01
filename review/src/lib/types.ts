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
  /**
   * The same state on the **extended** track (show-subtitles-extended.json) — the longer line the
   * voice-over is actually rendered from, and so the one to read along with the film. The two are
   * edited apart, since a change to one is not a change to the other.
   */
  voiceoverExtended?: string
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
  /**
   * The soundtracks the film can be watched with, beyond its own. Each is an audio file beside
   * the video, played in lockstep with it — the same frames, a different mix, so a note made
   * against a state holds whichever is playing.
   */
  audio?: { key: string; name: string; file: string; waveform?: string | null }[]
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

/**
 * A remark, or a proposed voice-over text for the state (`body` is the whole new text) — on the
 * default track or on the extended one, which are two lines and are changed apart.
 */
export type CommentKind =
  | 'comment'
  /** A proposed line: `body` is the whole new text, on the default or the extended track. */
  | 'voiceover'
  | 'voiceover_extended'
  /** A remark about a line rather than a change to it — it stands under the line, and takes replies. */
  | 'voiceover_note'
  | 'voiceover_extended_note'
  /**
   * Where a sound in the audio timeline stands: `body` is a [ClipStatus], `clip` the slot. The
   * latest row for a slot is its status, across releases — a status follows the sound, not the film.
   */
  | 'audio_status'
  /**
   * The level a sound-design cue should be played at, in dB against the file as delivered: `body` is
   * the number, `clip` the slot. The latest row for a slot is its gain, and `show-gains.json` carries
   * it into the next filmed release.
   */
  | 'audio_gain'
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
  /**
   * The sound in the audio timeline a note or a status is about, as its slot: `design:<state>` or
   * `voice:<state>` for a state's cue or line — so a note on a missing cue carries over to the file
   * that fills it — and `music:<file>` for a bed. Null on everything written in the review.
   */
  clip?: string | null
  /** The file name the slot held when this was written, so a note keeps saying which file it meant. */
  clipFile?: string | null
}

/** Where a sound stands in the audio review. */
export type ClipStatus = 'undone' | 'needs-work' | 'done'

/** The three layers the show mixes, which are the three audio tracks of the timeline. */
export type AudioLayer = 'voice' | 'design' | 'music'

/** One file the film played, measured by tools/release_audio.py. */
export interface AudioFileInfo {
  name: string
  dir: string
  duration?: number
  channels?: number
  sampleRate?: number
  /** The loudest sample, in dB below full scale. */
  peakDb?: number
  /** Integrated loudness (EBU R128), in LUFS; absent where the file is too short or quiet to gate. */
  lufs?: number | null
  /** Seconds of silence (under `silenceDb`) before the first sound, and after the last. */
  head?: number
  tail?: number
  silent?: boolean
  /** Peaks a second in `peaks`: base64 of one byte each, 0..255 over `floorDb` dB. */
  peakRate?: number
  peaks?: string
  /** The copy the page plays, relative to the release folder. */
  audio?: string
  hash?: string
  /** The cue log named it and it was not on disk when the release was built. */
  absent?: boolean
}

/** One play of one file, placed in the film by the rules the soundtrack is rendered with. */
export interface AudioClipInfo {
  layer: AudioLayer
  /** The file as the cue log names it — the key into `files`. */
  file: string
  slot: string
  /** The state it was fired on. */
  state: string
  start: number
  end: number
  frame: number
  /** When the slide let go of it, if it faded out rather than running out. */
  released: number | null
  gain: number
  loop: boolean
  /** Seconds into the file it starts at: past 0 only for a playlist picking up where it stopped. */
  offset?: number
  /** In frames of the film. */
  fadeIn: number
  fadeOut: number
}

/** A release's `audio.json`. */
export interface AudioDoc {
  version: number
  source: string
  fps: number
  frames: number
  mix: Record<string, number>
  floorDb: number
  silenceDb: number
  files: Record<string, AudioFileInfo>
  clips: AudioClipInfo[]
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
