#!/bin/bash
# Puts Glass on the TV over the network (adb): the assistant first, then Glass, then what Glass needs allowed.
#   tools/install-tv.sh              what tools/build.sh made (dist/)
#   tools/install-tv.sh --service    the newest release on the update service: exactly what the TVs download
#   tools/install-tv.sh --setup      also, for a TV that never had Glass: Glass as its home, its keyboard,
#                                    the assistant button and the screen saver (combines with --service)
# After this the TV updates itself (Settings › About this TV › Check for updates). Android never puts an older
# version over a newer one: to undo a release, release a fixed one with a higher versionCode.
# The TV must be on (nothing here wakes it), with network debugging on; the first time it asks to allow this
# computer. ./glass setup and ./glass update do all of this with a status screen.
set -euo pipefail
source "$(dirname "$0")/common.sh"
FROM=dist; SETUP=no
for a in "$@"; do
  case "$a" in --service) FROM=service ;; --setup) SETUP=yes ;; *) die "tools/install-tv.sh [--service] [--setup]" ;; esac
done
tools_ready
adb_ready
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

APKS=()
if [ "$FROM" = service ]; then
  updates_env
  say "Downloading the newest release from the update service…"
  while IFS= read -r p; do APKS+=("$p"); done < <(python3 "$ROOT/updates/fetch.py" "$TMP")
else
  V="$(cd "$ROOT" && python3 updates/manifest.py version | sed -n 's/^release=//p')"
  [ -f "$ROOT/dist/glass-$V-assistant.apk" ] && APKS+=("$ROOT/dist/glass-$V-assistant.apk")
  [ -f "$ROOT/dist/glass-$V-launcher.apk" ] && APKS+=("$ROOT/dist/glass-$V-launcher.apk")
  [ "${#APKS[@]}" -gt 0 ] || die "dist/ has no Glass $V: run tools/build.sh first"
fi

ver() { A "dumpsys package $1" 2>/dev/null | tr -d '\r' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2; }
A "logcat -b crash -c" >/dev/null 2>&1 || true
for apk in "${APKS[@]}"; do
  name="$(basename "$apk")"
  OUT="$("$ADB" -s "$TV" install -r "$apk" 2>&1 | tr -d '\r')" || true
  if echo "$OUT" | grep -q Success; then say "$name: installed"
  elif echo "$OUT" | grep -q VERSION_DOWNGRADE; then die "$name: the TV has a newer version (Android won't go back)"
  elif echo "$OUT" | grep -q UPDATE_INCOMPATIBLE; then die "$name: signed with another key than the one on the TV"
  else die "$name: $(echo "$OUT" | tail -1)"; fi
done

say "Allowing what Glass needs…"
A "appops set $PKG WRITE_SETTINGS allow; appops set $PKG REQUEST_INSTALL_PACKAGES allow; pm grant $PKG android.permission.WRITE_SECURE_SETTINGS; pm grant $PKG android.permission.READ_EXTERNAL_STORAGE; pm grant $PKG android.permission.ACCESS_FINE_LOCATION" >/dev/null 2>&1 || true
if A "pm path $AS" 2>/dev/null | grep -q package:; then A "pm grant $AS android.permission.RECORD_AUDIO" >/dev/null 2>&1 || true; fi
# Glass's remote helper (an accessibility service): on, and bound again to the new Glass
SVC=$PKG/.HomeKeyService
S="$(A settings get secure enabled_accessibility_services | tr -d '\r')"
case "$S" in *HomeKeyService*) ;; null|'') S="$SVC" ;; *) S="$S:$SVC" ;; esac
NO="$(echo "$S" | tr ':' '\n' | grep -v HomeKeyService | grep -v '^null$' | paste -sd: - || true)"
A "settings put secure enabled_accessibility_services '$NO'"; sleep 2
A "settings put secure enabled_accessibility_services '$S'; settings put secure accessibility_enabled 1"; sleep 2
say "Remote helper: $(A settings get secure enabled_accessibility_services | tr -d '\r' | grep -q HomeKeyService && echo on || echo 'NOT ON')"
say "Making Glass start fast (a minute)…"
A "cmd package compile -m speed -f $PKG; cmd package compile -m speed -f $AS" >/dev/null 2>&1 || true

if [ "$SETUP" = yes ]; then
  say "Setting up a new TV…"
  A "cmd package set-home-activity --user 0 $PKG/.GlassHomeActivity" >/dev/null 2>&1 || true
  if A "dumpsys package $AS" 2>/dev/null | grep -q GlassVoiceService; then
    A "settings put secure voice_interaction_service $AS/.GlassVoiceService; settings put secure assistant $AS/.GlassVoiceService"
  fi
  for _ in $(seq 1 20); do A "ime list -a -s" | tr -d '\r' | grep -q GlassKeyboard && break; sleep 1; done
  A "ime enable $PKG/.GlassKeyboard; ime set $PKG/.GlassKeyboard" >/dev/null 2>&1 || true
  A "settings put secure screensaver_enabled 1; settings put secure screensaver_components $PKG/.ClockDream" >/dev/null 2>&1 || true
  A "cmd notification allow_listener $PKG/$PKG.ThreadListener" >/dev/null 2>&1 || true
fi

if awake; then
  A input keyevent 3; sleep 8
  if A "logcat -d -b crash" 2>/dev/null | tr -d '\r' | grep -q "Process: $PKG"; then
    die "Glass stopped right after starting: see  adb logcat -b crash"
  fi
  say "Glass is up"
else
  say "The TV's screen is off: Glass starts when it comes on"
fi
say "On the TV now: Glass $(ver $PKG)$(v=$(ver $AS); [ -n "$v" ] && echo " · Assistant $v")"
