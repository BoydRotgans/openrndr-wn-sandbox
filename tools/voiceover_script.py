#!/usr/bin/env python3
"""
The subtitles of one track as a readable script, in the running order: a heading per chapter or
moment, the slide's title, and a line per state with its letter — the whole talk on a few pages,
which is what a speaker reads through and what a voice generator is handed.

    tools/voiceover_script.py                       # the extended track to stdout, as markdown
    tools/voiceover_script.py --track default
    tools/voiceover_script.py --plain > voice.txt   # the lines alone, one a paragraph, for TTS
    tools/voiceover_script.py --json                # [{id, state, chapter, title, text}, …]

It reads show-order.json for the order and chapters (a slide the order does not mention is
listed at the end, an archived one is left out) and show-subtitles[-extended].json for the
lines; the state counts are not known here, so a state with no line is simply not listed. The
letters are the nameplate's, the same the organizer, the cue sheet and the review site use.
"""
import argparse, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def order_entries(order):
    """(id, group title, kind) for every slide the order plays, in order."""
    out = []
    for item in order.get("order", []):
        if isinstance(item, str):
            out.append((item, "", ""))
        elif "id" in item:
            if item.get("on", True):
                out.append((item["id"], "", ""))
        else:
            title = item.get("chapter") or item.get("moment") or ""
            kind = "chapter" if "chapter" in item else "moment"
            for s in item.get("slides", []):
                sid = s if isinstance(s, str) else s.get("id")
                on = True if isinstance(s, str) else s.get("on", True)
                if sid and on:
                    out.append((sid, title, kind))
    return out


def state_index(key):
    n = 0
    for c in key.strip().upper():
        n = n * 26 + (ord(c) - 64)
    return n - 1


def lines_of(value):
    if isinstance(value, str):
        return {0: value.strip()}
    out = {}
    for k, v in value.items():
        text = " ".join(x.strip() for x in v) if isinstance(v, list) else str(v)
        if text.strip():
            out[state_index(k)] = text.strip()
    return out


def title_of(sid):
    return re.sub(r"-", " ", sid).capitalize()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="extended", choices=["default", "extended"])
    ap.add_argument("--order", default=str(ROOT / "show-order.json"))
    ap.add_argument("--plain", action="store_true", help="the lines alone, a paragraph each")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    file = ROOT / ("show-subtitles-extended.json" if a.track == "extended" else "show-subtitles.json")
    doc = json.loads(file.read_text())
    subs = {sid: lines_of(v) for sid, v in doc.get("subtitles", {}).items()}
    order = json.loads(Path(a.order).read_text())
    entries = order_entries(order)
    seen = {e[0] for e in entries}
    archived = set(order.get("archive", []))
    entries += [(sid, "", "") for sid in subs if sid not in seen and sid not in archived]
    headings = (doc.get("voiceover") or {}).get("slides", {})

    rows = []
    for sid, group, kind in entries:
        for step, text in sorted(subs.get(sid, {}).items()):
            rows.append({"id": sid, "step": step, "state": letter(step), "group": group, "kind": kind,
                         "title": (headings.get(sid) or {}).get("heading") or title_of(sid), "text": text})
    if a.json:
        json.dump(rows, sys.stdout, ensure_ascii=False, indent=2); print(); return
    if a.plain:
        for r in rows:
            print(r["text"]); print()
        return
    group = None; sid = None
    chars = sum(len(r["text"]) for r in rows)
    print(f"# Voice-over · {a.track}\n\n{file.name} · {len(rows)} lines · {chars} characters · about {chars / 15 / 60:.0f} min at 15 cps\n")
    for r in rows:
        if r["group"] != group:
            group = r["group"]
            print(f"\n## {group or '—'}\n")
        if r["id"] != sid:
            sid = r["id"]
            print(f"\n### {r['title']}  `{sid}`\n")
        print(f"**{r['state']}** — {r['text']}\n")


def letter(n):
    s = ""
    while True:
        s = chr(65 + n % 26) + s
        n = n // 26 - 1
        if n < 0:
            return s


if __name__ == "__main__":
    main()
