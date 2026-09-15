#!/usr/bin/env python3
"""Turn the client's deck into reference frames the show can stand a placeholder on.

    .venv/bin/python tools/reference_frames.py

The source is export/wn-speaker-notes.pptx — the deck tools/figma_to_pptx.py builds —
and the render cache beside it, export/pptx-images, which holds every Figma frame at
7816x2160: the whole wall at 2x, a chapter card on the left and the slide's own pane on
the right, or one picture edge to edge. This writes, into export/references:

    frames.json          every frame: name, chapter, whether it took the whole wall,
                         and its speaker note
    <name>-wall.jpg      the whole frame at a quarter size, for the organizer
    <name>-pane.png      the slide's own pane at 1920x1080, for the placeholder slide
                         (for frames that carry a chapter card, and for frames drawn as
                         a single pane with no wall around them at all)

**The deck holds two kinds of frame, and which is read off the file.** 21 are drawn at
the wall's size, 7816x2160 at 2x — a chapter card on the left, the pane on the right —
and the pane is cut out of those. The other 53 are drawn as a single 1920x1080 slide
(3840x2160 here) with no card and no wall around it, which is what a pptx page is; those
are the pane whole, and `pane_only` says so. A wall frame that painted straight across
the gutter between card and pane — the deck's grey on a card frame, black across it —
would be marked `wide` and kept whole; none does yet.

Nothing here reads Figma or the network, and the output folder is under export/, which
is client material and not committed: run this again after a checkout, or after the
deck is re-exported, and the references come back.
"""
import argparse
import hashlib
import json
import os
import re
import sys

from PIL import Image, ImageStat
from pptx import Presentation

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The wall at 2x: card 0..3840, gutter 3840..3976, pane 3976..7816.
CARD = 3840
GUTTER = 136
PANE = (CARD + GUTTER, 0, 7816, 2160)
FRAME_RE = re.compile(r"^(\d+)-(\d+)$")

# PowerPoint keeps a symbol font's glyph as a private-use code point; one Wingdings
# arrow sits in the middle of a sentence in this deck.
SYMBOLS = {"": "→", "": "→", "": "•", "": "•"}


def frame_names(prs, cache_dir):
    """Each slide's Figma frame name, recovered by matching its picture's bytes against
    the render cache — the same pairing tools/speaker_notes_pdf.py makes."""
    by_hash = {}
    for name in os.listdir(cache_dir):
        if name.endswith(".png"):
            with open(os.path.join(cache_dir, name), "rb") as f:
                by_hash[hashlib.md5(f.read()).hexdigest()] = re.sub(r"@\d+x\.png$", "", name)
    out = []
    for slide in prs.slides:
        picture = next((s for s in slide.shapes if s.shape_type == 13), None)
        out.append(by_hash.get(hashlib.md5(picture.image.blob).hexdigest()) if picture else None)
    return out


def note_of(slide):
    note = slide.notes_slide.notes_text_frame.text.strip() if slide.has_notes_slide else ""
    for glyph, char in SYMBOLS.items():
        note = note.replace(glyph, char)
    return note


def is_wall(image):
    """True for a frame drawn at the wall's size, 7816x2160. The deck also holds frames drawn
    as a single 1920x1080 pane (3840x2160 here) with no card and no wall around them."""
    return image.width == 7816


def is_wide(image):
    """True when a wall frame paints across the gutter — no card, one picture edge to edge."""
    gutter = image.crop((CARD, 0, CARD + GUTTER, image.height)).convert("L")
    return ImageStat.Stat(gutter).mean[0] < 10


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pptx", default=os.path.join(ROOT, "export/wn-speaker-notes.pptx"))
    ap.add_argument("--cache", default=os.path.join(ROOT, "export/pptx-images"))
    ap.add_argument("--out", default=os.path.join(ROOT, "export/references"))
    ap.add_argument("--wall-width", type=int, default=1954, help="a quarter of the frame")
    ap.add_argument("--quality", type=int, default=86)
    ap.add_argument("--force", action="store_true", help="write pictures that are already there")
    args = ap.parse_args()

    if not os.path.isfile(args.pptx):
        sys.exit(f"No deck at {args.pptx} — run tools/figma_to_pptx.py first.")
    if not os.path.isdir(args.cache):
        sys.exit(f"No render cache at {args.cache}; the frame names come from it.")
    os.makedirs(args.out, exist_ok=True)

    prs = Presentation(args.pptx)
    names = frame_names(prs, args.cache)
    frames = []
    written = 0
    for index, slide in enumerate(prs.slides):
        name = names[index]
        match = FRAME_RE.match(name or "")
        if not match:
            continue   # a chapter card, or a slide the cache does not know
        source = os.path.join(args.cache, f"{name}@2x.png")
        image = Image.open(source).convert("RGB")
        # A frame drawn as one pane is that pane, whole: not wide, and nothing to cut.
        wall_frame = is_wall(image)
        wide = wall_frame and is_wide(image)

        wall = os.path.join(args.out, f"{name}-wall.jpg")
        if args.force or not os.path.isfile(wall):
            w = args.wall_width
            image.resize((w, round(w * image.height / image.width)), Image.LANCZOS) \
                .save(wall, "JPEG", quality=args.quality, optimize=True)
            written += 1
        pane = None
        if not wide:
            pane = os.path.join(args.out, f"{name}-pane.png")
            if args.force or not os.path.isfile(pane):
                cut = image.crop(PANE) if wall_frame else image
                cut.resize((1920, 1080), Image.LANCZOS).save(pane, "PNG", optimize=True)
                written += 1

        frames.append({
            "name": name,
            "chapter": int(match.group(1)),
            "index": int(match.group(2)),
            "slide": index + 1,
            "wide": wide,
            "pane_only": not wall_frame,
            "note": note_of(slide),
            "wall": os.path.basename(wall),
            "pane": os.path.basename(pane) if pane else None,
        })

    frames.sort(key=lambda f: (f["chapter"], f["index"]))
    with open(os.path.join(args.out, "frames.json"), "w", encoding="utf-8") as f:
        json.dump({"source": os.path.relpath(args.pptx, ROOT), "frames": frames}, f, ensure_ascii=False, indent=2)
        f.write("\n")

    wide = sum(1 for f in frames if f["wide"])
    print(f"-> {os.path.relpath(args.out, ROOT)}: {len(frames)} frames, {wide} across the whole wall, "
          f"{sum(1 for f in frames if f['note'])} with notes, {written} pictures written")


if __name__ == "__main__":
    main()
