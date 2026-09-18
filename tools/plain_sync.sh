#!/usr/bin/env bash
#
# The Plain wall and its MIDI in one folder, for checking the two against each other.
#
#     tools/plain_sync.sh [seconds]
#
# Films the wall on its own through SlideStudio, exports the build as MIDI, and puts the
# two in video/plain-sync/. Both start at the wall's own frame 0 — the studio records from
# the first drawn frame and the MIDI's first note is that frame — so they drop onto a
# timeline together with no offset.
#
# It has to be run from a terminal with a display: the film needs a window, which is the one
# part of this that cannot be done headless.

set -euo pipefail
cd "$(dirname "$0")/.."

SECONDS_LONG="${1:-10}"
OUT="video/plain-sync"

echo "— filming the wall (${SECONDS_LONG}s)"
SLIDE=Plain SLIDE_RECORD=true SLIDE_DURATION="$SECONDS_LONG" \
    ./gradlew run -Popenrndr.application=SlideStudioKt

echo "— exporting the build as MIDI"
./gradlew run -Popenrndr.application=PlainMidiKt

# The studio names the file by the slide's index, which moves with the running order, so the
# newest match is taken rather than a number being assumed. The pattern is anchored on two
# digits so `slide-NN-block-city-plain.mp4` is not picked up instead.
FILM=$(ls -t video/slide-[0-9][0-9]-plain.mp4 2>/dev/null | head -1 || true)
if [ -z "$FILM" ]; then echo "no film written — did the window open?" >&2; exit 1; fi

mkdir -p "$OUT"
cp "$FILM" "$OUT/plain.mp4"
cp midi/plain.mid "$OUT/plain.mid"

cat > "$OUT/README.txt" <<'NOTE'
plain.mp4  the Plain wall, filmed on its own, from its first frame
plain.mid  the same build: one note per element, on the frame it starts to arrive

Both begin at the wall's frame 0, so they line up with no offset.

In the MIDI: a track and a channel per column, named after the piece that column
stands; pitch is the row the element lands on, a semitone a row up the stack; a note
is held for the 0.35s the element takes to grow. The whole build is 2.13s — the rest
of the clip is the wall holding while its colour runs red to navy and back over 8s.
NOTE

echo "— $OUT"
ls -l "$OUT"
