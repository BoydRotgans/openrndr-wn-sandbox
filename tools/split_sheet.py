"""Split a flat sheet of drawings into one svg per object.

It reads the sheet the way `loadObjectSheet` in Demo02.kt does, so the files come out in
the grouping and order every sketch already sees: captions dropped by colour and by
position, the grid read off the gaps that run clear across the sheet, and every path in a
cell one object. Files are numbered from 0 in reading order, the same index
`DECISION_OBJECTS` and the scenes' picks use.

The path data is carried over as written and only shifted so each file's frame starts at
0,0. The shift is done in decimal rather than float, so no coordinate picks up rounding
noise on the way.

    python3 tools/split_sheet.py data/svg/subset.svg             # -> data/svg/subset_svg/
    python3 tools/split_sheet.py data/svg/objects-iso.svg out/
"""
import math
import re
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path
from xml.sax.saxutils import quoteattr

SVG = "http://www.w3.org/2000/svg"

# How close above the type block a path may sit before it counts as type itself, in line
# heights. Demo02's CAPTION_REACH.
CAPTION_REACH = 2.0

TOKEN = re.compile(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "Z": 0}


class Drawn:
    def __init__(self, element, fill):
        self.element = element
        self.fill = fill
        self.commands = parse(element.get("d", ""))
        self.box = bounds(self.commands)

    @property
    def height(self):
        return self.box[3] - self.box[1]


def parse(d):
    """[(command, [Decimal])] for absolute path data — which is all a Figma export writes."""
    commands = []
    for token in TOKEN.findall(d):
        if token.isalpha():
            if token not in ARITY:
                raise ValueError(f"path command {token!r} is not read here, only absolute M L H V C Z")
            commands.append((token, []))
        else:
            commands[-1][1].append(Decimal(token))
    return commands


def bounds(commands):
    """(x0, y0, x1, y1), exact: a curve counts where it turns, not where its handles are."""
    xs, ys = [], []
    start = current = (0.0, 0.0)
    for command, numbers in commands:
        values = [float(n) for n in numbers]
        step = ARITY[command]
        if step == 0:
            current = start
            continue
        for i in range(0, len(values), step):
            a = values[i:i + step]
            if command in "ML":
                current = (a[0], a[1])
                if command == "M" and i == 0:
                    start = current
            elif command == "H":
                current = (a[0], current[1])
            elif command == "V":
                current = (current[0], a[0])
            else:
                points = [current, (a[0], a[1]), (a[2], a[3]), (a[4], a[5])]
                for t in turns(points, 0) + turns(points, 1):
                    x, y = cubic(points, t)
                    xs.append(x)
                    ys.append(y)
                current = points[3]
            xs.append(current[0])
            ys.append(current[1])
    return min(xs), min(ys), max(xs), max(ys)


def turns(points, axis):
    """Where a cubic's derivative on one axis is zero, inside the segment."""
    p0, p1, p2, p3 = (p[axis] for p in points)
    a = -p0 + 3 * p1 - 3 * p2 + p3
    b = 2 * (p0 - 2 * p1 + p2)
    c = p1 - p0
    if abs(a) < 1e-12:
        roots = [] if abs(b) < 1e-12 else [-c / b]
    else:
        disc = b * b - 4 * a * c
        roots = [] if disc < 0 else [(-b + s * math.sqrt(disc)) / (2 * a) for s in (1, -1)]
    return [t for t in roots if 0 < t < 1]


def cubic(points, t):
    u = 1 - t
    weights = (u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t)
    return tuple(sum(w * p[axis] for w, p in zip(weights, points)) for axis in (0, 1))


def shifted(commands, dx, dy):
    parts = []
    for command, numbers in commands:
        offsets = {"H": (dx,), "V": (dy,), "Z": ()}.get(command, (dx, dy))
        moved = [n - offsets[i % len(offsets)] for i, n in enumerate(numbers)]
        parts.append(command + " ".join(plain(n) for n in moved))
    return "".join(parts)


