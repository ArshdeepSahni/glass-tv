#!/usr/bin/env python3
"""Builds the website from site/ into a folder ready to serve.

  site/build.py [OUT]              the real site (default site/dist): full HTML documents
  site/build.py --preview OUT      the same for a preview page whose host adds the document around each page

Each page is a run of folds, each at least one screen high, that the page snaps between; nothing scrolls inside a
fold (one that can't fit its screen grows, and the page scrolls through it: site.js). The chart and the terminals sit in mdxcn's frame; the steps, keys, endpoints, tree, diff and changelog
follow mdxcn without one.

Nothing that changes with a release is typed twice; it's read from the repository on every build:
  the version and the oldest Android it runs on      launcher/app/build.gradle (versionName, minSdk)
  what it's built with                               Gradle's wrapper, the Android plugin, Java's version
  how many tests                                     every @Test under launcher/ and assistant/
  what's new                                         CHANGELOG.md
  credits, with their links                          NOTICE.md (and, with GITHUB_TOKEN, the repository's people)
  the numbers measured on the TV                     site/data/bench.json (tools/glasscli bench)
  the screens, components, asks and the phone        site/data/*.json
  the installer's steps                              tools/glasscli/flows.py
  pictures                                           site/assets/tv: a name means name-light.jpg and name-dark.jpg
                                                     when both are there, else name.jpg; one that isn't there yet
                                                     leaves its entry out until tools/scenes.py draws it
  the installer's recordings                         site/assets/term: NAME.json (100 columns) and NAME-narrow.json
                                                     (44 columns, for phones)
The page's address (canonical links, the sitemap, the share card, the one-line installer) comes from GLASS_SITE_URL
when it's set. Icons are Lucide and Simple Icons, inlined from site/icons.json. With Pillow, every picture also gets
AVIF copies sized for phones and for wide screens (kept in site/.cache between builds), and a share card is drawn;
without it the JPEGs alone are served. Otherwise standard library only.
"""
import hashlib
import html
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.request

try:
    from PIL import Image, ImageDraw, ImageFont, features as pil_features
    AVIF_OK = pil_features.check("avif")
except Exception:   # Pillow isn't required
    Image = None
    AVIF_OK = False

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TV = os.path.join(HERE, "assets", "tv")
CACHE = os.path.join(HERE, ".cache")
ICONS = json.load(open(os.path.join(HERE, "icons.json"), encoding="utf-8"))
NAME = "Glass for Android TV"
WIDE = "(min-width: 1168px) 1040px, 92vw"   # how wide a TV picture shows at most: the frame, or the screen less its gutters


def read(*p):
    with open(os.path.join(ROOT, *p), encoding="utf-8") as f:
        return f.read()


def load(name, default):
    p = os.path.join(HERE, "data", name)
    if not os.path.exists(p):
        return default
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def version(project):
    g = read(project, "app", "build.gradle")
    name = re.search(r"versionName\s+'([^']+)'", g).group(1)
    code_ = int(re.search(r"versionCode\s+(\d+)", g).group(1))
    return name.split()[0], code_


ANDROID = {21: "5", 22: "5.1", 23: "6", 24: "7", 25: "7.1", 26: "8", 27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12",
           32: "12L", 33: "13", 34: "14", 35: "15", 36: "16"}


def min_android():
    m = re.search(r"minSdk\s+(\d+)", read("launcher", "app", "build.gradle"))
    return ANDROID.get(int(m.group(1)), "8") if m else "8"


def built_with():
    gradle = re.search(r"gradle-([\d.]+)-", read("launcher", "gradle", "wrapper", "gradle-wrapper.properties"))
    agp = re.search(r"com\.android\.application'\s+version\s+'([\d.]+)'", read("launcher", "build.gradle"))
    java = re.search(r"VERSION_(\d+)", read("launcher", "app", "build.gradle"))
    out = []
    if java:
        out.append("Java " + java.group(1))
    if gradle:
        out.append("Gradle " + gradle.group(1))
    if agp:
        out.append("Android Gradle Plugin " + ".".join(agp.group(1).split(".")[:2]))
    return ", ".join(out)


def tests():
    n = 0
    for project in ("launcher", "assistant"):
        for d, _, fs in os.walk(os.path.join(ROOT, project, "app", "src", "test")):
            for f in fs:
                if f.endswith((".java", ".kt")):
                    n += open(os.path.join(d, f), encoding="utf-8").read().count("@Test")
    return n


def repo_url():
    if os.environ.get("GLASS_REPO_URL"):
        return os.environ["GLASS_REPO_URL"].rstrip("/")
    if os.environ.get("GITHUB_REPOSITORY"):
        return "%s/%s" % (os.environ.get("GITHUB_SERVER_URL", "https://github.com"), os.environ["GITHUB_REPOSITORY"])
    try:
        u = subprocess.run(["git", "-C", ROOT, "remote", "get-url", "origin"], capture_output=True, text=True,
                           timeout=10).stdout.strip()
    except Exception:
        u = ""
    m = re.match(r"(?:git@github\.com:|https://(?:[^@/]+@)?github\.com/)([^/]+/[^/.]+)", u)
    return "https://github.com/" + m.group(1) if m else ""


def slug_of(url):
    m = re.match(r"https://github\.com/([^/]+/[^/]+)$", url or "")
    return m.group(1) if m else "OWNER/glass-tv"


def esc(s):
    return html.escape(s, quote=True)


def icon(name):
    ic = ICONS[name]
    if ic["k"] == "stroke":
        return ('<svg class="i i-%s" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
                'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">%s</svg>' % (name, ic["d"]))
    return '<svg class="i i-%s" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">%s</svg>' % (name, ic["d"])


def path(*parts, escape=True):
    """A path through the TV's menus, kept whole between its steps: Settings › About this TV › ..."""
    return '<span class="path">%s</span>' % icon("chevron-right").join(
        '<span>%s</span>' % (esc(p) if escape else p) for p in parts)


# ---- pictures: by name, one for each look when there are two, with AVIF copies when Pillow is here ------------------

def img_size(path_):
    """(width, height) of a JPEG or PNG, read from its header."""
    with open(path_, "rb") as f:
        data = f.read()
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        mk = data[i + 1]
        if mk in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        if mk in (0xD8, 0x01) or 0xD0 <= mk <= 0xD7:
            i += 2
            continue
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    return 0, 0


def pic(name, folder=TV):
    """'x' -> [(file, look)]: x-light.jpg and x-dark.jpg when both are there (look 'light', 'dark'), else the one that
    is (look None). [] when there's none yet."""
    l, d = name + "-light.jpg", name + "-dark.jpg"
    if os.path.exists(os.path.join(folder, l)) and os.path.exists(os.path.join(folder, d)):
        return [(l, "light"), (d, "dark")]
    for f in (name + ".jpg", l, d, name + ".png"):
        if os.path.exists(os.path.join(folder, f)):
            return [(f, None)]
    return []


MADE = []   # AVIF copies to put in the output: (cache path, published path)


def cache_path(src, rel, name):
    """Where an AVIF copy of src is kept between builds: named by what's in src, not when it changed, so a copy
    restored from an earlier run (whose files are all newer, fresh from git) is still used. A copy kept by the
    earlier way of naming them is moved over when it's still current."""
    with open(src, "rb") as f:
        key = hashlib.sha256(f.read()).hexdigest()[:12]
    cached = os.path.join(CACHE, rel, key, name)
    old = os.path.join(CACHE, rel, name)
    if not os.path.exists(cached) and os.path.exists(old) and os.path.getmtime(old) >= os.path.getmtime(src):
        os.makedirs(os.path.dirname(cached), exist_ok=True)
        os.replace(old, cached)
    KEPT.add(cached)
    return cached


KEPT = set()   # the cached copies this build used; the rest are cleared away at its end


def prune_cache():
    for root, _, names in os.walk(CACHE, topdown=False):
        for n in names:
            p = os.path.join(root, n)
            if p not in KEPT:
                os.remove(p)
        if root != CACHE and not os.listdir(root):
            os.rmdir(root)


def avif(rel, f):
    """AVIF copies of assets/<rel>/<f> at 768 and 1280 wide and at its own width: [(published path, width)]."""
    if not AVIF_OK:
        return []
    src = os.path.join(HERE, rel, f)
    w, h = img_size(src)
    out = []
    for width in sorted({min(768, w), min(1280, w), w}):
        name = f.rsplit(".", 1)[0] + ("" if width == w else ".%d" % width) + ".avif"
        cached = cache_path(src, rel, name)
        if not os.path.exists(cached):
            os.makedirs(os.path.dirname(cached), exist_ok=True)
            im = Image.open(src).convert("RGB")
            if width != w:
                im = im.resize((width, round(h * width / w)), Image.LANCZOS)
            im.save(cached + ".tmp", "AVIF", quality=64, speed=5)
            os.replace(cached + ".tmp", cached)
        MADE.append((cached, os.path.join(rel, name)))
        out.append((rel + "/" + name, width))
    return out


def picture(f, look, alt, rel="assets/tv", eager=False, sizes=WIDE, cls=""):
    w, h = img_size(os.path.join(HERE, rel, f))
    srcs = avif(rel, f)
    source = ('<source type="image/avif" srcset="%s" sizes="%s">' % (", ".join("%s %dw" % s for s in srcs), sizes)
              if srcs else "")
    return ('<picture%s>%s<img src="%s/%s" alt="%s" width="%d" height="%d"%s decoding="async"%s></picture>'
            % (' class="l-%s"' % look if look else "", source, rel, f, esc(alt), w, h,
               ' fetchpriority="high"' if eager else ' loading="lazy"', ' class="%s"' % cls if cls else ""))


def pictures(files, alt, rel="assets/tv", eager=False, sizes=WIDE, cls=""):
    return "".join(picture(f, look, alt, rel, eager, sizes, cls) for f, look in files)


def screen(name, alt, eager=False, sizes=WIDE):
    """A TV picture, both looks, in its hairline frame. '' when it isn't there yet."""
    files = pic(name)
    return '<div class="screen">%s</div>' % pictures(files, alt, eager=eager, sizes=sizes) if files else ""


def fit(inner, cls="fit"):
    """Whatever's inside, as large as the room left in its fold allows, at its own proportions."""
    return '<div class="stage"><div class="%s">%s</div></div>' % (cls, inner)


# ---- blocks, after mdxcn: the frame only round a chart or a terminal; the rest stand on the page -------------------

def framed(title, body, cls="", tag="div", label="", attrs=""):
    """A figure in mdxcn's frame: dashed, a + at each corner, its title set into the top edge."""
    return ('<%s class="frame%s"%s%s><span class="t">[ %s ]</span><span class="c" aria-hidden="true"></span>%s</%s>'
            % (tag, (" " + cls) if cls else "", ' aria-label="%s"' % esc(label) if label else "", attrs, esc(title),
               body, tag))


def tabs(name, label, items, auto=0, cls="", film=False):
    """items: (tab label, panel html). The first one is chosen; without script every panel but the first stays hidden,
    so the first one is always the one to read. auto: each panel shows this many ms, then the next (the line under the
    chosen tab fills as it goes); film: the next once the chosen panel's film has played through. Choosing one
    yourself stops it."""
    a = (' data-auto="film"' if film else (' data-auto="%d"' % auto if auto else ""))
    out = ['<div class="tabs%s" data-tabs%s>' % ((" " + cls) if cls else "", a),
           '<div class="tablist" role="tablist" aria-label="%s">' % esc(label)]
    for i, (t, _) in enumerate(items):
        out.append('<button class="tab" type="button" role="tab" id="tab-%s-%d" aria-controls="panel-%s-%d" '
                   'aria-selected="%s" tabindex="%d">%s<span class="tp" aria-hidden="true"></span></button>'
                   % (name, i, name, i, "true" if i == 0 else "false", 0 if i == 0 else -1, t))
    out.append('</div><div class="panels">')
    for i, (_, body) in enumerate(items):
        out.append('<div class="panel" role="tabpanel" id="panel-%s-%d" aria-labelledby="tab-%s-%d" tabindex="0"%s>'
                   '%s</div>' % (name, i, name, i, "" if i == 0 else " hidden", body))
    out.append("</div></div>")
    return "".join(out)


ICON_KEYS = {"Assistant": "mic"}


def key(k):
    """One key of the remote or the keyboard, as a small key: its name, or its glyph when it has one."""
    if k in ICON_KEYS:
        return '<span class="rk">%s<span class="sr-only">%s</span></span>' % (icon(ICON_KEYS[k]), esc(k))
    return '<span class="rk">%s</span>' % esc(k)


