#!/usr/bin/env python3
"""Lay a release's sound out as clips, for the review site's audio timeline.

    python3 tools/release_audio.py review/public/releases/2026-09-24

Reads the cue log the film was scored from (`video/<film>.cues`, found off the manifest's
`source`) and writes, into the release folder:

    audio.json         every cue as a clip: its file, lane, where it starts and stops in the film,
                       the state it was fired on, and the file's own peaks, length and silent head
    sounds/<hash>.m4a  every file the film played, encoded for the browser, so a clip can be heard
                       on its own (--no-audio to skip)

`release_build.py` calls this for every new release. Run it on its own only for a release whose
film was made with the sound files as they are now: an older film played sheets that have been
re-cut since, and its clips would be measured off files it never heard.

A clip is placed by the driver's own rules (Soundtrack.render): a cue with no fade out and no loop
is a one-shot and rings for the file's length; a sustained one plays until its release and its fade
out, or until the file runs out; a play while the same file is still sounding carries it on rather
than starting it again. A clip belongs to the state it was fired on, counted in frames — the
manifest's starts are seconds rounded to the millisecond, which puts a cue fired on a state's very
first frame a third of a millisecond before it.
"""
import argparse, base64, bisect, hashlib, json, os, re, subprocess, sys

import numpy as np

# The quietest a sample may be and still count as sound: ffmpeg silencedetect's -60 dB, the
# threshold the delivered sheets were measured against.
SILENCE_DB = -60.0
# How a peak is drawn: 0..255 over this many dB below full scale, so a quiet cue still shows a
# shape and a silent one is a flat line — normalising each file to its own loudest moment would
# draw a file of silence as loud as any other.
FLOOR_DB = 60.0
DECODE_RATE = 16000


def read_cues(path):
    with open(path) as f:
        lines = [l.rstrip("\n") for l in f if l.strip()]
    head = lines[0].split()
    frames, fps = int(head[1]), int(head[3])
    mix = {}
    for term in head[4:]:
        if term.startswith("mix:") and "=" in term:
            k, v = term[4:].split("=", 1)
            mix[k] = float(v)
    events = []
    for l in lines[1:]:
        t = l.split("\t")
        events.append(dict(frame=int(t[0]), release=t[1] == "release", gain=float(t[2]),
                           loop=t[3] == "true", fadeIn=int(t[4]), fadeOut=int(t[5]), path=t[6],
                           layer=t[8] if len(t) > 8 else "design", resume=len(t) > 9 and t[9] == "true"))
    return frames, fps, mix, events


def loudness(path):
    """Integrated loudness in LUFS (EBU R128), which is what a mix is balanced by — a peak says how
    close a file comes to clipping, not how loud it sounds. None for a file too short or too quiet
    for the meter to gate."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    summary = out[out.rfind("Summary:"):]
    m = re.search(r"I:\s+(-?[\d.]+|-inf)\s+LUFS", summary)
    if not m or m.group(1) == "-inf":
        return None
    v = float(m.group(1))
    return None if v <= -70 else round(v, 1)


def measure(path, peak_rate):
    """The file's length, and one peak per 1/peak_rate s drawn in dB, off a 16 kHz decode."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0",
                            "-show_entries", "stream=channels,sample_rate:format=duration",
                            "-of", "json", path], capture_output=True, text=True, check=True).stdout
    j = json.loads(probe)
    channels = int(j["streams"][0].get("channels", 1))
    rate = int(j["streams"][0].get("sample_rate", 0))
    duration = float(j["format"]["duration"])
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vn", "-ac", str(channels),
                          "-ar", str(DECODE_RATE), "-f", "f32le", "-"], capture_output=True, check=True).stdout
    x = np.frombuffer(pcm, dtype=np.float32)
    x = x[: len(x) - len(x) % channels].reshape(-1, channels)
    env = np.abs(x).max(axis=1) if len(x) else np.zeros(1, dtype=np.float32)
    window = max(1, round(DECODE_RATE / peak_rate))
    n = -(-len(env) // window)
    padded = np.zeros(n * window, dtype=np.float32)
    padded[: len(env)] = env
    peaks = padded.reshape(n, window).max(axis=1)
    db = 20 * np.log10(np.maximum(peaks, 1e-9))
    drawn = np.clip((db + FLOOR_DB) / FLOOR_DB, 0, 1) * 255
    loudest = float(env.max()) if len(env) else 0.0
    peak_db = 20 * np.log10(max(loudest, 1e-9))
    audible = np.nonzero(env > 10 ** (SILENCE_DB / 20))[0]
    head = float(audible[0]) / DECODE_RATE if len(audible) else duration
    tail = float(len(env) - 1 - audible[-1]) / DECODE_RATE if len(audible) else duration
    return dict(duration=round(duration, 3), channels=channels, sampleRate=rate,
                peakDb=round(peak_db, 1), lufs=loudness(path), head=round(head, 3), tail=round(tail, 3),
                silent=bool(peak_db < SILENCE_DB), peakRate=DECODE_RATE / window,
                peaks=base64.b64encode(np.round(drawn).astype(np.uint8).tobytes()).decode())


def encode(path, folder, bitrate):
    """A copy the browser plays, named by the file's content so a re-cut is a new file."""
    with open(path, "rb") as f:
        digest = hashlib.sha1(f.read()).hexdigest()[:16]
    name = f"{digest}.m4a"
    target = os.path.join(folder, name)
    if not os.path.exists(target):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-vn", "-c:a", "aac", "-b:a", bitrate,
                        "-movflags", "+faststart", target], check=True)
    return name, digest


