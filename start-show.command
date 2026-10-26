#!/usr/bin/env bash
# Starts the WN show on this Mac. Double-click it in Finder (it opens in Terminal), or run it here:
#
#   ./start-show.command                 the organizer page opens in the browser: ▶ start presentation there
#   ./start-show.command --show          the show window straight away, without the page
#   ./start-show.command <package.zip>   import that data package first (a folder unpacked from one works too)
#   ./start-show.command --check         import and check, but start nothing
#
# Every start: imports the newest data package it finds (wn-show-data-*.zip beside this file, in
# Downloads or on the Desktop) when it is newer than the one imported before; checks for Java, ffmpeg
# and rsvg-convert, offering to install what is missing through Homebrew; then starts the show.
# A machine whose data/ did not come from a package (the one the packages are made on) is never
# imported over unless a package is named on the command line.
set -euo pipefail
cd "$(dirname "$0")"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

bold=$'\033[1m'; red=$'\033[31m'; amber=$'\033[33m'; plain=$'\033[0m'
say()  { echo "${bold}$*${plain}"; }
warn() { echo "${amber}! $*${plain}"; }
fail() { echo "${red}✗ $*${plain}"; echo; read -r -p "Press return to close." _ || true; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
ask()  { local answer; read -r -p "$1 [Y/n] " answer || answer=n; [[ -z "$answer" || "$answer" == [Yy]* ]]; }

mode=organizer
package=""
for arg in "$@"; do
    case "$arg" in
        --show) mode=show ;;
        --check) mode=check ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) [[ -e "$arg" ]] || fail "no such file: $arg"; package="$arg" ;;
    esac
done

# ── The data package ────────────────────────────────────────────────────────────────────────

marker=data/.show-data-version
installed=$(head -1 "$marker" 2>/dev/null || true)

# A package is a wn-show-data-*.zip, or the folder macOS unpacks one into on download.
is_package() { [[ -f "$1" && "$1" == *.zip ]] || [[ -f "$1/show-data/roots.txt" ]]; }
package_name() { basename "${1%.zip}" | sed -E 's/ ?\([0-9]+\)$//'; }

