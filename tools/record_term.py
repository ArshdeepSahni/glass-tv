#!/usr/bin/env python3
"""Records a full-screen program as text frames, for the website's recordings of the installer: runs it in a
pseudo-terminal, samples the screen every 100 ms, keeps the frames that changed, and writes compact JSON:
  {"w":W,"h":H,"styles":[[fg,bg,bold,reverse],...],"frames":[[t_ms,{row:[[text,style],...]}],...]}

  tools/record_term.py OUT.json W H THEME -- cmd...     one recording
  tools/record_term.py --site                           every recording the website plays, into site/assets/term:
                                                        setup, update, status, doctor and restore, each at 100 x 30 and
                                                        at 44 x 30 (NAME-narrow.json, for phones), from ./glass demo

REC_KEYS="3.5:down,4.2:enter" presses keys at those seconds (down, up, enter, esc, q, or text).
REC_END="text": once the screen shows it, records 3 more seconds and stops (the done screen waits for a key).
Needs pyte (pip install pyte)."""

import fcntl
import json
import os
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time

import pyte


def main():
    out, w, h, theme = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    cmd = sys.argv[sys.argv.index("--") + 1:]
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", h, w, 0, 0))
    env = dict(os.environ, TERM="xterm-256color", LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8", COLORTERM="truecolor",
               GLASS_HOME=os.environ.get("GLASS_HOME", "/tmp/glass-rec-home"))
    p = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave, env=env, start_new_session=True)
    os.close(slave)
    screen = pyte.Screen(w, h)
    stream = pyte.ByteStream(screen)
    styles, sidx = [], {}
    frames = []
    prev = [None] * h
    t0 = time.time()
    last_sample = 0
    done = False
    KEYS = {"down": "\x1b[B", "up": "\x1b[A", "enter": "\r", "esc": "\x1b", "q": "q"}
    plan = []
    end_text, end_at = os.environ.get("REC_END", ""), None
    for part in filter(None, os.environ.get("REC_KEYS", "").split(",")):
        at, key = part.split(":", 1)
        plan.append((float(at), KEYS.get(key, key)))
    while not done:
        r, _, _ = select.select([master], [], [], 0.02)
        if r:
            try:
                data = os.read(master, 65536)
            except OSError:
                data = b""
            if not data:
                done = True
            else:
                if b"\x1b]11;?" in data:
                    rgb = "1e1e/1e1e/1e1e" if theme == "dark" else "ffff/ffff/ffff"
                    os.write(master, ("\x1b]11;rgb:%s\x07" % rgb).encode())
                stream.feed(data)
        if p.poll() is not None and not r:
            done = True
        now = time.time() - t0
        while plan and now >= plan[0][0]:
            os.write(master, plan.pop(0)[1].encode())
        if end_text and end_at is None and any(end_text in "".join(c.data for c in screen.buffer[y].values())
                                                 for y in range(h)):
            end_at = now + 3.0
        if end_at is not None and now >= end_at:
            done = True
        if now - last_sample >= 0.1 or done:
            last_sample = now
            changed = {}
            for y in range(h):
                line = screen.buffer[y]
                runs, cur, text = [], None, ""
                for x in range(w):
                    c = line[x]
                    st = (c.fg, c.bg, bool(c.bold), bool(c.reverse))
                    ch = c.data or " "
                    if st != cur:
                        if text:
                            runs.append([text, sidx[cur]])
                        if st not in sidx:
                            sidx[st] = len(styles)
                            styles.append(list(st))
                        cur, text = st, ch
                    else:
                        text += ch
                if text:
                    runs.append([text.rstrip() if sidx[cur] == 0 else text, sidx[cur]])
                key = json.dumps(runs, ensure_ascii=False)
                if key != prev[y]:
                    prev[y] = key
                    changed[y] = runs
            if changed:
                frames.append([int(now * 1000), changed])
    try:
        os.killpg(p.pid, signal.SIGTERM)
    except OSError:
        pass
    json.dump({"w": w, "h": h, "styles": styles, "frames": frames}, open(out, "w"), ensure_ascii=False,
              separators=(",", ":"))
    print("frames", len(frames), "styles", len(styles), "bytes", os.path.getsize(out), "secs", round(time.time() - t0, 1))


def site():
    """Every recording the website plays. The demo answers its own questions (--auto), slowed down so each step can be
    read (GLASS_DEMO_PACE=1.6); each run gets a fresh, throwaway home so nothing of yours is in it."""
    import shutil
    import tempfile
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "site", "assets", "term")
    for flow in ("setup", "update", "status", "doctor", "restore"):
        for w, suffix in ((100, ""), (44, "-narrow")):
            home = tempfile.mkdtemp(prefix="glass-rec-")
            env = dict(os.environ, GLASS_HOME=home, GLASS_DEMO_PACE="1.6", REC_END="Enter close")
            target = os.path.join(out, flow + suffix + ".json")
            print("%-8s %3d x 30  " % (flow, w), end="", flush=True)
            subprocess.run([sys.executable, os.path.abspath(__file__), target, str(w), "30", "dark", "--",
                            os.path.join(root, "glass"), "demo", flow, "--auto", "1.2"], env=env, check=True)
            shutil.rmtree(home, ignore_errors=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["--site"]:
        site()
    else:
        main()
