"""
Pull the factory graphics out of the client's "onderdeel per fabriek" document.

The docx is a factory name a paragraph, each followed by one screenshot of that factory's product
page. Every screenshot is written whole to data/factories/<factory>.png, named by the paragraph
before it, and the ones named in TILES are also cut into their product photos, one png a tile,
without the caption under it:

    python3 tools/factory_graphics.py

data/ is not committed, so this is how the folder comes back.
"""
import re
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

DOCX = Path("data/other/Fabrieken overzicht/Fabrieken - Overzicht onderdeel per Fabriek.docx")
OUT = Path("data/factories")

# Factories whose screenshot is a row of product tiles, and what each tile shows, left to right.
TILES = {
    "seveton": ["wandelementen-volbeton", "sandwichpanelen", "verdiepingselementen-ttx"],
}


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def runs(mask):
    """Start and end of every run of True."""
    edges = np.flatnonzero(np.diff(np.r_[0, mask.astype(int), 0]))
    return list(zip(edges[::2], edges[1::2]))


def tiles(image):
    """The photos in a row of tiles on the page's pale ground, captions left off."""
    a = np.asarray(image.convert("RGB")).astype(int)
    ink = np.abs(a - a[5, 5]).sum(axis=2) > 30
    photos = []
    for x0, x1 in runs(ink.mean(axis=0) > 0.5):
        # The photo is the tallest run down the column; the caption is short lines under it.
        y0, y1 = max(runs(ink[:, x0:x1].mean(axis=1) > 0.5), key=lambda r: r[1] - r[0])
        photos.append(image.crop((x0, y0, x1, y1)))
    return photos


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(DOCX) as docx:
        document = docx.read("word/document.xml").decode()
        rels = docx.read("word/_rels/document.xml.rels").decode()
        targets = {m[0]: m[1] for m in re.findall(r'Id="(rId\d+)"[^>]*Target="([^"]+)"', rels)}

        name = None
        for paragraph in re.findall(r"<w:p[ >].*?</w:p>", document, re.S):
            text = "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", paragraph)).strip()
            if text:
                # "Tripan     wanden" carries its picture in the same paragraph: the name is the first word.
                name = slug(text.split()[0] if "  " in text else text)
            for rid in re.findall(r'r:embed="(rId\d+)"', paragraph):
                if name is None:
                    continue
                with docx.open("word/" + targets[rid]) as f:
                    image = Image.open(f).convert("RGB")
                image.save(OUT / f"{name}.png")
                print(f"{name}.png  {image.width}x{image.height}")
                if name in TILES:
                    cut = tiles(image)
                    labels = TILES[name]
                    if len(cut) != len(labels):
                        print(f"  expected {len(labels)} tiles, found {len(cut)}: not cut")
                        continue
                    (OUT / name).mkdir(exist_ok=True)
                    for label, photo in zip(labels, cut):
                        photo.save(OUT / name / f"{label}.png")
                        print(f"  {name}/{label}.png  {photo.width}x{photo.height}")


if __name__ == "__main__":
    main()
