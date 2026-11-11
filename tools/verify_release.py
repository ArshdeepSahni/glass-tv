#!/usr/bin/env python3
"""One release, the same on every surface (tools/verify-release.sh runs this with the update service's address and key
from GLASS_HOME/.updates.env). Standard library, git, and the GitHub CLI (gh) when it's there.

  python3 tools/verify_release.py [VERSION]

  CHANGELOG.md        the version's section: its day and its lines
  the tag             v<version> on GitHub
  the release page    GitHub's: published on that day (here, in this computer's time zone), its notes the changelog's
  the update service  what a TV gets: that version, that day, those lines
  the website         the landing page and the docs say that version, the docs' changelog that day
  the README          GitHub's main has the README that's here (its template filled in: tools/parity.py)

Prints one line each, with nothing private in them (no address, no key, no account). Exits 1 when any differ.
"""
import datetime
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import changelog  # noqa: E402

UA = "Glass-Verify"


def run(*cmd):
    try:
        out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return out.stdout if out.returncode == 0 else None


def fetch(url, token=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Cache-Control": "no-cache"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except (urllib.error.URLError, OSError):
        return 0, b""


def main(argv):
    rels = changelog.releases()
    v = argv[1] if len(argv) > 1 else (rels[0].head if rels else "")
    sec = changelog.find(v)
    results = []

    def report(ok, what, words):
        results.append(ok)
        print(("✓ " if ok else "✗ " if ok is False else "• ") + what + ": " + words)

    if not sec or not sec.date:
        print("✗ CHANGELOG.md: no dated section for %s" % (v or "any release"))
        return 1
    day, items = sec.date, sec.items
    report(True, "CHANGELOG.md", "%s, %s, %d lines" % (v, changelog.human(day), len(items)))

    tag = run("git", "ls-remote", "--tags", "origin", "refs/tags/v" + v)
    report(bool(tag and tag.strip()) if tag is not None else None, "the tag",
           "v%s is on GitHub" % v if tag and tag.strip() else "v%s isn't on GitHub" % v if tag is not None else "couldn't ask GitHub")

    gh = run("gh", "auth", "status") is not None
    if gh:
        page = run("gh", "api", "repos/{owner}/{repo}/releases/tags/v" + v)
        if page is None:
            report(False, "the release page", "GitHub has none for v%s" % v)
        else:
            r = json.loads(page)
            when = r.get("published_at")
            local = datetime.datetime.fromisoformat(when.replace("Z", "+00:00")).astimezone().date() if when else None
            body = r.get("body") or ""
            missing = [i for i in items[:12] if i not in body]
            ok = bool(when) and not r.get("draft") and local == day and not missing
            words = "Glass %s, published %s" % (v, changelog.human(local) if local else "not yet")
            if local and local != day:
                words += ", but CHANGELOG.md says %s" % changelog.human(day)
            if missing:
                words += ", without %d of the changelog's lines" % len(missing)
            report(ok, "the release page", words)
    else:
        report(None, "the release page", "not checked: the GitHub CLI (gh) isn't signed in here")

    base, token = os.environ.get("GLASS_UPDATE_URL", "").rstrip("/"), os.environ.get("GLASS_UPDATE_TOKEN", "")
    if base and token:
        status, data = fetch(base + "/v1/manifest", token)
        try:
            m = json.loads(data) if status == 200 else {}
        except ValueError:
            m = {}
        ok = m.get("release") == v and m.get("date") == day.isoformat() and m.get("notes") == items[:12]
        if m:
            words = "a TV gets %s, dated %s%s" % (m.get("release"), m.get("date"),
                                                 "" if m.get("notes") == items[:12] else ", with other lines than the changelog's")
        else:
            words = "didn't answer (HTTP %d)" % status
        report(ok, "the update service", words)
    else:
        report(None, "the update service", "not checked: no GLASS_HOME/.updates.env here")

    site = os.environ.get("GLASS_SITE_URL", "").rstrip("/")
    if not site and gh:
        site = (run("gh", "repo", "view", "--json", "homepageUrl", "-q", ".homepageUrl") or "").strip().rstrip("/")
    if site:
        _, home = fetch(site + "/")
        _, docs = fetch(site + "/docs")
        home, docs = home.decode("utf-8", "replace"), docs.decode("utf-8", "replace")
        ver = 'class="ver">v%s<' % v
        dated = 'id="v%s"><header><span class="v">v%s</span><span class="latest">Latest</span><time datetime="%s">' % (
            v.replace(".", "-"), v, day.isoformat())
        ok = ver in home and ver in docs and dated in docs
        if ok:
            words = "the landing page and the docs say v%s, the docs' changelog %s" % (v, changelog.human(day))
        elif not home or not docs:
            words = "didn't answer"
        else:
            words = "it doesn't say v%s with %s yet (a deploy takes a minute or two: run this again)" % (v, changelog.human(day))
        report(ok, "the website", words)
    else:
        report(None, "the website", "not checked: the repository has no Website set (go-live.sh sets it)")

    if run("git", "fetch", "-q", "origin", "main") is None:
        report(None, "the README", "not checked: couldn't ask GitHub")
    else:
        same = run("git", "diff", "--quiet", "FETCH_HEAD", "--", "README.md")
        report(same is not None, "the README", "GitHub's is this one" if same is not None else "GitHub's differs from this one")

    if all(r is not False for r in results):
        print("✓ Glass %s (%s) is the same everywhere it was checked" % (v, changelog.human(day)))
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
