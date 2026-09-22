#!/usr/bin/env python3
"""
Renders a subtitle track as speech: one wav per state, named the way the cue sheet names a
state — `<slide-id>-<LETTER>.wav` — under `data/sounds/voice/<track>/`, with a `manifest.json`
carrying each file's length, which the show reads to hold a state for exactly as long as its
line takes to say (see Voice.kt).

    tools/voice/.venv/bin/python tools/voiceover_render.py                 # extended track, Chatterbox
    tools/voiceover_render.py --engine say                                  # the Mac's own Dutch voice, instant
    tools/voiceover_render.py --only the-catalogue-city,kernboodschap-1     # a few slides
    tools/voiceover_render.py --voice data/sounds/voice/reference.wav       # clone a voice from a 10 s clip
    tools/voiceover_render.py --force                                       # render again whatever the cache says

**The engine is Chatterbox Multilingual** (Resemble AI, MIT), the half-billion-parameter model
with Dutch among its 23 languages and Apple Silicon support, run on the Mac GPU (`--device mps`).
Chosen for running in minutes on this machine rather than hours: measured, a line renders at
about real time. `say` is the zero-install baseline and is what to use to check the plumbing.

**A line is rendered a sentence at a time.** The model is happiest under a few hundred
characters and drifts on a long paragraph, so a line is cut at its sentences, sentences are
packed up to `CHUNK` characters, each piece is rendered on its own and they are joined with a
short breath between them. Silence is trimmed off both ends and the whole line is brought to one
peak level, so every state comes out at the same loudness.

**Rendering is cached by what went into it.** The manifest keeps a hash of the engine, the text
as spoken, the voice and the settings; a state whose hash is unchanged and whose file is there
is skipped, so editing one line in the organizer and running this again renders that line alone.

**Every line is checked by ear substitute, and rendered again until it passes.** The model
garbles one-word lines ("Seveton." came back as "Zet het om"), sometimes runs a short question on
into invented speech, and now and then drops words. So each render is transcribed by a local
Whisper and compared with what was asked, letters only so a compound split differently does not
count against it, numbers read back as words; a render also has to run at a plausible pace and be
mostly speech. One that fails is rendered again with another seed, up to `--tries` times, and the
best is kept — flagged `"check": true` in the manifest, and in the organizer, when even that is not
good enough. Speech carrying on past the last word Whisper heard is cut off there.

**What the voice is given is not quite what the wall shows.** `CO₂` is spelled for it, `CEM I`
becomes `CEM één`, a `[Naam]` placeholder is read as a neutral phrase — see `spoken()`. The
subtitle keeps the written form; only the voice gets the spoken one.
"""
import argparse, hashlib, json, re, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from voiceover_script import order_entries, lines_of, letter  # noqa: E402

CHUNK = 220            # characters a rendered piece may hold; sentences are packed up to it
BREATH = 0.28          # seconds of silence between the pieces of one line
PEAK_DB = -3.0         # every line brought to this peak
TRIM_DB = -45.0        # what counts as silence at the ends

SPOKEN = [
    (r"CO₂", "CO-twee"),
    (r"\bCEM III A\b", "CEM drie A"), (r"\bCEM III\b", "CEM drie"), (r"\bCEM II\b", "CEM twee"), (r"\bCEM I\b", "CEM één"),
    (r"\b3D-model\b", "drie-D-model"), (r"\b3D\b", "drie-D"),
    (r"\[Naam\]", "onze gast"), (r"\[Functie\]", ""),
    # Names the model does not know are spelled the way they are said. Transwinaton alone came back
    # as "Frans Binato" at best; split into its syllables it reads back as the name (0.83 against 0.75).
    (r"\bTranswinaton\b", "Trans-wie-na-ton"),
    (r"\bCSC\b", "C-S-C"), (r"\bESG\b", "E-S-G"), (r"\bWDP\b", "W-D-P"), (r"\bVGP\b", "V-G-P"), (r"\bB & C\b", "B en C"),
    (r"\bkm\b", "kilometer"), (r"\bm²", "vierkante meter"),
    (r"[“”„]", '"'), (r"[‘’]", "'"), (r"\s*[–—]\s*", ", "), (r"…", "."),
    (r"\s+", " "),
]


