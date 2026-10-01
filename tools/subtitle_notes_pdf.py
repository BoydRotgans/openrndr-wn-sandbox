#!/usr/bin/env python3
"""The practice sheet again, but for the show as it now stands: every state of the wall as
a picture, and under it what is said while it stands — once for each subtitle track.

    .venv/bin/python tools/subtitle_notes_pdf.py

writes export/wn-sprekersnotities-standaard.pdf (show-subtitles.json, the voice-over script
as delivered) and export/wn-sprekersnotities-uitgebreid.pdf (show-subtitles-extended.json, the
talk as it is given). tools/speaker_notes_pdf.py is the older sheet, off the client's pptx.

**A picture is a state, and a line belongs to the state it is said over.** Both files key
their lines by the nameplate's letter — A the state a slide arrives on, B its first click —
so a line lands on exactly one picture, and the pictures are taken off the rehearsal film by
the same rule the review site takes its thumbnails by (three quarters into the state, at most
six seconds in), only from the full 3840 film rather than the site's 640 wide copy.

**The running order is the show's, not the film's.** The organizer, when it is up, says which
slides play and how many states each has now (`/api/show`); a state the film has and the show
no longer does is left out, and a slide the film does not have is drawn from its organizer
preview (`build/previews`). With no organizer the film's own order stands.

    --film     the film the pictures come off (a clean one: no subtitles in the picture)
    --cut      the state log of that film: the .states file beside it, or a review site manifest
    --show     the organizer's /api/show, a url or a saved file
    --track    default, extended or both
"""
import argparse
import html
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

TRACKS = {
    "default": {
        "file": "show-subtitles.json",
        "out": "export/wn-sprekersnotities-standaard.pdf",
        "title": "Standaard voice-over",
    },
    "extended": {
        "file": "show-subtitles-extended.json",
        "out": "export/wn-sprekersnotities-uitgebreid.pdf",
        "title": "Uitgebreide voice-over",
    },
}

# The order file names its moments in English; the evening is run in Dutch.
MOMENTS = {
    "Arrival": "Aankomst", "Opening": "Opening", "Aperitif": "Aperitief",
    "Opening course": "Voorgerecht", "First course": "Eerste gang",
    "Second course": "Tweede gang", "Third course": "Derde gang", "Questions": "Vragen", "Dessert": "Dessert",
    "Exit": "Uitloop",
}

# A wall nobody speaks over has no heading in either track, so it is named here.
WALLS = {
    "opening-scene": "Openingswand · de catalogus tekent zichzelf",
    "director-is-speaking": "Welkom door de directeur",
    "studiobuik-voorgerecht": "StudioBuik · het voorgerecht",
    "studiobuik-eerste-gang": "StudioBuik · de eerste gang",
    "studiobuik-tweede-gang": "StudioBuik · de tweede gang",
    "studiobuik-derde-gang": "StudioBuik · de derde gang",
    "studiobuik-dessert": "StudioBuik · het dessert",
    "plain": "Wand · staven van elementen",
    "sketch-flow-v2-out-of-a-concrete-block": "Wand · uit een blok beton",
    "shadow-mosaic": "Wand · schaduwmozaïek",
    "sketch-climb": "Wand · de klim",
    "sketch-kit-two-screens-quiet": "Wand · de bouwdoos op twee schermen",
    "sketch-kit-two-screens-wn-landscape-jump-cuts": "Wand · W en N, uit elkaar en rond",
}
TRANSITION_RE = re.compile(r"^course-transition-to-chapter-(\d+)$")

