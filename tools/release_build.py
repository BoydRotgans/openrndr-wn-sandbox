#!/usr/bin/env python3
"""Cut a filmed run into a release for the review site (review/).

    python3 tools/release_build.py --video video/wn-experience-subtitles-full-mixed.mp4 \
        --states video/wn-experience-subtitles-full.states --name "Release 21 September"

Reads the film and the state log the show wrote beside it (or ReleaseStatesKt derived), and
writes review/public/releases/<slug>/:

    manifest.json     every state with its slide id, letter, title, chapter, start, end and voice-over
    thumbs/<key>.jpg  one frame per state, taken once the state has settled
    video.mp4         the film scaled for the web (--no-video to skip, --width / --crf to tune)

and adds the release to review/public/releases/index.json, which is what the site lists when
it runs without Supabase. `scripts/publish-release.mjs` in review/ uploads the folder to
Supabase Storage and registers it there.

The state log's frames are deck frames at the fps on its first line; the film is at the same
rate, so a state's start in seconds is frame / fps.
"""
import argparse, json, os, re, subprocess, sys, datetime

def slugify(s):
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return s or "release"

def read_states(path):
    with open(path) as f:
        lines = [l.rstrip("\n") for l in f if l.strip()]
    head = lines[0].split()
    frames, fps = int(head[1]), int(head[3])
    states = []
    for l in lines[1:]:
        t = l.split("\t")
        frame, slide, step, letter = int(t[0]), t[1], int(t[2]), t[3]
        title = t[4] if len(t) > 4 else slide
        chapter = t[5] if len(t) > 5 else ""
        kind = t[6] if len(t) > 6 else ""
        slide_kind = t[7] if len(t) > 7 else "slide"
        states.append(dict(frame=frame, slide=slide, step=step, letter=letter, title=title,
                           chapter=chapter, chapterKind=kind, slideKind=slide_kind))
    return frames, fps, states

def write_waveform(video, path, rate):
    """One peak per 1/rate s of the mixed track, 0..255, so the site can draw any state's stretch
    of it: the film decoded to mono 8 kHz 16-bit through ffmpeg, the loudest sample of each window."""
    import array
    sr = 8000
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-vn", "-ac", "1", "-ar", str(sr), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    samples = array.array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    # the raw decode begins at the audio stream's first sample; pad by its start time so the
    # first peak is the film's first frame
    start = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=start_time",
                            "-of", "csv=p=0", video], capture_output=True, text=True).stdout.strip()
    pad = int(round(float(start or 0) * sr))
    if pad > 0:
        samples = array.array("h", [0] * pad) + samples
    # whole windows, and the rate written is the one they really give: 8000 / 266 is 30.075,
    # not 30, and the first file said 30 and drifted two seconds by the thirteenth minute
    window = max(1, round(sr / rate))
    rate = sr / window
    peaks = []
    for i in range(0, len(samples), window):
        chunk = samples[i:i + window]
        peaks.append(max((abs(x) for x in chunk), default=0))
    top = max(peaks) or 1
    with open(path, "w") as f:
        json.dump(dict(rate=rate, peaks=[round(p * 255 / top) for p in peaks]), f, separators=(",", ":"))
    print(f"waveform: {len(peaks)} peaks at {rate:g}/s in {path}")
    return