def say_number(match) -> str:
    """A number as words: a year as two halves (tweeduizend vierentwintig), 2.750 as a whole, 1,2 as komma."""
    from num2words import num2words
    raw = match.group(0)
    if re.fullmatch(r"\d{1,3}(\.\d{3})+", raw):
        return num2words(int(raw.replace(".", "")), lang="nl")
    if "," in raw:
        whole, frac = raw.split(",", 1)
        return f"{num2words(int(whole), lang='nl')} komma {' '.join(num2words(int(d), lang='nl') for d in frac)}"
    n = int(raw)
    if 1900 <= n <= 2099:
        head, tail = (2000, n - 2000) if n >= 2000 else (1900, n - 1900)
        head_words = "tweeduizend" if head == 2000 else "negentienhonderd"
        return head_words if tail == 0 else f"{head_words} {num2words(tail, lang='nl')}"
    return num2words(n, lang="nl")


def spoken(text: str) -> str:
    """The line as the voice should say it."""
    for pattern, repl in SPOKEN:
        text = re.sub(pattern, repl, text)
    text = re.sub(r"(\d+)\s*%", r"\1 procent", text)
    text = re.sub(r"\d{1,3}(?:\.\d{3})+|\d+,\d+|\d+", say_number, text)
    return re.sub(r"\s+", " ", text).strip()


# ---------------------------------------------------------------------------- the check -- //

class Ear:
    """A local Whisper that reads a render back, and the score it gets against what was asked."""

    def __init__(self, size="small"):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(size, device="cpu", compute_type="int8")

    @staticmethod
    def letters(text: str) -> str:
        """Lower case letters only, digits read as words: compounds and number formats compare equal."""
        text = re.sub(r"\d{1,3}(?:\.\d{3})+|\d+,\d+|\d+", say_number, text.lower())
        return re.sub(r"[^a-zàáâäéèêëïîóòôöúùûüç]", "", text)

    def hear(self, wav: Path):
        """(what was heard, when the last word ends, every word with its times) — the times are
        what puts a subtitle card on the speech rather than on a character count; see Subtitles.kt."""
        segs, _ = self.model.transcribe(str(wav), language="nl", beam_size=3, word_timestamps=True,
                                        condition_on_previous_text=False)
        words = [w for s in segs for w in (s.words or [])]
        spoken = [[round(w.start, 3), round(w.end, 3), w.word.strip()] for w in words if w.word.strip()]
        return " ".join(w.word.strip() for w in words), (words[-1].end if words else None), spoken

    def score(self, asked: str, heard: str) -> float:
        import difflib
        a, b = self.letters(asked), self.letters(heard)
        return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio() if a else 0.0


def sentences(text: str):
    return [s for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]


def pieces(text: str):
    """Sentences packed into pieces of at most CHUNK characters; a longer sentence stands alone."""
    out, cur = [], ""
    for s in sentences(text):
        if cur and len(cur) + 1 + len(s) > CHUNK:
            out.append(cur); cur = s
        else:
            cur = f"{cur} {s}".strip()
    if cur: out.append(cur)
    return out


# ---------------------------------------------------------------------------- engines ---- //

class SayEngine:
    name = "say"
    sr = 22050

    def __init__(self, voice="Xander", rate=170):
        self.voice, self.rate = voice, rate
        self.settings = {"voice": voice, "rate": rate}

    def render(self, text: str, tmp: Path):
        import numpy as np, soundfile as sf
        aiff = tmp.with_suffix(".aiff")
        subprocess.run(["say", "-v", self.voice, "-r", str(self.rate), "-o", str(aiff), text], check=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(aiff), "-ac", "1", "-ar", str(self.sr), str(tmp)], check=True)
        audio, _ = sf.read(tmp, dtype="float32")
        aiff.unlink(missing_ok=True)
        return audio