# The sheet is a script: each slide is named in a few plain words, as the speaker would call it,
# rather than by the worksheet headings the tracks carry ("H1 · … · het citaat"). A slide not
# named here takes the part of its heading before the first " · ".
TITLES = {
    "opening-scene": "Openingswand",
    "director-is-speaking": "Welkom door de directeur",
    "who-is-speaking": "Erik Koremans",
    "het-programma": "Het programma",
    "hoe-bouw-je-een-wereld": "Opening van het hoofdstuk",
    "esg-is-geen-checklist": "Opening van het hoofdstuk",
    "we-gieten-kennis": "Opening van het hoofdstuk",
    "we-bouwen-vandaag": "Opening van het hoofdstuk",
    "the-catalogue-city": "Kaart Den Bosch",
    "everything-a-build-answers-to": "Locatie, materiaal, productie, transport",
    "the-globe": "De wereldbol",
    "de-cijfers": "Kerncijfers",
    "de-fabrieken": "De fabrieken",
    "esg-beoordelingskader": "ESG",
    "co2-prestatieladder": "De CO₂-prestatieladder",
    "co2-behaald": "De cijfers achter de ladder",
    "geen-compensatie": "Niet compenseren, maar reduceren",
    "domino-effect": "Het domino-effect",
    "esg-social-governance": "Social en governance",
    "co2-impact-van-beton": "De CO₂-impact van beton",
    "de-fasen-a-b-en-c": "A, B en C",
    "levenscyclus-van-betonproducten": "De levenscyclus",
    "verborgen-verhaal": "Het verborgen verhaal",
    "recyclage": "Recyclage",
    "the-circle-in-elementen": "The Circle in elementen",
    "100-elementen": "100 elementen",
    "gebouw-uit-de-webtool": "Het gebouw uit de webtool",
    "case-studies": "Case studies",
    "ending": "Afsluiting",
}
KERNBOODSCHAP_RE = re.compile(r"^kernboodschap-\d+$")
HIGHLIGHT_RE = re.compile(r"^projecthighlight-\d+$")

# Headings that describe a state the slide no longer has. 100 elementen lost its second
# click on 29 September (the sheet to scale), and the extended heading still names it.
HEADINGS = {
    "100-elementen": "100 elementen · de catalogus op een raster",
}

LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


# ---------------------------------------------------------------- the show


def read_cut(path, film):
    """The film's states with their start and end in seconds: off the `.states` log the recorder
    writes beside the film, or off a review site manifest, which already has them."""
    if not path.endswith(".states"):
        with open(path) as f:
            return json.load(f)
    with open(path) as f:
        rows = [l.rstrip("\n") for l in f if l.strip()]
    fps = float(rows[0].split()[3])
    duration = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                     "-of", "csv=p=0", film], capture_output=True, text=True, check=True).stdout)
    states = []
    for row in rows[1:]:
        cols = row.split("\t")
        states.append({"key": f"{cols[1]}-{cols[3]}", "slide": cols[1], "start": int(cols[0]) / fps})
    for i, st in enumerate(states):
        st["end"] = states[i + 1]["start"] if i + 1 < len(states) else duration
    return {"states": states}


def load_show(source, cut):
    """[(slide id, states)] in the order the show plays them. The organizer knows the
    states the drawers have now; without it the film's own order is the best there is."""
    try:
        if re.match(r"^https?://", source):
            with urllib.request.urlopen(source, timeout=5) as r:
                show = json.load(r)
        else:
            with open(source) as f:
                show = json.load(f)
        steps = {c["id"]: c["steps"] for c in show["catalogue"]}
        print(f"order: the organizer's, {len(show['running'])} slides")
        return [(i, steps[i]) for i in show["running"]]
    except Exception as e:
        print(f"order: no organizer at {source} ({e.__class__.__name__}); the film's own")
        order = []
        for s in cut["states"]:
            if not order or order[-1][0] != s["slide"]:
                order.append([s["slide"], 0])
            order[-1][1] += 1
        return [tuple(o) for o in order]


def sections(path):
    """Which chapter or moment each slide stands in, off the order file itself — the
    catalogue says where a slide was declared, which is not where it plays."""
    with open(path) as f:
        order = json.load(f)["order"]
    where, heads = {}, []
    for entry in order:
        if isinstance(entry, dict) and ("chapter" in entry or "moment" in entry):
            kind = "chapter" if "chapter" in entry else "moment"
            name = entry[kind]
            heads.append((kind, name))
            for s in entry.get("slides", []):
                where[s if isinstance(s, str) else s.get("id")] = (kind, name)
    return where, heads


# ---------------------------------------------------------------- the draaiboek
#
# The evening's clock is the draaiboek's Show tab, kept by the production in an .xlsx: a row a
# part of the evening with its time (Tijd), length (Duur) and name (Onderdeel). The sheet reads
# it at every build, so a time moved there is moved here, and lays it on the show's sections:
# a chapter by its number ("Hoofdstuk 2"), a course by its place among the courses ("Gang 3"
# with the "Uithalen" after it), and the other moments by the rows named below.

