#!/usr/bin/env python3
"""The checks that keep Glass's public faces in step with each other and with the TV: run by site/build.py (so by every
Check and Site run on GitHub) and by tools/release.sh before it tags. Standard library only.

  python3 tools/parity.py          the repository
  python3 tools/parity.py DIST     the repository, and the site built into DIST (site/build.py)

  changelog   CHANGELOG.md is well formed, and launcher/app/build.gradle's version agrees with it
  pictures    no picture of the TV is older than what it shows: each was drawn after the last change to the launcher's
              files its scene mirrors and to the scenes' code (site/data/pictures.json, written by tools/scenes.py),
              and the README's after the site's they're made from (tools/readme_pictures.py)
  sections    the pictures' Settings has the TV's sections, in the TV's order (SettingsSheet.SECTIONS)
  named       every picture the site's data names is there (an entry without one would quietly be left off the site)
  readme      README.md is docs/readme/README.template.md filled in (tools/readme.py), and its pictures are there
  private     no addresses, MAC addresses, e-mail addresses or home folders in what's committed or built (only the
              documentation examples 192.168.1.20, 127.0.0.1 and 192.0.2.x)

Each failure says what to do. Nothing here prints anything private: what it finds is named by file and line only.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "updates"))
import changelog  # noqa: E402
import readme  # noqa: E402

TV = os.path.join(ROOT, "site", "assets", "tv")
PHONE = os.path.join(ROOT, "site", "assets", "phone")
DATA = os.path.join(ROOT, "site", "data")
RECORD = os.path.join(DATA, "pictures.json")
L = "launcher/app/src/main/java/com/gathin/tvhome/"


def rel(path):
    return os.path.relpath(path, ROOT)


def fingerprint(paths):
    """The same as tools/scenes.py and tools/readme_pictures.py: each file by its path and its bytes."""
    h = hashlib.sha256()
    for r in sorted(set(paths)):
        with open(os.path.join(ROOT, r), "rb") as f:
            h.update(r.encode() + b"\0" + hashlib.sha256(f.read()).hexdigest().encode() + b"\n")
    return h.hexdigest()


# ---- changelog ------------------------------------------------------------------------------------------------------
def gradle_version():
    with open(os.path.join(ROOT, "launcher", "app", "build.gradle"), encoding="utf-8") as f:
        m = re.search(r"""^\s*versionName\s+['"]([^'"]+)['"]""", f.read(), re.M)
    return m.group(1).split(" ")[0] if m else None


def check_changelog():
    bad = ["CHANGELOG.md: " + p for p in changelog.problems()]
    if bad:
        return bad
    v = gradle_version()
    rel_ = changelog.releases()
    u = changelog.unreleased()
    waiting = len(u.items) if u else 0
    if not v or not rel_:
        return bad
    key = lambda s: tuple(int(x) for x in s.split("."))
    try:
        newer, same = key(v) > rel_[0].key(), key(v) == rel_[0].key()
    except ValueError:
        return ["launcher/app/build.gradle's versionName %s isn't a version like 1.0.2" % v]
    if not newer and not same:
        bad.append("launcher/app/build.gradle says %s, but CHANGELOG.md has %s released: the version only goes up" % (v, rel_[0].head))
    if newer and not waiting:
        bad.append("launcher/app/build.gradle says %s, but nothing waits under \"## Unreleased\" in CHANGELOG.md to "
                   "release as %s: write what's new there" % (v, v))
    return bad


# ---- pictures -------------------------------------------------------------------------------------------------------
def check_pictures():
    try:
        with open(RECORD, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return ["site/data/pictures.json is missing: python3 tools/scenes.py, then python3 tools/readme_pictures.py"]
    bad, stale, readme_stale = [], [], []
    for name, e in sorted(data.get("pictures", {}).items()):
        missing = [p for p in e.get("from", []) if not os.path.isfile(os.path.join(ROOT, p))]
        if missing:
            bad.append("the picture %s was drawn from %s, which isn't there any more: fix SOURCES in tools/scenes.py"
                       % (name, ", ".join(missing)))
        elif fingerprint(e["from"]) != e.get("sha256"):
            stale.append(name)
    for out, e in sorted(data.get("readme", {}).items()):
        missing = [p for p in e.get("from", []) if not os.path.isfile(os.path.join(ROOT, p))]
        if missing or not os.path.isfile(os.path.join(ROOT, out)) or fingerprint(e["from"]) != e.get("sha256"):
            readme_stale.append(out)
    if stale:
        names = ", ".join(stale) if len(stale) <= 6 else "%s and %d more" % (", ".join(stale[:6]), len(stale) - 6)
        cmd = " ".join(stale) if len(stale) <= 6 else ""
        bad.append("drawn before the TV (or the scenes) changed: %s. Draw them again (python3 tools/scenes.py%s), look at "
                   "them, then python3 tools/readme_pictures.py" % (names, " " + cmd if cmd else ""))
    if readme_stale:
        bad.append("the README's pictures are older than the site's: %s. python3 tools/readme_pictures.py"
                   % ", ".join(readme_stale))
    return bad


def check_sections():
    """Settings' sections on the TV and in the pictures: the same names, in the same order."""
    try:
        java = open(os.path.join(ROOT, L + "SettingsSheet.java"), encoding="utf-8").read()
        js = open(os.path.join(ROOT, "tools", "scenes", "tv.js"), encoding="utf-8").read()
    except OSError:
        return []
    block = java[java.find("SECTIONS = {"):]
    block = block[:block.find("};")]
    tv = re.findall(r'\{"\w+", "\w+", "([^"]+)"\}', block)
    m = re.search(r"const SECTIONS = \[(.*?)\];", js, re.S)
    pics = re.findall(r"\['\w+', '([^']+)'", m.group(1)) if m else []
    if tv and tv != pics:
        return ["Settings on the TV has %s; the pictures' (tools/scenes/tv.js SECTIONS) has %s: make them the same, then "
                "python3 tools/scenes.py settings settings-speed" % (", ".join(tv), ", ".join(pics))]
    return []


