#!/usr/bin/env python3
"""Render WN-Draaiboek.pdf to one PNG per slide, with the client's comment
set above the slides that have one.

    python3 tools/annotate_slides.py --pdf ~/Downloads/WN-Draaiboek.pdf

Every page is exported by default; --only-commented restricts it to the 15 the
client wrote on. Page numbers are the client's own ("Slide 70" = PDF page 70).
"""
import argparse, os, re, shutil, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

COMMENTS = {
    70: "'Hoe bouw je een wereld die vandaag stevig overeind blijft, zich aanpast aan morgen "
        "en haar voetafdruk steeds verder verkleint?'",
    74: "transport, montage en beheer.",
    76: "checklist, maar de systemische manier",
    77: "Prestatieladder, hier gaat @Erik Koremans achteraan",
    81: "Onze eigen kraan i.p.v. de huidige die hierop wordt toegepast",
    82: "Benadrukken familiebedrijf met mensen als grootste kapitaal. Oprechte aandacht voor "
        "mensen op de werf… ect",
    83: "Bestuur, beleid en besluitvorming.",
    84: "Kan dit erin verwerkt worden? Beetje context bij het waardekader. En is het waardekader "
        "of waardenkader? Het helpt bij het opbouwen van de bedrijfscultuur, het stroomlijnen van "
        "de visie en het behouden van consistentie in communicatie en beleid.",
    85: "Misschien een ander woord voor ruggengraat? Beton: de kracht om te blijven en de "
        "vrijheid om te veranderen, misschien?",
    86: "Zonder beton geen continuïteit in de bouw van logistieke hubs, geen productiehallen, "
        "geen voedselverwerking op schaal.",
    87: "In de totale bouwbehoefte kunnen we niet zonder beton. Dus als we niet zonder beton "
        "kunnen, zetten wij het zo duurzaam als mogelijk in.",
    88: "Benadert*",
    95: "Wordt een circulaire bouw geïntroduceerd.",
    96: "Maar nu al een bestaand systeem*",
    98: "Governance: daar moet besturing komen.",
}

GLOBAL_NOTE = "Overal 'Willy Naessens Group' in plaats van 'Groep'."

# 74 of the 98 pages are rasterised, so a text search cannot see the word on
# them. These were found by reading the document and are added by hand; the
# list is not guaranteed complete without OCR.
MANUAL_GROEP = {56, 57}

RED, INK, GREY, PAPER = "#FF0000", "#000000", "#8A8A8A", "#FFFFFF"

def font(size, bold=False):
    for path in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold
                 else "/System/Library/Fonts/Supplemental/Arial.ttf",
                 "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()

def wrap(draw, text, fnt, max_w):
    """Greedy wrap on real glyph widths rather than character counts."""
    lines, line = [], ""
    for word in text.split():
        probe = f"{line} {word}".strip()
        if draw.textlength(probe, font=fnt) <= max_w or not line:
            line = probe
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines

def page_count(pdf):
    out = subprocess.run(["pdfinfo", pdf], capture_output=True, text=True).stdout
    m = re.search(r"^Pages:\s+(\d+)", out, re.M)
    return int(m.group(1)) if m else 0

def pages_mentioning_groep(pdf, pages):
    """Which pages actually contain 'Groep' — so the global note is only
    stamped where it applies."""
    if not shutil.which("pdftotext"):
        return set()
    hits = set()
    for page in pages:
        out = subprocess.run(["pdftotext", "-f", str(page), "-l", str(page), pdf, "-"],
                             capture_output=True, text=True)
        if "Groep" in out.stdout:
            hits.add(page)
    return hits

def render(pdf, page, dpi, tmp):
    stem = os.path.join(tmp, f"page-{page:03d}")
    subprocess.run(["pdftoppm", "-f", str(page), "-l", str(page), "-r", str(dpi),
                    "-png", "-singlefile", pdf, stem], check=True)
    return stem + ".png"

def annotate(slide_path, page, comment, global_note, out_path):
    slide = Image.open(slide_path).convert("RGB")
    W = slide.width
    pad = max(24, W // 60)

    label_f = font(max(15, W // 90), bold=True)
    body_f  = font(max(21, W // 58))
    note_f  = font(max(15, W // 90))

    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    lines = wrap(probe, comment, body_f, W - pad * 2) if comment else []
    line_h = int(body_f.size * 1.42)

    head = pad + int(label_f.size * 1.7) + len(lines) * line_h + pad
    foot = (pad + int(note_f.size * 1.6)) if global_note else 0

    canvas = Image.new("RGB", (W, head + slide.height + foot), PAPER)
    d = ImageDraw.Draw(canvas)

    d.text((pad, pad), f"SLIDE {page}", font=label_f, fill=INK)
    y = pad + int(label_f.size * 1.7)
    for ln in lines:
        d.text((pad, y), ln, font=body_f, fill=RED)
        y += line_h

    rule = RED if comment else "#D8D8D8"
    d.line([(0, head - 1), (W, head - 1)], fill=rule, width=max(2, W // 700))
    canvas.paste(slide, (0, head))

    if global_note:
        d.text((pad, head + slide.height + pad // 2), global_note, font=note_f, fill=GREY)

    canvas.save(out_path)
    return canvas.size

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--out", default="export/wn-draaiboek-annotated")
    ap.add_argument("--dpi", type=int, default=150)
    ap.add_argument("--only-commented", action="store_true",
                    help="export just the 15 pages the client wrote on")
    args = ap.parse_args()

    if not os.path.exists(args.pdf):
        sys.exit(f"not found: {args.pdf}")
    if not shutil.which("pdftoppm"):
        sys.exit("pdftoppm missing — brew install poppler")

    os.makedirs(args.out, exist_ok=True)
    tmp = os.path.join(args.out, ".render")
    os.makedirs(tmp, exist_ok=True)

    total = page_count(args.pdf)
    pages = sorted(COMMENTS) if args.only_commented else list(range(1, total + 1))
    if not pages:
        sys.exit("could not read a page count — is pdfinfo installed?")

    groep = pages_mentioning_groep(args.pdf, pages) | (MANUAL_GROEP & set(pages))
    missing = [p for p in COMMENTS if p > total]
    if missing:
        print(f"warning: comments for pages not in this PDF: {missing}")

    for page in pages:
        src = render(args.pdf, page, args.dpi, tmp)
        dst = os.path.join(args.out, f"slide-{page:03d}.png")
        annotate(src, page, COMMENTS.get(page),
                 GLOBAL_NOTE if page in groep else None, dst)
        if page in COMMENTS:
            print(f"  slide-{page:03d}.png   <- comment")

    shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{len(pages)} slides -> {args.out}")
    print(f"{len([p for p in pages if p in COMMENTS])} with a comment, "
          f"{len(groep)} carrying the 'Groep' note")

if __name__ == "__main__":
    main()
