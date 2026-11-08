"""The terminal: its size, its colours (light or dark, how many), keys, and drawing a whole screen without flicker.

Standard library only, macOS and Linux. Everything that changes the terminal is undone on the way out, even after
an error (Terminal is a context manager).
"""
import codecs
import os
import re
import select
import shutil
import signal
import subprocess
import sys
import time
import unicodedata

try:
    import termios
    import tty  # noqa: F401  (termios is enough; tty documents intent)
except ImportError:  # Windows outside WSL: plain mode only
    termios = None


# ---- width of text in cells ------------------------------------------------------------------------------------

def char_width(ch):
    if ch == "":
        return 0
    o = ord(ch)
    if o < 32 or 0x7F <= o < 0xA0:
        return 0
    if unicodedata.combining(ch):
        return 0
    if unicodedata.east_asian_width(ch) in ("W", "F"):
        return 2
    return 1


def width(s):
    return sum(char_width(c) for c in s)


def clip(s, w, ellipsis="…"):
    """s cut to at most w cells, ending in … when cut."""
    if w <= 0:
        return ""
    if width(s) <= w:
        return s
    out, used = [], 0
    room = w - width(ellipsis)
    for c in s:
        cw = char_width(c)
        if used + cw > room:
            break
        out.append(c)
        used += cw
    return "".join(out).rstrip() + ellipsis


def wrap(text, w):
    """Words wrapped to lines of at most w cells (a word longer than a line is broken)."""
    if w <= 0:
        return []
    lines = []
    for para in str(text).split("\n"):
        words = para.split(" ")
        line = ""
        for word in words:
            if not line:
                cand = word
            else:
                cand = line + " " + word
            if width(cand) <= w:
                line = cand
                continue
            if line:
                lines.append(line)
            while width(word) > w:
                cut, used = "", 0
                for c in word:
                    if used + char_width(c) > w:
                        break
                    cut += c
                    used += char_width(c)
                lines.append(cut)
                word = word[len(cut):]
            line = word
        lines.append(line)
    return lines


# ---- colour ----------------------------------------------------------------------------------------------------

