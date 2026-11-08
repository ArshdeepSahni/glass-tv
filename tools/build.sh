#!/bin/bash
# Builds Glass and its assistant on this Mac, into dist/, the way the release workflow does (signed with this Mac's
# Android key, so they install over what's on the TV).
#   tools/build.sh              both
#   tools/build.sh launcher     Glass only
#   tools/build.sh assistant    the assistant only
# The assistant's keys come from ~/Desktop/tv/.env (or GLASS_ENV), the update service's address and key from
# ~/Desktop/tv/.updates.env (or GLASS_UPDATES_FILE). Without them the assistant only does TV commands and Glass says
# updates aren't set up; nothing else changes.
set -euo pipefail
source "$(dirname "$0")/common.sh"
WHAT="${1:-all}"
case "$WHAT" in all|launcher|assistant) ;; *) die "tools/build.sh [launcher|assistant]" ;; esac
tools_ready
V="$(cd "$ROOT" && python3 updates/manifest.py version | sed -n 's/^release=//p')"
mkdir -p "$ROOT/dist"
if [ "$WHAT" != assistant ]; then
  if [ -s "$GLASS_UPDATES_FILE" ]; then say "Updates: built in (from $(short "$GLASS_UPDATES_FILE"))"; else say "Updates: not set up in this build"; fi
  say "Building Glass $V (2–5 minutes)…"
  (cd "$ROOT/launcher" && gradle_run assembleRelease)
  cp "$ROOT/launcher/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/glass-$V-launcher.apk"
  say "Glass: dist/glass-$V-launcher.apk ($(du -k "$ROOT/dist/glass-$V-launcher.apk" | cut -f1) KB)"
fi
if [ "$WHAT" != launcher ]; then
  if [ -f "$GLASS_ENV" ]; then say "Assistant keys: from $(short "$GLASS_ENV")"; else say "Assistant keys: none (it'll do TV commands only)"; fi
  say "Building the assistant…"
  (cd "$ROOT/assistant" && gradle_run assembleRelease)
  cp "$ROOT/assistant/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/glass-$V-assistant.apk"
  say "Assistant: dist/glass-$V-assistant.apk ($(du -k "$ROOT/dist/glass-$V-assistant.apk" | cut -f1) KB)"
fi
say "BUILD OK"