def drawn(name, folder=TV):
    return (os.path.isfile(os.path.join(folder, name + "-light.jpg")) and os.path.isfile(os.path.join(folder, name + "-dark.jpg"))) \
        or os.path.isfile(os.path.join(folder, name + ".jpg"))


def check_named():
    """Every picture the site's data names: drawn (the site leaves out an entry whose picture isn't there)."""
    bad = []

    def walk(o, where, phone=False):
        if isinstance(o, dict):
            for k, v in o.items():
                if k in ("tv", "part", "pic") and isinstance(v, str):
                    if not drawn(v, PHONE if phone else TV):
                        bad.append("%s names the picture %s, which isn't drawn: python3 tools/scenes.py %s" % (where, v, v))
                elif k in ("light", "dark") and isinstance(v, str) and not drawn(v):
                    bad.append("%s names the picture %s, which isn't there" % (where, v))
                elif k == "video" and isinstance(v, str) and not (os.path.isfile(os.path.join(TV, v + ".mp4"))
                                                                  or os.path.isfile(os.path.join(TV, v + "-light.mp4"))):
                    bad.append("%s names the film %s, which isn't there" % (where, v))
                else:
                    walk(v, where, phone or k == "phones")
        elif isinstance(o, list):
            for v in o:
                walk(v, where, phone)

    for name in ("components", "screens", "asks"):
        try:
            with open(os.path.join(DATA, name + ".json"), encoding="utf-8") as f:
                walk(json.load(f), "site/data/%s.json" % name)
        except (OSError, ValueError):
            continue
    return bad


# ---- the README -----------------------------------------------------------------------------------------------------
def check_readme():
    bad = []
    b = readme.check()
    if b:
        bad.append(b)
    try:
        text = open(os.path.join(ROOT, "docs", "readme", "README.template.md"), encoding="utf-8").read()
    except OSError:
        return bad
    for src in sorted(set(re.findall(r'(?:src|srcset)="(docs/readme/[^"]+)"', text))):
        if not os.path.isfile(os.path.join(ROOT, src)):
            bad.append("the README shows %s, which isn't there: python3 tools/readme_pictures.py" % src)
    return bad