newest_package() {
    local found=() candidate
    for dir in "$PWD" "$HOME/Downloads" "$HOME/Desktop"; do
        for candidate in "$dir"/wn-show-data-*; do
            is_package "$candidate" && found+=("$candidate")
        done
    done
    ((${#found[@]})) || return 1
    local best="" best_name="" n
    for candidate in "${found[@]}"; do
        n=$(package_name "$candidate")
        [[ "$n" > "$best_name" ]] && { best="$candidate"; best_name="$n"; }
    done
    echo "$best"
}

import_package() {
    local source="$1" from tmp="" root name
    if [[ -d "$source" ]]; then
        from="$source"
    else
        tmp=$(mktemp -d "${TMPDIR:-/tmp}/wn-show-data.XXXXXX")
        say "Unpacking $(basename "$source") — a minute or two …"
        unzip -q "$source" -d "$tmp" || { rm -rf "$tmp"; fail "could not unpack $source"; }
        from="$tmp"
    fi
    [[ -f "$from/show-data/roots.txt" ]] || { [[ -n "$tmp" ]] && rm -rf "$tmp"; fail "$source is not a show data package (it has no show-data/roots.txt)"; }

    while IFS= read -r root; do
        [[ -z "$root" || "$root" == \#* ]] && continue
        case "$root" in /*|*..*) fail "the package names an unsafe path: $root" ;; esac
        rm -rf "./$root"
        [[ -e "$from/$root" ]] || continue
        mkdir -p "$(dirname "./$root")"
        if [[ -n "$tmp" ]]; then mv "$from/$root" "./$root"; else ditto "$from/$root" "./$root"; fi
    done < "$from/show-data/roots.txt"

    if [[ -f .env ]] && ! cmp -s .env "$from/show-data/show.env"; then
        local kept=".env.before-$(date +%Y%m%d-%H%M%S)"
        cp .env "$kept"
        warn "the .env here was replaced by the package's; the old one is kept as $kept"
    fi
    cp "$from/show-data/show.env" .env
    name=$(head -1 "$from/show-data/version.txt" 2>/dev/null || package_name "$source")
    mkdir -p data
    cp "$from/show-data/version.txt" "$marker" 2>/dev/null || echo "$name" > "$marker"
    [[ -n "$tmp" ]] && rm -rf "$tmp"
    installed="$name"
    say "✓ Imported $name"
}

if [[ -n "$package" ]]; then
    is_package "$package" || fail "$package is not a show data package"
    import_package "$package"
elif [[ -n "$installed" || ! -d data/sounds ]]; then
    if candidate=$(newest_package); then
        name=$(package_name "$candidate")
        if [[ -z "$installed" ]]; then
            say "Found the show's data: $candidate"
            import_package "$candidate"
        elif [[ "$name" > "$installed" ]]; then
            say "A newer data package is here: $candidate (imported now: $installed)"
            ask "Import it?" && import_package "$candidate"
        fi
    fi
fi
[[ -d data/sounds && -d data/objects ]] || fail "The show's data is not here. Put the wn-show-data-….zip you were given in Downloads and start this again, or run: ./start-show.command /path/to/wn-show-data-….zip"
[[ -f .env ]] || fail "There is no .env. It comes with the data package; import one first."
[[ -n "$installed" ]] && echo "Data: $installed"

# ── What the show needs installed ───────────────────────────────────────────────────────────

offer_brew() {   # formula, what it is for
    if ! have brew; then
        warn "$2 needs $1. Install Homebrew from https://brew.sh, then run: brew install $1"
        return 1
    fi
    ask "$2 needs $1. Install it with Homebrew now?" && brew install "$1"
}

# A JDK from 17 to 24 (Gradle 8.14 runs on no later one) built for this Mac's processor.
java_ok() {
    local home="$1" props version arch
    [[ -x "$home/bin/java" ]] || return 1
    props=$("$home/bin/java" -XshowSettings:properties -version 2>&1) || return 1
    version=$(awk -F'= ' '/java.specification.version/ {print $2}' <<<"$props" | cut -d. -f1)
    arch=$(awk -F'= ' '/ os.arch/ {print $2}' <<<"$props")
    [[ "$version" =~ ^[0-9]+$ ]] && ((version >= 17 && version <= 24)) || return 1
    case "$(uname -m)" in
        arm64) [[ "$arch" == aarch64 ]] ;;
        *) [[ "$arch" == x86_64 || "$arch" == amd64 ]] ;;
    esac
}
find_java() {
    local candidates=() home
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
    for v in 21 17 23 22 24 18 19 20; do
        candidates+=("/opt/homebrew/opt/openjdk@$v/libexec/openjdk.jdk/Contents/Home" "/usr/local/opt/openjdk@$v/libexec/openjdk.jdk/Contents/Home")
        home=$(/usr/libexec/java_home -v "$v" 2>/dev/null) && candidates+=("$home")
    done
    while IFS= read -r home; do candidates+=("$home"); done < <(/usr/libexec/java_home -V 2>&1 | awk '/\// {print $NF}')
    for home in "${candidates[@]}"; do
        java_ok "$home" && { echo "$home"; return 0; }
    done
    return 1
}

if ! JAVA_HOME=$(find_java); then
    offer_brew openjdk@21 "The show" || fail "No Java 17 to 24 was found. Install a JDK 21 for this Mac (https://adoptium.net, Temurin 21) and start this again."
    JAVA_HOME=$(find_java) || fail "Java still not found after installing openjdk@21."
fi
export JAVA_HOME
echo "Java: $JAVA_HOME"

have ffmpeg || offer_brew ffmpeg "The dinner music and the project highlights' sound" || warn "starting without ffmpeg: the course music and the highlights' sound stay silent"
have rsvg-convert || offer_brew librsvg "The reduction slide's and the A·B·C road's drawings" || warn "starting without rsvg-convert: those drawings fall back to plain pictograms"
for font in "Rockwell.ttc" "Arial Bold.ttf"; do
    [[ -f "/System/Library/Fonts/Supplemental/$font" ]] || warn "the system font $font is missing; the show falls back to its own face"
done

# ── Start ───────────────────────────────────────────────────────────────────────────────────

[[ "$mode" == check ]] && { say "✓ Ready. Start without --check to run the show."; exit 0; }

[[ -d .gradle ]] || say "The first start downloads Gradle and the libraries and compiles everything: give it a few minutes."
if [[ "$mode" == show ]]; then
    say "Starting the show window. Shift-Esc in the window, or ctrl-C here, quits."
    exec ./gradlew run -Popenrndr.application=SlideshowKt
else
    say "Starting the organizer: its page opens in the browser, and ▶ start presentation there starts the show."
    say "Keep this window open while the show runs; ctrl-C here stops it."
    exec ./gradlew run -Popenrndr.application=OrganizerKt
fi
