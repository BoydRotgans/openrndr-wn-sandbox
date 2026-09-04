#!/usr/bin/env python3
"""Turn a Figma page of slides into a .pptx with real PowerPoint speaker notes.

    .venv/bin/python tools/figma_to_pptx.py

Every frame named `<chapter>-<n>` becomes a slide, in name order; the text in its
`notes-<chapter>-<n>` box becomes that slide's speaker notes — the notes pane
PowerPoint shows in presenter view, not a caption drawn on the slide.

Configuration is read from .env (see .env.example). Rendered PNGs are cached in
PPT_CACHE, so a re-run that only changes the deck's assembly costs no export and
no network; PPT_REFRESH=true forces a re-render.
"""
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.util import Emu, Pt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.figma.com/v1"
EMU_PER_EMU_INCH = 914400


# ---------------------------------------------------------------- config


def load_env():
    """Real environment wins over .env, so one value can be changed for one run."""
    values = {}
    path = os.path.join(ROOT, ".env")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                values[key.strip()] = val.strip().strip('"').strip("'")
    values.update({k: v for k, v in os.environ.items() if k in values or k.startswith(("FIGMA_", "PPT_"))})
    return values


ENV = load_env()


def need(key):
    val = ENV.get(key, "").strip()
    if not val:
        sys.exit(f"{key} is not set — add it to .env (see .env.example)")
    return val


def get(key, default=""):
    val = ENV.get(key, "").strip()
    return val if val else default


def flag(key, default=False):
    val = get(key).lower()
    return val in ("1", "true", "yes", "on") if val else default


def file_key(raw):
    """FIGMA_FILE_KEY holds a full share URL in this project, not a bare key."""
    match = re.search(r"/(?:file|design)/([A-Za-z0-9]+)", raw)
    return match.group(1) if match else raw


# ---------------------------------------------------------------- figma


def api(path, token, timeout=120):
    req = urllib.request.Request(API + path, headers={"X-Figma-Token": token})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as err:
            if err.code in (429, 500, 502, 503) and attempt < 3:
                time.sleep(2 ** attempt)
                continue
            sys.exit(f"Figma API {err.code} on {path}: {err.read()[:300].decode('utf8', 'replace')}")
        except (urllib.error.URLError, TimeoutError, OSError) as err:
            # Rendering is done server side and a wide frame at 2x is slow, so a
            # read timeout here means "still working", not "failed".
            if attempt < 3:
                time.sleep(2 ** attempt)
                continue
            sys.exit(f"Figma API unreachable on {path}: {err}")
    raise RuntimeError("unreachable")


def find_page(token, key, name):
    doc = api(f"/files/{key}?depth=1", token)
    wanted = name.strip().lower()
    for page in doc["document"]["children"]:
        if page["name"].strip().lower() == wanted:
            return page["id"], doc.get("name", "")
    have = ", ".join(repr(p["name"]) for p in doc["document"]["children"])
    sys.exit(f"No page named {name!r}. This file has: {have}")


def text_of(node, out):
    """Every TEXT string under a node, in document order."""
    if node.get("type") == "TEXT":
        chars = (node.get("characters") or "").strip()
        if chars:
            out.append(chars)
    for child in node.get("children", ()) or ():
        text_of(child, out)
    return out


SLIDE_RE = re.compile(r"^(\d+)-(\d+)$")
CARD_RE = re.compile(r"^chapter-(\d+)$")


def collect(page_node, note_prefix, want_cards):
    """The deck in running order, each entry a frame id plus its note text.

    Order comes from the names rather than from the canvas: after the layout pass
    the names *are* the running order, and reading them cannot be thrown off by a
    frame that is a few pixels out of its row.
    """
    frames = [c for c in page_node.get("children", ()) if c.get("type") == "FRAME"]
    notes = {}
    for frame in frames:
        if frame["name"].startswith(note_prefix):
            body = "\n\n".join(text_of(frame, []))
            notes[frame["name"][len(note_prefix):]] = body

    slides = defaultdict(list)
    for frame in frames:
        match = SLIDE_RE.match(frame["name"])
        if match:
            slides[int(match.group(1))].append((int(match.group(2)), frame))
    cards = {}
    for frame in frames:
        match = CARD_RE.match(frame["name"])
        if match:
            cards[int(match.group(1))] = frame

    deck = []
    for chapter in sorted(slides):
        if want_cards and chapter in cards:
            card = cards[chapter]
            deck.append({"id": card["id"], "name": card["name"], "note": "", "card": True})
        for _, frame in sorted(slides[chapter], key=lambda pair: pair[0]):
            deck.append({
                "id": frame["id"],
                "name": frame["name"],
                "note": notes.get(frame["name"], ""),
                "card": False,
            })
    orphans = sorted(set(notes) - {d["name"] for d in deck})
    return deck, orphans


