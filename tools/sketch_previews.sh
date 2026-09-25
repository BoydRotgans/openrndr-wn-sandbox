#!/bin/sh
# Takes a picture of every sketch for the organizer's Sketches tab.
#
# Runs each sketch that calls sketchPreview(...) — and each course wall run through runCourse(...) —
# with SKETCH_PREVIEW=true, one at a time (two gradle runs at once collide), and leaves
# sketch-previews/<Name>.png. A sketch that declares variants (`// sketch-variant: name | main |
# env | what`) gets one more picture each, sketch-previews/<Name>--<variant>.png, run with that
# variant's main class and .env values. A run that hangs or fails is given MAX seconds and then
# stopped, and the run goes on to the next. Pass names to do only those:
#   tools/sketch_previews.sh ExtrudedType CourseGrid
cd "$(dirname "$0")/.." || exit 1
MAX=${MAX:-150}
mkdir -p sketch-previews

names="$*"
if [ -z "$names" ]; then
  # A real call stands at the start of a line; the code that reads for one mentions it mid-line. A
  # file that is only another sketch's variant is pictured under that sketch.
  names=$(grep -rlE '^[[:space:]]*sketchPreview\("|^fun main\(\) = runCourse\(' src/main/kotlin --include=*.kt \
    | xargs grep -L '^// sketch-variant-of:' | xargs -n1 basename | sed 's/\.kt$//' | sort)
fi

# shoot <label> <main class> <picture name> [NAME=value ...] — its own names, so it leaves the
# caller's $main alone for the variants that follow.
shoot() {
  label=$1; shot_main=$2; out=$3; shift 3
  printf '%-44s ' "$label"
  before=$(stat -f %m "sketch-previews/$out.png" 2>/dev/null || echo 0)
  env "$@" SKETCH_PREVIEW=true SKETCH_PREVIEW_NAME="$out" ./gradlew run -q -Popenrndr.application="$shot_main" >"/tmp/sketch-preview-$out.log" 2>&1 &
  pid=$!
  waited=0
  while kill -0 $pid 2>/dev/null && [ $waited -lt "$MAX" ]; do sleep 1; waited=$((waited + 1)); done
  if kill -0 $pid 2>/dev/null; then
    pkill -f "openrndr.application=$shot_main" 2>/dev/null; pkill -f "$shot_main" 2>/dev/null; kill $pid 2>/dev/null
    echo "stopped after ${MAX}s (see /tmp/sketch-preview-$out.log)"
    return
  fi
  after=$(stat -f %m "sketch-previews/$out.png" 2>/dev/null || echo 0)
  if [ "$after" != "$before" ] && [ "$after" != 0 ]; then echo "ok (${waited}s)"; else echo "no picture (see /tmp/sketch-preview-$out.log)"; fi
}

trim() { echo "$1" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'; }
slug() { echo "$1" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//'; }

for name in $names; do
  file=$(find src/main/kotlin -name "$name.kt" | head -1)
  pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -1)
  main="${name}Kt"; [ -n "$pkg" ] && main="$pkg.$main"
  shoot "$name" "$main" "$name"
  grep '^// sketch-variant:' "$file" | sed 's|^// sketch-variant:||' | while IFS='|' read -r vname vmain venv vwhat; do
    vname=$(trim "$vname"); vmain=$(trim "$vmain"); venv=$(trim "$venv")
    [ -z "$vname" ] && continue
    [ -z "$vmain" ] && vmain=$main
    # shellcheck disable=SC2086 — the values are NAME=value words, split on purpose.
    shoot "  $name · $vname" "$vmain" "$name--$(slug "$vname")" $venv </dev/null
  done
done

# The show's picture is one of its own slide previews — the catalogue city, which is the talk at a
# glance — or the first there is.
show=build/previews/the-catalogue-city-1.png
[ -f "$show" ] || show=$(ls build/previews/*.png 2>/dev/null | head -1)
[ -n "$show" ] && cp "$show" sketch-previews/Slideshow.png && echo "Slideshow                                    from $show"