MOMENT_ROWS = {
    "Arrival": [r"^Ontvangst", r"^Loop door"],
    "Opening": [r"^Opening"],
    "Questions": [r"Cases"],
    "Exit": [r"^Afsluitwoord", r"^Uitloop"],
}
TIME_RE = re.compile(r"(\d{1,2})[:.](\d{2})")
XLSX = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
COUNTDOWN = "0:33"   # the course transition's sample, course-transistion-T2: 33.13 s


def xlsx_rows(path, tab):
    """One sheet of an .xlsx as a list of {column letter: text}, read off the file's own XML —
    a workbook is a zip of it, and one tab is not worth a dependency. A number in column A is a
    time of day (Excel keeps those as a share of the day) and is given as HH:MM."""
    z = zipfile.ZipFile(path)
    rel = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
    targets = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
    sheets = [(s.get("name"), targets[s.get(rel)]) for s in ET.fromstring(z.read("xl/workbook.xml")).iter(XLSX + "sheet")]
    pick = next((t for n, t in sheets if n == tab), None) if tab else \
        next((t for n, t in sheets if n.lower().startswith("show")), None)
    if not pick:
        raise KeyError(f"no tab {tab or 'Show…'} among {[n for n, _ in sheets]}")
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        shared = ["".join(t.text or "" for t in si.iter(XLSX + "t"))
                  for si in ET.fromstring(z.read("xl/sharedStrings.xml")).iter(XLSX + "si")]
    rows = []
    for row in ET.fromstring(z.read("xl/" + pick.split("xl/")[-1].lstrip("/"))).iter(XLSX + "row"):
        cells = {}
        for c in row.iter(XLSX + "c"):
            col = re.match(r"[A-Z]+", c.get("r")).group(0)
            v = c.find(XLSX + "v")
            if c.get("t") == "s" and v is not None:
                text = shared[int(v.text)]
            elif c.get("t") == "inlineStr":
                text = "".join(t.text or "" for t in c.iter(XLSX + "t"))
            elif v is not None:
                text = v.text
                if col == "A":
                    try:
                        m = round(float(text) % 1 * 1440)
                        text = f"{m // 60:02d}:{m % 60:02d}"
                    except ValueError:
                        pass
            else:
                continue
            cells[col] = text.strip()
        rows.append(cells)
    return rows


def hhmm(m):
    return f"{m // 60:02d}:{m % 60:02d}" if m is not None else ""


def draaiboek(path, tab):
    """The Show tab's parts of the evening, each {title, start, end, minutes}, in minutes of the
    day, and the day itself off its title row ("SHOWTIME – woensdag 28 oktober | …")."""
    rows = xlsx_rows(path, tab)
    day = ""
    if rows and rows[0].get("A"):
        m = re.search(r"[–-]\s*([^|]+?)\s*(\||$)", rows[0]["A"])
        day = m.group(1) if m else ""
    parts = []
    for r in rows[2:]:
        title = r.get("C", "")
        if not title:
            continue
        times = [int(h) * 60 + int(mm) for h, mm in TIME_RE.findall(r.get("A", ""))]
        dur = re.search(r"(\d+)\s*min", r.get("B", ""))
        dur = int(dur.group(1)) if dur else None
        start = times[0] if times else None
        end = times[1] if len(times) > 1 else (start + dur if start is not None and dur else None)
        parts.append({"title": re.sub(r"\s+", " ", title), "start": start, "end": end, "minutes": dur})
    return day, parts