def kbd(keys_):
    """'hold Home', 'Menu', '← →': the keys as small keys, 'hold' and 'then' as quiet words between."""
    out, run = [], []
    for part in keys_.split(" "):
        if part in ("hold", "then", "or"):
            if run:
                out.append(key(" ".join(run)))
                run = []
            out.append('<span class="kw">%s</span>' % part.capitalize() if not out else '<span class="kw">%s</span>' % part)
        elif part in ("←", "→", "↑", "↓"):
            run.append(part)
        else:
            if run:
                out.append(key(" ".join(run)))
                run = []
            out.append(key(part))
    if run:
        out.append(key(" ".join(run)))
    return '<span class="kk">%s</span>' % "".join(out)


def keys(rows):
    """[(keys, what it does)] as mdxcn's keys: the key, then what it does."""
    return '<ul class="keys">%s</ul>' % "".join('<li>%s<span>%s</span></li>' % (kbd(k), d) for k, d in rows)


def flags(rows):
    """[(name html, what html)]: an option or a setting, then what it does."""
    return '<ul class="flags">%s</ul>' % "".join('<li><span>%s</span><span>%s</span></li>' % r for r in rows)


def table(rows, label=""):
    """[(thing html, answer html)] as a table of two columns."""
    body = "".join('<tr><th scope="row">%s</th><td>%s</td></tr>' % r for r in rows)
    return '<table class="table"%s><tbody>%s</tbody></table>' % (' aria-label="%s"' % esc(label) if label else "", body)


def rows_list(rows):
    """[(icon, title, detail, tag html)] as rows: an icon, a thing and what it is, a hairline between."""
    return '<ul class="rows">%s</ul>' % "".join(
        '<li>%s<span><b>%s</b><small>%s</small></span>%s</li>' % (icon(ic), t, d, '<span class="tag">%s</span>' % tag if tag else "<span></span>")
        for ic, t, d, tag in rows)


def steps(rows):
    """[(title, html)] as mdxcn's steps: 01, 02 ... in a column of their own."""
    width = max(2, len(str(len(rows))))
    body = "".join('<li><span class="sn" aria-hidden="true">%s</span><div class="sb"><h3>%s</h3>%s</div></li>'
                   % (str(i + 1).zfill(width), t, b) for i, (t, b) in enumerate(rows))
    return '<ol class="steps">%s</ol>' % body


def diff(rows):
    """[(sign, html)] as mdxcn's diff: + what comes back, − what goes, = what stays."""
    body = "".join('<li class="%s"><span class="sg" aria-hidden="true">%s</span><span>%s</span></li>'
                   % ({"+": "add", "-": "del", " ": "keep"}[s], {"+": "+", "-": "−", " ": "="}[s], t) for s, t in rows)
    return '<ul class="diff">%s</ul>' % body


def tree(root, rows):
    """root, [(name, what)] as mdxcn's tree: ├─ and └─ down the side."""
    body = ['<li class="root">%s</li>' % root]
    for i, (n, w) in enumerate(rows):
        body.append('<li><span class="tg" aria-hidden="true">%s</span><b>%s</b><span class="tw">%s</span></li>'
                    % ("└─" if i == len(rows) - 1 else "├─", n, w))
    return '<ul class="tree">%s</ul>' % "".join(body)


def callout(html_, kind="info"):
    """A note to read before going on: a glyph in its own column, then the words."""
    return '<div class="callout %s">%s<div>%s</div></div>' % (kind, icon("triangle-alert" if kind == "warn" else "info"), html_)


def more(summary, body):
    """More, folded away under a line that says what it is."""
    return '<details class="more-d"><summary>%s%s</summary><div class="dd">%s</div></details>' % (
        icon("chevron-right"), summary, body)


def sub(ic, text):
    return '<h3 class="sub">%s%s</h3>' % (icon(ic), text)


def codeblock(*lines):
    pre = "\n".join('<span class="pr">$ </span>%s' % esc(l) for l in lines)
    return ('<div class="code" data-copy="%s"><pre tabindex="0">%s</pre><button class="copy" type="button" aria-label="Copy">'
            '%s%s</button></div>' % (esc("\n".join(lines)), pre, icon("copy"), icon("check")))


def cmd(text, label="Copy the command"):
    return ('<div class="cmd" data-copy="%s"><span class="pr" aria-hidden="true">$</span><code tabindex="0">%s</code>'
            '<button class="copy" type="button" aria-label="%s">%s%s</button></div>'
            % (esc(text), esc(text), esc(label), icon("copy"), icon("check")))


# ---- the numbers: every measure as glyph bars, one format everywhere ----------------------------------------------------

def change(theirs, ours):
    """How much better ours is, the way people say it: '2.4×' when it's half or less, else '−32%'."""
    r = theirs / ours
    if r >= 1.5:
        return "%.1f×" % r
    return "−%d%%" % int(round((1 - ours / theirs) * 100))


def amount(v, unit):
    if unit == "MB":
        return ("%.1f" % v if v < 10 else "%d" % int(v + 0.5)) + " MB"
    if unit == "s":
        return "%.2f s" % v
    if unit == "%":
        return "%d%%" % round(v)
    return "%d %s" % (int(v + 0.5), unit)


def bar_group(big, what, ours, theirs, unit, worse=False):
    """One measure: its change, big, then Glass and the TV's own home as bars drawn with glyphs."""
    top = max(ours, theirs) or 1
    rows = "".join('<div class="r%s"><span class="w">%s</span><span class="track" style="--w:%.1f%%" aria-hidden="true">'
                   '<i></i></span><span class="v">%s</span></div>'
                   % (" me" if me else "", who, max(2.0, 100.0 * v / top), amount(v, unit))
                   for who, v, me in (("Glass", ours, True), ("TV's home", theirs, False)))
    return ('<div class="g%s"><p class="gl"><b>%s</b><span>%s</span></p>%s</div>'
            % (" worse" if worse else "", esc(big), esc(what), rows))


def measures(bench):
    """Every measure, by kind: [(kind, icon, [bar_group html])]. The first of memory, speed and smoothness are the
    landing page's three."""
    m, s, f, z = (bench.get(k, {}) for k in ("memory", "start", "frames", "size"))
    raw = bench.get("raw", {})
    kinds = []
    if m.get("glass_pss_mb"):
        g = [bar_group(change(m["stock_pss_mb"], m["glass_pss_mb"]), "less memory on its home", m["glass_pss_mb"],
                       m["stock_pss_mb"], "MB"),
             bar_group(change(m["stock_bg_pss_mb"], m["glass_bg_pss_mb"]), "less memory while you watch",
                       m["glass_bg_pss_mb"], m["stock_bg_pss_mb"], "MB")]
        avail = raw.get("mem_available_kb", {})
        if avail.get("glass") and avail.get("stock"):
            gm, tm = avail["glass"] / 1024.0, avail["stock"] / 1024.0
            g.append(bar_group("+%d MB" % int(gm - tm + 0.5), "more left for your apps", gm, tm, "MB"))
        kinds.append(("Memory", "memory-stick", g))
    if s.get("glass_cold_ms"):
        kinds.append(("Speed", "zap", [bar_group(change(s["stock_cold_ms"], s["glass_cold_ms"]), "faster to open from cold",
                                                 s["glass_cold_ms"] / 1000.0, s["stock_cold_ms"] / 1000.0, "s")]))
    if f.get("glass_p99_ms"):
        g = [bar_group(change(f["stock_p99_ms"], f["glass_p99_ms"]), "quicker at its slowest frame in 100",
                       f["glass_p99_ms"], f["stock_p99_ms"], "ms")]
        if f.get("glass_p95_ms"):
            g.append(bar_group(change(f["stock_p95_ms"], f["glass_p95_ms"]), "quicker at its slowest frame in 20",
                               f["glass_p95_ms"], f["stock_p95_ms"], "ms"))
        if f.get("glass_janky_pct") is not None:
            g.append(bar_group(change(f["stock_janky_pct"], f["glass_janky_pct"]), "frames over 16.7 ms",
                               f["glass_janky_pct"], f["stock_janky_pct"], "%"))
        if f.get("glass_p50_ms"):
            d = f["glass_p50_ms"] - f["stock_p50_ms"]
            g.append(bar_group(("%+d ms" % d) if d else "the same", "half the frames within", f["glass_p50_ms"],
                               f["stock_p50_ms"], "ms", worse=d > 0))
        kinds.append(("Smoothness", "gauge", g))
    if z.get("glass_apk_mb"):
        kinds.append(("Size", "package", [bar_group(change(z["stock_apk_mb"], z["glass_apk_mb"]), "smaller app",
                                                    z["glass_apk_mb"], z["stock_apk_mb"], "MB")]))
    return kinds


def numbers(bench):
    """The landing page's chart (the first measure of memory, speed and smoothness) and the docs' (every measure): the
    same chart, each measure once, its change large beside its bars."""
    kinds = measures(bench)
    first = [k[2][0] for k in kinds if k[0] in ("Memory", "Speed", "Smoothness")]
    u = bench.get("update", {})
    landing = framed("lower is better", '<div class="gs">%s</div><p class="bars-note">One 720p TV with 1 GB of memory and '
                     'Android TV 9, the middle of five runs. <a href="docs.html#performance">Every number</a></p>'
                     % "".join(first), "bars", label="Glass against the TV's own home") if first else ""
    f = bench.get("frames", {})
    note = (' Frames are over the same 18 presses of the remote: Glass drew %d, the TV\'s home %d; more is smoother.'
            % (f["glass_frames"], f["stock_frames"])) if f.get("glass_frames") else ""
    every = [g for k in kinds for g in k[2]]
    perf = framed("every measure", '<div class="gs">%s</div><p class="bars-note">One 720p TV with '
                  '1 GB of memory and Android TV 9; each home started the same way, the middle of five runs.%s</p>'
                  % ("".join(every), note), "bars all", label="Every measure, Glass against the TV's own home") if every else ""
    return {"NUMBERS": landing, "PERF": perf,
            "UPDATE_SECONDS": "%d" % round(u["seconds"]) if u.get("seconds") else "30"}


# ---- the screens, the films, the phone ---------------------------------------------------------------------------

def screens_tabs(screens):
    items = []
    for sc in screens:
        shot = screen(sc["pic"], sc["alt"])
        if shot:
            items.append((esc(sc["name"]), '<figure class="shot">%s<figcaption>%s</figcaption></figure>'
                          % (fit(shot), sc["caption"])))
    return tabs("screens", "Glass's screens", items, auto=5000, cls="fill")


# the remote's buttons: what the film shows for each part, and the docs' full list
REMOTE_KEYS = {
    "move": ("← →", "Move", "The arrows move along the dock. The app's name shows above it."),
    "menu": ("Menu", "Control Center", "Menu opens Control Center, over anything."),
    "hold-home": ("hold Home", "Open apps", "Hold Home for the apps that are open."),
    "home": ("Home", "Desktop", "Home takes you back to the desktop and closes what's open."),
    "assistant": ("Assistant", "Assistant", "The assistant button opens Glass Assistant, listening."),
}
REMOTE_ALL = [("move", "← → ↑ ↓", "Move along the dock and through every window"),
              ("home", "Home", "The desktop. Anything open closes."),
              ("hold-home", "hold Home", "The open apps, over the app you're in"),
              ("menu", "Menu", "Control Center, over any app"),
              ("assistant", "Assistant", "Glass Assistant, or Google's if you choose it"),
              (None, "Input", "The TV's sources, as a glass menu"),
              (None, "Volume", "Hold it for bigger steps"),
              (None, "hold OK", "Move, close or more, for what's chosen"),
              (None, "Back", "Closes what's open, one step at a time")]


def video_of(name):
    """[(film, poster, look)]: name-light.mp4 and name-dark.mp4 when both are there, else name.mp4."""
    l, d = name + "-light.mp4", name + "-dark.mp4"
    if os.path.exists(os.path.join(TV, l)) and os.path.exists(os.path.join(TV, d)):
        return [(l, name + "-light-poster.jpg", "light"), (d, name + "-dark-poster.jpg", "dark")]
    if os.path.exists(os.path.join(TV, name + ".mp4")):
        return [(name + ".mp4", name + "-poster.jpg", None)]
    return []


AV1 = 'video/webm; codecs="av01.0.05M.08"'


