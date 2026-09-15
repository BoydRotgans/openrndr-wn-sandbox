"""Write a square, simplified icon for each piece of the subset.

`data/svg/subset_svg` holds the fifteen pieces as drawn: one path each, at their own proportion,
which runs from 0.85 to 3.3 wide-to-high. That is right for a piece and awkward in a grid — a
cell has to either letterbox the piece or take its shape, and the fine features (a 6px tooth, a
2px lifting hole) disappear at cell size.

So each piece is redrawn here on a 24 by 24 grid, filling the square edge to edge:

- **Stretched to the square**, keeping where each feature sits as a share of the piece — a
  doorway at 43-63% across stays at 10-15 of 24.
- **Small features are enlarged** to at least three units, so a notch that was 5% of the piece is
  still a notch at icon size. Two was tried first and read on the contact sheet, but a wall of
  these puts a cell at about 22px, where a unit is under a pixel and two of them vanish. A feature
  smaller than that and not the point of the piece — the lifting hole in 07 — is dropped.
- **Repeats are evened out**, so teeth and notches keep one rhythm rather than each rounding
  on its own.

The shapes are stated rather than derived, because the judgement of what makes a piece
recognisable is the whole job and a snapping rule gets the rhythms wrong. `data/` is not
committed, so this script is how the folder comes back. Numbering matches `subset_svg`.

    python3 tools/iconify_pieces.py                      # -> data/svg/subset_icons/
    python3 tools/iconify_pieces.py out/ --preview p.png # plus a sheet: originals over icons
"""
import re
import sys
from pathlib import Path

GRID = 24
FILL = "#0D00FF"   # the subset's own blue

# Absolute M/H/V/L/Z only, y down, on the 24 grid. A second subpath is a hole (evenodd).
ICONS = {
    # A slab with its top-left corner cut away on a slope.
    "00": "M0 12L11 0H24V24H0Z",
    # A full-width head over a body set in either side.
    "01": "M0 0H24V12H21V24H3V12H0Z",
    # A panel with a rebate out of the bottom-left corner.
    "02": "M0 0H24V24H6V20H0Z",
    # A slab with four notches under it, half-width teeth at the ends.
    "03": "M0 0H24V24H22.5V21H19.5V24H16.5V21H13.5V24H10.5V21H7.5V24H4.5V21H1.5V24H0Z",
    # A slab with four notches under it, full-width teeth at the ends.
    "04": "M0 0H24V24H21V21H18.75V24H15.75V21H13.5V24H10.5V21H8.25V24H5.25V21H3V24H0Z",
    # A T: a flange over a stem.
    "05": "M0 0H24V9H20V24H4V9H0Z",
    # A slab with a shallow notch in its top.
    "06": "M0 0H9V3H15V0H24V24H0Z",
    # A panel on three feet: two notches under it. The lifting hole is dropped.
    "07": "M0 0H24V24H20V21H15V24H9V21H4V24H0Z",
    # An L: a head across the top and a leg down the left.
    "08": "M0 0H24V9H16V24H0Z",
    # A panel with a narrow slot through its middle.
    "09": "M0 0H24V24H0Z M10 6H14V21H10Z",
    # A panel with a slot through it toward the right.
    "10": "M0 0H24V24H0Z M17 4H22V22H17Z",
    # A head over a body set in further on the left than the right.
    "11": "M0 0H24V12H22V24H5V12H0Z",
    # A panel with a doorway up from the bottom.
    "12": "M0 0H24V24H15V7H10V24H0Z",
    # A channel: a U open at the top.
    "13": "M0 0H3V8H21V0H24V24H0Z",
    # A panel with a wide slot through its middle.
    "14": "M0 0H24V24H0Z M9 6H15V21H9Z",
}

SVG = """<svg width="{g}" height="{g}" viewBox="0 0 {g} {g}" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="{d}" fill="{fill}"/>
</svg>
"""


def rings(d):
    """The subpaths of an absolute M/H/V/L/Z path, as lists of points."""
    out, ring, x, y = [], [], 0.0, 0.0
    for cmd, args in re.findall(r"([MHVLZ])([^MHVLZ]*)", d):
        nums = [float(n) for n in re.findall(r"[-+]?(?:\d*\.\d+|\d+\.?)", args)]
        if cmd == "M":
            if ring:
                out.append(ring)
            x, y = nums[0], nums[1]
            ring = [(x, y)]
        elif cmd == "L":
            for i in range(0, len(nums), 2):
                x, y = nums[i], nums[i + 1]
                ring.append((x, y))
        elif cmd == "H":
            for n in nums:
                x = n
                ring.append((x, y))
        elif cmd == "V":
            for n in nums:
                y = n
                ring.append((x, y))
        elif cmd == "Z":
            out.append(ring)
            ring = []
    if ring:
        out.append(ring)
    return out


def preview(source, icons, path):
    """Originals fitted in a row over their icons, for checking by eye."""
    from PIL import Image, ImageDraw

    cell, pad = 120, 16
    sheet = Image.new("RGB", (len(icons) * (cell + pad) + pad, 2 * (cell + pad) + pad), "white")
    draw = ImageDraw.Draw(sheet)
    for i, name in enumerate(sorted(icons)):
        x0 = pad + i * (cell + pad)
        text = (source / f"{name}.svg").read_text() if (source / f"{name}.svg").exists() else ""
        found = re.search(r' d="([^"]+)"', text)
        for row, (d, box) in enumerate([
            (found.group(1) if found else "", None),
            (icons[name], (GRID, GRID)),
        ]):
            if not d:
                continue
            shapes = rings(d)
            if box is None:
                xs = [p[0] for r in shapes for p in r]
                ys = [p[1] for r in shapes for p in r]
                left, top, w, h = min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)
            else:
                left, top, (w, h) = 0.0, 0.0, box
            s = cell / max(w, h)
            ox = x0 + (cell - w * s) / 2
            oy = pad + row * (cell + pad) + (cell - h * s) / 2
            for k, r in enumerate(shapes):
                pts = [(ox + (px - left) * s, oy + (py - top) * s) for px, py in r]
                draw.polygon(pts, fill=(13, 0, 255) if k == 0 else (255, 255, 255))
    sheet.save(path)


def main():
    rest = sys.argv[1:]
    target = None
    if "--preview" in rest:
        at = rest.index("--preview")
        target = rest[at + 1]
        del rest[at:at + 2]
    out = Path(rest[0]) if rest else Path("data/svg/subset_icons")
    out.mkdir(parents=True, exist_ok=True)
    for name, d in ICONS.items():
        (out / f"{name}.svg").write_text(SVG.format(g=GRID, d=d, fill=FILL))
    print(f"wrote {len(ICONS)} icons to {out}")

    if target:
        preview(Path("data/svg/subset_svg"), ICONS, target)
        print(f"preview {target}")


if __name__ == "__main__":
    main()
