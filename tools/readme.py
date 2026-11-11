#!/usr/bin/env python3
"""README.md, made from docs/readme/README.template.md: the template with the site's address (__SITE__) and the
repository's owner (__OWNER__) filled in. It never prints either. Standard library only.

  python3 tools/readme.py                  makes README.md again, with the values README.md has now (found by matching
                                           it against the template it was made from: git's HEAD copy, else this one)
  python3 tools/readme.py --was TEMPLATE   the same, when README.md was made from another template file
  python3 tools/readme.py --check          exits 1 when README.md isn't the template filled in (tools/parity.py)
  GLASS_SITE_URL=... [GLASS_OWNER=...] python3 tools/readme.py    with these values instead (the first time)

So the README changes only through its template, in the same commit as what it describes, and anyone can make it
again without knowing (or seeing) the site's address.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")
TEMPLATE_REL = "docs/readme/README.template.md"
TEMPLATE = os.path.join(ROOT, TEMPLATE_REL)
HOLES = {"__SITE__": "site", "__OWNER__": "owner"}
VALUE = r"[^\s\"'<>()`]+"


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def pattern(template):
    """A regex that matches the template filled in, the same value wherever the same hole is."""
    out, seen = [], set()
    for part in re.split(r"(__SITE__|__OWNER__)", template):
        if part in HOLES:
            name = HOLES[part]
            out.append("(?P=%s)" % name if name in seen else "(?P<%s>%s)" % (name, VALUE))
            seen.add(name)
        else:
            out.append(re.escape(part))
    return re.compile("".join(out), re.S)


def values(readme, template):
    m = pattern(template).fullmatch(readme)
    return m.groupdict() if m else None


def salvage(readme, template):
    """The values from any line of README.md that is still a line of the template filled in (when other lines were
    changed by hand), or None."""
    found = {}
    lines = readme.splitlines()
    for t in template.splitlines():
        if not any(h in t for h in HOLES):
            continue
        rx = pattern(t)
        for l in lines:
            m = rx.fullmatch(l)
            if m:
                for k, v in m.groupdict().items():
                    found.setdefault(k, v)
                break
    wanted = {HOLES[h] for h in HOLES if h in template}
    return found if wanted <= set(found) else None


def fill(template, vals):
    for hole, name in HOLES.items():
        if hole in template:
            if not vals.get(name):
                raise ValueError("no value for %s" % hole)
            template = template.replace(hole, vals[name])
    return template


def head_template():
    try:
        out = subprocess.run(["git", "-C", ROOT, "show", "HEAD:" + TEMPLATE_REL], capture_output=True, text=True)
    except OSError:
        return None
    return out.stdout if out.returncode == 0 else None


def check():
    """'' when README.md is the template filled in, else what's wrong, in plain words."""
    if not os.path.isfile(TEMPLATE):
        return "%s isn't there" % TEMPLATE_REL
    if not os.path.isfile(README):
        return "README.md isn't there: python3 tools/readme.py makes it"
    if values(read(README), read(TEMPLATE)) is None:
        return ("README.md isn't %s filled in: change the template, never README.md, then python3 tools/readme.py"
                % TEMPLATE_REL)
    return ""


def main(argv):
    if "--check" in argv:
        bad = check()
        if bad:
            print("✗ " + bad)
            return 1
        print("✓ README.md is its template filled in")
        return 0
    template = read(TEMPLATE)
    vals = None
    if os.environ.get("GLASS_SITE_URL"):
        vals = {"site": os.environ["GLASS_SITE_URL"].rstrip("/"), "owner": os.environ.get("GLASS_OWNER", "")}
    elif os.path.isfile(README):
        readme = read(README)
        was = argv[argv.index("--was") + 1] if "--was" in argv else None
        tried = [t for t in ([read(was)] if was else []) + [head_template(), template] if t]
        for t in tried:
            if values(readme, t) is not None:
                vals = values(readme, t)
                break
        for t in tried if vals is None else []:
            vals = salvage(readme, t)
            if vals:
                break
    if vals is None:
        print("✗ README.md doesn't match its template, so its values can't be read from it: run it once with "
              "GLASS_SITE_URL set (the site's address, on the Site run's summary page)")
        return 1
    try:
        new = fill(template, vals)
    except ValueError as e:
        print("✗ %s: set it (GLASS_OWNER for __OWNER__)" % e)
        return 1
    if os.path.isfile(README) and read(README) == new:
        print("✓ README.md: already its template filled in")
        return 0
    with open(README + ".tmp", "w", encoding="utf-8") as f:
        f.write(new)
    os.replace(README + ".tmp", README)
    print("✓ README.md: made again from its template")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
