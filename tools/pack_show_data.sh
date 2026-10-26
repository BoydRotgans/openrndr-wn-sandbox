#!/usr/bin/env bash
# Packs what the show needs and git does not carry into one zip, to hand to another laptop:
# every path in tools/show-data.txt, and this machine's .env as show.env with its secrets and its
# paths into this home folder left empty. start-show.command on the other laptop imports it.
#
#   tools/pack_show_data.sh                 → dist/wn-show-data-<date>-<time>.zip
#   tools/pack_show_data.sh some/where.zip
#
# The name carries the date and time it was packed, so the importing side can tell a newer
# package from the one it already has.
set -euo pipefail
cd "$(dirname "$0")/.."

stamp=$(date +%Y-%m-%d-%H%M)
out=${1:-dist/wn-show-data-$stamp.zip}
name=$(basename "$out" .zip)
mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"
rm -f "$out"

roots=()
while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ -e "$line" ]] || { echo "pack: $line is in tools/show-data.txt and not on this machine" >&2; exit 1; }
    roots+=("$line")
done < tools/show-data.txt
[[ -f .env ]] || { echo "pack: no .env here to hand over" >&2; exit 1; }

meta=$(mktemp -d)
trap 'rm -rf "$meta"' EXIT
mkdir -p "$meta/show-data"
printf '%s\n' "${roots[@]}" > "$meta/show-data/roots.txt"
{
    echo "$name"
    echo "packed $(date '+%Y-%m-%d %H:%M') from $(git rev-parse --abbrev-ref HEAD) at $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- 2>/dev/null || echo ', with uncommitted changes')"
} > "$meta/show-data/version.txt"

# The .env as it stands here, less what must not travel: tokens and keys, and paths into this
# machine's home folder, which mean nothing on another one (the show falls back where one is empty).
awk -v home="$HOME" '
    /^[A-Za-z0-9_]+=/ {
        key = substr($0, 1, index($0, "=") - 1); value = substr($0, index($0, "=") + 1)
        if (key ~ /(TOKEN|SECRET|PASSWORD|_KEY)$/ && value != "") { print "# (left empty in the handover)"; print key "="; next }
        if (index(value, home "/") == 1) { print "# (left empty in the handover: was a path on the machine it was packed on)"; print key "="; next }
    }
    { print }
' .env > "$meta/show-data/show.env"

cat > "$meta/show-data/README.txt" <<EOF
The WN show's data: everything the show reads that is not in git.

Put this zip in the repo's data/ folder and double-click start-show.command in the repo:
it imports the newest package in data/ and starts the show. Or run
    ./start-show.command /path/to/$name.zip

roots.txt lists what it replaces on import; show.env becomes the repo's .env (the old one is kept
beside it as .env.before-<date>).
EOF

echo "pack: listing files"
list="$meta/files.txt"
for root in "${roots[@]}"; do
    if [[ -d "$root" ]]; then
        find "$root" -type f ! -name .DS_Store ! -path '*/alternates/*'
    else
        echo "$root"
    fi
done > "$list"

count=$(wc -l < "$list" | tr -d ' ')
size=$(tr '\n' '\0' < "$list" | xargs -0 du -k | awk '{s += $1} END {printf "%.0f", s / 1024}')
echo "pack: $count files, $size MB, into $out"
zip -q -X -1 "$out" -@ < "$list"
(cd "$meta" && zip -q -X -r "$out" show-data)
echo "pack: done — $(du -h "$out" | cut -f1) $out"
