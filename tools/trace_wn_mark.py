"""The WN mark's centreline as an svg path, for `RingOfPieces` to lay the catalogue along.

The mark (data/logo/wn-logo.png, 562x545) is one closed stroke 25 px wide: a ring open at the
foot whose two ends turn up into an arch standing inside it. Measured off the png — the stroke's
middle on rows and columns through it — the ring is a circle of radius 197.5 about (275, 278),
the arch a half circle of radius 52 about (274.5, 275) on legs at x 222.5 and 326.5, and a ring
end meets its leg at the foot on a short fillet. The path is written in the png's own pixels, so
it lies on the drawing, and resampled densely enough that the loader's even spacing follows it.

    python3 tools/trace_wn_mark.py            # writes data/logo/wn-mark.svg
"""
import math
from pathlib import Path

pts = []


def line(a, b, n=20):
    for i in range(n):
        t = i / n
        pts.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))


def arc(c, r, t0, t1, n=80):
    for i in range(n):
        t = math.radians(t0 + (t1 - t0) * i / n)
        pts.append((c[0] + r * math.cos(t), c[1] + r * math.sin(t)))


def quad(a, c, b, n=12):
    for i in range(n):
        t = i / n
        pts.append(((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0],
                    (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]))


RING, R = (275.0, 278.0), 197.5
ARCH, A = (274.5, 275.0), 52.0
LEFT, RIGHT, FOOT = 222.5, 326.5, 450.0
END = 68.9                       # degrees below the ring's middle at which each end leaves it

right_end = (RING[0] + R * math.cos(math.radians(END)), RING[1] + R * math.sin(math.radians(END)))
left_end = (RING[0] + R * math.cos(math.radians(180 - END)), RING[1] + R * math.sin(math.radians(180 - END)))

line((LEFT, FOOT), (LEFT, ARCH[1]))                          # up the left leg
arc(ARCH, A, 180, 360, 60)                                   # over the arch
line((RIGHT, ARCH[1]), (RIGHT, FOOT))                        # down the right leg
quad((RIGHT, FOOT), (RIGHT, FOOT + 14), right_end)           # into the ring's right end
arc(RING, R, END, -(180 + END), 240)                         # round the ring, over the top
quad(left_end, (LEFT, FOOT + 14), (LEFT, FOOT))              # and back into the left leg

d = "M " + " L ".join(f"{x:.2f} {y:.2f}" for x, y in pts) + " Z"
out = Path(__file__).resolve().parent.parent / "data/logo/wn-mark.svg"
out.write_text(f'''<svg xmlns="http://www.w3.org/2000/svg" width="562" height="545" viewBox="0 0 562 545">
<!-- The WN mark's centreline, traced off data/logo/wn-logo.png by tools/trace_wn_mark.py. -->
<path d="{d}" fill="none" stroke="#000000" stroke-width="25"/>
</svg>
''')
print(f"{len(pts)} points to {out}")