def probe(video):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                          "-show_entries", "stream=width,height:format=duration",
                          "-of", "json", video], capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    return int(j["streams"][0]["width"]), int(j["streams"][0]["height"]), float(j["format"]["duration"])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--states", help="the .states log; defaults to the one beside the video (or its un-mixed twin)")
    ap.add_argument("--name", required=True, help="how the release is listed")
    ap.add_argument("--slug", help="folder name; defaults to a slug of --name")
    ap.add_argument("--notes", default="")
    ap.add_argument("--created", help="the release's date, YYYY-MM-DD; defaults to today")
    ap.add_argument("--out", default="review/public/releases")
    ap.add_argument("--width", type=int, default=1920, help="web video width")
    ap.add_argument("--crf", type=int, default=30)
    ap.add_argument("--thumb-width", type=int, default=640)
    ap.add_argument("--no-video", action="store_true")
    ap.add_argument("--no-thumbs", action="store_true")
    ap.add_argument("--subtitles", default="show-subtitles.json", help="the voice-over per state, carried into the manifest")
    ap.add_argument("--subtitles-extended", default="show-subtitles-extended.json",
                    help="the extended track — the longer line the voice actually speaks — carried beside it")
    ap.add_argument("--audio", action="append", default=[],
                    help="another soundtrack for the same film, as key=name=path — e.g. "
                         "novoice=\"without voice\"=video/wn-walkthrough-v2-novoice.mp4. It is "
                         "encoded to aac beside the video and the page plays it in lockstep.")
    ap.add_argument("--audio-bitrate", default="128k")
    ap.add_argument("--no-waveform", action="store_true")
    ap.add_argument("--waveform-rate", type=int, default=100, help="peaks a second in waveform.json")
    a = ap.parse_args()

    states_path = a.states
    if not states_path:
        base = re.sub(r"-mixed$", "", os.path.splitext(a.video)[0])
        for c in (base + ".states", os.path.splitext(a.video)[0] + ".states"):
            if os.path.exists(c):
                states_path = c
                break
    if not states_path or not os.path.exists(states_path):
        sys.exit("no state log found; film with SLIDES_RECORD=true or run ReleaseStatesKt")

    frames, fps, states = read_states(states_path)
    w, h, duration = probe(a.video)
    logged = frames / fps
    if abs(logged - duration) > 2.0:
        print(f"warning: the log runs {logged:.1f}s and the film {duration:.1f}s — the timeline may not match")

    slug = a.slug or slugify(a.name)
    out = os.path.join(a.out, slug)
    os.makedirs(os.path.join(out, "thumbs"), exist_ok=True)

    def track(path):
        if path and os.path.exists(path):
            with open(path) as f:
                return json.load(f).get("subtitles", {})
        return {}

    voice = track(a.subtitles)
    # The extended track is what the voice-over is rendered from, so it is the one to read along
    # with the film — carried beside the default so the page can show and edit either.
    voice_extended = track(a.subtitles_extended)

    for i, s in enumerate(states):
        s["index"] = i
        s["voiceover"] = voice.get(s["slide"], {}).get(s["letter"], "")
        s["voiceoverExtended"] = voice_extended.get(s["slide"], {}).get(s["letter"], "")
        s["key"] = f"{s['slide']}-{s['letter']}"
        s["start"] = round(s["frame"] / fps, 3)
        end = states[i + 1]["frame"] / fps if i + 1 < len(states) else duration
        s["end"] = round(min(end, duration), 3)
        s["thumb"] = f"thumbs/{s['key']}.jpg"

    if not a.no_thumbs:
        for s in states:
            length = s["end"] - s["start"]
            # once the click has played and the state has settled: three quarters in, but at
            # most six seconds after it starts, and never past the state's own end
            at = s["start"] + min(0.75 * length, 6.0)
            at = max(s["start"], min(at, s["end"] - 0.05))
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", a.video,
                            "-frames:v", "1", "-vf", f"scale={a.thumb_width}:-2", "-q:v", "4",
                            os.path.join(out, s["thumb"])], check=True)
        print(f"thumbs: {len(states)} frames in {out}/thumbs")

    video_name = "video.mp4"
    if not a.no_video:
        target = os.path.join(out, video_name)
        print(f"video: encoding {a.video} to {target} at {a.width} wide, crf {a.crf} …")
        subprocess.run(["ffmpeg", "-v", "error", "-stats", "-y", "-i", a.video,
                        "-vf", f"scale={a.width}:-2", "-c:v", "libx264", "-preset", "medium",
                        "-crf", str(a.crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                        "-c:a", "aac", "-b:a", "96k", target], check=True)
        print(f"video: {os.path.getsize(target) / 1e6:.0f} MB")

    waveform_name = None
    if not a.no_waveform:
        waveform_name = "waveform.json"
        # off the web video where there is one — the track the browser actually plays
        source = os.path.join(out, video_name) if not a.no_video else a.video
        write_waveform(source, os.path.join(out, waveform_name), a.waveform_rate)

    # Another mix of the same film: audio alone, since the picture is the picture. The page mutes
    # the video and plays this with it, so a note made against a state holds whichever is playing.
    tracks = []
    for term in a.audio:
        key, name, path = (term.split("=", 2) + ["", ""])[:3]
        if not path or not os.path.isfile(path):
            print(f"audio: no file for \"{term}\" — left out")
            continue
        file = f"audio-{slugify(key)}.m4a"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-vn",
                        "-c:a", "aac", "-b:a", a.audio_bitrate, os.path.join(out, file)], check=True)
        size = os.path.getsize(os.path.join(out, file)) / 1e6
        print(f"audio: {name or key} — {file}, {size:.0f} MB")
        tracks.append(dict(key=slugify(key), name=name or key, file=file))

    manifest = dict(
        name=a.name, slug=slug, notes=a.notes,
        created=a.created or datetime.date.today().isoformat(),
        source=os.path.basename(a.video), fps=fps, frames=frames, duration=round(duration, 3),
        width=w, height=h, video=video_name, waveform=waveform_name, audio=tracks, states=[
            {k: s[k] for k in ("key", "index", "slide", "step", "letter", "title", "chapter",
                                "chapterKind", "slideKind", "start", "end", "thumb", "voiceover", "voiceoverExtended")}
            for s in states
        ],
    )
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)

    index_path = os.path.join(a.out, "index.json")
    index = []
    if os.path.exists(index_path):
        with open(index_path) as f:
            index = json.load(f).get("releases", [])
    index = [r for r in index if r.get("slug") != slug]
    index.append(dict(slug=slug, name=a.name, created=manifest["created"]))
    with open(index_path, "w") as f:
        json.dump(dict(releases=index), f, indent=1, ensure_ascii=False)
    print(f"release: {len(states)} states, {duration:.1f}s — {out}/manifest.json, listed in {index_path}")

if __name__ == "__main__":
    main()
