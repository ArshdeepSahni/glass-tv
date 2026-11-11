#!/bin/bash
# Checks that a release is the same everywhere: CHANGELOG.md, the tag, GitHub's release page, the TVs' update service,
# the website (landing page, docs, changelog) and the README on GitHub. tools/release.sh runs it at the end; run it
# again any time. It prints what it checked, never an address or a key.
#
#   tools/verify-release.sh [VERSION]      the newest release in CHANGELOG.md when no version is given
#
# Uses the GitHub CLI (gh) for the release page and the site's address (the repository's Website), and
# GLASS_HOME/.updates.env for the update service (left out when there's none).
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$ROOT"
if [ -s "$GLASS_UPDATES_FILE" ]; then updates_env; fi
exec python3 tools/verify_release.py "$@"