def _hex(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# DESIGN.md §2, Terminal. Each role: (truecolor, 256-colour index, 16-colour SGR)
PALETTES = {
    "dark": {
        "ink2": ("#AEAEB2", 250, "37"),
        "hint": ("#8E8E93", 246, "90"),
        "line": ("#5F6480", 60, "90"),
        "accent": ("#FFB88D", 216, "33"),
        "ok": ("#30D158", 77, "32"),
        "warn": ("#FF9F0A", 214, "33"),
        "err": ("#FF453A", 203, "31"),
        "platter": ("#2C2C38", 236, "7"),
        "well": ("#24242E", 235, "7"),
        "track": ("#3A3A4A", 238, "90"),
    },
    "light": {
        "ink2": ("#55565B", 240, "90"),
        "hint": ("#6E6E73", 243, "90"),
        "line": ("#8E90B0", 103, "90"),
        "accent": ("#C2410C", 130, "33"),
        "ok": ("#1F7A35", 28, "32"),
        "warn": ("#C2410C", 130, "33"),
        "err": ("#D70015", 160, "31"),
        "platter": ("#E9E9F0", 254, "7"),
        "well": ("#F2F2F7", 255, "7"),
        "track": ("#D8D8E2", 252, "90"),
    },
}
# the progress fill: the sunrise, low to high (truecolor only)
SUNRISE = {"dark": ["#FF6A3D", "#FFB88D", "#FFE4DE"], "light": ["#9A3412", "#C2410C", "#E46A2E"]}
BG_ROLES = ("platter", "well")


class Theme:
    """Turns a style (a tuple of role names) into the escape codes for this terminal."""

    def __init__(self, depth=256, dark=True, uni=True):
        self.depth = depth          # 0 (no colour), 16, 256 or 24 (truecolor)
        self.dark = dark
        self.uni = uni
        self.name = "dark" if dark else "light"
        self.reduced = os.environ.get("GLASS_REDUCED_MOTION", "").lower() in ("1", "true", "yes")
        self._cache = {}

    def _color(self, role, bg=False):
        if role.startswith("#"):
            if self.depth == 24:
                r, g, b = _hex(role)
                return "%d;2;%d;%d;%d" % (48 if bg else 38, r, g, b)
            return None
        spec = PALETTES[self.name].get(role)
        if not spec or self.depth == 0:
            return None
        tc, i256, c16 = spec
        if self.depth == 24:
            r, g, b = _hex(tc)
            return "%d;2;%d;%d;%d" % (48 if bg else 38, r, g, b)
        if self.depth == 256:
            return "%d;5;%d" % (48 if bg else 38, i256)
        if bg:
            return None
        return c16

    def sgr(self, style):
        if not style:
            return "\x1b[0m"
        key = style
        hit = self._cache.get(key)
        if hit is not None:
            return hit
        parts = ["0"]
        fg_set = False
        for role in style:
            if role == "bold":
                parts.append("1")
            elif role == "dim":
                if self.depth == 0:
                    parts.append("2")
            elif role == "under":
                parts.append("4")
            elif role == "cursor":
                parts.append("7")
            elif role in BG_ROLES:
                if self.depth in (0, 16):
                    parts.append("7")
                else:
                    c = self._color(role, bg=True)
                    if c:
                        parts.append(c)
            elif role.startswith("bg#"):
                c = self._color(role[2:], bg=True)
                if c:
                    parts.append(c)
            elif not fg_set:
                c = self._color(role)
                if c:
                    parts.append(c)
                    fg_set = True
        s = "\x1b[" + ";".join(parts) + "m"
        self._cache[key] = s
        return s

    def sunrise(self, n):
        """n truecolor stops along the progress fill, low to high; [] when there's no truecolor."""
        if self.depth != 24 or n <= 0:
            return []
        stops = [_hex(c) for c in SUNRISE[self.name]]
        out = []
        for i in range(n):
            t = i / max(1, n - 1)
            seg = min(len(stops) - 2, int(t * (len(stops) - 1)))
            local = t * (len(stops) - 1) - seg
            a, b = stops[seg], stops[seg + 1]
            out.append("#%02X%02X%02X" % tuple(int(round(a[k] + (b[k] - a[k]) * local)) for k in range(3)))
        return out


# Glyphs, with ASCII stand-ins for terminals that can't show them
GLYPHS = {
    "done": ("✓", "v"), "fail": ("✗", "x"), "todo": ("·", "."), "skip": ("–", "-"), "you": ("●", "*"),
    "warn": ("!", "!"), "pick": ("❯", ">"), "dot": ("·", "-"), "arrow": ("›", ">"), "info": ("○", "o"),
    "bar": ("━", "="), "barhalf": ("╸", "-"), "barstart": ("╺", "-"), "vline": ("│", "|"), "hline": ("─", "-"),
    "tl": ("╭", "+"), "tr": ("╮", "+"), "bl": ("╰", "+"), "br": ("╯", "+"), "bullet": ("•", "*"),
    "up": ("↑", "^"), "down": ("↓", "v"), "enter": ("↵", "Enter"),
}
SPINNER = ("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏", "|/-\\")


def glyph(name, uni=True):
    g = GLYPHS[name]
    return g[0] if uni else g[1]


# ---- what this terminal can do ---------------------------------------------------------------------------------

def unicode_ok():
    enc = (getattr(sys.stdout, "encoding", "") or "").lower().replace("-", "")
    if enc not in ("utf8", "utf8mb4"):
        return False
    loc = " ".join(os.environ.get(k, "") for k in ("LC_ALL", "LC_CTYPE", "LANG")).lower().replace("-", "")
    return "utf8" in loc or sys.platform == "darwin"


def color_depth():
    if "NO_COLOR" in os.environ or os.environ.get("TERM") == "dumb":
        return 0
    want = os.environ.get("GLASS_COLOR", "").lower()
    if want in ("0", "none", "no"):
        return 0
    if want in ("16", "256", "24"):
        return int(want)
    ct = os.environ.get("COLORTERM", "").lower()
    if ct in ("truecolor", "24bit"):
        return 24
    term = os.environ.get("TERM", "")
    if os.environ.get("TERM_PROGRAM") in ("iTerm.app", "vscode", "WezTerm", "ghostty") or "kitty" in term \
            or "ghostty" in term or "alacritty" in term:
        return 24
    if "256" in term:
        return 256
    return 16


def _apple_terminal_dark():
    """Terminal.app without an answer to the colour question: its default profile and the Mac's appearance."""
    try:
        prof = subprocess.run(["defaults", "read", "com.apple.Terminal", "Default Window Settings"],
                              capture_output=True, text=True, timeout=2).stdout.strip()
    except Exception:
        prof = ""
    if prof in ("Pro", "Homebrew", "Clear Dark", "Grass", "Ocean", "Red Sands"):
        return True
    if prof in ("Clear Light", "Man Page", "Novel", "Silver Aerogel"):
        return False
    try:  # "Basic" follows the Mac's light or dark appearance
        style = subprocess.run(["defaults", "read", "-g", "AppleInterfaceStyle"], capture_output=True, text=True,
                               timeout=2).stdout.strip()
        return style.lower() == "dark"
    except Exception:
        return False


# ---- keys ------------------------------------------------------------------------------------------------------

CSI_KEYS = {"A": "up", "B": "down", "C": "right", "D": "left", "H": "home", "F": "end", "Z": "backtab"}
TILDE_KEYS = {"1": "home", "2": "insert", "3": "delete", "4": "end", "5": "pageup", "6": "pagedown", "7": "home",
              "8": "end"}
CTRL = {"\r": "enter", "\n": "enter", "\x7f": "backspace", "\x08": "backspace", "\t": "tab", "\x03": "ctrl-c",
        "\x04": "ctrl-d", "\x15": "ctrl-u", "\x17": "ctrl-w", "\x0c": "ctrl-l", "\x01": "home", "\x05": "end"}
CSI_RE = re.compile(r"\x1b\[([0-9;?]*)([@-~])")
SS3_RE = re.compile(r"\x1bO([@-~])")


def parse_keys(buf):
    """Keys from what the terminal sent: [(kind, value)] and what's left over (an unfinished sequence)."""
    out = []
    i = 0
    n = len(buf)
    while i < n:
        c = buf[i]
        if c == "\x1b":
            if i + 1 >= n:
                return out, buf[i:]   # maybe the start of a sequence: wait a moment for the rest
            if buf.startswith("\x1b[200~", i):
                end = buf.find("\x1b[201~", i + 6)
                if end < 0:
                    return out, buf[i:]
                out.append(("paste", buf[i + 6:end]))
                i = end + 6
                continue
            m = CSI_RE.match(buf, i)
            if m:
                params, final = m.group(1), m.group(2)
                if final == "~":
                    k = TILDE_KEYS.get(params.split(";")[0])
                    if k:
                        out.append(("key", k))
                elif final in CSI_KEYS:
                    out.append(("key", CSI_KEYS[final]))
                elif final in "IO":   # focus in/out: ignored
                    pass
                i = m.end()
                continue
            m = SS3_RE.match(buf, i)
            if m:
                k = CSI_KEYS.get(m.group(1))
                if k:
                    out.append(("key", k))
                i = m.end()
                continue
            if buf[i + 1] == "[" or buf[i + 1] == "O":
                return out, buf[i:]   # incomplete
            out.append(("key", "esc"))
            i += 1
            continue
        if c in CTRL:
            if c == "\r" and i + 1 < n and buf[i + 1] == "\n":
                i += 1
            out.append(("key", CTRL[c]))
        elif ord(c) >= 32:
            out.append(("char", c))
        i += 1
    return out, ""


# ---- the terminal ----------------------------------------------------------------------------------------------

class Terminal:
    """The whole terminal, for one full-screen session: raw keys, the alternate screen, no cursor, no wrapping."""

    def __init__(self, out=None, fd=None):
        self.out = out or sys.stdout
        self.fd = fd if fd is not None else sys.stdin.fileno()
        self.saved = None
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.pending = ""
        self.resized = True
        self.prev = []
        self.size = (80, 24)
        self.theme = None
        self._old_winch = None

    # -- set up and undo
    def __enter__(self):
        if termios is None:
            raise OSError("no terminal control here")
        self.saved = termios.tcgetattr(self.fd)
        attrs = termios.tcgetattr(self.fd)
        attrs[0] &= ~(termios.IXON | termios.ICRNL | termios.INLCR | termios.IGNCR)
        attrs[3] &= ~(termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN)
        attrs[6][termios.VMIN] = 0
        attrs[6][termios.VTIME] = 0
        termios.tcsetattr(self.fd, termios.TCSADRAIN, attrs)
        dark = self._detect_dark()
        self.theme = Theme(color_depth(), dark, unicode_ok())
        self.write("\x1b[?1049h\x1b[?25l\x1b[?7l\x1b[?2004h\x1b[2J")
        self.flush()
        try:
            self._old_winch = signal.signal(signal.SIGWINCH, self._on_winch)
        except (ValueError, AttributeError):
            pass
        self.size = self.get_size()
        return self

    def __exit__(self, *exc):
        self.restore()
        return False

    def restore(self):
        if self.saved is None:
            return
        try:
            self.write("\x1b[0m\x1b[?2004l\x1b[?7h\x1b[?25h\x1b[?1049l")
            self.flush()
        finally:
            try:
                termios.tcsetattr(self.fd, termios.TCSADRAIN, self.saved)
            except termios.error:
                pass
            self.saved = None
            if self._old_winch is not None:
                try:
                    signal.signal(signal.SIGWINCH, self._old_winch)
                except (ValueError, TypeError):
                    pass

    def _on_winch(self, *_):
        self.resized = True

    def get_size(self):
        s = shutil.get_terminal_size((80, 24))
        return max(1, s.columns), max(1, s.lines)

    # -- light or dark: ask the terminal its background colour (OSC 11)
    def _detect_dark(self):
        want = os.environ.get("GLASS_THEME", "").lower()
        if want in ("light", "dark"):
            return want == "dark"
        answer = self._ask_background()
        if answer is not None:
            r, g, b = answer
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 0.5
        fgbg = os.environ.get("COLORFGBG", "")
        if fgbg:
            try:
                bg = int(fgbg.split(";")[-1])
                return bg in (0, 1, 2, 3, 4, 5, 6, 8)
            except ValueError:
                pass
        if os.environ.get("TERM_PROGRAM") == "Apple_Terminal":
            return _apple_terminal_dark()
        return True

    def _ask_background(self):
        if os.environ.get("TERM", "").startswith("screen") and not os.environ.get("TMUX"):
            return None
        try:
            self.write("\x1b]11;?\x07")
            self.flush()
        except OSError:
            return None
        got = ""
        end = time.time() + 0.25
        while time.time() < end:
            r, _, _ = select.select([self.fd], [], [], max(0.0, end - time.time()))
            if not r:
                break
            chunk = os.read(self.fd, 256).decode("utf-8", "replace")
            got += chunk
            if "\x07" in got or "\x1b\\" in got:
                break
        m = re.search(r"\x1b\]11;rgba?:([0-9a-fA-F]+)/([0-9a-fA-F]+)/([0-9a-fA-F]+)", got)
        rest = re.sub(r"\x1b\]11;[^\x07\x1b]*(\x07|\x1b\\)?", "", got)
        if rest:
            self.pending += rest   # someone typed while we asked
        if not m:
            return None
        vals = []
        for h in m.groups():
            vals.append(int(h, 16) / float(16 ** len(h) - 1))
        return tuple(vals)

    # -- output
    def write(self, s):
        self.out.write(s)

    def flush(self):
        try:
            self.out.flush()
        except (BrokenPipeError, OSError):
            pass

    def draw(self, rows):
        """Puts a whole frame (one string per row, escape codes included) on screen, rewriting only changed rows."""
        size = self.get_size()
        if self.resized or size != self.size:
            self.resized = False
            self.size = size
            self.prev = []
            self.write("\x1b[0m\x1b[2J")
        buf = []
        for y, row in enumerate(rows):
            if y < len(self.prev) and self.prev[y] == row:
                continue
            buf.append("\x1b[%d;1H%s\x1b[0m" % (y + 1, row))
        if buf:
            self.write("".join(buf))
            self.flush()
        self.prev = list(rows)

    # -- input
    def keys(self, timeout):
        """Keys pressed within timeout seconds (maybe none)."""
        out = []
        if self.pending:
            ks, self.pending = parse_keys(self.pending)
            out.extend(ks)
            if out:
                return out
        try:
            r, _, _ = select.select([self.fd], [], [], timeout)
        except (InterruptedError, OSError):
            return out
        if not r:
            if self.pending == "\x1b":     # Esc on its own
                self.pending = ""
                out.append(("key", "esc"))
            return out
        try:
            data = os.read(self.fd, 4096)
        except OSError:
            return out
        if not data:
            return out
        text = self.pending + self.decoder.decode(data)
        ks, self.pending = parse_keys(text)
        out.extend(ks)
        if self.pending == "\x1b":
            r, _, _ = select.select([self.fd], [], [], 0.03)
            if not r:
                self.pending = ""
                out.append(("key", "esc"))
        return out
