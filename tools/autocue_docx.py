#!/usr/bin/env python3
"""Erik's autocue off the production's Word file: show-subtitles-erik.json, the `erik` track.

    python3 tools/autocue_docx.py
    python3 tools/autocue_docx.py --docx input/autocue/other.docx --show build/show.json

The file is "Concrete Force Experience — definitieve redactie per klikbeeld": a heading a click
(`SLIDE 003 / 176`), the moment or chapter under it, then either **AUTOCUE – letterlijk uitspreken**
with the words Erik reads, or **REGIE – geen spreektekst** with what to do instead, and a red
`KLIK → VOLGENDE BEELD` after every one (`EINDE PRESENTATIE` after the last).

**A click is a state of the running order, counted in order.** SLIDE n is the n-th state the show
plays — the document was written off the slides-per-click pdf, a page a state — so nothing in it
names a slide and the pairing is by position. That is only safe while the order has not moved, so
it is checked twice and the tool refuses on either: the document's count against the order's
states, and the document's sections (AANKOMST, OPENING, HOOFDSTUK 1 …) changing on exactly the
states where the order's moments and chapters change.

**What is said and what is done are kept apart.** The words go under `subtitles`, the track's
lines, which the autocue reads and the voice-over selector switches to. Everything that is not to
be read aloud goes under `directions` — a REGIE block, a `▸` cue inside an AUTOCUE block, a
`REGIE:` or `BEELDCUE:` line under one, the closing `EINDE PRESENTATIE` — which only the autocue
draws, apart from the script. A REGIE block that is plainly speech (the ending's thanks) is taken
as speech and said so.

**What the Word file does not carry is laid over it** from `show-autocue-additions.json`: Karim Weiss's welcome
on the director's tag and his closing on the ending wall (19 October 2026). A state there is named by its key,
not its place, so a new import keeps it. `said` replaces the line, given as a list of lines (an empty one is a
paragraph), which the autocue keeps as written; `speaker` names who reads it, which the autocue shows; and
`directions`, where given, replace the state's own ([] clears them); and `added` marks a click the Word file
does not count, a section of a speech it does not carry, which the pairing leaves out. Karim's welcome is the
director's tag's eight states and his closing the ending's three, a section a click, so the slides' click counts
in Slideshow.kt are kept to the file. The lines written over from the Word file are listed as the tool runs.

**Every state read aloud has a speaker**: `--speaker` (Erik Koremans) for the track as a whole, written as its
`speaker`, and the additions' `speaker` where someone else reads, written under `speakers`. The autocue shows
who it is.

    --docx   the Word file (default input/autocue/Concrete_Force_Experience_Autocue_Met_KLIK_Aanwijzingen.docx)
    --show   the organizer's /api/show, a url or a saved copy (default http://127.0.0.1:8765/api/show)
    --extra  what is laid over it (default show-autocue-additions.json; none where the file is missing)
    --out    the track to write (default show-subtitles-erik.json)
"""
import argparse
import json
import os
import re
import sys
import urllib.request
import zipfile
from datetime import date
from xml.etree import ElementTree

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
LETTERS = [chr(c) for c in range(ord("A"), ord("Z") + 1)]
SLIDE = re.compile(r"^SLIDE\s+(\d+)\s*/\s*(\d+)\s*$")
# A REGIE block whose body is one of these is a direction; any other body is speech under the wrong heading.
STAGE = re.compile(r"^(Laat het beeld spreken|\[)")


