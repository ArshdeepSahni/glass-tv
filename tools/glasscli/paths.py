"""Where Glass keeps things on this computer.

Everything lives in one folder, GLASS_HOME: ~/.glass, or ~/Desktop/tv on the computer that made Glass (it kept its
tools and keys there before this installer existed, and still does). Removing that folder removes what Glass added
to this computer, apart from the repository itself.
"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEGACY = Path.home() / "Desktop" / "tv"


def _home():
    h = os.environ.get("GLASS_HOME")
    if h:
        return Path(h).expanduser()
    if (LEGACY / "toolchain").is_dir() or (LEGACY / ".updates.env").is_file() or (LEGACY / ".env").is_file():
        return LEGACY
    return Path.home() / ".glass"


HOME = _home()
TOOLCHAIN = Path(os.environ.get("GLASS_TOOLCHAIN") or HOME / "toolchain").expanduser()
if not (TOOLCHAIN / "android-sdk").is_dir() and (HOME / "_bridge" / "android-sdk").is_dir():
    TOOLCHAIN = HOME / "_bridge"
ENV_FILE = Path(os.environ.get("GLASS_ENV") or HOME / ".env").expanduser()
UPDATES_FILE = Path(os.environ.get("GLASS_UPDATES_FILE") or HOME / ".updates.env").expanduser()
LOGS = HOME / "logs"
TV_FILE = ROOT / ".tv"
DIST = ROOT / "dist"
DEBUG_KEYSTORE = Path.home() / ".android" / "debug.keystore"


def keystore():
    """The key Glass is signed with. Android takes an update only when it's signed with the same key as the Glass
    already on the TV, so this one file has to be kept (and a copy of it kept somewhere safe)."""
    k = os.environ.get("GLASS_KEYSTORE")
    if k:
        return Path(k).expanduser()
    if HOME == LEGACY:
        return DEBUG_KEYSTORE
    return HOME / "glass.keystore"


def short(p):
    """A path with ~ for the home folder."""
    s = str(p)
    home = str(Path.home())
    if s == home:
        return "~"
    if s.startswith(home + os.sep):
        return "~" + s[len(home):]
    return s


def mask_home(s):
    return str(s).replace(str(Path.home()), "~")


def read_env(path):
    """KEY=value lines (comments and blank lines skipped). Values are never printed by anything here."""
    out = {}
    try:
        for line in Path(path).read_text().splitlines():
            t = line.strip()
            if not t or t.startswith("#") or "=" not in t:
                continue
            k, v = t.split("=", 1)
            k = k.strip()
            if k.startswith("export "):
                k = k[7:].strip()
            out[k] = v.strip().strip("'\"")
    except OSError:
        pass
    return out


def write_env(path, values, header):
    """Writes KEY=value lines, readable by this user only."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    old = os.umask(0o077)
    try:
        with open(path, "w") as f:
            f.write("# %s\n" % header)
            for k, v in values.items():
                f.write("%s=%s\n" % (k, v))
    finally:
        os.umask(old)
    os.chmod(path, 0o600)
