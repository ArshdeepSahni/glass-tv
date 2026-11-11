#!/usr/bin/env python3
"""CHANGELOG.md, read and written the one way every tool shares (the site, the release list, the release script, the
checks). Standard library only.

  changelog.py check                 the file is well formed (exits 1 and says what's wrong when it isn't)
  changelog.py unreleased            how many lines wait under "## Unreleased"
  changelog.py latest                the newest release: "1.0.1 2026-11-11"
  changelog.py release VERSION [DAY] turns "## Unreleased" into "## VERSION" dated DAY (YYYY-MM-DD, default today on
                                     this computer), leaves an empty "## Unreleased" on top, and prints the title

The file: a short intro, then "## Unreleased", then each release, newest first:

  ## 1.0.1

  Glass on more TVs. 11 November 2026.

  - Starts on Glass even on TVs that bring back their own home at every start, like Xiaomi's

The line under the heading is the release's title and the day it was released; the "- " lines are what the TV and the
phone show as what's new. Under "## Unreleased" the title line is written without a day: the release script adds it,
so no date is ever typed by hand.
"""
import datetime
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "CHANGELOG.md")
UNRELEASED = "Unreleased"
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
DAY = re.compile(r"\s*(\d{1,2}) (%s) (\d{4})\.?\s*$" % "|".join(MONTHS))
VERSION = re.compile(r"^\d+(?:\.\d+){1,2}$")


class Section(object):
    def __init__(self, head, body):
        self.head = head.strip()
        self.body = body
        self.items = [l[2:].strip() for l in body.splitlines() if l.startswith("- ")]
        prose = [l.strip() for l in body.splitlines() if l.strip() and not l.startswith("- ")]
        self.line = prose[0] if prose else ""
        self.rest = " ".join(prose[1:])
        m = DAY.search(self.line)
        self.date = None
        if m:
            try:
                self.date = datetime.date(int(m.group(3)), MONTHS.index(m.group(2)) + 1, int(m.group(1)))
            except ValueError:
                self.date = None
        self.title = (self.line[:m.start()] if m else self.line).strip().rstrip(".").strip()

    @property
    def released(self):
        return self.head != UNRELEASED

    def key(self):
        return tuple(int(x) for x in self.head.split("."))


def read(path=PATH):
    with open(path, encoding="utf-8") as f:
        return f.read()


def parse(text):
    """(intro, [Section]) in the file's order."""
    parts = re.split(r"^## ", text, flags=re.M)
    sections = []
    for block in parts[1:]:
        head, _, body = block.partition("\n")
        sections.append(Section(head, body))
    return parts[0], sections


def unreleased(text=None):
    _, secs = parse(read() if text is None else text)
    return next((s for s in secs if not s.released), None)


def releases(text=None):
    _, secs = parse(read() if text is None else text)
    return [s for s in secs if s.released]


def find(version, text=None):
    return next((s for s in releases(text) if s.head == version), None)


def human(day):
    return "%d %s %d" % (day.day, MONTHS[day.month - 1], day.year)


def problems(text=None):
    """What's wrong with the file, in plain words (empty when nothing is)."""
    text = read() if text is None else text
    _, secs = parse(text)
    out = []
    if not secs or secs[0].head != UNRELEASED:
        out.append('"## Unreleased" has to be the first section (new lines go there)')
    if sum(1 for s in secs if s.head == UNRELEASED) > 1:
        out.append('"## Unreleased" is there more than once')
    seen = []
    for s in secs:
        if not s.released:
            continue
        if not VERSION.match(s.head):
            out.append('"## %s": a release heading is its version and nothing else, like "## 1.0.2"' % s.head)
            continue
        if not s.items:
            out.append("%s has no \"- \" lines" % s.head)
        if not s.title:
            out.append("%s has no title line (like \"Glass on more TVs. 11 November 2026.\")" % s.head)
        if s.date is None:
            out.append("%s has no day it was released on its title line (\"11 November 2026.\")" % s.head)
        seen.append(s)
    for newer, older in zip(seen, seen[1:]):
        if VERSION.match(newer.head) and VERSION.match(older.head) and newer.key() <= older.key():
            out.append("%s is listed above %s: newest first" % (newer.head, older.head))
        if newer.date and older.date and newer.date < older.date:
            out.append("%s (%s) is dated before %s (%s)" % (newer.head, human(newer.date), older.head, human(older.date)))
    heads = [s.head for s in seen]
    for h in set(heads):
        if heads.count(h) > 1:
            out.append("%s is there twice" % h)
    return out


def release(version, day, text=None):
    """The file with "## Unreleased" released as VERSION on DAY, and a new empty "## Unreleased" above it."""
    text = read() if text is None else text
    if not VERSION.match(version):
        raise ValueError("%s isn't a version (like 1.0.2)" % version)
    if find(version, text):
        raise ValueError("CHANGELOG.md has a %s section already: it's released" % version)
    u = unreleased(text)
    if u is None or not u.items:
        raise ValueError('nothing is waiting under "## Unreleased": write what\'s new there first ("- " lines)')
    if not u.title:
        raise ValueError('"## Unreleased" needs a title line above its "- " lines, like "Glass on more TVs."')
    if u.date is not None:
        raise ValueError('"## Unreleased" has a day on its title line: leave it out, this script writes it')
    newest = releases(text)
    if newest and VERSION.match(newest[0].head) and tuple(int(x) for x in version.split(".")) <= newest[0].key():
        raise ValueError("%s isn't newer than %s, the last release" % (version, newest[0].head))
    body = ["%s. %s." % (u.title, human(day)), ""]
    if u.rest.strip():
        body += [u.rest.strip(), ""]
    body += ["- %s" % i for i in u.items]
    new = "## %s\n\n## %s\n\n%s\n\n" % (UNRELEASED, version, "\n".join(body))
    start = re.search(r"^## %s[ \t]*$" % UNRELEASED, text, flags=re.M).start()
    end = text.find("\n## ", start + 1)
    if end < 0:
        return text[:start] + new.rstrip("\n") + "\n"
    return text[:start] + new + text[end + 1:]


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "check":
        bad = problems()
        for b in bad:
            print("✗ CHANGELOG.md: %s" % b)
        return 1 if bad else 0
    if cmd == "unreleased":
        u = unreleased()
        print(len(u.items) if u else 0)
        return 0
    if cmd == "latest":
        r = releases()
        if not r:
            return 1
        print("%s %s" % (r[0].head, r[0].date.isoformat() if r[0].date else ""))
        return 0
    if cmd == "release" and len(argv) in (3, 4):
        day = datetime.date.fromisoformat(argv[3]) if len(argv) == 4 else datetime.date.today()
        text = read()
        try:
            new = release(argv[2], day, text)
        except ValueError as e:
            print("✗ %s" % e, file=sys.stderr)
            return 1
        with open(PATH, "w", encoding="utf-8") as f:
            f.write(new)
        print(find(argv[2], new).title)
        return 0
    print(__doc__.strip(), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