class PiperEngine:
    """
    Piper (rhasspy), the voice the samples were approved from — a small onnx model run on the CPU.

    **It is here for its evenness rather than its realism.** Chatterbox generates each line on its
    own and its pace wanders, 12.7 to 21.6 characters a second of speech across one track, which
    reads as hurried on the quick lines however slow the average is. Piper paces every line the
    same and pauses the same at every comma, and `length_scale` sets that pace outright: 1.0 is the
    voice as trained, 1.35 the talk's.

    It is also deterministic, so a line comes out the same every time. The read-back check still
    runs — a mispronounced name is a mispronounced name — but rendering again with another seed
    cannot help it, so a line that fails is rendered once and flagged.
    """
    name = "piper"
    deterministic = True

    def __init__(self, model: str, length_scale=1.35, noise_scale=0.667, noise_w=0.8):
        from piper import PiperVoice
        self.model = str(model)
        self.voice = PiperVoice.load(self.model)
        self.sr = self.voice.config.sample_rate
        self.length_scale, self.noise_scale, self.noise_w = length_scale, noise_scale, noise_w
        self.settings = {"model": Path(self.model).name, "length_scale": length_scale,
                         "noise_scale": noise_scale, "noise_w": noise_w}
        print(f"render: Piper {Path(self.model).name} at length scale {length_scale}")

    def render(self, text: str, tmp: Path):
        import soundfile as sf, wave
        from piper import SynthesisConfig
        with wave.open(str(tmp), "wb") as w:
            self.voice.synthesize_wav(text, w, syn_config=SynthesisConfig(
                length_scale=self.length_scale, noise_scale=self.noise_scale, noise_w_scale=self.noise_w))
        audio, _ = sf.read(tmp, dtype="float32")
        return audio


class ChatterboxEngine:
    name = "chatterbox"

    def __init__(self, device="mps", voice=None, exaggeration=0.4, cfg=0.5, language="nl"):
        import torch
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS
        if device == "mps" and not torch.backends.mps.is_available():
            print("render: mps not available, using cpu"); device = "cpu"
        t = time.time()
        self.model = ChatterboxMultilingualTTS.from_pretrained(device=device)
        print(f"render: Chatterbox Multilingual on {device} in {time.time() - t:.1f}s")
        self.sr = self.model.sr
        self.voice, self.exaggeration, self.cfg, self.language = voice, exaggeration, cfg, language
        self.settings = {"device": device, "voice": voice, "exaggeration": exaggeration, "cfg": cfg, "language": language}

    def render(self, text: str, tmp: Path):
        wav = self.model.generate(
            text, language_id=self.language, audio_prompt_path=self.voice,
            exaggeration=self.exaggeration, cfg_weight=self.cfg
        )
        return wav.squeeze(0).cpu().numpy()


# ---------------------------------------------------------------------------- audio ------ //

def trim(audio, sr, floor_db=TRIM_DB, pad=0.06):
    import numpy as np
    if len(audio) == 0: return audio
    floor = 10 ** (floor_db / 20) * max(float(np.max(np.abs(audio))), 1e-6)
    loud = np.flatnonzero(np.abs(audio) > floor)
    if len(loud) == 0: return audio
    a, b = max(0, loud[0] - int(pad * sr)), min(len(audio), loud[-1] + int(pad * sr))
    return audio[a:b]


def join(parts, sr):
    import numpy as np
    gap = np.zeros(int(BREATH * sr), dtype="float32")
    out = []
    for i, p in enumerate(parts):
        if i: out.append(gap)
        out.append(p.astype("float32"))
    return np.concatenate(out) if out else np.zeros(0, dtype="float32")


def normalise(audio, peak_db=PEAK_DB):
    import numpy as np
    peak = float(np.max(np.abs(audio))) if len(audio) else 0.0
    return audio * (10 ** (peak_db / 20) / peak) if peak > 1e-6 else audio


CHECK_VERSION = 2
# A plausible pace, in characters a second. This voice speaks Dutch fast — 22 to 27 on lines
# Whisper reads back whole — so the ceiling only catches a line that lost most of its words, and
# the floor one that ran on into invented speech. The word match does the real judging.
MIN_CPS, MAX_CPS = 6.0, 32.0
MIN_VOICED = 0.45               # share of 50 ms windows carrying speech

