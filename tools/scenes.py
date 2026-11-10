#!/usr/bin/env python3
"""Glass on the TV, drawn: every picture of the TV the website shows, in both looks, at 2560 x 1440.

  python3 tools/scenes.py                  every picture
  python3 tools/scenes.py desktop controls only these (a picture's name, or a part's)
  python3 tools/scenes.py --list           what there is
  python3 tools/scenes.py --look dark      one look only
  python3 tools/scenes.py --films          the films too (remote, phone, remind), or name one: remote

The screens are drawn by tools/scenes/tv.html from the launcher's own design: its measurements (Ui, Pane, Bar, Dock,
Widgets, the sheets, the assistant), fonts, glyphs, wallpapers and the apps' icons, placed in the TV's 1280 x 720
design px and drawn at twice that, so they stay sharp on any screen. The content is Glass's sample content
(Sample.java), the same in both looks: by day in the light look, in the evening in the dark one. Nothing comes from a
TV, so nothing private is in them, and no stray keyboard, volume bar or label.

Needs Python 3 with Pillow, numpy and segno, and Playwright for Python with its Chromium.
"""
import functools
import http.server
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, "tools", "scenes")
CACHE = os.path.join(HERE, ".cache")
OUT = os.environ.get("SCENES_OUT") or os.path.join(ROOT, "site", "assets", "tv")
MAIN = os.path.join(ROOT, "launcher", "app", "src", "main")
LOOKS = ("light", "dark")
WALL = {"light": "silk-d", "dark": "silk-n"}   # the backgrounds the pictures use, by day and by night

# Every picture: its scene in tv.js, whether the whole screen is kept (NAME-light.jpg, NAME-dark.jpg), and parts of
# it [left, top, right, bottom] in design px, kept as PART-light.jpg and PART-dark.jpg.
NOTE, WIDGET = [820, 32, 1280, 126], [24, 56, 372, 240]
SHOTS = {
    "desktop": {"parts": {"part-bar": [0, 0, 1280, 34]}},
    "dock": {"full": False, "parts": {"part-dock": [180, 556, 1100, 716]}},
    "controls": {"parts": {"part-appearance": [862, 418, 1262, 486]}},
    "settings": {}, "weather": {}, "search": {}, "keyboard": {}, "apps": {}, "files": {}, "inputs": {}, "switcher": {},
    "phone": {},
    "ask-volume": {}, "ask-watch": {}, "ask-cast": {}, "ask-remind": {}, "ask-sleep": {},
    "note-reminder": {"full": False, "parts": {"part-reminder": NOTE}},
    "note-phone": {"full": False, "parts": {"part-note-phone": NOTE}},
    "note-updated": {"full": False, "parts": {"part-note-updated": NOTE}},
    "volume": {"full": False, "parts": {"part-volume": [400, 632, 880, 720]}},
    "widgets-small": {}, "widgets-medium": {}, "widgets-large": {},
    "solo-clock": {"full": False, "parts": {"part-widget-clock": WIDGET}},
    "solo-weather": {"full": False, "parts": {"part-widget-weather": WIDGET}},
    "solo-playing": {"full": False, "parts": {"part-widget-playing": WIDGET}},
    "solo-calendar": {"full": False, "parts": {"part-widget-calendar": WIDGET}},
    "solo-photos": {"full": False, "parts": {"part-widget-photos": WIDGET}},
    # the screen savers are night pictures whatever the look: one each (NAME.jpg), drawn in the dark look
    "saver-clock": {"one": True}, "saver-drift": {"one": True}, "saver-glass": {"one": True}, "saver-glow": {"one": True},
    "saver-photos": {"one": True}, "saver-night": {"one": True},
}


# The films (tools/scenes/films.js), drawn frame by frame at 60 a second and 1920 x 1080 (sharp in the page's frame,
# and light: a keyframe every 10 s, as nearly everything holds still between moves); remote and phone also write their
# timelines to site/data/NAME.json (where each part starts, when the TV answers each press) for the cues and the phone
# shot.
FILMS = {"remote": {"timeline": True, "poster": 0.0}, "phone": {"timeline": True, "poster": 0.0},
         "remind": {"timeline": False, "poster": 1.0}}
FPS, FILM_SCALE, KEY = 60, 1.5, 600
DATA = os.path.join(ROOT, "site", "data")


def say(*a):
    print("•", *a, flush=True)


# ---- what the page draws with -----------------------------------------------------------------------------------
def glyphs():
    """Glyph.java's paths, by name: {name: [stroke, fill]}."""
    src = open(os.path.join(MAIN, "java", "com", "gathin", "tvhome", "Glyph.java")).read()
    found = re.findall(r'P\.put\("(\w+)", new String\[\]\{"([^"]*)", "([^"]*)"\}\);', src)
    if len(found) < 40:
        sys.exit("✗ Glyph.java's paths weren't found")
    return {n: [s, f] for n, s, f in found}