def section_times(parts, heads, where):
    """{(kind, name): (start, end)} for the show's chapters and moments, and for each course
    moment its draaiboek course and the clearing after it."""
    def span(rows):
        st = [r["start"] for r in rows if r["start"] is not None]
        en = [r["end"] for r in rows if r["end"] is not None]
        return (min(st) if st else None, max(en) if en else None)

    times, courses = {}, {}
    chapters = [n for k, n in heads if k == "chapter"]
    for i, name in enumerate(chapters, 1):
        rows = [r for r in parts if re.match(rf"^Hoofdstuk\s*{i}\b", r["title"])]
        if rows:
            times[("chapter", name)] = span(rows)
    # a course is a moment that opens on StudioBuik's wall, and they are the draaiboek's Gang 1, 2, …
    course_moments = [n for k, n in heads if k == "moment"
                      and any(s.startswith("studiobuik-") and w == ("moment", n) for s, w in where.items())]
    for i, name in enumerate(course_moments, 1):
        at = next((j for j, r in enumerate(parts) if re.match(rf"^Gang\s*{i}\b", r["title"])), None)
        if at is None:
            continue
        nxt = parts[at + 1] if at + 1 < len(parts) else None
        clearing = nxt if nxt and nxt["title"].lower().startswith("uithalen") else None
        courses[name] = (parts[at], clearing)
        times[("moment", name)] = span([parts[at]] + ([clearing] if clearing else []))
    for name, patterns in MOMENT_ROWS.items():
        rows = [r for r in parts if any(re.search(p, r["title"]) for p in patterns)]
        if rows and ("moment", name) in heads:
            times[("moment", name)] = span(rows)
    return times, courses


def course_name(title):
    """"Gang 5 – Verplaatsen: dessert-spiegellandschap + netwerken" as "Gang 5 – Verplaatsen"."""
    return title.split(":")[0].strip()


def cues_for(order, where, heads, times, courses):
    """The few stage directions the speaker needs between the lines, a line each: {slide:
    {"before": [...], "after": [...]}}. The draaiboek gives their times."""
    cues = {}
    add = lambda slide, when, text: cues.setdefault(slide, {"before": [], "after": []})[when].append(text)
    chapters = [n for k, n in heads if k == "chapter"]
    ids = [s for s, _ in order]
    for i, slide in enumerate(ids):
        if slide == "director-is-speaking":
            add(slide, "before", "Zaallicht dimt. De directeur heet welkom, live.")
        elif slide == "het-programma":
            add(slide, "after", "Else de Bruin stelt StudioBuik voor, live.")
        elif slide.startswith("studiobuik-"):
            add(slide, "before", "Else de Bruin licht de gang toe, live en nog zonder muziek.")
            kind, name = where.get(slide, (None, None))
            course = courses.get(name)
            wall = ids[i + 1] if i + 1 < len(ids) and where.get(ids[i + 1]) == (kind, name) else None
            if course and wall:
                gang, clearing = course
                text = f"Klik: muziek aan. {hhmm(gang['start'])} {course_name(gang['title'])}"
                if gang["minutes"]:
                    text += f" ({gang['minutes']} min)"
                if clearing:
                    text += f", {hhmm(clearing['start'])} uithalen"
                add(wall, "before", text + ".")
        elif TRANSITION_RE.match(slide):
            n = int(TRANSITION_RE.match(slide).group(1))
            start = times.get(("chapter", chapters[n - 1]), (None, None))[0] if n <= len(chapters) else None
            # the countdown ends on the chapter's start, so the click comes that much before it
            when = f" een halve minuut vóór {hhmm(start)}" if start is not None else ""
            add(slide, "before", f"Klik{when}: de wand dooft uit en telt {COUNTDOWN} af, linksonder; "
                                 f"bij 0:00 klik naar hoofdstuk {n}.")
        elif slide == "case-studies":
            add(slide, "before", "Interactief: vragen uit de zaal.")
        elif slide == "ending":
            add(slide, "before", "Erik sluit af en nodigt uit terug te gaan naar waar we begonnen.")
    return cues


# ---------------------------------------------------------------- the lines


def track_lines(track, show_steps, warn):
    """{slide: {letter: line}} for one track, as it now falls on the show's states."""
    with open(os.path.join(ROOT, TRACKS[track]["file"])) as f:
        data = json.load(f)
    lines = {k: dict(v) for k, v in data["subtitles"].items()}
    lines = relettered(lines, track, warn)
    for slide, by_letter in lines.items():
        n = show_steps.get(slide)
        if n is None:
            continue
        for letter in [l for l in by_letter if LETTERS.index(l) >= n]:
            warn(f"{track}: {slide}-{letter} has a line and the slide has {n} states; left out")
            del by_letter[letter]
    return data, lines