def videos(name, label, controls=False, loop=True, inview=False):
    """A film's <video>s, one for each look when there are two: AV1 first where there's a copy (a third of the size),
    H.264 for everything else. inview: plays, muted, while it's on screen."""
    out = []
    for src, poster, look in video_of(name):
        webm = src[:-4] + ".webm"
        sources = (('<source src="assets/tv/%s" type=\'%s\'>' % (webm, AV1)) if os.path.exists(os.path.join(TV, webm)) else "")
        sources += '<source src="assets/tv/%s" type="video/mp4">' % src
        out.append('<video%s data-poster="assets/tv/%s" muted playsinline%s%s%s preload="none" width="1280" height="720" '
                   'aria-label="%s">%s</video>'
                   % (' class="l-%s"' % look if look else "", poster, " loop" if loop else "",
                      " controls" if controls else "", " data-inview" if inview else "", esc(label), sources))
    return "".join(out)


FILM_LABEL = ("Glass on the TV as the remote's buttons are pressed: the arrows along the dock, Menu for Control Center, "
              "Home held for the open apps, Home from the weather back to the desktop, and the assistant button")


def remote_film(remote, docs=False):
    """The remote's film with its buttons as cues that follow it and start it from their part: on the landing page a row
    of them under it; in the docs every button of the remote beside it, the ones in the film among them."""
    if not remote or not video_of("remote"):
        return ""
    starts = {p["key"]: p["start"] for p in remote["parts"]}
    screen_ = ('<div class="screen">%s<button class="film-play" type="button" aria-label="Play or pause">%s%s</button></div>'
               % (videos("remote", FILM_LABEL), icon("pause"), icon("play")))
    if docs:
        rows = []
        for k, keys_, what in REMOTE_ALL:
            body = '%s<span class="kd">%s</span>' % (kbd(keys_), esc(what))
            if k in starts:
                rows.append('<li><button class="cue" type="button" data-t="%.2f" data-says="%s" aria-current="false">%s'
                            '</button></li>' % (starts[k], esc(what), body))
            else:
                rows.append('<li class="key-only"><span class="key">%s</span></li>' % body)
        return ('<figure class="film side" data-film>%s<ul class="keylist" aria-label="The remote\'s buttons">%s</ul></figure>'
                % (fit(screen_), "".join(rows)))
    cues = []
    for p in remote["parts"]:
        keys_, name, says = REMOTE_KEYS[p["key"]]
        cues.append('<li><button class="chip cue" type="button" data-t="%.2f" data-says="%s" aria-current="false">%s%s'
                    '</button></li>' % (p["start"], esc(says), kbd(keys_), esc(name)))
    first = REMOTE_KEYS[remote["parts"][0]["key"]][2]
    return ('<figure class="film" data-film>%s<ul class="chips cues" aria-label="The buttons in the film">%s</ul>'
            '<p class="film-now" aria-live="polite">%s</p></figure>' % (fit(screen_), "".join(cues), esc(first)))


def remind():
    """The reminder: its film when there is one (asked, then the note two minutes later), else the moment it's asked."""
    v = videos("remind", "Asked to remind you in 2 minutes to check the oven, the assistant sets a reminder; two "
                         "minutes later a note comes up at the top of the screen", inview=True)
    return '<div class="screen">%s</div>' % v if v else ""


def asks(data):
    """Things to ask, one line each; the TV answering, and a line under it."""
    items = []
    for a in data.get("asks", []):
        shot = (remind() if a.get("film") == "remind" else "") or screen(a["pic"], a["alt"])
        if not shot:
            continue
        items.append(('<b>%s</b>' % esc(a["ask"]),
                      '<figure class="shot">%s<figcaption>%s</figcaption></figure>' % (fit(shot), esc(a["cap"]))))
    if not items:
        return ""
    return tabs("ask", "Things to ask", items, auto=6500, cls="asks")


LEAD = 0.12   # a touch lands on the phone a moment before the TV answers


def pov_beats(pov, remote):
    """When each beat plays and where each touch lands, timed to when the TV answered each press (the film's own
    timeline, written by tools/scenes.py). A beat whose part the film doesn't have yet is left out."""
    parts = remote.get("parts", [])
    keys_ = [p["key"] for p in parts]
    out = []
    for b in pov["beats"]:
        if b.get("part") not in keys_:
            continue
        i = keys_.index(b["part"])
        presses = parts[i].get("presses") or []
        end = parts[i + 1]["start"] if i + 1 < len(parts) else remote.get("duration", parts[i]["start"] + 3)
        g = [{k: v for k, v in dict(x, at=round(presses[x["press"]] - LEAD, 3)).items() if k != "press"}
             for x in b["gestures"] if x.get("press", 0) < len(presses)]
        out.append({"t": parts[i]["start"], "end": end, "says": b["says"], "g": g, "label": b["label"], "icon": b["icon"]})
    return out


def phone_pov(pov, cues=True):
    """The phone remote from where you sit: the TV ahead, centred, playing its film; the phone in your hand below it,
    tilted away from you; each touch on its screen, a moment before the TV does what it asked."""
    if not pov:
        return ""
    film = pov["film"] if video_of(pov["film"]) else pov.get("fallback", "")
    if not film or not video_of(film):
        return ""
    shots = pic(pov["phone"], os.path.join(HERE, "assets", "phone"))
    beats = pov_beats(pov, load(film + ".json", {}))
    if not beats:
        return ""
    data = esc(json.dumps([{k: v for k, v in b.items() if k not in ("label", "icon")} for b in beats], separators=(",", ":")))
    cue_list = "".join('<li><button class="chip cue" type="button" data-b="%d" aria-current="%s">%s%s</button></li>'
                       % (i, "true" if i == 0 else "false", icon(b["icon"]), esc(b["label"])) for i, b in enumerate(beats))
    stage = ('<div class="stage"><div class="pov-stage"><div class="pov-tv"><div class="screen">%s</div></div>'
             '<div class="pov-hand"><div class="pov-phone"><div class="pscreen">%s<span class="touch" aria-hidden="true">'
             '</span><span class="ripple" aria-hidden="true"></span></div></div></div></div></div>'
             % (videos(film, "The TV ahead, answering the phone in your hand"),
                pictures(shots, "The phone remote in your hand: a touchpad, Back, Home, Glass and Type",
                         rel="assets/phone", sizes="(min-width: 900px) 280px, 46vw")))
    if not cues:
        return '<figure class="pov" data-pov="%s">%s</figure>' % (data, stage)
    return ('<figure class="pov" data-pov="%s">%s<ul class="chips cues" aria-label="What the phone does">%s</ul>'
            '<p class="film-now" aria-live="polite">%s</p></figure>' % (data, stage, cue_list, esc(beats[0]["says"])))


def remotes(remote, pov):
    items = [(icon("pointer") + "The TV's remote", remote_film(remote)), (icon("smartphone") + "Your phone", phone_pov(pov))]
    return tabs("rem", "The two remotes", [i for i in items if i[1]], film=True, cls="seg")


def remotes_docs(remote, pov):
    """The two remotes in one place: the TV's beside every one of its buttons, the phone beside how to connect it."""
    connect = steps([("Show the code", "<p>Press %s and choose <b>Phone</b>.</p>" % kbd("Menu")),
                     ("Scan it", "<p>The remote opens in your phone's browser. Nothing to install.</p>"),
                     ("Allow it on the TV", "<p>Once. The phone stays allowed until you remove it, on your home network "
                                            "only.</p>")])
    phone = phone_pov(pov)
    items = [(icon("pointer") + "The TV's remote", remote_film(remote, docs=True)),
             (icon("smartphone") + "Your phone", '<div class="phone-side">%s%s</div>' % (phone, connect) if phone else "")]
    return tabs("rdoc", "The two remotes", [i for i in items if i[1]], film=True, cls="seg")


# ---- the docs' components: kinds on one side, the chosen thing large on the other --------------------------------------

STRIP = "(min-width: 1100px) 300px, (min-width: 600px) 30vw, 46vw"


def visual(it):
    """(kind, html) for an item's picture, or ('', '') when it isn't there yet."""
    if "tv" in it:
        s = screen(it["tv"], it["alt"], sizes="(min-width: 1100px) 600px, 92vw")
        if s and it.get("box"):   # the screen, with the part marked on it: whole, where it sits
            x0, y0, x1, y1 = it["box"]
            s = s[:-len("</div>")] + ('<span class="mark" style="left:%.3f%%;top:%.3f%%;width:%.3f%%;height:%.3f%%" '
                                      'aria-hidden="true"></span></div>' % (x0 / 12.8, y0 / 7.2, (x1 - x0) / 12.8, (y1 - y0) / 7.2))
        return ("tv", fit(s)) if s else ("", "")
    if "wide" in it:   # a piece as wide as the screen (the bar): whole, edge to edge
        files = pic(it["wide"])
        if not files:
            return "", ""
        return "wide", ('<div class="stage"><div class="wide">%s</div></div>'
                        % pictures(files, it["alt"], sizes="(min-width: 1100px) 600px, 92vw"))
    if "part" in it:
        files = pic(it["part"])
        return ("part", fit(pictures(files, it["alt"], sizes="(min-width: 1100px) 600px, 92vw"), "part")) if files else ("", "")
    if "parts" in it:
        got = [pictures(pic(p["pic"]), p["alt"], sizes="(min-width: 1100px) 600px, 92vw") for p in it["parts"]]
        if not all(got):
            return "", ""
        return "part", fit("".join(got), "part parts")
    if "strip" in it and it.get("id") == "three-sizes":   # one picture, and a switch between its sizes
        items = []
        for e in it["strip"]:
            s = screen(e["pic"], "%s: %s" % (it["alt"], e["cap"]), sizes="(min-width: 1100px) 600px, 92vw")
            if s:
                items.append((esc(e["cap"]), fit(s)))
        if not items:
            return "", ""
        return "sizes", tabs("size", "Widget size", items, auto=2600, cls="seg sizes")
    if "strip" in it:
        figs = []
        for e in it["strip"]:
            if "light" in e:
                files = [(f, look) for f, look in ((e["light"] + ".jpg", "light"), (e["dark"] + ".jpg", "dark"))
                         if os.path.exists(os.path.join(TV, f))]
                im = '<div class="screen">%s</div>' % pictures(files, "%s: %s" % (it["alt"], e["cap"]), sizes=STRIP) if files else ""
            else:
                im = screen(e["pic"], "%s: %s" % (it["alt"], e["cap"].strip("“”")), sizes=STRIP)
            if im:
                figs.append('<figure>%s<figcaption>%s%s</figcaption></figure>'
                            % (im, esc(e["cap"]), '<span class="sub">%s</span>' % esc(e["sub"]) if e.get("sub") else ""))
        if not figs:
            return "", ""
        return "strip", fit("".join(figs), "grid n%d" % len(figs))
    if "video" in it:
        v = videos(it["video"], it["alt"], controls=True, loop=False)
        return ("tv", fit('<div class="screen">%s</div>' % v)) if v else ("", "")
    if "phones" in it:
        got = [(pictures(pic(p["pic"], os.path.join(HERE, "assets", "phone")), "%s: %s" % (it["alt"], p["cap"]),
                         rel="assets/phone", sizes="180px"), p) for p in it["phones"]]
        got = [(g, p) for g, p in got if g]
        if not got:
            return "", ""
        return "phones", fit("".join('<figure>%s<figcaption>%s</figcaption></figure>' % (g, esc(p["cap"])) for g, p in got),
                             "phones n%d" % len(got))
    return "", ""


def components(data):
    nav, views, first, names = [], [], True, []
    for gi, g in enumerate(data.get("groups", [])):
        rows = []
        for ii, it in enumerate(g["items"]):
            kind, v = visual(it)
            if not v and not it.get("list"):
                continue
            vid = "cmp-%d-%d" % (gi, ii)
            body = '<p>%s</p>' % it["text"] if it.get("text") else ""
            if it.get("list"):
                parts = [re.match(r"<b>(.*?)</b>:?\s*(.*)", x) for x in it["list"]]
                body += flags([("<b>%s</b>" % p.group(1), p.group(2)) for p in parts if p])
                kind = "words"
            rows.append('<li><button class="item" type="button" data-view="%s" aria-controls="%s" aria-pressed="%s">%s'
                        '<span>%s</span></button></li>' % (vid, vid, "true" if first else "false", icon(it["icon"]),
                                                           esc(it["title"])))
            views.append('<figure class="view %s" id="%s"%s>%s<figcaption><h3>%s%s</h3>%s</figcaption></figure>'
                         % (kind, vid, "" if first else " hidden", v, icon(it["icon"]), esc(it["title"]), body))
            first = False
        if rows:
            names.append((len(names), g["name"]))
            nav.append('<li class="grp%s"><button class="grp-b" type="button" aria-expanded="%s">%s<span>%s</span>'
                       '<span class="count">%d</span></button><ul class="items">%s</ul></li>'
                       % (" open" if gi == 0 else "", "true" if gi == 0 else "false", icon(g["icon"]), esc(g["name"]),
                          len(rows), "".join(rows)))
    pick = '<label class="grp-pick"><span class="sr-only">Kind of component</span><select>%s</select>%s</label>' % (
        "".join('<option value="%d">%s</option>' % (i, esc(n)) for i, n in names), icon("chevron-right"))
    return ('<div class="browser" data-browser><nav class="cats" aria-label="Components">%s<ul class="grps">%s</ul></nav>'
            '<div class="views">%s</div></div>' % (pick, "".join(nav), "".join(views)))


