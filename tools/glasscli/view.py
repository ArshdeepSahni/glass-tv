"""What the screen looks like: one frame, drawn from a snapshot of the state. No input and no threads here.

The layout follows docs/design/installer.md: a box filling the terminal, words set into its top and bottom lines,
a heading, the steps, and the one thing happening now (side by side from 100 columns, stacked below that).
"""
import time

from .term import clip, glyph, width, wrap, SPINNER

MIN_W, MIN_H = 60, 18
TWO_COLUMNS = 100


class Canvas:
    """A grid of cells, each a character and a style (a tuple of role names)."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.ch = [[" "] * w for _ in range(h)]
        self.st = [[()] * w for _ in range(h)]

    def put(self, y, x, text, style=(), limit=None):
        """Writes text at (y, x), at most `limit` cells; keeps a background already under it. Returns cells used."""
        if y < 0 or y >= self.h:
            return 0
        end = self.w if limit is None else min(self.w, x + limit)
        used = 0
        for c in text:
            cw = width(c)
            if cw == 0:
                continue
            if x + cw > end:
                break
            if x >= 0:
                bg = tuple(r for r in self.st[y][x] if r in ("platter", "well") or r.startswith("bg#"))
                self.ch[y][x] = c
                self.st[y][x] = tuple(style) + tuple(r for r in bg if r not in style)
                if cw == 2 and x + 1 < self.w:
                    self.ch[y][x + 1] = ""
                    self.st[y][x + 1] = self.st[y][x]
            x += cw
            used += cw
        return used

    def bg(self, y, x, w, role):
        if y < 0 or y >= self.h:
            return
        for i in range(max(0, x), min(self.w, x + w)):
            self.st[y][i] = tuple(r for r in self.st[y][i] if r not in ("platter", "well")) + (role,)

    def rows(self, theme):
        out = []
        for y in range(self.h):
            parts, cur = [], None
            for x in range(self.w):
                c = self.ch[y][x]
                if c == "":
                    continue
                s = self.st[y][x]
                if s != cur:
                    parts.append(theme.sgr(s))
                    cur = s
                parts.append(c)
            parts.append("\x1b[0m")
            out.append("".join(parts))
        return out


def fmt_elapsed(sec):
    sec = int(sec)
    if sec >= 3600:
        return "%d:%02d:%02d" % (sec // 3600, sec // 60 % 60, sec % 60)
    return "%d:%02d" % (sec // 60, sec % 60)


# ---- pieces --------------------------------------------------------------------------------------------------------

def border(cv, s, theme):
    u = theme.uni
    w, h = cv.w, cv.h
    hl, vl = glyph("hline", u), glyph("vline", u)
    line = ("line",)
    cv.put(0, 0, glyph("tl", u) + hl * (w - 2) + glyph("tr", u), line)
    cv.put(h - 1, 0, glyph("bl", u) + hl * (w - 2) + glyph("br", u), line)
    for y in range(1, h - 1):
        cv.put(y, 0, vl, line)
        cv.put(y, w - 1, vl, line)
    # words in the top line: Glass on the left, where we are on the right
    x = 2
    x += cv.put(0, x, " ", line)
    x += cv.put(0, x, s.get("brand", "Glass"), ("bold",))
    ver = s.get("version")
    if ver:
        x += cv.put(0, x, " " + ver, ("hint",))
    cv.put(0, x, " ", line)
    where = s.get("where", "")
    if where:
        where = clip(where, max(0, w - x - 8))
        rx = w - 3 - width(where) - 2
        if rx > x + 2:
            cv.put(0, rx, " ", line)
            cv.put(0, rx + 1, where, ("ink2",))
            cv.put(0, rx + 1 + width(where), " ", line)
    # the keys that work now, and where the log is, in the bottom line
    keys = s.get("keys", [])
    log = s.get("log", "")
    room = w - 6
    key_cells = sum(width(k) + 1 + width(d) for k, d in keys) + 3 * max(0, len(keys) - 1) + 2
    show_log = log and room - key_cells - width(log) - 6 > 0
    x = 2
    if keys:
        x += cv.put(h - 1, x, " ", line)
        for i, (k, d) in enumerate(keys):
            if i:
                x += cv.put(h - 1, x, " " + glyph("dot", theme.uni) + " ", ("hint",))
            if x + width(k) + 1 + width(d) > w - 4:
                break
            x += cv.put(h - 1, x, k, ("bold",))
            x += cv.put(h - 1, x, " " + d, ("hint",))
        cv.put(h - 1, x, " ", line)
    if show_log:
        lx = w - 3 - width(log) - 2
        cv.put(h - 1, lx, " ", line)
        cv.put(h - 1, lx + 1, log, ("hint",))
        cv.put(h - 1, lx + 1 + width(log), " ", line)


def progress_bar(cv, y, x, w, frac, label, theme, tick):
    """━━━━━━╸━━━━  label. frac None: a short segment sweeping back and forth."""
    u = theme.uni
    full_c, half_c = glyph("bar", u), glyph("barhalf", u)
    label = label or ""
    lw = width(label)
    bw = max(8, min(48, w - lw - 2))
    colors = theme.sunrise(bw)
    if frac is None:
        seg = max(3, bw // 5)
        span = bw - seg
        pos = int(abs((tick % (2 * span)) - span)) if span > 0 else 0
        for i in range(bw):
            on = pos <= i < pos + seg
            cv.put(y, x + i, full_c, ((colors[i],) if colors and on else ("accent",)) if on else ("track",))
    else:
        frac = max(0.0, min(1.0, frac))
        cells = frac * bw
        full = int(cells)
        half = cells - full >= 0.5
        for i in range(bw):
            if i < full:
                cv.put(y, x + i, full_c, (colors[i],) if colors else ("accent",))
            elif i == full and half:
                cv.put(y, x + i, half_c, (colors[i],) if colors else ("accent",))
            else:
                cv.put(y, x + i, full_c, ("track",))
    if label:
        cv.put(y, x + bw + 2, label, ("ink2",))


STATE_GLYPH = {"done": ("done", "ok"), "fail": ("fail", "err"), "todo": ("todo", "hint"), "skip": ("skip", "hint"),
               "you": ("you", "accent"), "warn": ("warn", "warn"), "info": ("info", "hint")}


def state_mark(state, theme, tick):
    if state == "run":
        frames = SPINNER[0] if theme.uni else SPINNER[1]
        if theme.reduced:
            return glyph("you", theme.uni), "accent"
        return frames[tick % len(frames)], "accent"
    g, role = STATE_GLYPH.get(state, ("todo", "hint"))
    return glyph(g, theme.uni), role


def step_rows(cv, y, x, w, steps, theme, tick, max_rows):
    """The steps, one per row; when there isn't room, a window around the current one. Returns rows used."""
    n = len(steps)
    first, last = 0, n
    if n > max_rows:
        cur = next((i for i, s in enumerate(steps) if s["state"] in ("run", "you", "fail")), None)
        if cur is None:
            cur = next((i for i, s in enumerate(steps) if s["state"] == "todo"), n - 1)
        first = max(0, min(cur - max_rows // 2, n - max_rows))
        last = first + max_rows
    name_w = min(22, max(12, max((width(s["name"]) for s in steps), default=12) + 2))
    row = y
    for i in range(first, last):
        s = steps[i]
        mark, role = state_mark(s["state"], theme, tick)
        st = s["state"]
        cv.put(row, x, mark, (role,))
        name_style = ("bold",) if st in ("run", "you", "fail") else ("hint",) if st in ("todo", "skip") else ()
        cv.put(row, x + 3, clip(s["name"], name_w - 1), name_style)
        res = s.get("result") or ""
        if st == "you" and not res:
            res = "Your turn"
        if res:
            rstyle = ("accent",) if st == "you" else ("err",) if st == "fail" else ("ink2",) if st == "run" else ("hint",)
            cv.put(row, x + 3 + name_w, clip(res, w - 3 - name_w), rstyle)
        row += 1
    return row - y


def paragraph(cv, y, x, w, text, style, max_rows):
    lines = wrap(text, w)
    for i, ln in enumerate(lines[:max_rows]):
        if i == max_rows - 1 and len(lines) > max_rows:
            ln = clip(ln + " …", w)
        cv.put(y + i, x, ln, style)
    return min(len(lines), max_rows)


def prompt_view(cv, y, x, w, h, p, theme, tick):
    """A question: choices on a platter, or a field. Returns rows used."""
    row = y
    if p["kind"] == "choice":
        for i, (label, detail) in enumerate(p["options"]):
            if row >= y + h:
                break
            on = i == p["index"]
            lw = min(w, max(36, width(label) + width(detail or "") + 8))
            if on:
                cv.bg(row, x, lw, "platter")
                cv.put(row, x + 1, glyph("pick", theme.uni), ("accent", "bold"))
            used = cv.put(row, x + 3, clip(label, w - 4), ("bold",) if on else ())
            if detail:
                cv.put(row, x + 3 + used + 2, clip(detail, max(0, w - used - 6)), ("ink2",) if on else ("hint",))
            row += 1
        return row - y
    # a field: a quiet well with the words (or a dot for each character of a secret) and the cursor
    fw = min(w, max(30, min(56, w)))
    cv.bg(row, x, fw, "well")
    val = p.get("value", "")
    shown = (glyph("bullet", theme.uni) * len(val)) if p["kind"] == "secret" else val
    cur = len(shown) if p["kind"] == "secret" else p.get("cursor", len(val))
    inner = fw - 3
    start = max(0, cur - inner + 1)
    vis = shown[start:start + inner]
    if not val and p.get("placeholder"):
        cv.put(row, x + 1, clip(p["placeholder"], inner), ("hint",))
        cv.put(row, x + 1, (p["placeholder"] or " ")[0], ("hint", "cursor"))
    else:
        cv.put(row, x + 1, vis, ())
        cx = x + 1 + width(shown[start:cur])
        under = shown[cur] if cur < len(shown) else " "
        cv.put(row, cx, under, ("cursor",))
    row += 1
    if p["kind"] == "secret" and val:
        cv.put(row, x + 1, "%d characters" % len(val), ("hint",))
        row += 1
    if p.get("error"):
        cv.put(row, x, glyph("fail", theme.uni), ("err",))
        row += paragraph(cv, row, x + 2, w - 2, p["error"], (), 2)
    return row - y


def status_view(cv, y, x, w, h, s, theme, tick, now):
    """The one thing happening now (or the question, or what went wrong)."""
    st = s.get("status") or {}
    fail = s.get("failure")
    prompt = s.get("prompt")
    row = y
    bottom = y + h
    if s.get("peek"):
        cv.put(row, x, "The log", ("bold",))
        row += 1
        cv.put(row, x, clip(s.get("log", ""), w), ("hint",))
        row += 2
        tail = s.get("log_tail", [])
        room = bottom - row
        for ln in tail[-room:] if room > 0 else []:
            cv.put(row, x, clip(ln, w), ("ink2",))
            row += 1
        return
    if fail:
        g = glyph("fail", theme.uni)
        cv.put(row, x, g, ("err",))
        cv.put(row, x + 2, clip(fail["title"], w - 2), ("bold",))
        row += 2
        row += paragraph(cv, row, x, w, fail.get("text", ""), ("ink2",), max(1, bottom - row - 1))
        tail = [t for t in fail.get("tail", []) if t.strip()][-3:]
        if tail and row + 2 < bottom:
            row += 1
            for ln in tail:
                if row >= bottom:
                    break
                cv.put(row, x + 2, clip(ln.strip(), w - 2), ("hint",))
                row += 1
        return
    if prompt:
        cv.put(row, x, clip(prompt["title"], w), ("bold",))
        row += 1
        if prompt.get("text"):
            row += paragraph(cv, row, x, w, prompt["text"], ("ink2",), max(1, bottom - row - 3))
        if prompt.get("lines"):
            row += 1
        for kind, text in prompt.get("lines", []):
            if row >= bottom - 2:
                break
            row = numbered_line(cv, row, x, w, kind, text, theme)
        row += 1
        prompt_view(cv, row, x, w, bottom - row, prompt, theme, tick)
        return
    title = st.get("title")
    if title:
        cv.put(row, x, clip(title, w), ("bold",))
        row += 1
    if st.get("text"):
        row += paragraph(cv, row, x, w, st["text"], ("ink2",), max(1, bottom - row - 2))
    if st.get("lines") and (title or st.get("text")):
        row += 1
    for kind, text in st.get("lines", []):
        if row >= bottom - 1:
            break
        row = numbered_line(cv, row, x, w, kind, text, theme)
    prog = st.get("progress")
    if prog is not None and row + 1 < bottom:
        row += 1
        progress_bar(cv, row, x, w, prog[0], prog[1], theme, tick)
        row += 1
    items = st.get("items") or []
    if items and row + 1 < bottom:
        row += 1
        iw = min(26, max(width(t) for _, t, _ in items) + 2)
        for state, text, right in items:
            if row >= bottom:
                break
            mark, role = state_mark(state, theme, tick)
            cv.put(row, x, mark, (role,))
            cv.put(row, x + 2, clip(text, iw), ("bold",) if state in ("run", "you") else ("hint",) if state == "todo" else ())
            if right:
                cv.put(row, x + 2 + iw + 1, clip(right, w - iw - 3), ("ink2",))
            row += 1
    note = st.get("note")
    started = st.get("started")
    elapsed = ""
    if started and now - started >= 3 and not st.get("no_clock"):
        elapsed = fmt_elapsed(now - started)
    tail_line = "  ".join(t for t in (elapsed, note) if t)
    if tail_line and row + 1 < bottom:
        row += 1
        paragraph(cv, row, x, w, tail_line, ("hint",), max(1, bottom - row))


def numbered_line(cv, row, x, w, kind, text, theme):
    """A line of guidance: ("n", "1  Settings › …") numbered, ("p", text) plain, ("k", "label\tvalue")."""
    if kind == "n":
        num, _, rest = text.partition(" ")
        cv.put(row, x, num, ("accent",))
        lines = wrap(rest.strip(), w - 4)
        for i, ln in enumerate(lines[:3]):
            cv.put(row + i, x + 3, ln, ())
        return row + max(1, min(3, len(lines)))
    if kind == "k":
        label, _, value = text.partition("\t")
        cv.put(row, x, clip(label, 15), ("ink2",))
        lines = wrap(value, max(10, w - 16))
        for i, ln in enumerate(lines[:3]):
            cv.put(row + i, x + 16, ln, ())
        return row + max(1, min(3, len(lines)))
    if kind == "blank":
        return row + 1
    lines = wrap(text, w)
    for i, ln in enumerate(lines[:4]):
        cv.put(row + i, x, ln, ("ink2",) if kind == "p" else ("hint",))
    return row + max(1, min(4, len(lines)))


def finish_view(cv, y, x, w, h, fin, theme):
    row = y
    ok = fin.get("ok", True)
    cv.put(row, x, glyph("done" if ok else "fail", theme.uni), ("ok",) if ok else ("err",))
    cv.put(row, x + 2, clip(fin["title"], w - 2), ("bold",))
    row += 2
    if fin.get("text"):
        row += paragraph(cv, row, x, w, fin["text"], ("ink2",), 4) + 1
    for kind, text in fin.get("lines", []):
        if row >= y + h:
            break
        row = numbered_line(cv, row, x, w, kind, text, theme)


def table_view(cv, y, x, w, h, rows, theme):
    """The status board: glyph, what, how it is."""
    lw = min(20, max([width(r[1]) for r in rows] + [8]) + 2)
    row = y
    for state, label, value in rows:
        if row >= y + h:
            break
        if state:
            mark, role = state_mark(state, theme, 0)
            cv.put(row, x, mark, (role,))
        cv.put(row, x + 2, clip(label, lw - 1), ("ink2",))
        lines = wrap(value, max(10, w - lw - 2))
        for i, ln in enumerate(lines[:2]):
            cv.put(row + i, x + 2 + lw, ln, ())
        row += max(1, min(2, len(lines)))


# ---- the frame -----------------------------------------------------------------------------------------------------

def render(s, w, h, theme, tick):
    """One frame (a list of h strings) from the state snapshot s."""
    now = time.time()
    cv = Canvas(w, h)
    if w < MIN_W or h < MIN_H:
        msg = "Make the window a little bigger"
        sub = "%d × %d at least" % (MIN_W, MIN_H)
        cy = max(0, h // 2 - 1)
        cv.put(cy, max(0, (w - width(msg)) // 2), clip(msg, w), ("bold",))
        cv.put(cy + 1, max(0, (w - width(sub)) // 2), clip(sub, w), ("hint",))
        return cv.rows(theme)
    border(cv, s, theme)
    px = 3 if w >= 72 else 2
    py = 2 if h >= 30 else 1
    x0, x1 = 1 + px, w - 1 - px
    y0, y1 = 1 + py, h - 1 - py
    iw = x1 - x0
    row = y0
    if s.get("heading"):
        cv.put(row, x0, clip(s["heading"], iw), ("bold",))
        row += 1
    if s.get("subheading"):
        row += paragraph(cv, row, x0, iw, s["subheading"], ("ink2",), 2)
    if s.get("heading") or s.get("subheading"):
        row += 1
    body_h = y1 - row
    steps = s.get("steps") or []
    fin = s.get("finish")
    board = s.get("board")
    if board is not None:
        if s.get("status") and not board:
            status_view(cv, row, x0, iw, body_h, s, theme, tick, now)
        else:
            table_view(cv, row, x0, iw, body_h, board, theme)
            if s.get("prompt") or s.get("failure"):
                used = len(board) + 1
                status_view(cv, row + used, x0, iw, body_h - used, s, theme, tick, now)
        return cv.rows(theme)
    if not steps:
        if fin:
            finish_view(cv, row, x0, iw, body_h, fin, theme)
        else:
            status_view(cv, row, x0, iw, body_h, s, theme, tick, now)
        return cv.rows(theme)
    if w >= TWO_COLUMNS:
        lw = min(52, max(36, iw * 9 // 20))
        step_rows(cv, row, x0, lw, steps, theme, tick, body_h)
        dx = x0 + lw + 2
        for yy in range(row, y1):
            cv.put(yy, dx, glyph("vline", theme.uni), ("line",))
        rx = dx + 3
        rw = x1 - rx
        if fin:
            finish_view(cv, row, rx, rw, body_h, fin, theme)
        else:
            status_view(cv, row, rx, rw, body_h, s, theme, tick, now)
    elif fin:
        finish_view(cv, row, x0, iw, body_h, fin, theme)   # every step is done: the result gets the room
    else:
        status_min = 7
        max_rows = max(1, min(len(steps), body_h - 2 - status_min))
        used = step_rows(cv, row, x0, iw, steps, theme, tick, max_rows)
        row += used + 1
        cv.put(row, x0, glyph("hline", theme.uni) * iw, ("line",))
        row += 2
        if fin:
            finish_view(cv, row, x0, iw, y1 - row, fin, theme)
        else:
            status_view(cv, row, x0, iw, y1 - row, s, theme, tick, now)
    return cv.rows(theme)