def render(deck, token, key, scale, cache, refresh, batch, timeout):
    """Export each frame to PNG, in batches, skipping anything already cached."""
    os.makedirs(cache, exist_ok=True)
    todo = []
    for item in deck:
        item["png"] = os.path.join(cache, f"{item['name']}@{scale}x.png")
        if refresh or not os.path.exists(item["png"]):
            todo.append(item)
    if not todo:
        print(f"   all {len(deck)} frames already rendered in {os.path.relpath(cache, ROOT)}")
        return

    print(f"   rendering {len(todo)} of {len(deck)} frames at {scale}x, {batch} per request")
    for start in range(0, len(todo), batch):
        chunk = todo[start:start + batch]
        ids = ",".join(item["id"] for item in chunk)
        query = urllib.parse.urlencode({"ids": ids, "format": "png", "scale": scale})
        result = api(f"/images/{key}?{query}", token, timeout=timeout)
        if result.get("err"):
            sys.exit(f"Figma image export failed: {result['err']}")
        for item in chunk:
            url = (result.get("images") or {}).get(item["id"])
            if not url:
                print(f"     ! no image returned for {item['name']}, skipped")
                item["png"] = None
                continue
            with urllib.request.urlopen(url, timeout=180) as resp:
                data = resp.read()
            with open(item["png"], "wb") as fh:
                fh.write(data)
        done = min(start + batch, len(todo))
        print(f"     {done}/{len(todo)}  ({chunk[-1]['name']})", flush=True)


# ---------------------------------------------------------------- pptx


def png_size(path):
    """Width and height straight out of the IHDR — no image library needed."""
    with open(path, "rb") as fh:
        head = fh.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def build(deck, out, width_px, height_px, background, dpi):
    # px -> EMU through a stated dpi rather than the implicit 96: at 144 a
    # 1920x1080 deck comes out 13.333x7.5in, which is PowerPoint's own widescreen
    # size, so the file opens as an ordinary deck rather than a 20in oddity.
    emu = EMU_PER_EMU_INCH / dpi
    prs = Presentation()
    prs.slide_width = Emu(round(width_px * emu))
    prs.slide_height = Emu(round(height_px * emu))
    blank = prs.slide_layouts[6]
    ink = RGBColor.from_string(background.lstrip("#").upper())

    letterboxed = 0
    for item in deck:
        slide = prs.slides.add_slide(blank)

        # The deck mixes 16:9 and ultrawide frames, so the ground shows wherever
        # a slide does not fill the frame. Painted rather than left white.
        bg = slide.background
        bg.fill.solid()
        bg.fill.fore_color.rgb = ink

        if item.get("png"):
            src_w, src_h = png_size(item["png"])
            # Contain: never crop, never distort. A wide frame letterboxes.
            ratio = min(width_px / src_w, height_px / src_h)
            draw_w, draw_h = src_w * ratio, src_h * ratio
            if draw_h < height_px - 1:
                letterboxed += 1
            slide.shapes.add_picture(
                item["png"],
                Emu(round((width_px - draw_w) / 2 * emu)),
                Emu(round((height_px - draw_h) / 2 * emu)),
                Emu(round(draw_w * emu)),
                Emu(round(draw_h * emu)),
            )

        # The speaker note goes in PowerPoint's own notes pane, which is what
        # presenter view reads — not a caption drawn onto the slide.
        frame = slide.notes_slide.notes_text_frame
        frame.text = item["note"] or ""
        for para in frame.paragraphs:
            for run in para.runs:
                run.font.size = Pt(14)

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    prs.save(out)
    return letterboxed


# ---------------------------------------------------------------- main


def main():
    token = need("FIGMA_TOKEN")
    key = file_key(need("FIGMA_FILE_KEY"))
    page_name = get("PPT_PAGE", "SHEETS - SPEAKER NOTES")
    width_px = int(get("PPT_WIDTH", "1920"))
    height_px = int(get("PPT_HEIGHT", "1080"))
    scale = get("PPT_SCALE", "2")
    background = get("PPT_BACKGROUND", "000000")
    note_prefix = get("PPT_NOTE_PREFIX", "notes-")
    want_cards = flag("PPT_CHAPTER_SLIDES", True)
    cache = os.path.join(ROOT, get("PPT_CACHE", "export/pptx-images"))
    out = os.path.join(ROOT, get("PPT_OUT", "export/wn-speaker-notes.pptx"))
    refresh = flag("PPT_REFRESH", False)
    dpi = float(get("PPT_DPI", "144"))
    batch = int(get("PPT_BATCH", "5"))
    timeout = int(get("PPT_TIMEOUT", "300"))

    print(f"1. reading {page_name!r}")
    page_id, file_name = find_page(token, key, page_name)
    page = api(f"/files/{key}/nodes?ids={urllib.parse.quote(page_id)}&depth=3", token)
    node = page["nodes"][page_id]["document"]
    deck, orphans = collect(node, note_prefix, want_cards)
    if not deck:
        sys.exit(f"No frames named '<chapter>-<n>' on {page_name!r} — has the layout pass run?")
    with_notes = sum(1 for d in deck if d["note"])
    cards = sum(1 for d in deck if d["card"])
    print(f"   {file_name}: {len(deck)} slides ({cards} chapter cards), {with_notes} with speaker notes")
    if orphans:
        print(f"   ! {len(orphans)} note boxes match no slide: {', '.join(orphans)}")

    print("2. rendering frames")
    render(deck, token, key, scale, cache, refresh, batch, timeout)

    print("3. building the deck")
    boxed = build(deck, out, width_px, height_px, background, dpi)
    size = os.path.getsize(out) / 1e6
    print(f"   {os.path.relpath(out, ROOT)} — {len(deck)} slides, {size:.1f} MB, "
          f"{width_px / dpi:.3f}x{height_px / dpi:.3f} in")
    print(f"   {boxed} letterboxed (wider than {width_px}x{height_px}), {len(deck) - boxed} full-bleed")


if __name__ == "__main__":
    main()