# ---- the installer's recordings, and code to copy ------------------------------------------------------------------

STEP_ICONS = {"computer": "laptop", "tools": "wrench", "find": "search", "allow": "shield-check",
              "assistant": "sparkles", "build": "hammer", "install": "download", "home": "house", "code": "git-branch",
              "tv": "tv", "apps": "layout-grid", "remove": "undo-2"}


def steps_from_flows(var):
    m = re.search(r"%s = \[(.*?)\]\n" % var, read("tools", "glasscli", "flows.py"), re.S)
    return re.findall(r'\("(\w+)", "([^"]+)"\)', m.group(1)) if m else []


def rec(name):
    p = os.path.join(HERE, "assets", "term", name + ".json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def chapters(name, var):
    """Where each step starts in a recording: the first frame whose top line says 'N of M'."""
    d = rec(name)
    steps_ = steps_from_flows(var) if var else []
    if not d or not steps_:
        return []
    at = {}
    for t, rows in d["frames"]:
        top = "".join(r[0] for r in rows.get("0", []))
        mm = re.search(r"(\d+) of (\d+)", top)
        if mm and int(mm.group(1)) not in at:
            at[int(mm.group(1))] = t
    return [{"t": at[i + 1], "name": n, "key": k} for i, (k, n) in enumerate(steps_) if (i + 1) in at]


def term(name, title, var=None, label="", show_chapters=True, step_line=True):
    """A recording of the installer in mdxcn's frame, as large as its fold allows: the full 100 columns where there's
    room, the 44-column recording on phones. With steps, a chip for each to jump to it, the one it's on lit (and, in
    the docs, a line saying which it is)."""
    wide, narrow = rec(name), rec(name + "-narrow")
    if not wide:
        return ""
    ch = chapters(name, var)
    chn = {c["key"]: c["t"] for c in chapters(name + "-narrow", var)} if narrow else {}
    attrs = ' data-term="assets/term/%s.json"' % name
    if narrow:
        attrs += ' data-term-narrow="assets/term/%s-narrow.json"' % name
    attrs += ' style="--cols:%d;--rows:%d"' % (wide["w"], wide["h"])
    box = framed(title, '<pre class="term-screen" role="img" tabindex="0" aria-label="%s"></pre>'
                        '<div class="term-ctl"><button class="play" type="button" aria-label="Play">%s%s%s</button>'
                        '<div class="scrub" role="slider" tabindex="0" aria-label="Position" aria-valuemin="0" '
                        'aria-valuemax="0" aria-valuenow="0"><span class="scrub-fill"></span><span class="scrub-knob">'
                        '</span></div><span class="term-time">0:00 / 0:00</span>'
                        '<div class="speed" role="group" aria-label="Speed"><button type="button" data-speed="0.5" '
                        'aria-pressed="false">0.5×</button><button type="button" data-speed="1" aria-pressed="true">1×'
                        '</button><button type="button" data-speed="2" aria-pressed="false">2×</button></div></div>'
                 % (esc(label), icon("play"), icon("pause"), icon("rotate-ccw")), "term", attrs=attrs)
    out = '<div class="term-wrap"><div class="term-stage">%s</div>' % box
    if ch and show_chapters:
        buttons = "".join('<li><button class="chip" type="button" data-t="%d"%s data-name="%s">%s%s</button></li>'
                          % (c["t"], ' data-tn="%d"' % chn[c["key"]] if c["key"] in chn else "", esc(c["name"]),
                             icon(STEP_ICONS.get(c["key"], "check")), esc(c["name"])) for c in ch)
        if step_line:
            out += '<p class="term-step" aria-live="polite">&nbsp;</p>'
        out += '<ol class="chips chapters" aria-label="Jump to a step">%s</ol>' % buttons
    elif ch:
        out += '<ol class="chapters" hidden>%s</ol>' % "".join(
            '<li><button type="button" data-t="%d"%s data-name="%s" tabindex="-1">%s</button></li>'
            % (c["t"], ' data-tn="%d"' % chn[c["key"]] if c["key"] in chn else "", esc(c["name"]), esc(c["name"]))
            for c in ch)
    return out + "</div>"


def cli_tabs():
    def panel(says, inner):
        return '<div class="cmd-panel"><p class="prose">%s</p>%s</div>' % (says, inner)
    k = keys([("↑ ↓", "Choose"), ("Enter", "OK"), ("Esc", "Back"), ("R", "Try a stopped step again"),
              ("S", "Skip a step you don't need"), ("L", "The last lines of the log"), ("Q", "Stop. It asks first.")])
    o = flags([("<code>--tv 192.168.1.20</code>", "Use this address for the TV"),
               ("<code>--yes</code>", "The recommended answer to every question"),
               ("<code>--plain</code>", "Plain lines instead of the full screen"),
               ("<code>--no-color</code>", "No colour"),
               ("<code>./glass demo</code>", "The installer, with nothing really happening"),
               ("<code>~/.glass</code>", "Your signing key, the assistant's key, the build tools and the logs")])
    items = [(icon("refresh-cw") + "Update", panel("<code>./glass update</code> puts the newest Glass on the TV. "
                                                   "Every setting stays.",
                                                   term("update", "./glass update", "UPDATE_STEPS", "The installer updating "
                                                        "Glass", show_chapters=False))),
             (icon("gauge") + "Status", panel("<code>./glass status</code> shows how the TV and Glass are doing.",
                                              term("status", "./glass status", None, "Glass and the TV at a glance"))),
             (icon("wrench") + "Doctor", panel("<code>./glass doctor</code> checks twelve things and offers to fix what's off. "
                                               "Nothing changes without asking.",
                                               term("doctor", "./glass doctor", None, "Twelve checks, two things off, both "
                                                    "fixed"))),
             (icon("undo-2") + "Restore", panel("<code>./glass restore</code> removes Glass. The TV is as it was.",
                                                term("restore", "./glass restore", "RESTORE_STEPS", "Glass removed, the "
                                                     "TV's own home back", show_chapters=False))),
             (icon("keyboard") + "Keys", '<div class="cmd-panel"><p class="prose">In the installer\'s screens.</p>%s</div>' % k),
             (icon("sliders-horizontal") + "Options", '<div class="cmd-panel"><p class="prose">After <code>./glass</code> or any '
                                                      'command.</p>%s</div>' % o)]
    return tabs("cli", "The installer's commands", items)


# ---- the docs' other folds -------------------------------------------------------------------------------------------

CHAPTERS = [("Start", [("requirements", "list-checks", "Requirements"), ("setup", "download", "Set up"),
                       ("installer", "square-terminal", "The installer"), ("commands", "terminal", "Commands")]),
            ("Using Glass", [("components", "blocks", "Components"), ("remotes", "pointer", "The remotes")]),
            ("More", [("updates", "refresh-cw", "Updates"), ("privacy", "shield", "Privacy"),
                      ("performance", "gauge", "Performance"), ("specs", "cpu", "Specifications"),
                      ("trouble", "life-buoy", "Troubleshooting"), ("restore", "undo-2", "Remove Glass"),
                      ("changelog", "history", "Changelog"), ("credits", "heart", "Credits")])]


def rail():
    """The docs' contents: down the left on wide screens, a sheet from the bar's Contents on narrow ones."""
    out = []
    for group, chapters_ in CHAPTERS:
        out.append('<p class="rg">%s</p><ol>%s</ol>' % (esc(group), "".join(
            '<li><a href="#%s">%s<span>%s</span></a></li>' % (a, icon(ic), esc(t)) for a, ic, t in chapters_)))
    return '<nav class="rail" id="rail" aria-label="Docs contents">%s</nav>' % "".join(out)


def requirements(min_and):
    return rows_list([("tv", "An Android TV", "Android TV %s or newer, any size" % min_and, ""),
                      ("laptop", "A Mac or a Linux computer", "With Python 3 and 4 GB free. On Windows, use WSL.", ""),
                      ("wifi", "One network", "The TV and the computer on the same Wi-Fi", ""),
                      ("clock", "About 15 minutes", "The first time. A couple after that.", ""),
                      ("lock", "Root, or unlocking anything", "Glass installs like any app, through the TV's own "
                                                              "debugging. Your warranty stays.",
                       icon("check") + "Not needed")])


def setup_steps(install, clone):
    s = steps([("Turn on debugging on the TV",
                "<p>In %s, press %s on <b>Build</b> seven times. Then in %s, turn on <b>USB debugging</b>, and "
                "<b>Network debugging</b> if it's there.</p>"
                % (path("Settings", "Device Preferences", "About"), kbd("OK"), path("Developer options"))),
               ("Run the installer", "<p>In the Terminal on a Mac or Linux computer, on the same Wi-Fi:</p>%s"
                '<p class="or">Or with git:</p>%s' % (cmd(install), cmd(clone, "Copy the git command"))),
               ("Allow this computer", "<p>When the TV asks <b>Allow USB debugging?</b>, choose <b>Always allow from "
                                       "this computer</b>.</p>"),
               ("Allow Glass's helper", "<p>The TV asks once more, for <code>glass@tv</code>. Choose <b>Always allow</b>: "
                                        "it installs updates, and lets your phone press keys inside apps.</p>")])
    return (s + callout("<b>Back up <code>~/.glass/glass.keystore</code>.</b> Updates only install with it.", "warn")
            + more("The assistant's AI key, optional",
                   "<p>The installer asks for a free key for the assistant: Groq, Cerebras, OpenRouter or Cloudflare. "
                   "Skip it and TV commands still work offline; add one later with <code>./glass setup</code>.</p>"))


def updates_docs(seconds):
    own = steps([("Fork Glass on GitHub", "<p>In your fork, run <code>bash tools/set-secrets.sh</code> and commit the "
                                          "file it writes.</p>"),
                 ("Run <code>./glass update</code> once", "<p>The TV learns where your updates come from.</p>"),
                 ("To release", "<p>Raise the version, add a section to <code>CHANGELOG.md</code>, run "
                                "<code>tools/release.sh</code>.</p>")])
    return ('<div class="duo"><div class="stack">%s%s<p class="prose">Builds the newest Glass and puts it on the TV. Every '
            'setting stays. About 2 minutes.</p></div><div class="stack">%s<p class="prose">%s, then <b>Install</b>. About '
            '%s seconds. It needs your own update service.</p></div></div>%s'
            % (sub("laptop", "From your computer"), cmd("./glass update"), sub("tv", "From the TV"),
               path("Settings", "About this TV", "Check for updates"), seconds, more("Your own update service", own)))


def privacy():
    leaves = '<ul class="endpoints">%s</ul>' % "".join(
        '<li><span class="m">%s</span><code class="ep">%s</code><span class="ew"><b>%s</b>%s</span></li>' % r for r in (
            ("GET", "api.open-meteo.com", "The weather", "While it's on screen"),
            ("POST", "your AI service", "Your questions", "When you ask. With Reads the screen on, what's on it too."),
            ("GET", "your update service", "Update checks", "When you press Check"),
            ("LAN", ":8765", "The phone remote", "Never leaves your home network")))
    kept = ('<p class="prose">Settings, the phones you allowed, typing suggestions (never from passwords), three days of '
            'what you watched, and the photos you sent.</p>')
    mine = tree("<code>~/.glass</code>", [("glass.keystore", "your signing key"), (".env", "the assistant's AI key"),
                                          ("toolchain/", "Java and the Android SDK"), ("logs/", "what the installer did")])
    return ('<div class="stack">%s%s</div><div class="duo"><div class="stack">%s%s</div><div class="stack">%s%s'
            '<p class="prose">The AI key goes into the assistant on your TV, so don\'t share your copy of Glass.</p></div></div>'
            % (sub("arrow-up-right", "What leaves the TV, only when you use it"), leaves, sub("tv", "Kept on the TV"), kept,
               sub("laptop", "Kept on your computer"), mine))


def specs(fills):
    glass = table([("Glass", "<code>com.gathin.tvhome</code> v%s%s" % (fills["GLASS_VERSION"],
                                                                      ", " + fills["GLASS_SIZE"] if fills["GLASS_SIZE"] else "")),
                   ("Glass Assistant", "<code>com.gathin.glass.assistant</code> v%s%s"
                    % (fills["ASSISTANT_VERSION"], ", " + fills["ASSISTANT_SIZE"] if fills["ASSISTANT_SIZE"] else "")),
                   ("Runs on", "Android TV %s and newer" % fills["MIN_ANDROID"]),
                   ("Screen", "Laid out at 1280 × 720, scaled to any TV"),
                   ("Fonts on the TV", "Inter, Instrument Serif and Outfit"),
                   ("Built with", "%s, shrunk with R8" % fills["BUILT_WITH"]),
                   ("Tests", "%s unit tests, run on every change" % fills["TESTS"])], "Glass")
    perms = table([("Internet", "The weather, the assistant, updates, album art"),
                   ("Wi-Fi and location", "Nearby Wi-Fi in Control Center. Android shows it only with Location on: Glass uses the "
                                           "TV-only kind, with nothing sent to Google."),
                   ("Bluetooth", "The Bluetooth switch, and sending the sound to a device"),
                   ("Storage", "Files: USB drives, downloads, photos"),
                   ("Close background apps", "Freeing memory"),
                   ("Install apps", "Its own updates, when its helper isn't running"),
                   ("Exact alarms", "Reminders and the sleep timer"),
                   ("System settings", "The screen saver, the keyboard, the assistant button, Location and sleep")], "Permissions")
    runs = table([("Remote helper", "An accessibility service for Home, hold Home and Menu, Glass's panels over apps, and "
                                    "Brightness on TVs that keep their backlight to themselves"),
                  ("Keyboard", "Glass's input method"),
                  ("Screen saver", "The one you chose"),
                  ("Media listener", "What apps are playing, for Now playing. It reads no notifications."),
                  ("Phone remote", "A small web server on port 8765, on your home network"),
                  ("Glass's helper", "<code>glass@tv</code>, through the TV's own debugging. Only on the TV, only for "
                                     "Glass. It stops after 30 idle minutes.")], "What runs on the TV")
    return tabs("spec", "Specifications", [(icon("cpu") + "Glass", glass), (icon("lock") + "Permissions, and why", perms),
                                           (icon("layers") + "What runs on the TV", runs)])


def trouble():
    return table([("It can't find the TV", "Check debugging is on and both are on the same Wi-Fi. Or give it the address "
                                           "from %s: <code>./glass setup --tv 192.168.1.20</code>" % path("Settings", "Network")),
                  ("The TV never asked to allow", "Turn <b>USB debugging</b> off and on, choose <b>Revoke USB debugging "
                                                  "authorizations</b>, then <code>./glass setup</code>."),
                  ("“The TV has another copy of Glass”", "It was made on another computer. Choose <b>Remove it and "
                                                         "install this one</b>, or update from that computer to keep its "
                                                         "settings."),
                  ("Home or the keyboard is the TV's own", "<code>./glass doctor</code> gives both back to Glass."),
                  ("Bluetooth won't come on", "Restart the TV from its own menu. <code>./glass doctor</code> spots it."),
                  ("No networks in Wi-Fi", "<b>Show the networks</b> turns on Location, which Android needs."),
                  ("The phone can't press keys in apps", "%s, then allow <code>glass@tv</code>."
                   % path("Settings", "Remote & keyboard", "Phone remote helper")),
                  ("A build stopped", "Press %s to try again, or %s for the log." % (kbd("R"), kbd("L"))),
                  ("<code>./glass</code> says it needs Python", "On a Mac, run <code>xcode-select --install</code>. On "
                                                                "Linux, install <code>python3</code> with your package "
                                                                "manager. Then run it again.")], "Troubleshooting")


def restore_fold():
    d = diff([("+", "The TV's own home"), ("+", "The TV's own keyboard"), ("+", "The TV's own assistant button"),
              ("-", "Glass"), ("-", "Glass Assistant"), (" ", "Your apps, and everything in them")])
    return ('<div class="duo"><div class="stack">%s<p class="prose">The TV\'s own home, keyboard and assistant button come '
            'back, then Glass goes. Your apps stay. Restart the TV from its own menu afterwards, not the wall switch, so it '
            'keeps the changes.</p><p class="prose">To set Glass up again later, run <code>./glass</code>.</p></div>'
            '<div class="stack">%s%s</div></div>' % (cmd("./glass restore"), sub("git-branch", "What changes"), d))


# ---- what's new, and who made it ------------------------------------------------------------------------------------

def inline(s):
    s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
    s = re.sub(r"(?<!<code>)\./glass\b", "<code>./glass</code>", s)
    return re.sub(r"([A-Z][\w ]+?(?: › [A-Z][\w ]+)+)", lambda m: path(*m.group(1).split(" › "), escape=False), s)


def changelog():
    """mdxcn's changelog: each release, newest first, its changes with a + each."""
    out = []
    blocks = re.split(r"^## ", read("CHANGELOG.md"), flags=re.M)[1:]
    for n, block in enumerate(blocks):
        head, _, body = block.partition("\n")
        items = re.findall(r"^- (.+)$", body, flags=re.M)
        text = " ".join(l.strip() for l in body.splitlines() if l.strip() and not l.startswith("- "))
        date = re.search(r"(\d{1,2} [A-Z][a-z]+ \d{4})", text)
        text = re.sub(r"\s*\d{1,2} [A-Z][a-z]+ \d{4}\.?", "", text).strip()
        ver = head.strip()
        out.append('<article class="release" id="v%s"><header><span class="v">v%s</span>%s%s</header>%s%s</article>'
                   % (esc(ver.replace(".", "-")), esc(ver), '<span class="latest">Latest</span>' if n == 0 else "",
                      '<time>%s</time>' % esc(date.group(1)) if date else "",
                      "<p>%s</p>" % inline(esc(text)) if text else "",
                      '<ul class="changes">%s</ul>' % "".join('<li><span class="cg" aria-hidden="true">+</span><span>%s</span>'
                                                             '</li>' % inline(esc(i)) for i in items) if items else ""))
    return "".join(out)


CREDIT_ICONS = (("font", "type"), ("icon", "shapes"), ("mark", "shapes"), ("picture", "image"), ("librar", "package"),
                ("weather", "cloud-sun"), ("location", "globe"), ("ai", "sparkles"))


def credit_cards():
    """NOTICE.md's entries as cards with their links: (bundled cards, service cards)."""
    text = read("NOTICE.md")
    parts = re.split(r"\n(?=Services )", text, maxsplit=1)
    out = []
    for part in parts:
        cards = []
        for m in re.finditer(r"^- \*\*(.+?)\*\* \(([^)]+)\):(.*?)(?=^- |\Z)", part, flags=re.M | re.S):
            name, rest = m.group(1), " ".join(m.group(3).split())
            what = re.sub(r",?\s*`[^`]*`", "", m.group(2)).strip()
            url = re.search(r"https?://[^\s)]+", rest)
            lic = re.search(r"(SIL Open Font License|MIT|ISC|CC0|CC BY \d+(?:\.\d+)?)", rest)
            ic = next((i for k, i in CREDIT_ICONS if k in what.lower()), "package")
            small = esc(what[0].upper() + what[1:]) + (", " + esc(lic.group(1).replace("SIL Open Font License", "OFL"))
                                                     if lic else "")
            inner = '<span class="ic">%s</span><span><b>%s</b><small>%s</small></span>' % (icon(ic), esc(name), small)
            if url:
                u = url.group(0).rstrip(".,")
                cards.append('<li><a href="%s" rel="noopener">%s%s</a></li>'
                             % (esc(u), inner, icon("arrow-up-right").replace('class="i ', 'class="i go ')))
            else:
                cards.append('<li><span class="cc">%s</span></li>' % inner)
        out.append(cards)
    while len(out) < 2:
        out.append([])
    return out


def credits(people_html):
    bundled, services = credit_cards()
    go = icon("arrow-up-right").replace('class="i ', 'class="i go ')
    site = ['<li><a href="https://vercel.com/font" rel="noopener"><span class="ic">%s</span><span><b>Geist and Geist Mono'
            '</b><small>Fonts, OFL</small></span>%s</a></li>' % (icon("type"), go),
            '<li><a href="https://github.com/shadcn-labs/mdxcn" rel="noopener"><span class="ic">%s</span><span><b>mdxcn</b>'
            '<small>The framed chart and terminals, the steps, keys and changelog, MIT</small></span>%s</a></li>'
            % (icon("shapes"), go),
            '<li><a href="https://lucide.dev" rel="noopener"><span class="ic">%s</span><span><b>Lucide</b>'
            '<small>Icons, ISC</small></span>%s</a></li>' % (icon("shapes"), go)]
    items = [(icon("package") + "Made with", '<ul class="credits">%s</ul>' % "".join(bundled)),
             (icon("globe") + "Services", '<ul class="credits">%s</ul>' % "".join(services)),
             (icon("monitor") + "This website", '<ul class="credits">%s</ul>' % "".join(site))]
    if people_html:
        items.append((icon("users") + "People", people_html))
    return tabs("cred", "Credits", [i for i in items if i[1] != '<ul class="credits"></ul>'])


def github(path_):
    """GitHub's API, when the build may ask it (GITHUB_TOKEN, in Actions). None otherwise."""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        return None
    try:
        req = urllib.request.Request("https://api.github.com/" + path_,
                                     headers={"Authorization": "Bearer " + token, "User-Agent": "glass-site-build",
                                              "Accept": "application/vnd.github+json"})
        return json.load(urllib.request.urlopen(req, timeout=15))
    except Exception:
        return None


def people(url):
    """Who made Glass: the repository's contributors, when the build can ask GitHub. On a public repository the page
    asks GitHub again when it opens (data-live), so new people show up between builds too."""
    m = re.match(r"https://github\.com/([^/]+/[^/]+)$", url or "")
    if not m:
        return ""
    repo = github("repos/" + m.group(1))
    got = github("repos/%s/contributors?per_page=30" % m.group(1)) or []
    live = ' data-live' if repo and repo.get("private") is False else ""
    li = "".join('<li><a href="%s"><img src="%s&amp;s=64" alt="" width="28" height="28" loading="lazy">%s</a></li>'
                 % (esc(p["html_url"]), esc(p["avatar_url"]), esc(p["login"])) for p in got if p.get("type") == "User")
    if not li and not live:
        return ""
    return '<div data-people="%s"%s><ul class="people">%s</ul></div>' % (esc(url), live, li)


# ---- the parts every page shares ----------------------------------------------------------------------------------

MODE = ("(function(){var r=document.documentElement,k='glass-look',m=window.matchMedia&&matchMedia('(prefers-color-"
        "scheme: dark)');r.classList.add('js');function s(){var v=null;try{v=localStorage.getItem(k)}catch(e){}if(v!=="
        "'light'&&v!=='dark'){try{v=sessionStorage.getItem(k)}catch(e){}}return v==='light'||v==='dark'?v:null}"
        "function p(){var v=s();if(!v){var t=r.getAttribute('data-theme');v=t==='light'||t==='dark'?t:(m&&m.matches?"
        "'dark':'light')}if(r.getAttribute('data-mode')!==v)r.setAttribute('data-mode',v)}p();if(m&&m.addEventListener)"
        "m.addEventListener('change',p);if(window.MutationObserver)new MutationObserver(p).observe(r,{attributes:true,"
        "attributeFilter:['data-theme']});window.glassPick=p;try{if('scrollRestoration' in history)history.scroll"
        "Restoration='manual'}catch(e){}})();")


def minify_css(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"\s*([{};,])\s*", r"\1", s)
    s = re.sub(r"\s*>\s*(?=[.:a-z\[*])", ">", s)
    return s.replace(";}", "}").strip()


def versioned(text, out):
    """Every assets/ address with ?v= and the start of its file's hash: a changed file gets a new address, so the
    real site can tell browsers to keep its files for a year (deploy.py) and still never serve an old one."""
    seen = {}

    def v(p):
        if p not in seen:
            f = os.path.join(out, p)
            seen[p] = p + ("?v=" + hashlib.sha256(open(f, "rb").read()).hexdigest()[:10] if os.path.exists(f) else "")
        return seen[p]
    text = re.sub(r'((?:src|href|poster|data-term|data-term-narrow)=")(assets/[^"?#]+)"',
                  lambda m: m.group(1) + v(m.group(2)) + '"', text)
    text = re.sub(r'(srcset=")([^"]+)"', lambda m: m.group(1) + ", ".join(
        (v(part.split()[0]) + " " + " ".join(part.split()[1:])).strip() for part in m.group(2).split(", ")) + '"', text)
    return re.sub(r'url\("(assets/fonts/[^"?]+)"\)', lambda m: 'url("%s")' % v(m.group(1)), text)


PATHS = {"index.html": "/", "docs.html": "/docs", "404.html": "/404"}   # the real site's addresses: no .html in them
OG_ALT = ("Glass on a TV: the desktop by day, with the clock, the weather, what's playing and a calendar on glass, the dock "
          "along the bottom, and the words A new interface for Android TV, made of glass")
ROBOTS = "index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1"


def head(page, title, description, base, fills, preview, lost=False):
    """Everything in <head>: what search engines, link previews and AI answers read, the fonts, the look before the
    first paint. lost: the page for an address that isn't there, which no search engine should keep."""
    canonical = (base + PATHS.get(page, "/" + page)) if base and not lost else ""
    og = (base + "/og.png") if base else ""
    md = {"index.html": "/index.md", "docs.html": "/docs.md"}.get(page) if base and not lost else None
    lines = ['<title>%s</title>' % esc(title),
             '<meta name="claude:viewport-fit-sides" content="cover">' if preview else "",
             '<meta name="description" content="%s">' % esc(description),
             '<meta name="robots" content="%s">' % ("noindex" if lost else ROBOTS),
             '<meta name="color-scheme" content="light dark">',
             '<meta name="theme-color" content="#E9E4DB" media="(prefers-color-scheme: light)">',
             '<meta name="theme-color" content="#2B2C28" media="(prefers-color-scheme: dark)">',
             '<link rel="canonical" href="%s">' % canonical if canonical else "",
             '<meta property="og:type" content="%s">' % ("article" if page == "docs.html" else "website"),
             '<meta property="og:site_name" content="Glass">',
             '<meta property="og:locale" content="en_US">',
             '<meta property="og:title" content="%s">' % esc(title),
             '<meta property="og:description" content="%s">' % esc(description),
             '<meta property="og:url" content="%s">' % canonical if canonical else "",
             '<meta property="og:image" content="%s">' % og if og else "",
             '<meta property="og:image:type" content="image/png">' if og else "",
             '<meta property="og:image:width" content="1200">' if og else "",
             '<meta property="og:image:height" content="630">' if og else "",
             '<meta property="og:image:alt" content="%s">' % esc(OG_ALT) if og else "",
             '<meta name="twitter:card" content="summary_large_image">',
             '<meta name="twitter:title" content="%s">' % esc(title),
             '<meta name="twitter:description" content="%s">' % esc(description),
             '<meta name="twitter:image" content="%s">' % og if og else "",
             '<meta name="twitter:image:alt" content="%s">' % esc(OG_ALT) if og else "",
             '<link rel="icon" href="assets/orb-32.png" sizes="32x32">',
             '<link rel="icon" href="assets/orb-64.png" sizes="64x64">',
             '<link rel="apple-touch-icon" href="assets/glass-mark-192.png">',
             '<link rel="alternate" type="text/markdown" href="%s" title="%s, as Markdown">' % (md, esc(title)) if md else "",
             '<link rel="preload" href="assets/fonts/geist.woff2" as="font" type="font/woff2" crossorigin>',
             '<link rel="preload" href="assets/fonts/geist-mono.woff2" as="font" type="font/woff2" crossorigin>',
             '<script>%s</script>' % MODE,
             '<style>%s</style>' % minify_css(open(os.path.join(HERE, "assets", "site.css"), encoding="utf-8").read())
             .replace("url(\"fonts/", "url(\"assets/fonts/"),
             '' if lost else '<script type="application/ld+json">%s</script>'
             % json.dumps(structured(page, title, description, base, fills), separators=(",", ":"), ensure_ascii=False)
             .replace("</", "<\\/")]
    if not preview:
        lines.append('<meta name="glass-sw" content="1">')
        if not lost:
            lines.append('<script type="speculationrules">{"prerender":[{"where":{"href_matches":"/docs"},'
                         '"eagerness":"moderate"},{"where":{"href_matches":"/"},"eagerness":"moderate"}]}</script>')
    return "\n".join(l for l in lines if l)


def structured(page, title, description, base, fills):
    """What search engines and AI answers read: the site, who makes Glass, the app itself, the page, and on the docs how
    to set it up and the common questions. Linked by @id so they read as one graph."""
    url = (base + PATHS[page]) if base else None
    owner = slug_of(fills["REPO_URL"]).split("/")[0]
    today = fills["TODAY"]
    ids = (lambda k: base + "/#" + k) if base else (lambda k: None)

    def node(d, k=None):
        if k and ids(k):
            d["@id"] = ids(k)
        return d

    def ref(k):
        return {"@id": ids(k)} if ids(k) else None

    author = node({"@type": "Person", "name": owner, "url": "https://github.com/" + owner}, "author")
    site = node({"@type": "WebSite", "name": "Glass", "alternateName": NAME, "inLanguage": "en",
                 "description": PAGES["index.html"][1], "publisher": ref("author") or author}, "website")
    app = node({"@type": "SoftwareApplication", "name": "Glass", "alternateName": [NAME, "Glass launcher for Android TV"],
                "applicationCategory": "UtilitiesApplication", "applicationSubCategory": "Launcher (home screen) for Android TV",
                "operatingSystem": "Android TV %s or newer" % fills["MIN_ANDROID"], "softwareVersion": fills["VERSION"],
                "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"}, "isAccessibleForFree": True,
                "description": "Glass is a free home screen (launcher) and assistant for Android TV that replaces the TV's "
                               "own home: a desktop of your apps and widgets with no ads, less than half the memory, a "
                               "smarter assistant, and your phone as the remote. It sets up from a Mac or Linux computer "
                               "with one command, needs no root, and ./glass restore puts the TV back as it was.",
                "featureList": ["Desktop with widgets and a dock", "Control Center on the remote's Menu button",
                                "Open apps on a long press of Home", "Glass Assistant, with TV commands offline",
                                "Phone remote in the phone's browser", "Light and dark looks", "No ads, no tracking",
                                "Updates only when you ask"],
                "softwareRequirements": "Android TV %s or newer; a Mac or a Linux computer with Python 3.8 or newer on "
                                        "the same Wi-Fi to set it up" % fills["MIN_ANDROID"],
                "author": ref("author") or author, "sameAs": [fills["REPO_URL"]],
                "codeRepository": fills["REPO_URL"]}, "app")
    if base:
        app.update({"url": base + "/", "image": base + "/og.png", "downloadUrl": fills["REPO_URL"],
                    "installUrl": base + "/docs#setup", "releaseNotes": base + "/docs#changelog",
                    "screenshot": [base + "/assets/tv/desktop-light.jpg", base + "/assets/tv/desktop-dark.jpg"]})
    graph = [author, site, app]
    if page == "index.html":
        graph.append(node({"@type": "WebPage", "name": title, "description": description, "inLanguage": "en",
                           "isPartOf": ref("website"), "about": ref("app"), "dateModified": today,
                           "primaryImageOfPage": (base + "/og.png") if base else None, "url": url}, "home"))
    if page == "docs.html":
        graph.append(node({"@type": "TechArticle", "headline": "Glass docs", "name": title, "description": description,
                           "inLanguage": "en", "isPartOf": ref("website"), "about": ref("app"), "author": ref("author"),
                           "dateModified": today, "image": (base + "/og.png") if base else None, "url": url,
                           "proficiencyLevel": "Beginner"}, "docs"))
        if base:
            graph.append({"@type": "BreadcrumbList", "itemListElement": [
                {"@type": "ListItem", "position": 1, "name": "Glass", "item": base + "/"},
                {"@type": "ListItem", "position": 2, "name": "Docs", "item": url}]})
        graph.append({"@type": "HowTo", "name": "Set up Glass on an Android TV", "totalTime": "PT15M",
                      "supply": [{"@type": "HowToSupply", "name": "An Android TV %s or newer" % fills["MIN_ANDROID"]},
                                 {"@type": "HowToSupply", "name": "A Mac or a Linux computer on the same Wi-Fi"}],
                      "step": [{"@type": "HowToStep", "name": "Turn on debugging on the TV",
                                "text": "In Settings, Device Preferences, About, press OK on Build seven times. Then in "
                                        "Developer options, turn on USB debugging, and Network debugging if it's there."},
                               {"@type": "HowToStep", "name": "Run the installer",
                                "text": "In the Terminal, run: %s" % fills["INSTALL"]},
                               {"@type": "HowToStep", "name": "Allow this computer",
                                "text": "When the TV asks Allow USB debugging?, choose Always allow from this computer."},
                               {"@type": "HowToStep", "name": "Allow Glass's helper",
                                "text": "The TV asks once more, for glass@tv. Choose Always allow."}]})
        graph.append({"@type": "FAQPage", "mainEntity": [
            {"@type": "Question", "name": q, "acceptedAnswer": {"@type": "Answer", "text": a}} for q, a in (
                ("What is Glass?", "A free home screen (launcher) and assistant for Android TV %s or newer. It replaces "
                                   "the TV's own home with a desktop of your apps and widgets, with no ads and less than "
                                   "half the memory, and makes your phone a remote." % fills["MIN_ANDROID"]),
                ("Does Glass need root?", "No. Glass installs like any app, through the TV's own debugging, and your "
                                          "warranty stays."),
                ("How do I install Glass?", "On a Mac or Linux computer on the same Wi-Fi as the TV, turn on debugging "
                                            "on the TV, then run: %s. It takes about 15 minutes." % fills["INSTALL"]),
                ("How do I remove Glass?", "Run ./glass restore. The TV's own home, keyboard and assistant button come "
                                           "back, then Glass goes. Your apps stay."),
                ("Does Glass update on its own?", "No. Run ./glass update when you're ready; every setting stays."),
                ("What leaves the TV?", "Only when you use them: the weather (to Open-Meteo), your questions (to the AI "
                                        "service you chose) and update checks. No accounts, no ads, no tracking."),
                ("It can't find the TV. What now?", "Check debugging is on and both are on the same Wi-Fi, or give the "
                                                     "installer the TV's address: ./glass setup --tv 192.168.1.20"))]})
    for g in graph:
        if url and g.get("@type") not in ("Person", "WebSite", "BreadcrumbList"):
            g.setdefault("mainEntityOfPage", url)
        for k in [k for k, v in g.items() if v is None]:
            del g[k]
    return {"@context": "https://schema.org", "@graph": graph}