def relettered(lines, track, warn):
    """The tree lost its opening state on 29 September and Locatie became its arrival.
    CLAUDE.md records both tracks as moved a letter down with it — the old A folded into
    Locatie's line — and the rendered voice says exactly that, but the subtitle files still
    carry the six-state letters. The extended track's line on F is the tell: while it is
    there, both files are read the way the slide now plays them."""
    tree = "everything-a-build-answers-to"
    with open(os.path.join(ROOT, TRACKS["extended"]["file"])) as f:
        stale = "F" in json.load(f)["subtitles"].get(tree, {})
    if not stale or tree not in lines:
        return lines
    old = lines[tree]
    new = {}
    first = " ".join(t for t in (old.get("A"), old.get("B")) if t)
    if first:
        new["A"] = first
    for src, dst in zip("CDEF", "BCDE"):
        if old.get(src):
            new[dst] = old[src]
    lines[tree] = new
    warn(f"{track}: {tree} read one letter down (the files still carry its six-state letters)")
    return lines


# ---------------------------------------------------------------- the pictures


def frame_at(state):
    """The release's own rule: once the click has played and the state has settled."""
    length = state["end"] - state["start"]
    at = state["start"] + min(0.75 * length, 6.0)
    return max(state["start"], min(at, state["end"] - 0.05))


def pictures(order, cut, film, width, quality, cache):
    """A jpg per state of the show, off the film where it has the state and off the
    organizer's preview where it does not."""
    os.makedirs(cache, exist_ok=True)
    by_key = {s["key"]: s for s in cut["states"]}
    jobs, out = [], {}
    for slide, n in order:
        for i in range(n):
            key = f"{slide}-{LETTERS[i]}"
            path = os.path.join(cache, f"{key}-{width}.jpg")
            out[key] = path
            if os.path.isfile(path):
                continue
            if key in by_key:
                jobs.append(["ffmpeg", "-v", "error", "-y", "-ss", f"{frame_at(by_key[key]):.3f}",
                             "-i", film, "-frames:v", "1", "-vf", f"scale={width}:-2",
                             "-q:v", str(quality), path])
            else:
                preview = os.path.join(ROOT, "build/previews", f"{slide}-1.png")
                if os.path.isfile(preview):
                    print(f"picture: {key} is not in the film; its organizer preview stands in")
                    jobs.append(["ffmpeg", "-v", "error", "-y", "-i", preview,
                                 "-vf", f"scale={width}:-2", "-q:v", str(quality), path])
                else:
                    print(f"picture: {key} is in neither the film nor the previews")
                    out[key] = None
    if jobs:
        print(f"pictures: {len(jobs)} frames off {os.path.relpath(film, ROOT)} …")
        with ThreadPoolExecutor(6) as pool:
            list(pool.map(lambda c: subprocess.run(c, check=True), jobs))
    return out


# ---------------------------------------------------------------- the rehearsal link


def env_value(key, path):
    """A key off a .env file, a real environment variable winning, as `Env` reads them."""
    if os.environ.get(key):
        return os.environ[key]
    if not os.path.isfile(path):
        return ""
    with open(path) as f:
        for raw in f:
            line = raw.strip()
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip().strip("'\"")
    return ""


