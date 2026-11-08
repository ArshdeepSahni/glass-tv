#!/bin/bash
# Releases what's committed: checks the version has its CHANGELOG section and isn't released yet, then tags it
# v<version> and pushes. GitHub builds it, publishes it to the update service and makes a release page (gh run watch
# follows it). Then on the TV: Settings › About this TV › Check for updates.
# Before: bump versionCode (+1) and versionName in launcher/app/build.gradle (the assistant's too if it changed), and
# write the CHANGELOG section.
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$ROOT"
V="$(python3 updates/manifest.py version | sed -n 's/^release=//p')"
grep -qE "^## ${V//./\\.}( |$)" CHANGELOG.md || die "CHANGELOG.md has no \"## $V\" section: write what's new first"
[ -z "$(git status --porcelain)" ] || die "Commit (or put away) the changes first"
git rev-parse -q --verify "refs/tags/v$V" >/dev/null && die "v$V is released already: bump versionCode and versionName in launcher/app/build.gradle"
# pushed with gh's sign-in when gh is here (the same account that holds the repository)
push() { if command -v gh >/dev/null 2>&1; then git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push -q "$@"; else git push -q "$@"; fi; }
push origin HEAD
git tag -a "v$V" -m "Glass $V"
push origin "v$V"
say "v$V is tagged: GitHub is building and publishing it (gh run watch)"