def orb(size):
    """Glass's mark, as small as it shows: an AVIF copy (with its transparency) where the browser takes one."""
    src = os.path.join(HERE, "assets", "orb-64.png")
    img = '<img src="assets/orb-64.png" alt="" width="%d" height="%d">' % (size, size)
    if not AVIF_OK:
        return img
    cached = cache_path(src, "assets", "orb-64.avif")
    if not os.path.exists(cached):
        os.makedirs(os.path.dirname(cached), exist_ok=True)
        Image.open(src).convert("RGBA").save(cached + ".tmp", "AVIF", quality=80, speed=5)
        os.replace(cached + ".tmp", cached)
    if (cached, "assets/orb-64.avif") not in MADE:
        MADE.append((cached, "assets/orb-64.avif"))
    return '<picture class="orb"><source type="image/avif" srcset="assets/orb-64.avif">%s</picture>' % img


def nav(page, url):
    docs = page == "docs.html"
    return ('<header class="nav" id="nav"><div class="wrap%s nav-in">'
            '<a class="brand" href="./" data-top-of="index">%s'
            'Glass</a>'
            '<a class="link" href="docs.html"%s data-top-of="docs">Docs</a>'
            '%s'
            '<a class="gh" href="%s" aria-label="Glass on GitHub">%s<span>GitHub</span></a>'
            '<button class="mode" type="button" data-mode-toggle aria-label="Dark mode" aria-pressed="false">%s%s</button>'
            '</div></header>' % (" dw" if docs else "", orb(26), ' aria-current="page"' if docs else "",
                                 ('<button class="link toc-b" type="button" aria-controls="rail" aria-expanded="false" '
                                  'aria-label="Contents">%s'
                                  '<span>Contents</span></button>' % icon("list")) if docs else "",
                                 esc(url), icon("si-github"), icon("sun"), icon("moon")))


