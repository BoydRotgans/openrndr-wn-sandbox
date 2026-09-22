#!/usr/bin/env python3
"""
Renders one line of the talk in several voices, a wav per voice, to choose the voice by ear.

    tools/voice/.venv/bin/python tools/voice_samples.py --piper <folder of piper .onnx voices>

Chatterbox has one built-in voice; any other has to be *cloned* from a reference clip. The
references here are Piper's Dutch voices (rhasspy/piper-voices, trained for TTS): each speaks a
neutral paragraph slowly, and Chatterbox clones its timbre while keeping its own delivery. The
references are kept under `refs/`, so an approved voice goes straight into the show with
`voiceover_render.py --voice <folder>/refs/<name>.wav`.

Slower and calmer is Chatterbox's own settings: a lower `cfg` paces the speech more deliberately
and a lower `exaggeration` flattens it. Nothing is normalised — each file is as the model made it.
"""
import argparse, json, sys, wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from voiceover_render import ChatterboxEngine, spoken, pieces, trim, join, ear, seed_everything  # noqa: E402

LINE = ("Hoofdstuk één: de wereld van bouwen. Ik begin met de vraag die boven alles hangt wat we "
        "vanavond bespreken. Hoe bouw je een wereld die vandaag stevig overeind blijft, maar licht "
        "genoeg is om de toekomst niet te belasten?")

REFERENCE = ("Goedenavond, en welkom. Het is fijn dat u er bent. We nemen vanavond rustig de tijd "
             "om te kijken naar hoe we bouwen, waarom we dat zo doen, en wat er beter kan. "
             "Neem gerust iets te drinken, en luister even mee.")

# name, piper voice file, speaker id (for the multi-speaker model)
PIPER = [
    ("pim", "nl_NL-pim-medium", None),
    ("ronnie", "nl_NL-ronnie-medium", None),
    ("nathalie", "nl_BE-nathalie-medium", None),
    ("rdh", "nl_BE-rdh-medium", None),
    ("mls-5809", "nl_NL-mls_5809-low", None),
    ("mls-7432", "nl_NL-mls_7432-low", None),
]


def piper_reference(folder: Path, voice: str, speaker, out: Path):
    from piper import PiperVoice, SynthesisConfig
    v = PiperVoice.load(str(folder / f"{voice}.onnx"))
    with wave.open(str(out), "wb") as w:
        v.synthesize_wav(REFERENCE, w, syn_config=SynthesisConfig(length_scale=1.15, speaker_id=speaker))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--piper", required=True, help="folder holding the piper .onnx voices")
    ap.add_argument("--out", default=str(ROOT / "data/sounds/voice/samples-2026-09-22"))
    ap.add_argument("--tries", type=int, default=3)
    a = ap.parse_args()
    import soundfile as sf

    out = Path(a.out); (out / "refs").mkdir(parents=True, exist_ok=True)
    piper = Path(a.piper)

    # what to render: (file name, reference clip, exaggeration, cfg, description)
    runs = [("01-current", None, 0.4, 0.5, "the voice in the show now: Chatterbox's own, exaggeration 0.4, cfg 0.5"),
            ("02-current-slower", None, 0.3, 0.3, "the same voice, slower and calmer: exaggeration 0.3, cfg 0.3")]
    for i, (name, voice, speaker) in enumerate(PIPER):
        ref = out / "refs" / f"{name}.wav"
        if not ref.exists(): piper_reference(piper, voice, speaker, ref)
        runs.append((f"{i + 3:02d}-{name}", ref, 0.3, 0.3,
                     f"cloned from Piper's {voice}, slower and calmer: exaggeration 0.3, cfg 0.3"))

    engine = ChatterboxEngine("mps", None, 0.4, 0.5)
    listen = ear()
    said = spoken(LINE)
    report = []
    for name, ref, ex, cfg, what in runs:
        engine.voice, engine.exaggeration, engine.cfg = (str(ref) if ref else None), ex, cfg
        best = None
        for attempt in range(a.tries):
            seed_everything(1000 + attempt)
            audio = join([trim(engine.render(p, out), engine.sr) for p in pieces(said)], engine.sr)
            tmp = out / f".{name}.wav"; sf.write(tmp, audio, engine.sr)
            score = listen.score(said, listen.hear(tmp)[0]); tmp.unlink()
            if best is None or score > best[0]: best = (score, audio)
            if score >= 0.85: break
        score, audio = best
        sf.write(out / f"{name}.wav", audio, engine.sr)
        seconds = len(audio) / engine.sr
        report.append(dict(file=f"{name}.wav", reference=f"refs/{ref.name}" if ref else None, about=what,
                           seconds=round(seconds, 1), chars_per_second=round(len(said) / seconds, 1),
                           words_matched=round(score, 2)))
        print(f"{name}: {seconds:.1f}s, {len(said) / seconds:.1f} chars/s, matched {score:.2f}")
    (out / "samples.json").write_text(json.dumps(dict(line=LINE, samples=report), indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
