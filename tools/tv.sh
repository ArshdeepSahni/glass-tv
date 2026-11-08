#!/bin/bash
# The TV's upkeep from this computer, over the network (the TV must be on; nothing here wakes it). ./glass status,
# ./glass doctor and ./glass restore do the same with a status screen.
#   tools/tv.sh status          Glass and its helpers, the debloat, memory, crashes since the TV started
#   tools/tv.sh check           after a TV update or a factory reset: the debloat list again (tv/debloat-list.txt),
#                               Glass's remote helper, Glass as home
#   tools/tv.sh restore-stock   the TV as it shipped: its own home and every package back, Glass and its assistant
#                               removed (asks first: Glass's settings go with it)
#   tools/tv.sh home            the Home button
#   tools/tv.sh key NAME        any remote key, e.g. tools/tv.sh key DPAD_CENTER
set -euo pipefail
source "$(dirname "$0")/common.sh"
LIST="$ROOT/tv/debloat-list.txt"
tools_ready
adb_ready
home_now() { A 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tr -d '\r' | tail -1; }
ver() { A "dumpsys package $1" 2>/dev/null | tr -d '\r' | grep -m1 -oE 'versionName=[^ ]+' | cut -d= -f2; }
listed() { awk -v all="${1:-}" '!/^#/ && NF { if (all || $0 !~ /\[optional\]/) print $1 }' "$LIST"; }

case "${1:-}" in
  status)
    say "Glass $(ver $PKG) · Assistant $(ver $AS) · screen $(awake && echo on || echo off)"
    say "home: $(home_now)"
    say "remote helper: $(A settings get secure enabled_accessibility_services | tr -d '\r' | grep -q HomeKeyService && echo on || echo OFF)"
    say "disabled packages: $(A pm list packages -d | tr -d '\r' | grep -c package:) (the list has $(listed all | wc -l | tr -d ' '))"
    say "memory: $(A 'grep -E "MemTotal|MemAvailable" /proc/meminfo' | tr -d '\r' | awk '{printf "%s %d MB  ", $1, $2/1024}')"
    say "Glass crashes since the TV started: $(A 'logcat -d -b crash' | tr -d '\r' | grep -c 'Process: com.gathin')"
    ;;
  check)
    disabled="$(A pm list packages -d | tr -d '\r')"
    for p in $(listed); do
      if echo "$disabled" | grep -qx "package:$p"; then say "ok     $p"
      elif A pm disable-user --user 0 "$p" >/dev/null 2>&1; then say "FIXED  $p"
      else say "absent $p"; fi
    done
    case "$(A settings get secure enabled_accessibility_services | tr -d '\r')" in
      *HomeKeyService*) say "ok     Glass's remote helper" ;;
      *) A "settings put secure enabled_accessibility_services $PKG/.HomeKeyService; settings put secure accessibility_enabled 1"; say "FIXED  Glass's remote helper" ;;
    esac
    case "$(home_now)" in
      $PKG/*) say "ok     Glass is home" ;;
      *) say "!!     Glass isn't home: tools/install-tv.sh --service --setup" ;;
    esac
    A sync; say "Done. Restart the TV from its own menu (not the wall switch) so it keeps the changes."
    ;;
  restore-stock)
    read -rp "This removes Glass and its assistant (with their settings) and gives the TV its own home back. Type RESTORE: " ok
    [ "$ok" = RESTORE ] || die "nothing changed"
    A pm enable com.google.android.tvlauncher >/dev/null
    A "cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity" >/dev/null
    A "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0"
    A "settings delete secure voice_interaction_service; settings delete secure assistant; settings delete secure screensaver_components" >/dev/null 2>&1 || true
    for p in $(listed all); do A pm enable "$p" >/dev/null 2>&1 && say "on     $p" || say "absent $p"; done
    A "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings delete global animator_duration_scale; settings put global wifi_sleep_policy 0"
    A input keyevent 3
    A pm uninstall "$AS" >/dev/null 2>&1 && say "removed the assistant" || true
    A pm uninstall "$PKG" >/dev/null 2>&1 && say "removed Glass" || true
    A sync; say "Done. Restart the TV from its own menu so it keeps the changes."
    ;;
  home) A input keyevent 3 ;;
  key) [ -n "${2:-}" ] || die "tools/tv.sh key NAME"; A input keyevent "KEYCODE_${2#KEYCODE_}" ;;
  *) sed -n 2,10p "$0" ;;
esac
