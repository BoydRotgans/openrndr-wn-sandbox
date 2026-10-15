#!/usr/bin/env python3
"""The show as a deck on paper: a page per click, landscape, the picture and nothing said over it.

    .venv/bin/python tools/slides_pdf.py

writes export/wn-slides-per-klik.pdf. It stands on tools/subtitle_notes_pdf.py for everything but
the page: the same running order (the organizer's `/api/show`, or a saved copy of it), the same
film and state log, and the same moment in each state (three quarters in, at most six seconds), so
a page here is the picture beside the line on the speaker's sheet, only large.

**A slide is its own pane, a wall is the wall.** A slide composes for the right-hand projector and
stands beside its chapter card, which does not change for a whole chapter, so its page is that
1920 by 1080 pane — 16:9 fills a landscape page, where the whole 3.56:1 wall would be a strip
across it. A backdrop or a scene takes both projectors and is drawn whole, edge to edge. Which is
which is the catalogue's own `wide`.

    --film     the film the pictures come off (a clean one: no subtitles in the picture)
    --cut      the state log of that film: the .states file beside it
    --show     the organizer's /api/show, a url or a saved file
    --out      the pdf to write
"""
import argparse
import html
import json
import os
import re
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from subtitle_notes_pdf import (CHROME, LETTERS, MOMENTS, ROOT, frame_at, read_cut, sections,  # noqa: E402
                                title_of, to_pdf, track_lines)


def catalogue(source):
    """{slide: whether it takes the whole wall}, off the same /api/show the order comes from."""
    if re.match(r"^https?://", source):
        with urllib.request.urlopen(source, timeout=5) as r:
            show = json.load(r)
    else:
        with open(source) as f:
            show = json.load(f)
    cat = {c["id"]: c for c in show["catalogue"]}
    return [(i, cat[i]["steps"], bool(cat[i].get("wide"))) for i in show["running"]]


def pictures(order, cut, film, quality, cache):
    """A jpg per state: the slide's pane cropped off the film, or the whole wall."""
    os.makedirs(cache, exist_ok=True)
    by_key = {s["key"]: s for s in cut["states"]}
    jobs, out = [], {}
    for slide, n, wide in order:
        for i in range(n):
            key = f"{slide}-{LETTERS[i]}"
            path = os.path.join(cache, f"{key}-{'wall' if wide else 'pane'}.jpg")
            out[key] = path
            if os.path.isfile(path):
                continue
            # the film is the 3840 wall; a slide's pane is its right half
            crop = [] if wide else ["-vf", "crop=iw/2:ih:iw/2:0"]
            if key in by_key:
                jobs.append(["ffmpeg", "-v", "error", "-y", "-ss", f"{frame_at(by_key[key]):.3f}",
                             "-i", film, "-frames:v", "1", *crop, "-q:v", str(quality), path])
            else:
                preview = os.path.join(ROOT, "build/previews", f"{slide}-1.png")
                if os.path.isfile(preview):
                    print(f"picture: {key} is not in the film; its organizer preview stands in")
                    jobs.append(["ffmpeg", "-v", "error", "-y", "-i", preview, *crop, "-q:v", str(quality), path])
                else:
                    print(f"picture: {key} is in neither the film nor the previews")
                    out[key] = None
    if jobs:
        print(f"pictures: {len(jobs)} frames off {os.path.relpath(film, ROOT)} …")
        with ThreadPoolExecutor(6) as pool:
            list(pool.map(lambda c: subprocess.run(c, check=True), jobs))
    return out