def foot(version_, url):
    return ('<footer class="foot"><a class="brand" href="./" data-top-of="index">%s'
            'Glass</a><span class="ver">v%s</span><nav aria-label="More">'
            '<a href="docs.html" data-top-of="docs">Docs</a><a href="docs.html#changelog">Changelog</a><a href="%s">GitHub</a>'
            '<a href="%s/blob/main/DESIGN.md">Design</a><a href="%s/blob/main/NOTICE.md">Licences</a></nav>'
            '<p class="made">Made and measured on a 720p TV with 1 GB of memory.</p></footer>'
            % (orb(20), esc(version_), esc(url), esc(url), esc(url)))


HALO = ('<div class="halo" aria-hidden="true"><i class="halo-frost"></i><i class="halo-tint"></i><i class="halo-lit"></i>'
        '<svg class="halo-glint" xmlns="http://www.w3.org/2000/svg"></svg></div>')

PAGES = {
    "index.html": ("Glass: an ad-free home screen for Android TV",
                   "A free home screen (launcher) for Android TV: no ads, less than half the memory, a smarter "
                   "assistant, and your phone as the remote. One command, no root."),
    "docs.html": ("Glass docs: set up, use and remove Glass on Android TV",
                  "Everything about Glass for Android TV: setting it up, each component, the remote and the phone "
                  "remote, updates, privacy, performance and troubleshooting."),
    "404.html": ("Not here · Glass", "This page isn't part of Glass's site."),
}