def place(events, frames, fps, lengths):
    """Every play as a clip [start, end) in frames, by the rules the soundtrack is rendered with.

    A playlist resumes (the log's `resume`): played again after it stopped, it picks up where it
    stopped, so a clip carries `offset`, seconds into the file — Soundtrack.render's own rule."""
    clips = []
    held = {}  # path -> the sustained clip still sounding
    resume_at = {}  # path -> seconds into the file a resuming bed stopped at

    def ended(clip):
        if clip.get("resume"):
            at = clip["offset"] + (clip["end"] - clip["start"]) / fps
            length = lengths.get(clip["path"])
            resume_at[clip["path"]] = (at % length if clip["loop"] else at if at < length else 0.0) if length else 0.0
    for e in sorted(events, key=lambda e: e["frame"]):
        f, path = e["frame"], e["path"]
        length = lengths.get(path)
        own = round(length * fps) if length else None
        sustained = e["loop"] or e["fadeOut"] > 0
        if not sustained:
            if not e["release"]:
                end = f + own if own else f + fps
                clips.append(dict(e, start=f, end=min(frames, end), releasedAt=None))
            continue
        clip = held.get(path)
        if clip is not None:
            natural = None if clip["loop"] or not own else clip["start"] + own - round(clip.get("offset", 0) * fps)
            fading = clip.get("fadeEnd")
            if (natural is not None and f >= natural) or (fading is not None and f >= fading):
                clip["end"] = min(x for x in (natural, fading, frames) if x is not None)
                ended(clip)
                held.pop(path)
                clip = None
        if e["release"]:
            if clip is not None:
                clip["fadeEnd"] = f + e["fadeOut"]
                clip["releasedAt"] = f
            continue
        if clip is not None:
            # played again while still sounding: the driver fades it back up where it is
            clip.pop("fadeEnd", None)
            clip["releasedAt"] = None
            continue
        clip = dict(e, start=f, end=None, releasedAt=None, offset=resume_at.get(path, 0.0) if e.get("resume") else 0.0)
        held[path] = clip
        clips.append(clip)
    for path, clip in held.items():
        own = round(lengths[path] * fps) if lengths.get(path) else None
        natural = None if clip["loop"] or not own else clip["start"] + own - round(clip.get("offset", 0) * fps)
        clip["end"] = min(x for x in (natural, clip.get("fadeEnd"), frames) if x is not None)
    return clips


def find_cues(release_dir, manifest):
    source = manifest.get("source") or ""
    stem = re.sub(r"-mixed$", "", os.path.splitext(source)[0])
    for c in (f"video/{stem}.cues", os.path.join(release_dir, f"{stem}.cues")):
        if stem and os.path.exists(c):
            return c
    return None


