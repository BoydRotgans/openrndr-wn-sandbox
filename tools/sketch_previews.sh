#!/bin/sh
# Takes a picture of every sketch for the organizer's Sketches tab.
#
# Runs each sketch that calls sketchPreview(...) with SKETCH_PREVIEW=true, one at a time (two
# gradle runs at once collide), and leaves sketch-previews/<Name>.png. A sketch that hangs or fails
# is given MAX seconds and then stopped, and the run goes on to the next. Pass names to do only
# those:  tools/sketch_previews.sh ExtrudedType RotateTypeSegments2D
cd "$(dirname "$0")/.." || exit 1
MAX=${MAX:-150}
mkdir -p sketch-previews

names="$*"
if [ -z "$names" ]; then
  # A real call stands at the start of a line; the code that reads for one mentions it mid-line.
  names=$(grep -rlE '^[[:space:]]*sketchPreview\("' src/main/kotlin --include=*.kt \
    | xargs -n1 basename | sed 's/\.kt$//' | sort)
fi

for name in $names; do
  file=$(find src/main/kotlin -name "$name.kt" | head -1)
  pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -1)
  main="${name}Kt"; [ -n "$pkg" ] && main="$pkg.$main"
  printf '%-24s ' "$name"
  before=$(stat -f %m "sketch-previews/$name.png" 2>/dev/null || echo 0)
  SKETCH_PREVIEW=true ./gradlew run -q -Popenrndr.application="$main" >"/tmp/sketch-preview-$name.log" 2>&1 &
  pid=$!
  waited=0
  while kill -0 $pid 2>/dev/null && [ $waited -lt "$MAX" ]; do sleep 1; waited=$((waited + 1)); done
  if kill -0 $pid 2>/dev/null; then
    pkill -f "openrndr.application=$main" 2>/dev/null; pkill -f "$main" 2>/dev/null; kill $pid 2>/dev/null
    echo "stopped after ${MAX}s (see /tmp/sketch-preview-$name.log)"
    continue
  fi
  after=$(stat -f %m "sketch-previews/$name.png" 2>/dev/null || echo 0)
  if [ "$after" != "$before" ] && [ "$after" != 0 ]; then echo "ok (${waited}s)"; else echo "no picture (see /tmp/sketch-preview-$name.log)"; fi
done

# The show's picture is one of its own slide previews — the catalogue city, which is the talk at a
# glance — or the first there is.
show=build/previews/the-catalogue-city-1.png
[ -f "$show" ] || show=$(ls build/previews/*.png 2>/dev/null | head -1)
[ -n "$show" ] && cp "$show" sketch-previews/Slideshow.png && echo "Slideshow                from $show"
