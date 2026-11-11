#!/usr/bin/env python3
"""The README's pictures (docs/readme/NAME-light.webp and NAME-dark.webp), made from the site's own, so the README, the
landing page and the docs always show the same screens:

  python3 tools/readme_pictures.py     every still the README shows (run it after tools/scenes.py)

The two moving ones are made by hand from the site's: remote (the remote film) and installer (the installer's recording).
Each picture made is recorded in site/data/pictures.json with what it was made from; tools/parity.py fails the site's
build when that picture of the site changed after the README's was made. Needs Pillow.
"""
import hashlib
import json
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "readme")
RECORD = os.path.join(ROOT, "site", "data", "pictures.json")
TV, PHONE = "site/assets/tv/", "site/assets/phone/"
# the README's picture: (the site's picture it's made from, how wide)
PICTURES = {
    "desktop": (TV + "desktop", 1600),
    "controls": (TV + "controls", 960),
    "switcher": (TV + "switcher", 960),
    "search": (TV + "search", 960),
    "settings": (TV + "settings-speed", 960),   # Settings, open on Speed & memory
    "ask-watch": (TV + "ask-watch", 960),
    "weather": (TV + "weather", 960),
    "phone-remote": (PHONE + "remote", 360),
    "phone-apps": (PHONE + "apps", 360),
    "phone-assistant": (PHONE + "assistant", 360),
    "phone-tv": (PHONE + "tv", 360),
}


def fingerprint(paths):
    """One SHA-256 for files (each by its path and its bytes): the same as tools/scenes.py and tools/parity.py."""
    h = hashlib.sha256()
    for rel in sorted(set(paths)):
        with open(os.path.join(ROOT, rel), "rb") as f:
            h.update(rel.encode() + b"\0" + hashlib.sha256(f.read()).hexdigest().encode() + b"\n")
    return h.hexdigest()


def main():
    try:
        with open(RECORD, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    readme = data.setdefault("readme", {})
    made = 0
    for name, (src, width) in PICTURES.items():
        for look in ("light", "dark"):
            rel = "%s-%s.jpg" % (src, look)
            if not os.path.isfile(os.path.join(ROOT, rel)):
                sys.exit("✗ %s isn't there: draw it first (python3 tools/scenes.py)" % rel)
            im = Image.open(os.path.join(ROOT, rel)).convert("RGB")
            im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
            out = "%s-%s.webp" % (name, look)
            im.save(os.path.join(OUT, out + ".tmp"), "WEBP", quality=82, method=6)
            os.replace(os.path.join(OUT, out + ".tmp"), os.path.join(OUT, out))
            files = [rel, "tools/readme_pictures.py"]
            readme["docs/readme/" + out] = {"from": files, "sha256": fingerprint(files)}
            made += 1
    data["readme"] = dict(sorted(readme.items()))
    with open(RECORD, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("✓ %d of the README's pictures in docs/readme (site/data/pictures.json says what from)" % made)


if __name__ == "__main__":
    main()