# ---- privacy --------------------------------------------------------------------------------------------------------
IPV4 = re.compile(r"(?<![\w.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?![\w.])")
MAC = re.compile(r"(?<![\w:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![\w:])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
HOME = re.compile(r"/Users/(?!Shared\b)[A-Za-z][\w.-]*|/home/(?!runner\b|claude\b|user\b)[a-z][\w.-]*/|C:\\\\Users\\\\[A-Za-z]")
IP_OK = re.compile(r"^(?:127\.0\.0\.1|0\.0\.0\.0|192\.168\.1\.20|255\.255\.255\.\d+|192\.0\.2\.\d+|198\.51\.100\.\d+|203\.0\.113\.\d+)$")
EMAIL_OK = re.compile(r"(?:@example\.(?:com|org|net)|\.invalid|\.example|noreply@anthropic\.com|@users\.noreply\.github\.com)$", re.I)
SKIP = re.compile(r"(?:^|/)(?:icons\.json|[^/]*\.(?:jpg|jpeg|png|webp|gif|ico|mp4|webm|avif|ttf|otf|woff2?|jar|apk|aab|keystore|jks|zip|gz|pyc|bin))$")
TEXT = re.compile(r"\.(?:html|md|txt|json|js|css|xml|svg|webmanifest|py|sh|java|kt|gradle|yml|yaml|properties|toml)$|(?:^|/)(?:glass|_headers|LICENSE|NOTICE)$")


def tracked():
    try:
        out = subprocess.run(["git", "-C", ROOT, "ls-files", "-z"], capture_output=True)
        if out.returncode == 0 and out.stdout:
            return [p for p in out.stdout.decode().split("\0") if p]
    except OSError:
        pass
    found = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "dist", ".cache", "__pycache__", "node_modules")]
        found += [os.path.relpath(os.path.join(base, f), ROOT) for f in files]
    return found


def scan(path, label):
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except (OSError, UnicodeDecodeError):
        return []
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        kinds = []
        if any(not IP_OK.match(m) for m in IPV4.findall(line)):
            kinds.append("an IP address")
        if MAC.search(line):
            kinds.append("a MAC address")
        if any(not EMAIL_OK.search(m) for m in EMAIL.findall(line)):
            kinds.append("an e-mail address")
        if HOME.search(line):
            kinds.append("a home folder")
        if kinds:
            out.append("%s:%d has %s: take it out (examples use 192.168.1.20 and ~)" % (label, n, " and ".join(kinds)))
    return out


def check_private(dist=None):
    bad = []
    for p in tracked():
        if SKIP.search(p) or not os.path.isfile(os.path.join(ROOT, p)):
            continue
        bad += scan(os.path.join(ROOT, p), p)
    if dist and os.path.isdir(dist):
        for base, _, files in os.walk(dist):
            for f in files:
                p = os.path.join(base, f)
                r = os.path.relpath(p, dist)
                if TEXT.search(r) and not SKIP.search(r):
                    bad += scan(p, "the built site's " + r)
    return bad[:40]


CHECKS = [("changelog", check_changelog), ("pictures", check_pictures), ("sections", check_sections),
          ("named", check_named), ("readme", check_readme), ("private", None)]


def run(dist=None, quiet=False):
    """Every check; True when all pass. Prints one line each (and what to do about any that fail)."""
    ok = True
    for name, fn in CHECKS:
        bad = check_private(dist) if name == "private" else fn()
        if bad:
            ok = False
            for b in bad:
                print("✗ %s: %s" % (name, b))
        elif not quiet:
            print("✓ %s" % name)
    return ok


if __name__ == "__main__":
    sys.exit(0 if run(sys.argv[1] if len(sys.argv) > 1 else None) else 1)