# ---- what else the real site serves --------------------------------------------------------------------------------

AI_CRAWLERS = ("GPTBot", "OAI-SearchBot", "ChatGPT-User", "ClaudeBot", "Claude-SearchBot", "Claude-User", "PerplexityBot",
               "Perplexity-User", "Google-Extended", "Applebot-Extended", "Amazonbot", "DuckAssistBot", "MistralAI-User")


def extras(out, base, fills, slug):
    """robots.txt, the sitemap, llms.txt (a plain summary for AI answers) and the pages as Markdown, the one-line
    installer, the cache worker and the share card."""
    with open(os.path.join(out, "robots.txt"), "w") as f:
        f.write("# Glass's site. Search engines and AI assistants are welcome to all of it.\n"
                "User-agent: *\nAllow: /\n\n" + "".join("User-agent: %s\n" % a for a in AI_CRAWLERS) + "Allow: /\n"
                + ("\nSitemap: %s/sitemap.xml\n" % base if base else ""))
    if base:
        shots = {"/": ["desktop-light.jpg", "desktop-dark.jpg"], "/docs": ["widgets-medium-light.jpg", "controls-light.jpg"]}
        with open(os.path.join(out, "sitemap.xml"), "w") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" '
                    'xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">\n')
            for path_ in ("/", "/docs"):
                pics = "".join("<image:image><image:loc>%s/assets/tv/%s</image:loc></image:image>" % (base, n)
                               for n in shots[path_] if os.path.exists(os.path.join(TV, n)))
                f.write("<url><loc>%s%s</loc><lastmod>%s</lastmod>%s</url>\n" % (base, path_, fills["TODAY"], pics))
            f.write("</urlset>\n")
    b = load("bench.json", {})
    m, s, fr = b.get("memory", {}), b.get("start", {}), b.get("frames", {})
    facts = []
    if m.get("glass_pss_mb"):
        facts.append("- Memory on its home: %s MB, against %s MB for the TV's own home." % (
            round(m["glass_pss_mb"]), round(m["stock_pss_mb"])))
    if s.get("glass_cold_ms"):
        facts.append("- Opens from cold in %.2f s, against %.2f s." % (s["glass_cold_ms"] / 1000, s["stock_cold_ms"] / 1000))
    if fr.get("glass_p99_ms"):
        facts.append("- Slowest frame in 100: %d ms, against %d ms." % (fr["glass_p99_ms"], fr["stock_p99_ms"]))
    link = base or ""
    with open(os.path.join(out, "llms.txt"), "w") as f:
        f.write("# Glass\n\n> Glass is a free home screen (launcher) and assistant for Android TV %s or newer. It replaces "
                "the TV's own home with a desktop of your apps and widgets: no ads, less than half the memory, a smarter "
                "assistant, and your phone as the remote. Version %s. It sets up from a Mac or Linux computer on the same "
                "Wi-Fi with one command, needs no root, and `./glass restore` puts the TV back as it was.\n\n"
                "Install: `%s`\n\n"
                "## Docs\n\n"
                "- [The docs, as Markdown](%s/docs.md): everything below, in one file\n"
                "- [Set up](%s/docs#setup): Android TV %s or newer, a Mac or Linux computer on the same Wi-Fi, about 15 "
                "minutes, no root\n"
                "- [Commands](%s/docs#commands): ./glass, ./glass update, ./glass status, ./glass doctor, ./glass restore\n"
                "- [Components](%s/docs#components): widgets, the bar and the dock, Control Center, open apps, inputs, "
                "search, settings, Glass Assistant, Files, the keyboard, the phone remote\n"
                "- [The remotes](%s/docs#remotes): what the TV's remote does, and the phone remote\n"
                "- [Updates](%s/docs#updates): only when you ask, every setting kept\n"
                "- [Privacy](%s/docs#privacy): no accounts, no ads, no tracking; what leaves the TV, and when\n"
                "- [Performance](%s/docs#performance): every measure against the TV's own home\n"
                "- [Troubleshooting](%s/docs#trouble) and [removing Glass](%s/docs#restore)\n\n"
                "## Measured on a 720p TV with 1 GB of memory and Android TV 9\n\n%s\n\n"
                "## Optional\n\n- [The front page, as Markdown](%s/index.md)\n- [Source code on GitHub](%s)\n"
                % (fills["MIN_ANDROID"], fills["VERSION"], fills["INSTALL"], link, link, fills["MIN_ANDROID"], link,
                   link, link, link, link, link, link, link, "\n".join(facts), link, fills["REPO_URL"]))
    pages_md = []
    for page, md in (("index.html", "index.md"), ("docs.html", "docs.md")):
        text = to_markdown(open(os.path.join(out, page), encoding="utf-8").read(), base, PATHS[page])
        with open(os.path.join(out, md), "w", encoding="utf-8") as f:
            f.write(text)
        pages_md.append(text)
    with open(os.path.join(out, "llms-full.txt"), "w", encoding="utf-8") as f:
        f.write("\n\n---\n\n".join(pages_md))
    if base:   # site/deploy.py adds these to the site's headers: each Markdown copy names its page as the one to list
        with open(os.path.join(out, "_headers"), "w") as f:
            f.write("/index.md\n  Link: <%s/>; rel=\"canonical\"\n/docs.md\n  Link: <%s/docs>; rel=\"canonical\"\n"
                    % (base, base))
    inst = read("install.sh")
    inst = re.sub(r'REPO="\$\{GLASS_REPO:-[^}]*\}"', 'REPO="${GLASS_REPO:-%s}"' % slug, inst)
    inst = re.sub(r"raw\.githubusercontent\.com/[^/\s]+/[^/\s]+/main/install\.sh",
                  "raw.githubusercontent.com/%s/main/install.sh" % slug, inst)
    with open(os.path.join(out, "install.sh"), "w") as f:
        f.write(inst)
    with open(os.path.join(out, "sw.js"), "w") as f:
        f.write(SW)
    if Image is not None:
        share_card(os.path.join(out, "og.png"))


# ---- the pages as Markdown, for AI answers and anyone who'd rather read text -------------------------------------------