def build(release_dir, cues_path=None, peak_rate=40, audio=True, bitrate="96k"):
    with open(os.path.join(release_dir, "manifest.json")) as f:
        manifest = json.load(f)
    cues_path = cues_path or find_cues(release_dir, manifest)
    if not cues_path or not os.path.exists(cues_path):
        print(f"audio: no cue log for {manifest.get('slug')} — no audio timeline")
        return None
    frames, fps, mix, events = read_cues(cues_path)
    if fps != manifest["fps"] or abs(frames - manifest["frames"]) > 2:
        print(f"audio: warning — the cue log is {frames} frames at {fps} and the release {manifest['frames']} at {manifest['fps']}")

    paths = sorted({e["path"] for e in events})
    files, lengths = {}, {}
    sounds = os.path.join(release_dir, "sounds")
    if audio:
        os.makedirs(sounds, exist_ok=True)
    for i, p in enumerate(paths, 1):
        entry = dict(name=os.path.basename(p), dir=os.path.dirname(p))
        if not os.path.isfile(p):
            print(f"audio: {p} is not on disk — placed, but with no length or peaks")
            entry["absent"] = True
        else:
            entry.update(measure(p, peak_rate))
            lengths[p] = entry["duration"]
            if audio:
                name, digest = encode(p, sounds, bitrate)
                entry["audio"] = f"sounds/{name}"
                entry["hash"] = digest
        files[p] = entry
        if i % 25 == 0 or i == len(paths):
            print(f"audio: measured {i}/{len(paths)}", end="\r" if i < len(paths) else "\n")

    states = manifest["states"]
    starts = [round(s["start"] * fps) for s in states]
    placed = place(events, frames, fps, lengths)
    out = []
    for c in placed:
        i = max(0, bisect.bisect_right(starts, c["start"]) - 1)
        state = states[i]["key"]
        slot = f"music:{os.path.basename(c['path'])}" if c["layer"] == "music" else f"{c['layer']}:{state}"
        out.append(dict(
            layer=c["layer"], file=c["path"], slot=slot, state=state,
            start=round(c["start"] / fps, 3), end=round(c["end"] / fps, 3), frame=c["start"],
            released=round(c["releasedAt"] / fps, 3) if c.get("releasedAt") is not None else None,
            gain=c["gain"], loop=c["loop"], fadeIn=c["fadeIn"], fadeOut=c["fadeOut"],
            **({"offset": round(c["offset"], 3)} if c.get("offset") else {}),
        ))
    out.sort(key=lambda c: (c["start"], c["layer"]))

    doc = dict(version=1, source=os.path.basename(cues_path), fps=fps, frames=frames, mix=mix,
               floorDb=FLOOR_DB, silenceDb=SILENCE_DB, files=files, clips=out)
    path = os.path.join(release_dir, "audio.json")
    with open(path, "w") as f:
        json.dump(doc, f, separators=(",", ":"), ensure_ascii=False)
    size = sum(os.path.getsize(os.path.join(sounds, n)) for n in os.listdir(sounds)) / 1e6 if audio and os.path.isdir(sounds) else 0
    by = {}
    for c in out:
        by[c["layer"]] = by.get(c["layer"], 0) + 1
    print(f"audio: {len(out)} clips ({', '.join(f'{n} {k}' for k, n in sorted(by.items()))}) from {len(paths)} files"
          f" — {path} ({os.path.getsize(path) / 1e3:.0f} kB){f', sounds {size:.0f} MB' if audio else ''}")
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("release", help="the release folder, e.g. review/public/releases/2026-09-24")
    ap.add_argument("--cues", help="the film's .cues log; defaults to the one named by the manifest's source")
    ap.add_argument("--peak-rate", type=int, default=40, help="peaks a second drawn on a clip")
    ap.add_argument("--bitrate", default="96k", help="of the copies the page plays a clip from")
    ap.add_argument("--no-audio", action="store_true", help="measure and place only; no playable copies")
    a = ap.parse_args()
    if not build(a.release, a.cues, a.peak_rate, not a.no_audio, a.bitrate):
        sys.exit(1)


if __name__ == "__main__":
    main()
