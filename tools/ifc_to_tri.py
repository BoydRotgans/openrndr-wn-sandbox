"""
Tessellate The Circle's IFC into two flat binaries the show can upload straight into vertex
buffers, plus a small JSON of anchors:

    data/circle/circle.tri     float32 x y z nx ny nz per vertex, three vertices a triangle
    data/circle/circle.edge    float32 x y z per vertex, two vertices a line — the creased edges
    data/circle/circle.json    bounds, counts, and a centroid per class to point a leader at

Structure only: the fixings (IfcMechanicalFastener, IfcDiscreteAccessory) are 25 000 of the
41 000 products and none of them is a line worth drawing at this size. Coordinates are kept in
the model's own metres, z up; the loader turns them y up.

    .venv/bin/python tools/ifc_to_tri.py "input/IB-009109 The Circle.ifc"
"""
import json, struct, sys, time
import numpy as np
import ifcopenshell, ifcopenshell.geom

src = sys.argv[1] if len(sys.argv) > 1 else "input/IB-009109 The Circle.ifc"
KEEP = ("IfcWall", "IfcWallStandardCase", "IfcBeam", "IfcColumn", "IfcSlab", "IfcPlate", "IfcMember",
        "IfcRoof", "IfcStair", "IfcStairFlight", "IfcFooting", "IfcRailing", "IfcCovering")
CREASE = 45.0
# Elements smaller than this across their box are fixings by another name — an IfcBeam here is
# as often a 10 cm anchor as a 24 m beam — and none of them is a line at wall size.
MIN = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0

t0 = time.time()
f = ifcopenshell.open(src)
settings = ifcopenshell.geom.settings()
settings.set(settings.USE_WORLD_COORDS, True)
settings.set(settings.WELD_VERTICES, False)
# Boxes, not cut boxes: a wall drawn at wall size does not need its anchor holes, and the
# booleans are most of the triangles. A coarse mesher for the round profiles for the same reason.
for key, value in (("disable-opening-subtractions", True), ("mesher-linear-deflection", 0.05), ("mesher-angular-deflection", 1.0)):
    try:
        settings.set(key, value)
    except Exception as ex:
        print("setting", key, "not accepted:", ex)

products = [p for cls in KEEP for p in f.by_type(cls)]
print(f"{len(products)} products of {len(KEEP)} classes, {time.time()-t0:.1f}s to open")

tris = []      # (n,3,3)
edges = []
by_class = {}
count = 0
failed = 0
it = ifcopenshell.geom.iterator(settings, f, num_threads=8, include=products)
if it.initialize():
    while True:
        shape = it.get()
        try:
            g = shape.geometry
            v = np.array(g.verts, dtype=np.float64).reshape(-1, 3)
            fidx = np.array(g.faces, dtype=np.int64).reshape(-1, 3)
            if len(fidx) and np.linalg.norm(v.max(axis=0) - v.min(axis=0)) >= MIN:
                t = v[fidx]                                   # (n,3,3)
                tris.append(t.astype(np.float32))
                cls = shape.type if hasattr(shape, "type") else "?"
                c = by_class.setdefault(cls, [np.zeros(3), 0])
                c[0] += t.reshape(-1, 3).mean(axis=0); c[1] += 1
                count += 1
                # creased edges: each edge shared by two faces whose normals differ by > CREASE
                n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
                n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
                e = {}
                for i, tri in enumerate(fidx):
                    for a, b in ((0, 1), (1, 2), (2, 0)):
                        key = (min(tri[a], tri[b]), max(tri[a], tri[b]))
                        e.setdefault(key, []).append(i)
                cosc = np.cos(np.radians(CREASE))
                for (a, b), faces in e.items():
                    if len(faces) == 1 or (len(faces) == 2 and np.dot(n[faces[0]], n[faces[1]]) < cosc):
                        edges.append((v[a], v[b]))
        except Exception as ex:
            failed += 1
        if not it.next():
            break

T = np.concatenate(tris) if tris else np.zeros((0, 3, 3), np.float32)
print(f"{count} shapes, {failed} failed, {len(T)} triangles, {len(edges)} edges, {time.time()-t0:.1f}s")

# normals per triangle, flat
n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
out = np.empty((len(T), 3, 6), np.float32)
out[:, :, :3] = T
out[:, :, 3:] = n[:, None, :]
out.tofile("data/circle/circle.tri")
E = np.array(edges, dtype=np.float32).reshape(-1, 3) if edges else np.zeros((0, 3), np.float32)
E.tofile("data/circle/circle.edge")
lo = T.reshape(-1, 3).min(axis=0).tolist() if len(T) else [0, 0, 0]
hi = T.reshape(-1, 3).max(axis=0).tolist() if len(T) else [0, 0, 0]
json.dump({
    "source": src, "triangles": int(len(T)), "edges": int(len(E) // 2), "min": lo, "max": hi,
    "anchors": {k: (v[0] / v[1]).tolist() for k, v in by_class.items()},
    "counts": {k: int(v[1]) for k, v in by_class.items()},
}, open("data/circle/circle.json", "w"), indent=2)
print("written data/circle/circle.{tri,edge,json}", f"{time.time()-t0:.1f}s")
