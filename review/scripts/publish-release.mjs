#!/usr/bin/env node
// Uploads a release folder (what tools/release_build.py wrote) to Supabase Storage and
// registers it in the releases table, from the terminal — the way to publish a film that
// is too big to push through the browser.
//
//     node scripts/publish-release.mjs public/releases/2026-09-21
//     node scripts/publish-release.mjs public/releases/2026-09-21 --video-url https://…/video.mp4
//
// Reads SUPABASE_URL and SUPABASE_SERVICE_KEY from the environment or from review/.env.
// --static registers the release as served by the site itself (the folder is deployed under
// /releases/<slug>/ with the video and thumbnails) and uploads nothing to storage — the way
// round a plan whose storage cannot take the video;
// --video-url uses a film hosted elsewhere and uploads nothing but the thumbnails;
// --skip-video leaves the release's video as it is; --write-manifest also rewrites the
// folder's manifest.json so its `video` is the uploaded URL (so a git deploy of local
// mode plays the same film).
import fs from 'node:fs'
import path from 'node:path'
import { createClient } from '@supabase/supabase-js'
import * as tus from 'tus-js-client'

const args = process.argv.slice(2)
const folder = args.find((a) => !a.startsWith('--'))
const flag = (name) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}
if (!folder) {
  console.error('usage: publish-release.mjs <release folder> [--video-url URL] [--skip-video] [--write-manifest]')
  process.exit(1)
}

// .env beside this script's package, without a dependency
const envFile = path.join(path.dirname(new URL(import.meta.url).pathname), '..', '.env')
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/)
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '')
  }
}
const url = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || '').replace(/\/$/, '')
const key = process.env.SUPABASE_SERVICE_KEY
if (!url || !key) {
  console.error('SUPABASE_URL and SUPABASE_SERVICE_KEY are needed (review/.env or the environment)')
  process.exit(1)
}

const BUCKET = 'releases'
const client = createClient(url, key)
const manifestPath = path.join(folder, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
const slug = manifest.slug
const publicUrl = (p) => client.storage.from(BUCKET).getPublicUrl(p).data.publicUrl

if (args.includes('--static')) {
  const base = `/releases/${slug}/`
  const row = { slug, name: manifest.name, notes: manifest.notes || '', video_url: base + manifest.video, thumb_base: base, manifest: { ...manifest, video: base + manifest.video } }
  const { data, error } = await client.from('releases').upsert(row, { onConflict: 'slug' }).select().single()
  if (error) throw error
  console.log(`release: ${data.name} (${data.id}) — ${manifest.states.length} states, served from the site's ${base}`)
  process.exit(0)
}

// thumbnails
const thumbs = manifest.states.map((s) => s.thumb).filter((t) => !/^https?:/.test(t))
let n = 0
for (const rel of thumbs) {
  const file = path.join(folder, rel)
  if (!fs.existsSync(file)) {
    console.warn(`missing ${file}`)
    continue
  }
  const { error } = await client.storage.from(BUCKET).upload(`${slug}/${rel}`, fs.readFileSync(file), {
    upsert: true, contentType: 'image/jpeg',
  })
  if (error) throw error
  if (++n % 20 === 0) process.stdout.write(`thumbs ${n}/${thumbs.length}\r`)
}
console.log(`thumbs: ${n} uploaded`)

// the film
let videoUrl = flag('--video-url')
if (!videoUrl && !args.includes('--skip-video')) {
  const file = path.join(folder, manifest.video)
  if (/^https?:/.test(manifest.video)) videoUrl = manifest.video
  else if (!fs.existsSync(file)) console.warn(`no film at ${file}; pass --video-url or --skip-video`)
  else {
    const size = fs.statSync(file).size
    const objectName = `${slug}/video.mp4`
    await new Promise((resolve, reject) => {
      const upload = new tus.Upload(fs.createReadStream(file), {
        endpoint: `${url}/storage/v1/upload/resumable`,
        uploadSize: size,
        retryDelays: [0, 3000, 5000, 10000, 20000],
        headers: { authorization: `Bearer ${key}`, apikey: key, 'x-upsert': 'true' },
        uploadDataDuringCreation: true,
        chunkSize: 6 * 1024 * 1024,
        metadata: { bucketName: BUCKET, objectName, contentType: 'video/mp4', cacheControl: '3600' },
        onProgress: (sent, total) => process.stdout.write(`film ${((sent / total) * 100).toFixed(0)}%\r`),
        onError: reject,
        onSuccess: resolve,
      })
      upload.start()
    })
    videoUrl = publicUrl(objectName)
    console.log(`film: ${(size / 1e6).toFixed(0)} MB at ${videoUrl}`)
  }
}

const finalManifest = { ...manifest, video: videoUrl || manifest.video }
const row = {
  slug, name: manifest.name, notes: manifest.notes || '', video_url: finalManifest.video,
  thumb_base: publicUrl(`${slug}/`), manifest: finalManifest,
}
if (!/^https?:/.test(row.video_url)) {
  console.error(`the release has no film URL (${row.video_url}); pass --video-url`)
  process.exit(1)
}
const { data, error } = await client.from('releases').upsert(row, { onConflict: 'slug' }).select().single()
if (error) throw error
console.log(`release: ${data.name} (${data.id}) — ${finalManifest.states.length} states, film ${row.video_url}`)

if (args.includes('--write-manifest')) {
  fs.writeFileSync(manifestPath, JSON.stringify(finalManifest, null, 1) + '\n')
  console.log(`manifest: ${manifestPath} now points at the uploaded film`)
}
