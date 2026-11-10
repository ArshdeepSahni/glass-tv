#!/bin/sh
# Glass, from nothing to set up, in one line:
#
#   curl -fsSL https://raw.githubusercontent.com/ArshdeepSahni/glass-tv/main/install.sh | sh
#
# It checks this computer can run the installer, downloads Glass into ~/glass-tv (no git needed), and starts ./glass.
# Run it again any time: it brings that folder up to date and starts ./glass again. Nothing outside ~/glass-tv and
# ~/.glass is touched, and it never asks for your password.
#   GLASS_DIR=/somewhere/else   where to keep Glass (default ~/glass-tv)
#   GLASS_REPO=owner/name       download from another copy of the repository
#   GLASS_TARBALL=URL           download this archive instead (for testing)
# Anything after `sh -s --` is passed to ./glass, for example: curl ... | sh -s -- --tv 192.168.1.20
set -u

REPO="${GLASS_REPO:-ArshdeepSahni/glass-tv}"
DIR="${GLASS_DIR:-$HOME/glass-tv}"
TARBALL="${GLASS_TARBALL:-https://codeload.github.com/$REPO/tar.gz/refs/heads/main}"

say() { printf '%s\n' "$*"; }
stop() { printf '\n%s\n\n' "$*" >&2; exit 1; }

say ""
say "Glass for Android TV"

# ---- this computer ------------------------------------------------------------------------------------------------
OS="$(uname -s 2>/dev/null || echo unknown)"
case "$OS" in
  Darwin|Linux) ;;
  MINGW*|MSYS*|CYGWIN*|Windows*)
    stop "Glass sets up from macOS or Linux. On Windows, use WSL: in PowerShell run  wsl --install , restart, open
Ubuntu, and run this line again there." ;;
  *)
    stop "Glass sets up from macOS or Linux, and this computer says it is $OS." ;;
esac
if [ -z "${HOME:-}" ] || [ ! -d "$HOME" ]; then
  stop "There's no home folder to put Glass in. Set GLASS_DIR to a folder you can write to, and run this again."
fi

# Python 3.8 or newer: the installer is written in it. macOS has it once Apple's Command Line Tools are installed.
PY=""
for p in python3 /usr/bin/python3 /opt/homebrew/bin/python3 /usr/local/bin/python3; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    PY="$p"
    break
  fi
done
if [ -z "$PY" ]; then
  if [ "$OS" = Darwin ]; then
    stop "Glass's installer needs Python 3, which comes with Apple's Command Line Tools. Run  xcode-select --install ,
let it finish, then run this line again."
  fi
  how="your package manager"
  if command -v apt-get >/dev/null 2>&1; then how="sudo apt install python3"
  elif command -v dnf >/dev/null 2>&1; then how="sudo dnf install python3"
  elif command -v pacman >/dev/null 2>&1; then how="sudo pacman -S python"
  elif command -v zypper >/dev/null 2>&1; then how="sudo zypper install python3"
  elif command -v apk >/dev/null 2>&1; then how="sudo apk add python3"
  fi
  stop "Glass's installer needs Python 3.8 or newer. Install it ($how), then run this line again."
fi

# ---- download -----------------------------------------------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  get() { curl -fsL --retry 3 --connect-timeout 20 "$1"; }
elif command -v wget >/dev/null 2>&1; then
  get() { wget -q -O - "$1"; }
else
  stop "Downloading Glass needs curl or wget, and this computer has neither. Install one, then run this again."
fi
command -v tar >/dev/null 2>&1 || stop "Unpacking Glass needs tar. Install it, then run this again."

TMP="$(mktemp -d 2>/dev/null || mktemp -d -t glass)" || stop "Couldn't make a folder to download into."
trap 'rm -rf "$TMP"' EXIT INT TERM
say "Downloading Glass..."
if ! get "$TARBALL" > "$TMP/glass.tar.gz" || [ ! -s "$TMP/glass.tar.gz" ]; then
  stop "Couldn't download Glass. Check this computer is online, then run this again."
fi
mkdir -p "$TMP/x" && tar -xzf "$TMP/glass.tar.gz" -C "$TMP/x" 2>/dev/null || stop "The download was damaged. Run this again."
SRC="$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
[ -n "$SRC" ] && [ -f "$SRC/glass" ] || stop "The download didn't hold Glass. Run this again in a minute."

if [ -e "$DIR" ] && [ ! -f "$DIR/glass" ] && [ -n "$(ls -A "$DIR" 2>/dev/null)" ]; then
  stop "$DIR already holds something that isn't Glass. Move it, or choose another folder with GLASS_DIR, and run
this again."
fi
mkdir -p "$DIR" || stop "Couldn't make $DIR. Choose another folder with GLASS_DIR, and run this again."
# a copy over the top: new and changed files replace the old ones; your keys live in ~/.glass, not here
cp -R "$SRC/." "$DIR/" || stop "Couldn't copy Glass into $DIR. Check you can write there, and run this again."
chmod +x "$DIR/glass" 2>/dev/null
say "Glass is in $DIR"

# ---- start the installer -----------------------------------------------------------------------------------------
cd "$DIR" || stop "Couldn't open $DIR."
rm -rf "$TMP"; trap - EXIT INT TERM
if [ -t 1 ] && [ -r /dev/tty ] && (: </dev/tty) 2>/dev/null; then
  # this script arrived through a pipe, so the installer takes its keys from the terminal itself
  exec sh ./glass "$@" </dev/tty
fi
say "Now run:  cd \"$DIR\" && ./glass"
