// Seals the practice key with the review key, for VITE_PRACTICE_KEY_SEALED: the practice page's Share
// then works for a team member who opened it with the review key, while the bundle still carries no
// readable key. The AES key is SHA-256 of "wn-practice-share:" + the review key — deliberately not the
// plain hash of the review key, which is published as VITE_ACCESS_KEY_SHA256.
//
//     REVIEW_KEY=… PRACTICE_KEY=… node scripts/seal-practice-key.mjs
import crypto from 'node:crypto'

const review = process.env.REVIEW_KEY
const practice = process.env.PRACTICE_KEY
if (!review || !practice) {
  console.error('set REVIEW_KEY and PRACTICE_KEY')
  process.exit(1)
}
const key = crypto.createHash('sha256').update(`wn-practice-share:${review}`).digest()
const iv = crypto.randomBytes(12)
const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
const body = Buffer.concat([cipher.update(practice, 'utf8'), cipher.final(), cipher.getAuthTag()])
console.log(Buffer.concat([iv, body]).toString('base64'))