def rehearsal_block():
    """The sheet ends on a code to the rehearsal page. The address is the project's
    SPEAKER_NOTES_REHEARSAL; the key is the review site's own PRACTICE_KEY, read where it
    already lives rather than copied. Drawn by qrencode as svg, so it prints sharp."""
    base = env_value("SPEAKER_NOTES_REHEARSAL", os.path.join(ROOT, ".env"))
    key = env_value("PRACTICE_KEY", os.path.join(ROOT, "review/.env"))
    if not base or not key:
        print("rehearsal: no SPEAKER_NOTES_REHEARSAL or PRACTICE_KEY; the sheet ends without a code")
        return ""
    url = f"{base}?k={key}"
    try:
        svg = subprocess.run(["qrencode", "-t", "SVG", "-l", "M", "-m", "0", "-o", "-", url],
                             check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        print(f"rehearsal: qrencode failed ({e.__class__.__name__}); the sheet ends without a code")
        return ""
    svg = svg[svg.index("<svg"):]
    shown = re.sub(r"^https?://", "", base)
    return (
        "<article class='rehearsal'>"
        f"<a class='code' href='{html.escape(url)}'>{svg}</a>"
        "<div><p class='lead'>Oefen de presentatie online.</p>"
        f"<p class='link'><a href='{html.escape(url)}'>{html.escape(shown)}</a></p>"
        "<p class='private'>De code bevat de toegangssleutel: alleen delen met wie de presentatie mag zien.</p></div>"
        "</article>"
    )


# ---------------------------------------------------------------- typesetting


def heading_of(slide, data_d, data_e, fallback):
    if slide in HEADINGS:
        return HEADINGS[slide]
    for data in (data_e, data_d):
        h = data["voiceover"]["slides"].get(slide, {}).get("heading")
        if h:
            return h
    if slide in WALLS:
        return WALLS[slide]
    t = TRANSITION_RE.match(slide)
    if t:
        return f"Overgang naar hoofdstuk {t.group(1)}"
    return fallback


def title_of(slide, data_d, data_e):
    """The slide's name on the sheet: a few plain words, no worksheet notes."""
    if slide in TITLES:
        return TITLES[slide]
    if KERNBOODSCHAP_RE.match(slide):
        return "Kernboodschap"
    head = heading_of(slide, data_d, data_e, slide)
    if HIGHLIGHT_RE.match(slide) and " · " in head:
        return "Project: " + head.split(" · ", 1)[1]
    first, _, rest = head.partition(" · ")
    if first in ("StudioBuik", "Wand") and rest:
        return f"{first}: {rest}"
    return first


def build_html(track, order, where, heads, pics, lines, data, data_d, data_e, film_name, rehearsal="",
               times=None, cues=None, day=""):
    """The sheet as a plain script: the evening's moments and chapters, each slide named in a
    few words, and every state of the wall as its picture with what is said over it. No keys,
    numbers or marks: what the speaker needs on paper is the line and the picture it is said
    over, the draaiboek's time at each section, and a few cues between the lines, kept small."""
    times, cues = times or {}, cues or {}
    spec = TRACKS[track]
    months = ["januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus",
              "september", "oktober", "november", "december"]
    today = date.today()
    parts = [HEAD.replace("{title}", html.escape(spec["title"]))]
    parts.append(
        "<header class='sheet'>"
        "<div class='kicker'>Willy Naessens · sprekersnotities</div>"
        f"<h1>{html.escape(spec['title'])}</h1>"
        f"<p class='date'>{'Show ' + html.escape(day) + ' · tijden uit het draaiboek · ' if day else ''}"
        f"versie {today.day} {months[today.month - 1]} {today.year}</p>"
        "</header>"
    )

    # A section's head goes into the first picture under it, so a page can never end on a
    # head with nothing below it. (break-after: avoid is what this would be in CSS, and
    # Chrome does not keep it.)
    pending, opens_chapter = [], False
    shown = set()
    chapters = [n for k, n in heads if k == "chapter"]

    for slide, n in order:
        kind, name = where.get(slide, (None, None))
        if name and (kind, name) not in shown:
            shown.add((kind, name))
            start, end = times.get((kind, name), (None, None))
            when = (f"<span class='time'>{hhmm(start)}{' – ' + hhmm(end) if end else ''}</span>"
                    if start is not None else "")
            if kind == "chapter":
                title = re.sub(r"(?<=[a-z])-(?=[a-z])", "", name)
                pending.append(f"<section class='chapter'><div class='kicker'>Hoofdstuk "
                               f"{chapters.index(name) + 1}</div><h2>{html.escape(title)}{when}</h2></section>")
                opens_chapter = True
            else:
                pending.append(f"<section class='moment'><h2>{html.escape(MOMENTS.get(name, name))}{when}</h2></section>")

        slide_head = f"<h3>{html.escape(title_of(slide, data_d, data_e))}</h3>"
        cue = lambda when: "".join(f"<p class='cue'>{html.escape(c)}</p>" for c in cues.get(slide, {}).get(when, []))
        for i in range(n):
            line = lines.get(slide, {}).get(LETTERS[i], "")
            pic = pics.get(f"{slide}-{LETTERS[i]}")
            img = f"<img src='file://{pic}' alt=''>" if pic else "<div class='nopic'></div>"
            cls = "state" if line else "state quiet"
            if opens_chapter:
                cls += " opens-chapter"
                opens_chapter = False
            # the picture beside its line rather than over it: a row is as tall as the
            # longer of the two, which on paper is most of the saving
            parts.append(f"<article class='{cls}'>{''.join(pending)}"
                         f"{slide_head + cue('before') if i == 0 else ''}"
                         f"<div class='row'><div class='pic'>{img}</div>"
                         f"<div class='line'>{html.escape(line)}</div></div>"
                         f"{cue('after') if i == n - 1 else ''}</article>")
            pending = []

    if pending:
        parts.append(f"<article class='state'>{''.join(pending)}</article>")
    parts.append(rehearsal)
    parts.append("</body></html>")
    return "".join(parts)


HEAD = """<!doctype html><html lang="nl"><meta charset="utf-8"><title>{title}</title><style>
@page { size: A4 portrait; margin: 13mm 13mm 16mm;
  @bottom-right { content: counter(page) " / " counter(pages); font: 7.5pt "Helvetica Neue", Arial, sans-serif; color: #999; } }
* { box-sizing: border-box; }
body { margin: 0; font: 9.5pt/1.4 "Helvetica Neue", Helvetica, Arial, sans-serif; color: #111; }
.kicker { font-size: 7pt; letter-spacing: .16em; text-transform: uppercase; color: #999; margin-bottom: .8mm; }
h1 { font-family: Rockwell, Georgia, serif; font-size: 18pt; line-height: 1.1; margin: 0 0 2mm; }
header.sheet { padding-bottom: 3mm; border-bottom: 1.2pt solid #111; margin-bottom: 2mm; }
header.sheet .date { margin: 0; font-size: 8pt; color: #999; }

/* Chapters run on rather than starting a page: the sheet is printed, and a page per
   chapter was four part-empty pages. A moment of the evening is a quieter head. */
section.chapter { margin: 4mm 0 2mm; padding-bottom: 1.5mm; border-bottom: 1.2pt solid #111; }
section.chapter h2 { font-family: Rockwell, Georgia, serif; font-size: 15pt; line-height: 1.15; margin: 0; }
section.moment { margin: 3mm 0 2mm; padding-bottom: 1mm; border-bottom: .8pt solid #111; }
section.moment h2 { font-family: Rockwell, Georgia, serif; font-size: 11.5pt; margin: 0; }
section h2 { display: flex; align-items: baseline; gap: 4mm; }
section h2 .time { margin-left: auto; font: 8.5pt "Helvetica Neue", Arial, sans-serif; color: #777;
  font-variant-numeric: tabular-nums; white-space: nowrap; }
/* A cue is a stage direction between the lines: small, in the text's own column. */
.cue { margin: .6mm 0 1.2mm 64mm; font-size: 8pt; line-height: 1.35; font-style: italic; color: #666; }
.cue::before { content: "▸ "; font-style: normal; color: #d00; }


article.state { break-inside: avoid; page-break-inside: avoid; padding: 1.6mm 0; border-bottom: .4pt solid #e2e2e2; }
h3 { font-family: Rockwell, Georgia, serif; font-size: 10.5pt; font-weight: normal; line-height: 1.2; margin: .8mm 0 1.4mm; }

.row { display: flex; gap: 4mm; align-items: flex-start; }
.pic { flex: 0 0 60mm; }
.pic img { width: 100%; display: block; }
.nopic { width: 100%; aspect-ratio: 3840/1080; background: #eee; color: #aaa; display: flex;
  align-items: center; justify-content: center; font-size: 8pt; }
.line { flex: 1 1 auto; min-width: 0; font-size: 10pt; line-height: 1.45; }

/* A state nothing is said over is only there to keep your place, so its picture is drawn
   smaller, in the same column so the lines still start at one edge. */
article.state.quiet { padding: 1.2mm 0; }
article.state.quiet .pic img, article.state.quiet .nopic { width: 40mm; }

/* The last thing on the sheet: the code to the rehearsal page, kept whole on one page. */
article.rehearsal { break-inside: avoid; display: flex; gap: 6mm; align-items: center;
  margin-top: 6mm; padding: 5mm 0 0; border-top: 1.2pt solid #111; }
article.rehearsal .code { flex: 0 0 32mm; display: block; }
article.rehearsal svg { width: 32mm; height: 32mm; display: block; }
article.rehearsal .lead { font-size: 10pt; margin: 0 0 1.5mm; max-width: 120mm; }
article.rehearsal .link { margin: 0 0 1.5mm; font-size: 8.5pt; }
article.rehearsal .link a { color: #111; }
article.rehearsal .private { margin: 0; font-size: 7.5pt; color: #999; }
</style><body>"""


# ---------------------------------------------------------------- output


def to_pdf(markup, out, workdir):
    """Chrome prints it, as for the older sheet: it is the renderer here that honours the
    page size, the margins and `break-inside: avoid`, which keeps a picture with its line."""
    page = os.path.join(workdir, os.path.basename(out).replace(".pdf", ".html"))
    with open(page, "w") as f:
        f.write(markup)
    subprocess.run([
        CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer",
        "--allow-file-access-from-files", "--virtual-time-budget=60000",
        f"--print-to-pdf={out}", f"file://{page}",
    ], check=True, capture_output=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--film", default=os.path.join(ROOT, "video/wn-experience_2026-10-01-clean.mp4"))
    ap.add_argument("--cut", default=None, help="defaults to the .states log beside the film")
    ap.add_argument("--show", default="http://localhost:8765/api/show")
    ap.add_argument("--order", default=os.path.join(ROOT, "show-order.json"))
    ap.add_argument("--track", choices=["default", "extended", "both"], default="both")
    ap.add_argument("--width", type=int, default=900, help="picture width in pixels (60 mm on the page)")
    ap.add_argument("--quality", type=int, default=5, help="ffmpeg jpeg q, 2 best to 31 worst")
    args = ap.parse_args()

    if not args.cut:
        args.cut = os.path.splitext(args.film)[0] + ".states"
    for path, what in ((args.film, "film"), (args.cut, "state log"), (CHROME, "Chrome")):
        if not os.path.isfile(path):
            sys.exit(f"No {what} at {path}.")

    cut = read_cut(args.cut, args.film)
    order = load_show(args.show, cut)
    steps = dict(order)
    where, heads = sections(args.order)
    cache = os.path.join(ROOT, "build/speaker-notes")
    pics = pictures(order, cut, args.film, args.width, args.quality,
                    os.path.join(cache, "frames", os.path.splitext(os.path.basename(args.film))[0]))

    rehearsal = rehearsal_block()
    book = env_value("SLIDES_DRAAIBOEK", os.path.join(ROOT, ".env"))
    book = book if not book or os.path.isabs(book) else os.path.join(ROOT, book)
    day, times, courses = "", {}, {}
    if book and os.path.isfile(book):
        try:
            day, parts = draaiboek(book, env_value("SLIDES_DRAAIBOEK_TAB", os.path.join(ROOT, ".env")))
            times, courses = section_times(parts, heads, where)
            print(f"draaiboek: {len(parts)} rows, times for {len(times)} of {len(heads)} sections")
            for k, n in heads:
                if (k, n) not in times:
                    print(f"draaiboek: no time for the {k} {n}")
        except Exception as e:
            print(f"draaiboek: could not read {os.path.relpath(book, ROOT)} ({e}); no times")
    else:
        print("draaiboek: no SLIDES_DRAAIBOEK file; no times")
    cues = cues_for(order, where, heads, times, courses)
    warnings = []
    warn = lambda m: (warnings.append(m), print("note:", m))
    data_d, _ = track_lines("default", steps, lambda m: None)
    data_e, _ = track_lines("extended", steps, lambda m: None)
    for track in (["default", "extended"] if args.track == "both" else [args.track]):
        data, lines = track_lines(track, steps, warn)
        markup = build_html(track, order, where, heads, pics, lines, data, data_d, data_e,
                            os.path.basename(args.film), rehearsal, times, cues, day)
        out = os.path.join(ROOT, TRACKS[track]["out"])
        os.makedirs(os.path.dirname(out), exist_ok=True)
        to_pdf(markup, out, cache)
        spoken = sum(1 for s, n in order for i in range(n) if lines.get(s, {}).get(LETTERS[i]))
        print(f"-> {os.path.relpath(out, ROOT)}: {sum(steps.values())} states, {spoken} with a line, "
              f"{os.path.getsize(out) // 1024} KB")


if __name__ == "__main__":
    main()