_ear = None
def ear():
    global _ear
    if _ear is None:
        _ear = Ear()
    return _ear


def seed_everything(seed: int):
    import random
    random.seed(seed)
    try:
        import numpy as np, torch
        np.random.seed(seed); torch.manual_seed(seed)
    except ImportError:
        pass


def voiced_share(audio, sr):
    import numpy as np
    w = int(sr * 0.05); n = len(audio) // w
    if n == 0: return 0.0
    env = np.sqrt((audio[:n * w].reshape(n, w) ** 2).mean(1))
    return float((env > 0.1 * env.max()).mean())


def verdict(audio, sr, asked, ear, tmp: Path, cut=False):
    """(passes, score, note, audio, words): the render read back and judged — cut at the last word
    heard, and the word times kept, since they are what the subtitles are laid on."""
    import soundfile as sf
    sf.write(tmp, audio, sr, subtype="PCM_16")
    heard, last, words = ear.hear(tmp)
    tmp.unlink(missing_ok=True)
    score = ear.score(asked, heard)
    # speech running on past the last word heard is the model inventing: cut it off there
    if cut and last is not None and len(audio) / sr > last + 0.6 and score >= 0.6:
        audio = audio[: int((last + 0.25) * sr)]
    seconds = len(audio) / sr
    cps = len(asked) / max(seconds, 0.01)
    notes = []
    if score < PASS_SCORE: notes.append(f"heard \"{heard[:80]}\"")
    if not (MIN_CPS <= cps <= MAX_CPS) and len(asked) > 20: notes.append(f"{cps:.0f} chars/s")
    if voiced_share(audio, sr) < MIN_VOICED: notes.append("mostly silence")
    return (not notes, score, "; ".join(notes), audio, words)


PASS_SCORE = 0.8


