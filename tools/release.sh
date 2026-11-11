#!/bin/bash
# Releases Glass, everywhere at once: what waits under "## Unreleased" in CHANGELOG.md becomes the version in
# launcher/app/build.gradle, dated today, and goes to the TVs' update service, GitHub's release page, the website, the
# docs and the README together. RELEASING.md has the whole of it.
#
#   tools/release.sh            release (everything committed first; go-live.sh runs this when there's a release)
#   tools/release.sh --finish   the end of a release whose Release run stopped and was run again on GitHub
#   tools/release.sh --abandon  after a Release run the code made fail: the version's lines go back to Unreleased
#
# 1. Checks: nothing uncommitted; the version isn't tagged (here or on GitHub); lines and a title wait under
#    "## Unreleased"; the repository is in step (tools/parity.py: changelog, pictures, README, nothing private).
# 2. Pushes what's committed and waits for GitHub's Check run (both apps build, the tests pass). The site holds while
#    the changelog has unreleased lines, so nothing public changes yet.
# 3. Dates the changelog (tools/changelog.py) and commits it, then tags that commit v<version>, at one moment.
# 4. Pushes the tag and waits for GitHub's Release run: build, sign, publish to the update service, release page.
# 5. Only then pushes the dated changelog, which puts the website live; waits for that Site run.
# 6. tools/verify-release.sh: one version and one date on every surface.
# A tag that's pushed is never moved: when a release fails, the next one has a new version.
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$ROOT"
V="$(python3 updates/manifest.py version | sed -n 's/^release=//p')"
GH=0
command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1 && GH=1

# pushed with gh's sign-in when gh is here (the same account that holds the repository)
push() { if [ "$GH" = 1 ]; then git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push -q "$@"; else git push -q "$@"; fi; }

# waits for a workflow's run on a commit; 0 when it succeeded
wait_run() {   # $1 workflow file, $2 commit, $3 what it does
  if [ "$GH" = 0 ]; then
    say "Without the GitHub CLI (gh) this can't follow GitHub's runs: watch the Actions tab for $1"
    return 0
  fi
  local id="" i
  for i in $(seq 1 60); do
    id="$(gh run list --workflow "$1" -L 20 --json databaseId,headSha -q ".[] | select(.headSha == \"$2\") | .databaseId" | head -n 1)"
    [ -n "$id" ] && break
    sleep 5
  done
  [ -n "$id" ] || die "GitHub didn't start its ${1%.yml} run: look at the Actions tab"
  say "Waiting for GitHub's ${1%.yml} run: $3"
  gh run watch "$id" --exit-status >/dev/null 2>&1 && { say "${1%.yml}: done"; return 0; }
  RUN_ID="$id"
  return 1
}

finish() {
  push origin HEAD
  wait_run site.yml "$(git rev-parse HEAD)" "the website, now with $V (a minute or two)" \
    || die "The Site run stopped (gh run view $RUN_ID): the release is out, but the site isn't. Run it again on GitHub"
  [ "${GLASS_NO_VERIFY:-}" = 1 ] || bash tools/verify-release.sh "$V"
}

case "${1:-}" in
  --finish)
    [ "$(git tag --points-at HEAD)" = "v$V" ] || die "HEAD isn't v$V: --finish ends a release that tools/release.sh started"
    if [ "$GH" = 1 ]; then gh release view "v$V" >/dev/null 2>&1 || die "GitHub has no release page for v$V yet: run its Release run again first"; fi
    finish
    exit 0 ;;
  --abandon)
    [ "$(git tag --points-at HEAD)" = "v$V" ] || die "HEAD isn't v$V's dated changelog: nothing to abandon"
    git merge-base --is-ancestor HEAD origin/main 2>/dev/null && die "v$V's changelog is on GitHub's main already: release a new version instead"
    git reset -q --keep HEAD~1
    say "CHANGELOG.md's lines are back under Unreleased; v$V stays unused. Fix it, raise versionCode and versionName, release again"
    exit 0 ;;
  "") ;;
  *) die "tools/release.sh [--finish | --abandon]" ;;
esac

# 1. checks
[ -z "$(git status --porcelain)" ] || die "Commit (or put away) the changes first"
git rev-parse -q --verify "refs/tags/v$V" >/dev/null && die "v$V is released already: bump versionCode and versionName in launcher/app/build.gradle"
[ -z "$(git ls-remote --tags origin "refs/tags/v$V" 2>/dev/null)" ] || die "GitHub has a v$V tag already: bump versionCode and versionName"
WAITING="$(python3 tools/changelog.py unreleased)"
[ "$WAITING" -gt 0 ] || die "Nothing waits under \"## Unreleased\" in CHANGELOG.md: write what's new there first"
python3 tools/parity.py >/dev/null || { python3 tools/parity.py | grep '✗'; die "Not in step (above): fix that first"; }

# 2. what's committed, checked on GitHub
push origin HEAD
wait_run check.yml "$(git rev-parse HEAD)" "both apps build and the tests pass (about 8 minutes)" \
  || die "GitHub's Check run failed (gh run view $RUN_ID --log-failed): fix it and run this again. Nothing was released"

# 3. dated and tagged, at one moment (the Release run checks the date is the tagged commit's)
NOW="$(date '+%Y-%m-%d %H:%M:%S %z')"
TITLE="$(python3 tools/changelog.py release "$V" "${NOW%% *}")" || die "CHANGELOG.md couldn't be dated (above)"
git add CHANGELOG.md
GIT_AUTHOR_DATE="$NOW" GIT_COMMITTER_DATE="$NOW" git commit -q -m "Glass $V: $TITLE"
GIT_COMMITTER_DATE="$NOW" git tag -a "v$V" -m "Glass $V: $TITLE"
say "CHANGELOG.md: $V, $TITLE ($(python3 tools/changelog.py latest | cut -d' ' -f2))"

# 4. the release itself
push origin "v$V"
wait_run release.yml "$(git rev-parse HEAD)" "build, sign, publish to the update service, release page (about 10 minutes)" || {
  echo "✗ GitHub's Release run stopped (gh run view $RUN_ID --log-failed). The TVs, the site and the README are as they were." >&2
  echo "  A setup problem (a secret, Cloudflare; docs/RUNBOOK.md): fix it, run it again on GitHub (gh run rerun $RUN_ID)," >&2
  echo "  then tools/release.sh --finish. The code at fault: tools/release.sh --abandon, fix it, raise the version, release." >&2
  exit 1
}

# 5 and 6. the site, the docs and the README follow, then every surface is checked
finish