def letter(i):
    return LETTERS[i] if i < 26 else letter(i // 26 - 1) + LETTERS[i % 26]


def paragraphs(path):
    """Every paragraph of the document's body, in order: its text, tabs and breaks kept."""
    with zipfile.ZipFile(path) as z:
        root = ElementTree.fromstring(z.read("word/document.xml"))
    for p in root.iter(W + "p"):
        out = []
        for node in p.iter():
            if node.tag == W + "t":
                out.append(node.text or "")
            elif node.tag == W + "tab":
                out.append("\t")
            elif node.tag in (W + "br", W + "cr"):
                out.append("\n")
        yield "".join(out).strip()


def clean(text):
    """A line with its blank for handwritten changes cut off: "BEELDCUE: … | Eigen wijzigingen: ____"."""
    return re.split(r"\s*\|\s*Eigen wijzigingen", text)[0].strip()


def read_docx(path):
    """The document as a header and a list of clicks: number, of, section, kind, body and the lines under it."""
    head, clicks = [], []
    for text in paragraphs(path):
        m = SLIDE.match(text)
        if m:
            clicks.append({"n": int(m.group(1)), "of": int(m.group(2)), "section": None, "kind": None, "body": [], "after": []})
            continue
        if not clicks:
            if text:
                head.append(text)
            continue
        c = clicks[-1]
        if not text:
            continue
        if c["section"] is None and c["kind"] is None and not c["body"]:
            c["section"] = text
        elif c["kind"] is None and re.match(r"^(AUTOCUE|REGIE)\b", text):
            c["kind"] = "autocue" if text.startswith("AUTOCUE") else "regie"
        elif text.startswith("KLIK") or text.startswith("EINDE") or text.startswith("REGIE:") or text.startswith("BEELDCUE:"):
            c["after"].append(clean(text))
        else:
            c["body"].append(text)
    return head, clicks


def read_show(source):
    if re.match(r"^https?://", source):
        with urllib.request.urlopen(source, timeout=10) as r:
            return json.load(r)
    with open(source) as f:
        return json.load(f)


def states_of(show):
    """Every state the show plays, in order: (slide id, letter, the moment or chapter it stands in)."""
    where = {}

    def walk(items, head=None):
        for it in items:
            if isinstance(it, str):
                where[it] = head
            elif isinstance(it, dict):
                if "chapter" in it:
                    walk(it.get("slides", []), "chapter " + it["chapter"])
                elif "moment" in it:
                    walk(it.get("slides", []), "moment " + it["moment"])
                elif "id" in it:
                    where[it["id"]] = head

    order = show.get("order") or {}
    walk(order.get("order", []) if isinstance(order, dict) else order)
    steps = {c["id"]: c["steps"] for c in show["catalogue"]}
    return [(sid, letter(k), where.get(sid)) for sid in show["running"] for k in range(steps[sid])]


def split(click):
    """A click as (what is said, the directions): the body read by its heading, and the lines under it."""
    body = " ".join(click["body"]).strip()
    said, directions, note = "", [], None
    if click["kind"] == "autocue":
        # "▸" starts a cue inside the text: what to do, not what to say.
        parts = [p.strip() for p in body.split("▸")]
        said = parts[0]
        directions += [p for p in parts[1:] if p]
    elif STAGE.match(body) or not body:
        if body:
            directions.append(body)
    else:
        said = body
        note = "headed REGIE, but the text is speech: taken as speech"
    for line in click["after"]:
        if line.startswith("KLIK"):
            continue
        directions.append(line)
    return said, directions, note


def refuse(problems):
    if problems:
        print("refused, nothing written:")
        for p in problems:
            print("  " + p)
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--docx", default="input/autocue/Concrete_Force_Experience_Autocue_Met_KLIK_Aanwijzingen.docx")
    ap.add_argument("--show", default="http://127.0.0.1:8765/api/show")
    ap.add_argument("--extra", default="show-autocue-additions.json")
    ap.add_argument("--speaker", default="Erik Koremans", help="who reads a line no addition names")
    ap.add_argument("--out", default="show-subtitles-erik.json")
    args = ap.parse_args()

    head, clicks = read_docx(args.docx)
    states = states_of(read_show(args.show))
    extra = {}
    if args.extra and os.path.isfile(args.extra):
        with open(args.extra) as f:
            extra = json.load(f).get("states", {})
    # A click of a speech the Word file does not carry is no click of the Word file: left out of the pairing.
    added = {k for k, v in extra.items() if v.get("added")}
    paired = [s for s in states if f"{s[0]}-{s[1]}" not in added]
    print(f"{args.docx}: {len(clicks)} clicks; the running order: {len(states)} states"
          + (f", {len(added)} of them added in {args.extra}" if added else ""))

    problems = []
    keys = {f"{sid}-{let}" for sid, let, _ in states}
    for key, v in extra.items():
        if key not in keys:
            problems.append(f"{args.extra}: {key} is no state of the running order")
        elif v.get("added") and "said" not in v and "directions" not in v:
            problems.append(f"{args.extra}: {key} is added but says nothing and directs nothing")
    if [c["n"] for c in clicks] != list(range(1, len(clicks) + 1)):
        problems.append("the clicks are not numbered 1 to n in order")
    if clicks and any(c["of"] != len(clicks) for c in clicks):
        problems.append(f"the clicks say they are of {clicks[0]['of']}, but there are {len(clicks)}")
    if len(clicks) != len(paired):
        problems.append(f"{len(clicks)} clicks against {len(paired)} states: the running order has changed since the document was written"
                        + (", or a slide's click count no longer matches the states added for it" if added else ""))
    else:
        # The sections change where the order's moments and chapters change, and nowhere else.
        for i in range(1, len(clicks)):
            doc = clicks[i]["section"] != clicks[i - 1]["section"]
            show = paired[i][2] != paired[i - 1][2]
            if doc != show:
                problems.append(f"slide {i + 1:03} ({paired[i][0]}-{paired[i][1]}): the document's section "
                                f"{'changes' if doc else 'does not change'} here ({clicks[i - 1]['section']} → {clicks[i]['section']}), "
                                f"the order's {'does' if show else 'does not'} ({paired[i - 1][2]} → {paired[i][2]})")
    refuse(problems)

    subtitles, directions, speakers, slides, notes = {}, {}, {}, {}, []
    by_key = {f"{sid}-{let}": c for c, (sid, let, _) in zip(clicks, paired)}
    for sid, let, where in states:
        key = f"{sid}-{let}"
        c = by_key.get(key)
        said, todo, note = split(c) if c else ("", [], None)
        over = extra.get(key)
        if over:
            if "said" in over:
                text = over["said"]
                text = "\n".join(text) if isinstance(text, list) else text
                if said:
                    notes.append(f"slide {c['n']:03} ({key}): the Word file's line is written over by {args.extra}: “{said[:70]}…”")
                said, note = text.strip(), None
            if "directions" in over:
                todo = list(over["directions"])
        who = (over or {}).get("speaker") or args.speaker
        if said:
            subtitles.setdefault(sid, {})[let] = said
            if who != args.speaker:
                speakers.setdefault(sid, {})[let] = who
        if todo:
            directions.setdefault(sid, {})[let] = todo if len(todo) > 1 else todo[0]
        if note:
            notes.append(f"slide {c['n']:03} ({key}): {note}")
        s = slides.setdefault(sid, {"first": None, "last": None, "section": c["section"] if c else where, "states": {}, "speakers": []})
        if c:
            s["first"] = s["first"] or c["n"]
            s["last"] = c["n"]
        s["states"][let] = c["n"] if c else None
        if said and who not in s["speakers"]:
            s["speakers"].append(who)

    # The organizer prints a slide's heading above its rows: which clicks of the document it is, and who speaks.
    def heading(s):
        span = "" if s["first"] is None else f" · klik {s['first']:03}" + (f"–{s['last']:03}" if s["last"] != s["first"] else "")
        return f"Erik autocue{span} · {s['section']}" + (" · " + ", ".join(s["speakers"]) if s["speakers"] else "")

    def placed(s):
        if len(s["states"]) < 2:
            return ""
        return " ".join(f"{k} is klik {n:03}." if n else f"{k} is toegevoegd." for k, n in s["states"].items())

    voiceover = {sid: {"heading": heading(s), "note": placed(s)} for sid, s in slides.items()}

    out = {
        "note": "Erik's autocue, the erik track: what is read over each state (subtitles), who reads it where it is not "
                "the track's own speaker (speakers), and what is done instead or besides (directions, drawn on the "
                f"autocue only). Written by tools/autocue_docx.py from {args.docx}, with {args.extra} laid over it, on "
                f"{date.today().isoformat()}; a click of the document is a state of the running order, in order. Lines "
                "edited in the organizer are kept here; importing the document again writes over them.",
        "speaker": args.speaker,
        "autocue": {
            "source": args.docx,
            "title": head[0] if head else "",
            "edition": head[1] if len(head) > 1 else "",
            "instructions": [h for h in head[2:]],
            "clicks": {f"{sid}-{let}": c["n"] for c, (sid, let, _) in zip(clicks, paired)},
        },
        "voiceover": {"slides": voiceover},
        "speakers": speakers,
        "directions": directions,
        "subtitles": subtitles,
    }
    with open(args.out, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    said = sum(len(v) for v in subtitles.values())
    done = sum(len(v) for v in directions.values())
    print(f"wrote {args.out}: {said} states to read, {done} with a direction, on {len(slides)} slides" +
          (f"; {len(extra)} states from {args.extra}" if extra else ""))
    for n in notes:
        print("  " + n)


if __name__ == "__main__":
    main()