class _Markdown(__import__("html.parser").parser.HTMLParser):
    """The main content of a built page as Markdown: headings, paragraphs, lists, tables, commands and links. The
    page's chrome (the bar, the contents, buttons, films and their controls, the halo) is left out; a tab's panel
    gets its tab's name as a heading; a picture or film is kept as its description."""
    VOID = {"img", "br", "hr", "input", "source", "meta", "link", "wbr"}
    SKIP_TAGS = {"svg", "video", "audio", "select", "script", "style", "template", "button", "noscript", "footer"}
    SKIP_CLASSES = {"halo", "fold-end", "tablist", "term-ctl", "copy", "scrub", "speed", "sr-skip", "track", "skip",
                    "grp-pick", "count", "tp", "pr", "sn", "t", "c"}
    BLOCKS = {"p", "div", "section", "header", "figure", "figcaption", "ul", "ol", "table", "details", "summary",
              "blockquote", "pre", "main", "article", "nav", "dl", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6"}

    def __init__(self, base, path_):
        super().__init__(convert_charrefs=True)
        self.base, self.path = base, path_
        self.out, self.line = [], []
        self.skip = 0
        self.stack = []          # (tag, attrs, skipping?)
        self.lists = []          # "ul" / "ol" counters
        self.links = []
        self.row = None          # table row cells
        self.rows = []
        self.inmain = False
        self.tabs = {}           # tab id -> its name
        self.tabname = None
        self.closed = False      # the last thing written closed a **bold**, `code` or [link]

    def url(self, href):
        if href.startswith(("http://", "https://", "mailto:")):
            return href
        href = href.split("?v=")[0]
        if href.startswith("#"):
            return (self.base + self.path if self.base else "") + href
        if href.startswith("/"):
            return self.base + href
        if href.startswith("./"):
            href = href[2:]
        return self.base + "/" + href if self.base else href

    def flush(self, prefix=""):
        text = re.sub(r"[ \t]+", " ", "".join(self.line)).strip()
        self.line = []
        if text:
            self.out.append(prefix + text)

    def blank(self):
        self.flush()
        if self.out and self.out[-1] != "":
            self.out.append("")

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "main":
            self.inmain = True
        if tag == "button" and a.get("role") == "tab" and a.get("id"):
            self.tabs[a["id"]] = None
            self.tabname = a["id"]
        cls = set((a.get("class") or "").split())
        skipping = (self.skip > 0 or tag in self.SKIP_TAGS or a.get("aria-hidden") == "true"
                    or bool(cls & self.SKIP_CLASSES) or "l-dark" in cls)
        if tag == "svg" and self.skip == 0 and self.inmain and self.stack and "path" in (self.stack[-1][1].get("class") or "").split():
            self.line.append(" › ")
        if tag == "video" and self.inmain and self.skip == 0 and "l-dark" not in cls and a.get("aria-label"):
            self.blank()
            self.out.append("*A film: %s*" % a["aria-label"])
            self.blank()
        if tag in self.VOID:
            if not skipping and self.inmain and tag == "img" and a.get("alt"):
                self.line.append("![%s](%s)" % (a["alt"], self.url(a.get("src", ""))))
            if not skipping and tag == "br":
                self.line.append(" ")
            return
        self.stack.append((tag, a, skipping))
        if skipping:
            self.skip += 1
            return
        if not self.inmain:
            return
        if tag in ("span", "small", "b", "strong", "a", "code", "kbd") and self.line and (
                self.closed or re.search(r"[^\s\[(`*]$", self.line[-1])):
            self.line.append(" ")
        self.closed = False
        if (cls & {"cmd", "code"}) and a.get("data-copy") is not None:
            self.blank()
            self.out.extend(["```sh"] + a["data-copy"].split("\n") + ["```"])
            self.blank()
            self.skip += 1
            self.stack[-1] = (tag, a, True)
            return
        if a.get("role") == "tabpanel":
            self.blank()
            name = self.tabs.get(a.get("aria-labelledby"))
            if name:
                self.out.append("**%s**" % name)
                self.out.append("")
        if a.get("data-term") is not None and a.get("aria-label"):
            self.blank()
            self.out.append("*A recording: %s*" % a["aria-label"])
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self.blank()
        elif tag in ("ul", "ol"):
            self.flush()
            self.lists.append([tag, 0])
        elif tag == "li":
            self.flush()
            if self.lists:
                self.lists[-1][1] += 1
            self.li_parts = []
        elif tag == "tr":
            self.flush()
            self.row = []
        elif tag in ("td", "th"):
            self.line = []
        elif tag in ("b", "strong"):
            self.line.append("**")
        elif tag in ("code", "kbd"):
            self.line.append("`")
        elif tag == "a" and a.get("href"):
            self.links.append(self.url(a["href"]))
            self.line.append("[")
        elif tag == "summary":
            self.blank()
            self.line.append("**")
        elif tag in self.BLOCKS:
            self.flush()

    def handle_endtag(self, tag):
        if tag in self.VOID:
            return
        while self.stack:
            t, a, skipping = self.stack.pop()
            if t == "button":
                self.tabname = None
            if skipping:
                self.skip -= 1
                if t == tag:
                    return
                continue
            self.close(t, a)
            if t == tag:
                return

    def close(self, tag, a):
        if not self.inmain:
            return
        if tag == "main":
            self.flush()
            self.inmain = False
        elif tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            level = int(tag[1])
            self.flush("#" * level + " ")
            self.out.append("")
        elif tag in ("ul", "ol"):
            self.flush()
            if self.lists:
                self.lists.pop()
            if not self.lists:
                self.blank()
        elif tag == "li":
            kind, n = self.lists[-1] if self.lists else ("ul", 1)
            indent = "  " * (len(self.lists) - 1)
            self.flush(indent + ("%d. " % n if kind == "ol" else "- "))
        elif tag in ("td", "th"):
            text = re.sub(r"\s+", " ", "".join(self.line)).strip().replace("|", "\\|")
            self.line = []
            if self.row is not None:
                self.row.append((tag, text))
        elif tag == "tr":
            if self.row:
                self.rows.append(self.row)
            self.row = None
        elif tag == "table":
            if self.rows:
                self.blank()
                width = max(len(r) for r in self.rows)
                first = self.rows[0]
                head = [c for _, c in first] + [""] * (width - len(first))
                body = self.rows[1:] if all(k == "th" for k, _ in first) else self.rows
                if body is self.rows:
                    head = [""] * width
                self.out.append("| " + " | ".join(head) + " |")
                self.out.append("|" + "---|" * width)
                for r in body:
                    cells = [c for _, c in r] + [""] * (width - len(r))
                    self.out.append("| " + " | ".join(cells) + " |")
                self.blank()
            self.rows = []
        elif tag in ("b", "strong"):
            self.line.append("**")
            self.closed = True
        elif tag in ("code", "kbd"):
            self.line.append("`")
            self.closed = True
        elif tag == "a" and a.get("href"):
            href = self.links.pop() if self.links else ""
            self.line.append("](%s)" % href)
            self.closed = True
        elif tag == "summary":
            self.line.append("**")
            self.blank()
        elif tag == "button" and a.get("role") == "tab":
            pass
        elif tag in self.BLOCKS:
            self.flush()
            if tag in ("p", "section", "details", "figure", "header", "table"):
                self.blank()

    def handle_data(self, data):
        if self.skip or not self.inmain:
            # a tab's own name, kept for its panel
            if self.tabname and self.stack and any(t == "button" for t, _, _ in self.stack[-2:]) and data.strip():
                self.tabs[self.tabname] = ((self.tabs.get(self.tabname) or "") + " " + data.strip()).strip()
            return
        if self.closed and data[:1].isalnum():
            self.line.append(" ")
        self.closed = False
        self.line.append(data.replace("\n", " "))


def to_markdown(page_html, base, path_):
    """A built page's main content as Markdown (see _Markdown), under its title and address."""
    p = _Markdown(base, path_)
    p.feed(page_html)
    p.flush()
    title = re.search(r"<title>(.*?)</title>", page_html, re.S)
    desc = re.search(r'<meta name="description" content="([^"]*)"', page_html)
    lines, prev = [], None
    for l in p.out:
        if not l.startswith("```"):
            l = re.sub(r"\*\*\s*\*\*", "", l).replace("[](", "(").rstrip()
            l = re.sub(r"`\s*`", "", l)
        if l == "" and prev == "":
            continue
        lines.append(l)
        prev = l
    top = ["# " + html.unescape(title.group(1)) if title else "", ""]
    if desc:
        top += ["> " + html.unescape(desc.group(1)), ""]
    if base:
        top += ["Address: %s%s" % (base, path_), ""]
    return "\n".join(top + lines).strip() + "\n"


SW = """// Glass's cache worker: pictures, fonts and films come from the cache once seen; pages from the network, else the
// last copy kept, so a second visit is instant and a dropped connection still shows the page.
const KEEP = 'glass-v2';
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== KEEP).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => {
  const r = e.request, u = new URL(r.url);
  if (r.method !== 'GET' || u.origin !== location.origin || r.headers.has('range')) return;
  if (u.pathname.startsWith('/assets/')) {
    e.respondWith(caches.open(KEEP).then(c => c.match(r).then(hit => hit || fetch(r).then(res => { if (res.ok && res.status === 200) c.put(r, res.clone()); return res; }))));
  } else if (r.mode === 'navigate') {
    e.respondWith(fetch(r).then(res => { const copy = res.clone(); caches.open(KEEP).then(c => c.put(r, copy)); return res; }).catch(() => caches.match(r)));
  }
});
"""


def share_card(path_):
    """The picture a shared link shows: the desktop by day, with the name and the promise beside it."""
    try:
        W, H = 1200, 630
        card = Image.new("RGB", (W, H), "#E9E4DB")
        shot = Image.open(os.path.join(TV, "desktop-light.jpg" if os.path.exists(os.path.join(TV, "desktop-light.jpg"))
                                       else "desktop-dark.jpg")).convert("RGB")
        sw = 760
        shot = shot.resize((sw, round(shot.height * sw / shot.width)), Image.LANCZOS)
        mask = Image.new("L", shot.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), 18, fill=255)
        card.paste(shot, (W - sw - 48, H - shot.height - 48), mask)
        d = ImageDraw.Draw(card)
        font_path = os.path.join(HERE, "assets", "fonts", "geist.woff2")
        try:
            big = ImageFont.truetype(font_path, 76)
            small = ImageFont.truetype(font_path, 30)
            for f_, w_ in ((big, 600), (small, 500)):
                try:
                    f_.set_variation_by_axes([w_])
                except Exception:
                    pass
        except Exception:
            big = small = ImageFont.load_default()
        d.text((56, 52), "Glass", font=big, fill="#2B2C28")
        y = 158
        for line in ("A new interface", "for Android TV,", "made of glass."):
            d.text((58, y), line, font=small, fill="#5F5E57")
            y += 40
        card.save(path_, optimize=True)
    except Exception as e:   # a share card is a nicety, never a reason to fail the build
        print("no share card: %s" % e, file=sys.stderr)


# ---- the pages -----------------------------------------------------------------------------------------------------

def build(out, preview=False):
    bench = load("bench.json", {})
    remote = load("remote.json", {})
    pov = load("pov.json", {})
    rel, _ = version("launcher")
    arel, _ = version("assistant")
    url = repo_url() or "https://github.com/OWNER/glass-tv"
    slug = slug_of(url)
    base = "" if preview else os.environ.get("GLASS_SITE_URL", "").rstrip("/")
    install = ("curl -fsSL %s/install.sh | sh" % base) if base else (
        "curl -fsSL https://raw.githubusercontent.com/%s/main/install.sh | sh" % slug)
    clone = "git clone %s.git && cd glass-tv && ./glass" % url
    z = bench.get("size", {})
    MADE.clear()
    fills = {
        "VERSION": rel,
        "REPO_URL": url,
        "INSTALL": install,
        "MIN_ANDROID": min_android(),
        "BUILT_WITH": built_with(),
        "GLASS_VERSION": rel,
        "ASSISTANT_VERSION": arel,
        "GLASS_SIZE": ("%.1f MB" % z["glass_apk_mb"]) if z.get("glass_apk_mb") else "",
        "ASSISTANT_SIZE": ("%d KB" % z["assistant_apk_kb"]) if z.get("assistant_apk_kb") else "",
        "TESTS": str(tests() or bench.get("tests", "")),
        "FOOT": foot(rel, url),
        "HALO": HALO,
        "TODAY": time.strftime("%Y-%m-%d", time.gmtime()),
    }
    fills.update(numbers(bench))
    fills.update({
        "HERO_SHOT": screen("desktop", "Glass's desktop on the TV: the clock, the weather, what's playing and a calendar "
                                       "on glass over the wallpaper, and the dock of apps along the bottom", eager=True),
        "CMD": cmd(install),
        "SCREEN_TABS": screens_tabs(load("screens.json", [])),
        "REMOTES": remotes(remote, pov),
        "ASKS": asks(load("asks.json", {})),
        "TERM_SETUP": term("setup", "./glass", "SETUP_STEPS", "The installer setting up Glass: a box with eight steps, "
                                                              "each ticked off, then Glass is on your TV", step_line=False),
        "TERM_INSTALL": term("setup", "./glass", "SETUP_STEPS", "The installer setting up Glass: a box with eight steps, "
                                                                "each ticked off, then Glass is on your TV"),
        "RAIL": rail(),
        "REQUIREMENTS": requirements(fills["MIN_ANDROID"]),
        "SETUP_STEPS": setup_steps(install, clone),
        "CLI_TABS": cli_tabs(),
        "COMPONENTS": components(load("components.json", {})),
        "REMOTES_DOCS": remotes_docs(remote, pov),
        "UPDATES_DOCS": updates_docs(fills["UPDATE_SECONDS"]),
        "PRIVACY": privacy(),
        "SPECS": specs(fills),
        "TROUBLE": trouble(),
        "RESTORE": restore_fold(),
        "CHANGELOG": changelog(),
        "CREDITS": credits(people(url)),
    })
    if os.path.isdir(out):
        shutil.rmtree(out)
    shutil.copytree(os.path.join(HERE, "assets"), os.path.join(out, "assets"),
                    ignore=shutil.ignore_patterns("site.css"))
    small_js(os.path.join(out, "assets", "site.js"))
    for page, (title, description) in PAGES.items():
        lost = page == "404.html"
        if lost and preview:
            continue
        text = read("site", page)
        text = text.replace("{{HEAD}}", head(page, title, description, base, fills, preview, lost))
        text = text.replace("{{NAV}}", nav(page, url))
        for k, v in fills.items():
            text = text.replace("{{%s}}" % k, v)
        # each fold ends in a marker: a second place to stop when the fold is taller than the screen
        text = text.replace("</section>", '<span class="fold-end" aria-hidden="true"></span></section>')
        text = re.sub(r"\{\{i:([a-z0-9-]+)\}\}", lambda mm: icon(mm.group(1)), text)
        left = re.findall(r"\{\{[A-Za-z_:-]+\}\}", text)
        if left:
            print("%s: not filled yet: %s" % (page, " ".join(sorted(set(left)))), file=sys.stderr)
        if not preview:
            for cached, published in MADE:   # the AVIF copies, there before their addresses get their ?v=
                if not os.path.exists(os.path.join(out, published)):
                    shutil.copy(cached, os.path.join(out, published))
            text = wrap(addresses(versioned(text, out), lost))
        elif page != "index.html":   # the preview's host wraps only its first page; the others are documents of their own
            text = wrap(text, host_like=True)
        with open(os.path.join(out, page), "w", encoding="utf-8") as f:
            f.write(text)
    for cached, published in MADE:
        if not os.path.exists(os.path.join(out, published)):
            shutil.copy(cached, os.path.join(out, published))
    if not preview:
        extras(out, base, fills, slug)
    if AVIF_OK and os.path.isdir(CACHE):
        prune_cache()
    print("built %s (Glass %s, %d AVIF copies)" % (out, rel, len(MADE)))


def addresses(text, lost=False):
    """The real site's addresses: the docs at /docs and the front page at /, as Cloudflare serves them (an address
    ending in .html is sent on there, which costs a round trip). The page for an address that isn't there can show
    under any path, so everything it loads is from the site's root."""
    text = re.sub(r'href="docs\.html', 'href="/docs', text)
    text = text.replace('href="./"', 'href="/"')
    if lost:
        text = re.sub(r'((?:src|href|srcset|data-poster)=")(assets/)', r"\1/\2", text)
        text = re.sub(r'(srcset="[^"]*")', lambda m: m.group(1).replace(", assets/", ", /assets/"), text)
        text = text.replace('url("assets/', 'url("/assets/').replace('src="assets/site.js', 'src="/assets/site.js')
    return text


def small_js(path_):
    """The script without its comments and spaces (rJSmin, when it's installed): the same program, a third smaller."""
    try:
        import rjsmin
    except ImportError:
        return
    with open(path_, encoding="utf-8") as f:
        src = f.read()
    with open(path_, "w", encoding="utf-8") as f:
        f.write(rjsmin.jsmin(src))


def wrap(body, host_like=False):
    """A page's source is its content; the real site gets the document around it. host_like: the same reset as the
    preview's host puts around its first page, so every page of the preview behaves alike."""
    head_, rest = [], []
    for line in body.splitlines():
        if not rest and re.match(r"\s*<(title|meta|link|script|style)\b", line) and "<script src=" not in line:
            head_.append(line)
        else:
            rest.append(line)
    reset = ("<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}"
             "</style>\n" if host_like else "")
    return ("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
            + reset + "\n".join(head_) + "\n</head>\n<body>\n" + "\n".join(rest) + "\n</body>\n</html>\n")


if __name__ == "__main__":
    args = sys.argv[1:]
    preview = "--preview" in args
    args = [a for a in args if a != "--preview"]
    build(args[0] if args else os.path.join(HERE, "dist"), preview)