def colour(a, saturate, bright):
    """Android's ColorMatrix: setSaturation(saturate), then a scale by bright (Pane.color)."""
    inv = 1 - saturate
    r, g, b = .213 * inv, .715 * inv, .072 * inv
    m = np.array([[r + saturate, g, b], [r, g + saturate, b], [r, g, b + saturate]], np.float32) * bright
    return np.clip(a @ m.T, 0, 255)


def tint(a, rgba):
    *rgb, al = rgba
    return a * (1 - al) + np.array(rgb, np.float32) * al


def box_blur(a, r, passes=3):
    """Lens.boxBlur: three box passes each way, edges clamped."""
    for _ in range(passes):
        for axis in (1, 0):
            p = np.pad(a, [(r, r) if i == axis else (0, 0) for i in range(3)], mode="edge")
            c = np.cumsum(p, axis=axis, dtype=np.float64)
            c = np.concatenate([np.zeros_like(np.take(c, [0], axis=axis)), c], axis=axis)
            n = a.shape[axis]
            a = (np.take(c, range(2 * r + 1, 2 * r + 1 + n), axis=axis) - np.take(c, range(0, n), axis=axis)) / (2 * r + 1)
    return a.astype(np.float32)


def prepare():
    """The glyphs, and for each look the wallpaper at twice the TV's size with the two textures the glass shows
    (Pane.glass: clear glass and frosted glass, each with its colour lift and tint), and the song's artwork."""
    os.makedirs(CACHE, exist_ok=True)
    with open(os.path.join(CACHE, "glyphs.json"), "w") as f:
        json.dump(glyphs(), f)
    for look in LOOKS:
        night = look == "dark"
        src = Image.open(os.path.join(MAIN, "assets", "walls", WALL[look] + ".jpg")).convert("RGB")
        wall = src.resize((2560, 1440), Image.LANCZOS)
        wall.save(os.path.join(CACHE, f"wall-{look}.jpg"), quality=95)
        w = np.asarray(wall, np.float32)
        sharp = tint(colour(w, 1.8, 1.10 if night else 1.07), (12, 12, 16, .2) if night else (255, 255, 255, .1))
        Image.fromarray(sharp.round().astype(np.uint8)).save(os.path.join(CACHE, f"sharp-{look}.jpg"), quality=95)
        small = np.asarray(src.resize((160, 90), Image.BILINEAR), np.float32)
        small = box_blur(small, 3)
        big = Image.fromarray(small.round().astype(np.uint8)).resize((2560, 1440), Image.BICUBIC)
        frost = tint(colour(np.asarray(big, np.float32), 1.8, 1.0), (24, 24, 30, .62) if night else (250, 250, 252, .6))
        Image.fromarray(frost.round().astype(np.uint8)).save(os.path.join(CACHE, f"frost-{look}.jpg"), quality=95)
        # All apps' veil (Lens.veil: the wallpaper blurred, sigma 28, a little more colourful)
        veil = np.asarray(src.filter(ImageFilter.GaussianBlur(28)), np.float32)
        veil = Image.fromarray(colour(veil, 1.3, 1.0).round().astype(np.uint8)).resize((2560, 1440), Image.BICUBIC)
        veil.save(os.path.join(CACHE, f"veil-{look}.jpg"), quality=95)
    # the code to scan (PhoneSheet.qrDrawable: medium correction, a border of two), for the sample address
    import segno
    segno.make("http://192.168.1.20:8765/p/glass", error="m").save(os.path.join(CACHE, "qr.svg"), scale=1, border=2,
                                                                     dark="#0E0E10", light="#FFFFFF")
    # Sample.art: the orbs at night, a square from just right of the middle
    orbs = Image.open(os.path.join(MAIN, "assets", "walls", "orbs-n.jpg")).convert("RGB")
    s = min(orbs.size)
    x = (orbs.width - s) // 2 + s // 6
    orbs.crop((x, (orbs.height - s) // 2, x + s - s // 6, (orbs.height - s) // 2 + s - s // 6)).save(os.path.join(CACHE, "art.jpg"), quality=92)


# ---- drawing ----------------------------------------------------------------------------------------------------------
def serve():
    handler = functools.partial(Quiet, directory=ROOT)
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{httpd.server_address[1]}/tools/scenes/tv.html"


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


def save(im, name, look, box=None):
    if box:
        im = im.crop(tuple(v * 2 for v in box))
    path = os.path.join(OUT, f"{name}-{look}.jpg")
    im.save(path, quality=86, optimize=True, progressive=True)
    return path


def ff(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", *args], check=True)


def film(page, url, name, look, tmp):
    """Every frame of a film into a folder, then the film as H.264 (every browser) and AV1 (a third of the size)."""
    page.goto(f"{url}?film={name}&look={look}")
    page.wait_for_function("window.READY !== undefined", timeout=30000)
    if page.evaluate("window.READY") is not True:
        sys.exit(f"✗ {name} ({look}): {page.evaluate('window.READY')}")
    info = page.evaluate("window.FILM")
    frames = os.path.join(tmp, f"{name}-{look}")
    os.makedirs(frames, exist_ok=True)
    n = int(round(info["duration"] * FPS))
    for i in range(n):
        page.evaluate(f"window.frame({i / FPS})")
        page.screenshot(path=os.path.join(frames, f"{i:05d}.jpg"), type="jpeg", quality=93)
    base = os.path.join(OUT, f"{name}-{look}")
    pattern = os.path.join(frames, "%05d.jpg")
    ff("-framerate", str(FPS), "-i", pattern, "-c:v", "libx264", "-preset", "slow", "-tune", "animation", "-crf", "22",
       "-profile:v", "high", "-g", str(KEY), "-pix_fmt", "yuv420p", "-movflags", "+faststart", base + ".part.mp4")
    os.replace(base + ".part.mp4", base + ".mp4")
    ff("-framerate", str(FPS), "-i", pattern, "-c:v", "libsvtav1", "-crf", "38", "-preset", "6", "-g", str(KEY),
       "-pix_fmt", "yuv420p", base + ".part.webm")
    os.replace(base + ".part.webm", base + ".webm")
    at = int(round(FILMS[name]["poster"] * FPS))
    Image.open(os.path.join(frames, f"{at:05d}.jpg")).convert("RGB").save(base + "-poster.jpg", quality=88, optimize=True, progressive=True)
    shutil.rmtree(frames)
    return info


def timeline(name, info):
    data = {"duration": info["duration"], "parts": info["parts"],
            "about": f"The {name} film (assets/tv/{name}-*): where each button's part starts, and when the TV answered each "
                     "press, in seconds. Written by tools/scenes.py from tools/scenes/films.js."}
    with open(os.path.join(DATA, name + ".json"), "w") as f:
        json.dump(data, f, indent=1)
        f.write("\n")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--list" in sys.argv:
        for n, s in SHOTS.items():
            print(n if s.get("full", True) else f"({n})", " ".join(s.get("parts", {})))
        print("films:", " ".join(FILMS))
        return
    looks = LOOKS
    if "--look" in sys.argv:
        looks = (sys.argv[sys.argv.index("--look") + 1],)
        args = [a for a in args if a not in looks]
    films = [a for a in args if a in FILMS] or (list(FILMS) if "--films" in sys.argv else [])
    stills = [a for a in args if a not in FILMS]
    todo = [n for n, s in SHOTS.items() if (not args and "--films" not in sys.argv) or n in stills
            or set(stills) & set(s.get("parts", {}))]
    if not todo and not films:
        sys.exit("✗ Nothing by that name (--list shows what there is)")
    prepare()
    from playwright.sync_api import sync_playwright
    url = serve()
    made = 0
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1280, "height": 720}, device_scale_factor=2)
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        for name in todo:
            spec = SHOTS[name]
            for look in (("dark",) if spec.get("one") else looks):
                page.goto(f"{url}?scene={spec.get('scene', name)}&look={look}")
                page.wait_for_function("window.READY !== undefined", timeout=30000)
                ready = page.evaluate("window.READY")
                if ready is not True or errors:
                    sys.exit(f"✗ {name} ({look}): {ready} {errors}")
                im = Image.open(io.BytesIO(page.screenshot(type="png"))).convert("RGB")
                if spec.get("one"):
                    im.save(os.path.join(OUT, name + ".jpg"), quality=86, optimize=True, progressive=True)
                    made += 1
                elif spec.get("full", True):
                    save(im, name, look)
                    made += 1
                for part, box in spec.get("parts", {}).items():
                    if args and name not in args and part not in args:
                        continue
                    save(im, part, look, box)
                    made += 1
            say(name)
        tmp = tempfile.mkdtemp(prefix="glass-films-")
        if films:
            page = browser.new_context(viewport={"width": 1280, "height": 720}, device_scale_factor=FILM_SCALE).new_page()
        try:
            for name in films:
                info = None
                for look in looks:
                    info = film(page, url, name, look, tmp)
                    made += 1
                    say(f"{name} ({look}): {info['duration']} s")
                if FILMS[name]["timeline"] and info:
                    timeline(name, info)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        browser.close()
    print(f"✓ {made} pictures and films in site/assets/tv")


if __name__ == "__main__":
    main()