def plain(number):
    text = format(number, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text == "-0" else text


def median_height(items):
    return sorted(item.height for item in items)[len(items) // 2]


def gutters(spans):
    """The middle of every gap no span covers, between the first span and the last."""
    cuts, reach = [], None
    for start, end in sorted(spans):
        if reach is not None and start > reach:
            cuts.append((reach + start) / 2)
        reach = end if reach is None else max(reach, end)
    return cuts


def read_sheet(sheet):
    root = ET.parse(sheet).getroot()
    others = {element.tag.split("}")[-1] for element in root.iter()} - {"svg", "path"}
    if others:
        sys.exit(f"{sheet} holds {', '.join(sorted(others))}; only a flat list of <path> is read")

    root_fill = root.get("fill")
    drawn = []
    for element in root.iter(f"{{{SVG}}}path"):
        if element.get("transform"):
            sys.exit(f"{sheet} has a transformed path; only a flat list of <path> is read")
        drawn.append(Drawn(element, element.get("fill", root_fill)))
    if not drawn:
        sys.exit(f"{sheet} holds no paths")

    # Artwork and captions use a colour each, and the caption colour is the group whose
    # shapes are far shorter. A cell's first caption line is set in the artwork colour, so
    # a path sitting just above a known caption is a caption too.
    by_fill = {}
    for item in drawn:
        by_fill.setdefault(item.fill, []).append(item)
    if len(by_fill) < 2:
        artwork = drawn
    else:
        caption_fill = min(by_fill, key=lambda fill: median_height(by_fill[fill]))
        line_height = median_height(by_fill[caption_fill])
        caption_tops = sorted(item.box[1] for item in by_fill[caption_fill])
        artwork = []
        for item in drawn:
            if item.fill == caption_fill:
                continue
            below = next((top for top in caption_tops if top >= item.box[3]), None)
            if below is None or below - item.box[3] > line_height * CAPTION_REACH:
                artwork.append(item)

    columns = gutters([(item.box[0], item.box[2]) for item in artwork])
    rows = gutters([(item.box[1], item.box[3]) for item in artwork])

    # dicts keep insertion order, and an exported sheet lists its paths in reading order
    cells = {}
    for item in artwork:
        cx = (item.box[0] + item.box[2]) / 2
        cy = (item.box[1] + item.box[3]) / 2
        cell = (sum(r < cy for r in rows), sum(c < cx for c in columns))
        cells.setdefault(cell, []).append(item)
    return root_fill, list(cells.values())


def write_object(path, items, root_fill):
    x0 = math.floor(min(item.box[0] for item in items))
    y0 = math.floor(min(item.box[1] for item in items))
    width = math.ceil(max(item.box[2] for item in items)) - x0
    height = math.ceil(max(item.box[3] for item in items)) - y0

    fill = f" fill={quoteattr(root_fill)}" if root_fill else ""
    lines = [f'<svg width="{width}" height="{height}" viewBox="0 0 {width} {height}"{fill} xmlns="{SVG}">']
    for item in items:
        attributes = dict(item.element.attrib)
        attributes["d"] = shifted(item.commands, Decimal(x0), Decimal(y0))
        lines.append("<path " + " ".join(f"{key}={quoteattr(value)}" for key, value in attributes.items()) + "/>")
    lines.append("</svg>")
    path.write_text("\n".join(lines) + "\n")
    return width, height


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    sheet = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) == 3 else sheet.parent / f"{sheet.stem}_svg"

    root_fill, objects = read_sheet(sheet)
    out.mkdir(parents=True, exist_ok=True)
    digits = max(2, len(str(len(objects) - 1)))
    for index, items in enumerate(objects):
        name = f"{index:0{digits}d}.svg"
        width, height = write_object(out / name, items, root_fill)
        print(f"{name}  {width}x{height}  {len(items)} path{'s' if len(items) != 1 else ''}")
    print(f"{len(objects)} objects from {sheet} -> {out}/")


if __name__ == "__main__":
    main()