# ---------------------------------------------------------------------------- main ------- //

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="extended", choices=["default", "extended"])
    ap.add_argument("--engine", default="chatterbox", choices=["chatterbox", "say", "piper"])
    ap.add_argument("--device", default="mps")
    ap.add_argument("--voice", default=None, help="a reference clip to clone (Chatterbox), or a say voice name")
    ap.add_argument("--exaggeration", type=float, default=0.4)
    ap.add_argument("--length-scale", type=float, default=1.35, help="Piper's pace; 1.0 is the voice as trained")
    ap.add_argument("--cfg", type=float, default=0.5)
    ap.add_argument("--only", default="", help="comma-separated slide ids")
    ap.add_argument("--limit", type=int, default=0, help="render at most this many states")
    ap.add_argument("--out", default=None, help="folder; default data/sounds/voice/<track>")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--tries", type=int, default=4, help="renders a line may take to pass the check")
    ap.add_argument("--pass-score", type=float, default=0.8, help="letters matched, 0..1, for a render to pass")
    ap.add_argument("--no-check", action="store_true", help="render once, unchecked")
    a = ap.parse_args()
    global PASS_SCORE
    PASS_SCORE = a.pass_score

    file = ROOT / ("show-subtitles-extended.json" if a.track == "extended" else "show-subtitles.json")
    doc = json.loads(file.read_text())
    subs = {sid: lines_of(v) for sid, v in doc.get("subtitles", {}).items()}
    order = json.loads((ROOT / "show-order.json").read_text())
    ids = [e[0] for e in order_entries(order)]
    ids += [s for s in subs if s not in ids and s not in set(order.get("archive", []))]
    only = {s.strip() for s in a.only.split(",") if s.strip()}
    rows = [(sid, step, text) for sid in ids if sid in subs and (not only or sid in only)
            for step, text in sorted(subs[sid].items())]
    if a.limit: rows = rows[:a.limit]

    out = Path(a.out) if a.out else ROOT / "data/sounds/voice" / a.track
    out.mkdir(parents=True, exist_ok=True)
    manifest_file = out / "manifest.json"
    manifest = json.loads(manifest_file.read_text()) if manifest_file.is_file() else {}

    engine = None
    def get_engine():
        nonlocal engine
        if engine is None:
            engine = (SayEngine(voice=a.voice or "Xander") if a.engine == "say"
                      else PiperEngine(a.voice, a.length_scale) if a.engine == "piper"
                      else ChatterboxEngine(a.device, a.voice, a.exaggeration, a.cfg))
        return engine

    import soundfile as sf
    started, rendered, kept, total_audio = time.time(), 0, 0, 0.0
    for sid, step, text in rows:
        key = f"{sid}-{letter(step)}"
        say = spoken(text)
        settings = ({"voice": a.voice, "exaggeration": a.exaggeration, "cfg": a.cfg} if a.engine == "chatterbox"
                    else {"model": Path(a.voice).name if a.voice else None, "length_scale": a.length_scale} if a.engine == "piper"
                    else {"voice": a.voice or "Xander"})
        digest = hashlib.sha1(json.dumps([a.engine, say, settings], sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:16]
        target = out / f"{key}.wav"
        entry = manifest.get(key)
        cached = not a.force and entry and entry.get("hash") == digest and target.is_file()
        # a file made before the check existed is checked now, and kept if it passes
        if cached and (a.no_check or entry.get("checked") == CHECK_VERSION):
            kept += 1; total_audio += entry["seconds"]; continue
        t = time.time()
        best = None
        if cached:
            audio, sr = sf.read(target, dtype="float32")
            ok0, score0, note0, _ = verdict(audio, sr, say, ear(), out / f".{key}.check.wav")
            best = (ok0, score0, note0, audio, sr, 0)
            if ok0:
                entry.update({"score": round(best[1], 3), "check": False, "checked": CHECK_VERSION})
                manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
                kept += 1; total_audio += entry["seconds"]
                print(f"render: {key:40s} kept, checked {best[1]:.2f}")
                continue
        eng = get_engine()
        # A deterministic engine renders the same line the same way however often it is asked, so a
        # failure there is the model's reading of the words rather than a bad draw: render it once.
        tries = 1 if (a.no_check or getattr(get_engine(), "deterministic", False)) else a.tries
        for attempt in range(1, tries + 1):
            seed_everything(attempt * 7919 + len(say))
            parts = []
            for piece in pieces(say):
                audio = eng.render(piece, out / f".{key}.tmp.wav")
                parts.append(trim(audio, eng.sr))
            audio = normalise(join(parts, eng.sr))
            if a.no_check:
                best = (True, 1.0, "", audio, eng.sr, attempt, []); break
            ok, score, note, audio, words = verdict(audio, eng.sr, say, ear(), out / f".{key}.check.wav", cut=True)
            print(f"render: {key:40s} try {attempt}: {score:.2f} {note}")
            if best is None or (ok, score) > (best[0], best[1]):
                best = (ok, score, note, audio, eng.sr, attempt, words)
            if ok: break
        ok, score, note, audio, sr, attempt, words = best
        sf.write(target, audio, sr, subtype="PCM_16")
        for tmp in (f".{key}.tmp.wav", f".{key}.check.wav"): (out / tmp).unlink(missing_ok=True)
        seconds = len(audio) / sr
        manifest[key] = {"file": target.name, "seconds": round(seconds, 3), "engine": a.engine, "hash": digest,
                         "text": text, "spoken": say, "settings": settings,
                         "score": round(score, 3), "tries": attempt, "check": not ok,
                         # Every word as it was really said. The wall lays its subtitle cards on
                         # these rather than on a pace in characters a second, so a card comes up
                         # with the words it carries — see Pace.cards and VoiceTrack.
                         "words": [w for w in words if w[1] <= seconds + 0.05],
                         "checked": None if a.no_check else CHECK_VERSION}
        manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        rendered += 1; total_audio += seconds
        print(f"render: {key:40s} {seconds:6.1f}s audio in {time.time() - t:5.1f}s  ({len(say)} chars)"
              + ("" if ok else f"  — CHECK: best {score:.2f} {note}"))
    print(f"render: {rendered} rendered, {kept} kept, {total_audio / 60:.1f} min of speech under {out}, "
          f"{time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