def build_html(order, where, heads, pics, data_d, data_e):
    months = ["januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus",
              "september", "oktober", "november", "december"]
    today = date.today()
    version = f"versie {today.day} {months[today.month - 1]} {today.year}"
    chapters = [n for k, n in heads if k == "chapter"]
    parts = [HEAD]
    for slide, n, wide in order:
        kind, name = where.get(slide, (None, None))
        if kind == "chapter":
            section = f"Hoofdstuk {chapters.index(name) + 1} · {re.sub(r'(?<=[a-z])-(?=[a-z])', '', name)}"
        else:
            section = MOMENTS.get(name, name or "")
        title = title_of(slide, data_d, data_e)
        for i in range(n):
            pic = pics.get(f"{slide}-{LETTERS[i]}")
            img = f"<img src='file://{pic}' alt=''>" if pic else "<div class='nopic'></div>"
            step = f"{LETTERS[i]} · {i + 1}/{n}" if n > 1 else ""
            parts.append(
                f"<article class='{'wall' if wide else 'pane'}'>"
                f"<header><span class='section'>{html.escape(section)}</span>"
                f"<span class='title'>{html.escape(title)}</span>"
                f"<span class='step'>{step}</span></header>"
                f"<div class='pic'>{img}</div>"
                f"<footer>Willy Naessens · {version}</footer>"
                "</article>")
    parts.append("</body></html>")
    return "".join(parts)


HEAD = """<!doctype html><html lang="nl"><meta charset="utf-8"><title>Slides per klik</title><style>
@page { size: A4 landscape; margin: 9mm 10mm 10mm;
  @bottom-right { content: counter(page) " / " counter(pages); font: 7.5pt "Helvetica Neue", Arial, sans-serif; color: #999; } }
* { box-sizing: border-box; }
body { margin: 0; font: 9pt/1.3 "Helvetica Neue", Helvetica, Arial, sans-serif; color: #111; }
/* A page a click: the head, the picture as large as the page allows, and nothing else. */
article { height: 190mm; display: flex; flex-direction: column; break-after: page; page-break-after: always; }
article:last-child { break-after: auto; page-break-after: auto; }
header { display: flex; align-items: baseline; gap: 5mm; padding-bottom: 2mm; border-bottom: .8pt solid #111; }
header .section { font-size: 7.5pt; letter-spacing: .14em; text-transform: uppercase; color: #888; }
header .title { font-family: Rockwell, Georgia, serif; font-size: 13pt; }
header .step { margin-left: auto; font-size: 9pt; color: #666; font-variant-numeric: tabular-nums; }
.pic { flex: 1 1 auto; min-height: 0; display: flex; align-items: center; justify-content: center; padding: 3mm 0; }
.pic img { max-width: 100%; max-height: 100%; display: block; }
.nopic { width: 100%; aspect-ratio: 16/9; background: #eee; }
footer { font-size: 7.5pt; color: #999; }
</style><body>"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--film", default=os.path.join(ROOT, "video/wn-experience_2026-10-02-clean.mp4"))
    ap.add_argument("--cut", default=None, help="defaults to the .states log beside the film")
    ap.add_argument("--show", default="http://localhost:8765/api/show")
    ap.add_argument("--order", default=os.path.join(ROOT, "show-order.json"))
    ap.add_argument("--out", default=os.path.join(ROOT, "export/wn-slides-per-klik.pdf"))
    ap.add_argument("--quality", type=int, default=4, help="ffmpeg jpeg q, 2 best to 31 worst")
    args = ap.parse_args()

    if not args.cut:
        args.cut = os.path.splitext(args.film)[0] + ".states"
    for path, what in ((args.film, "film"), (args.cut, "state log"), (CHROME, "Chrome")):
        if not os.path.isfile(path):
            sys.exit(f"No {what} at {path}.")

    cut = read_cut(args.cut, args.film)
    order = catalogue(args.show)
    print(f"order: {len(order)} slides, {sum(n for _, n, _ in order)} clicks")
    where, heads = sections(args.order)
    cache = os.path.join(ROOT, "build/slides-pdf")
    pics = pictures(order, cut, args.film, args.quality,
                    os.path.join(cache, "frames", os.path.splitext(os.path.basename(args.film))[0]))
    data_d, _ = track_lines("default", {}, lambda m: None)
    data_e, _ = track_lines("extended", {}, lambda m: None)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    to_pdf(build_html(order, where, heads, pics, data_d, data_e), args.out, cache)
    print(f"-> {os.path.relpath(args.out, ROOT)}: {sum(n for _, n, _ in order)} pages, "
          f"{os.path.getsize(args.out) // 1024} KB")


if __name__ == "__main__":
    main()
