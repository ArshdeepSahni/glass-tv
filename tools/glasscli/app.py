"""The conversation between a flow (setup, update, …) and the person: steps, what's happening, questions.

A flow calls the same methods whichever way it's shown: full screen (Screen, the box) or plain lines (Plain, for a
pipe, CI or --plain). Every command a flow runs is written to the log file, never to the screen.
"""
import collections
import getpass
import os
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
import traceback

from . import paths
from .term import Terminal, glyph, unicode_ok
from . import view


class Failed(Exception):
    """A step stopped. title: what stopped; text: what happened and the one next step; tail: the log's last lines."""

    def __init__(self, title, text="", tail=None):
        super().__init__(title)
        self.title = title
        self.text = text
        self.tail = list(tail or [])


class Cancelled(Exception):
    """The person chose to stop."""


KEEP = object()


class Base:
    def __init__(self, log_name, assume_yes=False):
        paths.LOGS.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.log_path = paths.LOGS / ("%s-%s.log" % (log_name, stamp))
        self.logf = open(self.log_path, "a", encoding="utf-8", errors="replace")
        try:
            os.chmod(self.log_path, 0o600)
        except OSError:
            pass
        self.tail = collections.deque(maxlen=400)
        self.lock = threading.RLock()
        self.cancelled = False
        self.proc = None
        self.assume_yes = assume_yes
        self.exit_code = 0
        self.error = None
        self.s = {"brand": "Glass", "version": "", "where": "", "heading": "", "subheading": "", "steps": [],
                  "status": {}, "prompt": None, "failure": None, "finish": None, "board": None, "keys": [],
                  "log": paths.short(self.log_path), "peek": False}
        self.current = None

    # ---- the log ---------------------------------------------------------------------------------------------------
    def log(self, text):
        for ln in str(text).splitlines() or [""]:
            self.tail.append(ln)
            try:
                self.logf.write(time.strftime("%H:%M:%S ") + ln + "\n")
                self.logf.flush()
            except ValueError:
                pass

    # ---- the plan ----------------------------------------------------------------------------------------------------
    def begin(self, where, heading="", subheading="", steps=(), version=""):
        with self.lock:
            self.s["where"] = where
            self.s["heading"] = heading
            self.s["subheading"] = subheading
            self.s["version"] = version
            self.s["steps"] = [{"key": k, "name": n, "state": "todo", "result": ""} for k, n in steps]
            self.s["status"] = {}
            self.s["finish"] = None
            self.s["board"] = None
            self.s["failure"] = None
            self.current = None
        self.log("== %s" % where)
        self.changed()

    def _step(self, key):
        for st in self.s["steps"]:
            if st["key"] == key:
                return st
        return None

    def step(self, key, title=None, text=""):
        self.check()
        with self.lock:
            st = self._step(key)
            self.current = key
            if st:
                st["state"] = "run"
                st["result"] = ""
            self.s["status"] = {"title": title or (st["name"] if st else ""), "text": text, "started": time.time()}
            self.s["failure"] = None
            n = len(self.s["steps"])
            idx = [x["key"] for x in self.s["steps"]].index(key) + 1 if st else 0
            if n and idx:
                base = self.s["where"].split(" · ")[0]
                self.s["where"] = "%s · %d of %d" % (base, idx, n)
        self.log("-- %s" % (st["name"] if st else key))
        self.changed()

    def mark(self, state, result="", key=None):
        with self.lock:
            st = self._step(key or self.current)
            if st:
                st["state"] = state
                st["result"] = result
        if result:
            self.log("   %s: %s" % (state, result))
        self.changed()

    def done(self, result="", key=None):
        self.mark("done", result, key)

    def skip(self, result="", key=None):
        self.mark("skip", result, key)

    def result(self, text):
        with self.lock:
            st = self._step(self.current)
            if st:
                st["result"] = text
        self.changed()

    def say(self, title=KEEP, text=KEEP, lines=KEEP, note=KEEP, progress=KEEP, items=KEEP, clock=KEEP):
        self.check()
        with self.lock:
            st = self.s["status"]
            if title is not KEEP:
                st["title"] = title
            if text is not KEEP:
                st["text"] = text
            if lines is not KEEP:
                st["lines"] = lines or []
            if note is not KEEP:
                st["note"] = note
            if progress is not KEEP:
                st["progress"] = progress
            if items is not KEEP:
                st["items"] = items or []
            if clock is not KEEP:
                st["no_clock"] = not clock
                if clock:
                    st["started"] = time.time()
        self.changed()

    def progress(self, frac, label=""):
        self.say(progress=(frac, label))

    def board(self, rows):
        with self.lock:
            self.s["board"] = rows
        self.changed()

    def changed(self):
        pass

    def check(self):
        if self.cancelled:
            raise Cancelled()

    # ---- running things --------------------------------------------------------------------------------------------
    def run(self, cmd, cwd=None, env=None, on_line=None, timeout=None, feed=None, what=None, ok=(0,)):
        """Runs cmd with its output going to the log (and to on_line, a line at a time). Raises Failed unless the
        exit code is in ok; returns (code, the last lines)."""
        self.check()
        shown = " ".join(shlex.quote(str(c)) for c in cmd)
        self.log("$ " + paths.mask_home(shown) + (("   (in %s)" % paths.short(cwd)) if cwd else ""))
        try:
            p = subprocess.Popen([str(c) for c in cmd], cwd=str(cwd) if cwd else None, env=env,
                                 stdin=subprocess.PIPE if feed is not None else subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as e:
            raise Failed(what or "Couldn't start %s" % os.path.basename(str(cmd[0])), str(e))
        with self.lock:
            self.proc = p
        timer = None
        if timeout:
            timer = threading.Timer(timeout, lambda: self._kill(p))
            timer.daemon = True
            timer.start()
        if feed is not None:
            def feeder():
                try:
                    for _ in range(200):
                        p.stdin.write(feed.encode())
                        p.stdin.flush()
                        time.sleep(0.05)
                except (BrokenPipeError, OSError, ValueError):
                    pass
            threading.Thread(target=feeder, daemon=True).start()
        last = collections.deque(maxlen=60)
        buf = b""
        fd = p.stdout.fileno()
        while True:
            try:
                chunk = os.read(fd, 8192)
            except OSError:
                chunk = b""
            if not chunk:
                break
            buf += chunk
            parts = re.split(rb"[\r\n]", buf)
            buf = parts.pop()
            for part in parts:
                line = part.decode("utf-8", "replace").rstrip()
                if not line.strip():
                    continue
                self.log("  " + line)
                last.append(line)
                if on_line:
                    try:
                        on_line(line)
                    except Cancelled:
                        raise
                    except Exception:
                        self.log(traceback.format_exc())
        if buf.strip():
            line = buf.decode("utf-8", "replace").rstrip()
            self.log("  " + line)
            last.append(line)
            if on_line:
                on_line(line)
        code = p.wait()
        if timer:
            timer.cancel()
        with self.lock:
            self.proc = None
        self.log("   exit %d" % code)
        self.check()
        if code not in ok:
            raise Failed(what or "%s stopped" % os.path.basename(str(cmd[0])),
                         "It ended with code %d." % code, list(last))
        return code, list(last)

    def out(self, cmd, timeout=30, env=None, cwd=None):
        """A short command's output (stdout and stderr together), or '' if it couldn't run."""
        self.check()
        shown = " ".join(shlex.quote(str(c)) for c in cmd)
        self.log("$ " + paths.mask_home(shown))
        try:
            r = subprocess.run([str(c) for c in cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               stdin=subprocess.DEVNULL, timeout=timeout, env=env, cwd=str(cwd) if cwd else None,
                               start_new_session=True)
        except subprocess.TimeoutExpired:
            self.log("   (no answer in %ds)" % timeout)
            return ""
        except OSError as e:
            self.log("   " + str(e))
            return ""
        text = r.stdout.decode("utf-8", "replace").replace("\r", "")
        for ln in text.splitlines()[:40]:
            self.log("  " + ln)
        return text

    def _kill(self, p):
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            pass

    def stop_running(self):
        with self.lock:
            p = self.proc
        if p is not None and p.poll() is None:
            self._kill(p)

    def close(self):
        try:
            self.logf.close()
        except Exception:
            pass


# ======================================================================================================================

class Screen(Base):
    """Full screen: the box. The flow runs on a thread; this one draws and reads keys."""

    def __init__(self, log_name, assume_yes=False):
        super().__init__(log_name, assume_yes)
        self.dirty = threading.Event()
        self.answer = None
        self.answered = threading.Event()
        self.quitting = None       # the "Stop?" question, owned by the screen
        self.auto = None           # demo: answer questions by themselves after this many seconds

    def changed(self):
        self.dirty.set()

    # ---- questions (called by the flow; the screen answers them) ------------------------------------------------------
    def _ask(self, prompt):
        self.check()
        with self.lock:
            self.s["prompt"] = prompt
            self.answered.clear()
            self.answer = None
            st = self._step(self.current)
            if st and st["state"] == "run":
                st["state"] = "you"
        self.changed()
        if self.auto:
            threading.Thread(target=self._autoplay, args=(prompt,), daemon=True).start()
        while not self.answered.wait(0.1):
            self.check()
        with self.lock:
            self.s["prompt"] = None
            st = self._step(self.current)
            if st and st["state"] == "you":
                st["state"] = "run"
            a = self.answer
        self.changed()
        self.check()
        return a

    def _autoplay(self, p):
        """The demo answers its own questions, the way a person would: a pause, the arrows, typing, Enter."""
        time.sleep(self.auto)
        if p["kind"] == "choice":
            want = p.get("auto", p["index"])
            while p["index"] != want and not self.answered.is_set():
                self.on_key("key", "down")
                self.dirty.set()
                time.sleep(0.45)
            time.sleep(0.5)
        else:
            text = p.get("auto") or p.get("placeholder") or ""
            for ch in text:
                if self.answered.is_set():
                    return
                self.on_key("char", ch)
                self.dirty.set()
                time.sleep(0.03 if p["kind"] == "secret" else 0.09)
            time.sleep(0.6)
        if not self.answered.is_set():
            self.on_key("key", "enter")
            self.dirty.set()

    def choose(self, title, text="", options=(), default=0, lines=None, back=False, auto=None):
        if self.assume_yes:
            self.log("? %s → %s (--yes)" % (title, options[default][0]))
            return default
        a = self._ask({"kind": "choice", "title": title, "text": text, "lines": lines or [],
                       "options": [(o, d) for o, d in options], "index": default, "back": back,
                       "auto": default if auto is None else auto})
        self.log("? %s → %s" % (title, "back" if a is None else options[a][0]))
        return a

    def ask(self, title, text="", placeholder="", validate=None, back=True, secret=False, initial="", lines=None,
            auto=None):
        p = {"kind": "secret" if secret else "text", "title": title, "text": text, "lines": lines or [],
             "placeholder": placeholder, "value": initial, "cursor": len(initial), "error": "", "back": back,
             "validate": validate, "auto": auto}
        a = self._ask(p)
        self.log("? %s → %s" % (title, "back" if a is None else ("(%d characters)" % len(a) if secret else a)))
        return a

    def wait(self, check, every=1.0, timeout=None):
        """Calls check() every `every` seconds until it returns something true (returned), or timeout (None)."""
        end = time.time() + timeout if timeout else None
        with self.lock:
            st = self._step(self.current)
            if st:
                st["state"] = "you"
        self.changed()
        try:
            while True:
                self.check()
                v = check()
                if v:
                    return v
                if end and time.time() > end:
                    return None
                t = time.time() + every
                while time.time() < t:
                    self.check()
                    time.sleep(0.1)
        finally:
            with self.lock:
                st = self._step(self.current)
                if st and st["state"] == "you":
                    st["state"] = "run"
            self.changed()

    def failed(self, f, can_skip=False):
        self.log("!! %s: %s" % (f.title, f.text))
        with self.lock:
            st = self._step(self.current)
            if st:
                st["state"] = "fail"
                st["result"] = "Stopped"
            self.s["failure"] = {"title": f.title, "text": f.text, "tail": f.tail, "skip": can_skip}
            self.answered.clear()
            self.answer = None
        self.changed()
        while not self.answered.wait(0.1):
            self.check()
        with self.lock:
            self.s["failure"] = None
            a = self.answer
        self.changed()
        return a

    def finish(self, title, text="", lines=None, ok=True):
        self.log("== %s %s" % ("done:" if ok else "stopped:", title))
        with self.lock:
            self.s["finish"] = {"title": title, "text": text, "lines": lines or [], "ok": ok}
            self.s["prompt"] = None
            self.answered.clear()
        self.changed()
        end = time.time() + 5 * self.auto + 2 if self.auto else None
        while not self.answered.wait(0.1):
            if self.cancelled or (end and time.time() > end):
                return

    def hold(self):
        """A board stays up until the person closes it (Enter, Esc or Q)."""
        with self.lock:
            self.answered.clear()
            self.s["holding"] = True
        self.changed()
        end = time.time() + 5 * self.auto + 2 if self.auto else None
        while not self.answered.wait(0.1):
            if self.cancelled or (end and time.time() > end):
                return None
        with self.lock:
            self.s["holding"] = False
            return self.answer

    # ---- keys -------------------------------------------------------------------------------------------------------
    def keys_now(self):
        s = self.s
        u = unicode_ok()
        if self.quitting is not None:
            return [(glyph("up", u) + glyph("down", u), "choose"), ("Enter", "OK")]
        if s.get("peek"):
            return [("L", "back"), ("Q", "quit")]
        if s.get("finish"):
            return [("Enter", "close")]
        if s.get("holding"):
            return list(s.get("hold_keys") or []) + [("Enter", "close")]
        f = s.get("failure")
        if f:
            k = [("R", "try again")]
            if f.get("skip"):
                k.append(("S", "skip"))
            return k + [("L", "the log"), ("Q", "quit")]
        p = s.get("prompt")
        if p:
            if p["kind"] == "choice":
                k = [(glyph("up", u) + glyph("down", u), "choose"), ("Enter", "OK")]
            else:
                k = [("Enter", "OK")]
            if p.get("back"):
                k.append(("Esc", "back"))
            return k
        return [("L", "the log"), ("Q", "quit")]

    def on_key(self, kind, val):
        with self.lock:
            s = self.s
            if kind == "key" and val == "ctrl-c":
                if self.quitting is not None:
                    self._quit_now()
                    return
                self._ask_quit()
                return
            if self.quitting is not None:
                self._quit_key(kind, val)
                return
            if kind == "key" and val == "ctrl-l":
                self.term.resized = True
                return
            p = s.get("prompt")
            typing = p is not None and p["kind"] in ("text", "secret")
            ch = val.lower() if kind == "char" else None
            if not typing and ch == "l" and not s.get("finish"):
                s["peek"] = not s.get("peek")
                return
            if s.get("peek"):
                if (kind == "key" and val == "esc") or ch == "l":
                    s["peek"] = False
                elif ch == "q":
                    self._ask_quit()
                return
            if s.get("finish") or s.get("holding"):
                if (kind == "key" and val in ("enter", "esc")) or ch == "q":
                    self.answer = "close"
                    self.answered.set()
                elif s.get("holding") and ch:
                    for k, _ in s.get("hold_keys") or []:
                        if ch == k.lower():
                            self.answer = ch
                            self.answered.set()
                return
            f = s.get("failure")
            if f:
                if ch == "r" or (kind == "key" and val == "enter"):
                    self.answer = "retry"
                    self.answered.set()
                elif ch == "s" and f.get("skip"):
                    self.answer = "skip"
                    self.answered.set()
                elif ch == "q" or (kind == "key" and val == "esc"):
                    self.answer = "quit"
                    self.answered.set()
                return
            if p:
                self._prompt_key(p, kind, val)
                return
            if ch == "q" or (kind == "key" and val == "esc"):
                self._ask_quit()

    def _prompt_key(self, p, kind, val):
        if p["kind"] == "choice":
            n = len(p["options"])
            if kind == "key" and val in ("up", "backtab"):
                p["index"] = (p["index"] - 1) % n
            elif kind == "key" and val in ("down", "tab"):
                p["index"] = (p["index"] + 1) % n
            elif kind == "key" and val == "home":
                p["index"] = 0
            elif kind == "key" and val == "end":
                p["index"] = n - 1
            elif kind == "key" and val == "enter":
                self.answer = p["index"]
                self.answered.set()
            elif kind == "key" and val == "esc" and p.get("back"):
                self.answer = None
                self.answered.set()
            elif kind == "char" and val.isdigit() and 1 <= int(val) <= n:
                p["index"] = int(val) - 1
                self.answer = p["index"]
                self.answered.set()
            elif kind == "char" and val.lower() == "q":
                self._ask_quit()
            return
        v, c = p["value"], p["cursor"]
        if kind == "char":
            v = v[:c] + val + v[c:]
            c += len(val)
            p["error"] = ""
        elif kind == "paste":
            t = val.replace("\r", "").replace("\n", "").strip() if p["kind"] == "secret" else val.replace("\n", " ").replace("\r", "")
            v = v[:c] + t + v[c:]
            c += len(t)
            p["error"] = ""
        elif kind == "key":
            if val == "backspace" and c > 0:
                v = v[:c - 1] + v[c:]
                c -= 1
            elif val == "delete" and c < len(v):
                v = v[:c] + v[c + 1:]
            elif val == "left" and p["kind"] == "text":
                c = max(0, c - 1)
            elif val == "right" and p["kind"] == "text":
                c = min(len(v), c + 1)
            elif val == "home":
                c = 0
            elif val == "end":
                c = len(v)
            elif val == "ctrl-u":
                v, c = "", 0
            elif val == "ctrl-w":
                left = v[:c].rstrip()
                cut = left.rfind(" ") + 1
                v, c = v[:cut] + v[c:], cut
            elif val == "esc" and p.get("back"):
                self.answer = None
                self.answered.set()
                return
            elif val == "enter":
                text = v.strip()
                err = p["validate"](text) if p.get("validate") else None
                if err:
                    p["error"] = err
                else:
                    self.answer = text
                    self.answered.set()
                return
        p["value"], p["cursor"] = v, c

    def _ask_quit(self):
        self.quitting = {"kind": "choice", "title": "Stop here?",
                         "text": "Nothing is lost: run the same command again and it picks up where it stopped.",
                         "options": [("Keep going", ""), ("Stop", "")], "index": 0, "back": True}

    def _quit_key(self, kind, val):
        q = self.quitting
        if kind == "key" and val in ("up", "down", "tab", "backtab"):
            q["index"] = 1 - q["index"]
        elif kind == "key" and val == "esc":
            self.quitting = None
        elif kind == "key" and val == "enter":
            if q["index"] == 1:
                self._quit_now()
            else:
                self.quitting = None
        elif kind == "char" and val.lower() in ("y", "q"):
            self._quit_now()
        elif kind == "char" and val.lower() == "n":
            self.quitting = None

    def _quit_now(self):
        self.quitting = None
        self.cancelled = True
        self.exit_code = 130
        self.stop_running()
        self.answer = None
        self.answered.set()

    # ---- the loop ---------------------------------------------------------------------------------------------------
    def snapshot(self):
        with self.lock:
            s = dict(self.s)
            s["status"] = dict(self.s["status"])
            s["steps"] = [dict(x) for x in self.s["steps"]]
            if self.s.get("prompt"):
                s["prompt"] = dict(self.s["prompt"])
            if self.quitting is not None:
                s["prompt"] = dict(self.quitting)
                s["failure"] = None
                s["finish"] = None
                s["peek"] = False
            s["keys"] = self.keys_now()
            s["log_tail"] = list(self.tail)[-80:]
            return s

    def play(self, flow):
        """Runs flow(self) on a thread and shows it until it ends. Returns the exit code."""
        def worker():
            try:
                flow(self)
            except Cancelled:
                self.exit_code = self.exit_code or 130
            except Failed as f:
                self.error = f
                self.exit_code = 1
            except Exception:
                self.error = Failed("Something went wrong in the installer", traceback.format_exc().strip().splitlines()[-1])
                self.log(traceback.format_exc())
                self.exit_code = 1
            finally:
                self.dirty.set()

        t = threading.Thread(target=worker, daemon=True)
        with Terminal() as term:
            self.term = term
            t.start()
            tick = 0
            last_draw = 0
            while True:
                now = time.time()
                if self.dirty.is_set() or now - last_draw >= 0.08:
                    self.dirty.clear()
                    w, h = term.get_size()
                    term.draw(view.render(self.snapshot(), w, h, term.theme, tick))
                    last_draw = now
                    tick += 1
                for kind, val in term.keys(0.04):
                    self.on_key(kind, val)
                    self.dirty.set()
                if not t.is_alive():
                    break
            w, h = term.get_size()
            term.draw(view.render(self.snapshot(), w, h, term.theme, tick))
        if self.cancelled:
            sys.stdout.write("Stopped. Run the same command again to pick up where it stopped.\n")
        elif self.error:
            sys.stdout.write("%s %s\n%s\n" % (glyph("fail", unicode_ok()), self.error.title, self.error.text))
        sys.stdout.write("The log: %s\n" % paths.short(self.log_path))
        self.close()
        return self.exit_code


# ======================================================================================================================

class Plain(Base):
    """One line per change, for a pipe, CI or --plain. Questions are asked on the terminal if there is one, else the
    recommended answer is taken (--yes) or the run stops and says which flag answers it."""

    def __init__(self, log_name, assume_yes=False):
        super().__init__(log_name, assume_yes)
        self.uni = unicode_ok()
        self.last_title = None
        self.last_pct = -1
        self.interactive = sys.stdin.isatty()

    def p(self, text):
        sys.stdout.write(text + "\n")
        sys.stdout.flush()

    def begin(self, where, heading="", subheading="", steps=(), version=""):
        super().begin(where, heading, subheading, steps, version)
        if heading:
            self.p(heading + (" (Glass %s)" % version if version else ""))

    def step(self, key, title=None, text=""):
        super().step(key, title, text)
        st = self._step(key)
        self.p("%s %s" % (glyph("arrow", self.uni), st["name"] if st else key))
        self.last_title = None
        self.last_pct = -1

    def mark(self, state, result="", key=None):
        super().mark(state, result, key)
        st = self._step(key or self.current)
        if st and state in ("done", "skip", "warn", "fail", "info"):
            g = glyph({"done": "done", "skip": "skip", "warn": "warn", "fail": "fail", "info": "info"}[state], self.uni)
            self.p("%s %s%s" % (g, st["name"], (": " + result) if result else ""))

    def say(self, title=KEEP, text=KEEP, lines=KEEP, note=KEEP, progress=KEEP, items=KEEP, clock=KEEP):
        super().say(title, text, lines, note, progress, items, clock)
        if title is not KEEP and title and title != self.last_title:
            self.last_title = title
            self.p("  " + title + ((": " + text) if text not in (KEEP, None, "") else ""))
        if progress is not KEEP and progress and progress[0] is not None:
            pct = int(progress[0] * 100) // 25 * 25
            if pct != self.last_pct:
                self.last_pct = pct
                self.p("  %d%%%s" % (pct, ("  " + progress[1]) if progress[1] else ""))

    def board(self, rows):
        super().board(rows)
        for state, label, value in rows:
            self.p("%s %-18s %s" % (glyph({"done": "done", "fail": "fail", "warn": "warn"}.get(state, "dot"), self.uni),
                                    label, value))

    def choose(self, title, text="", options=(), default=0, lines=None, back=False, auto=None):
        if self.assume_yes or not self.interactive:
            if not self.assume_yes and len(options) > 1:
                raise Failed("%s needs an answer" % title, "Run it in a terminal, or with --yes to take the "
                                                           "recommended answer (%s)." % options[default][0])
            self.p("  %s %s" % (title, options[default][0]))
            return default
        self.p("")
        self.p(title)
        if text:
            self.p(text)
        for kind, t in lines or []:
            self.p("  " + t.replace("\t", ": "))
        for i, (o, d) in enumerate(options):
            self.p("  %d. %s%s" % (i + 1, o, ("  (" + d + ")") if d else ""))
        while True:
            a = input("Choose 1–%d [%d]: " % (len(options), default + 1)).strip()
            if not a:
                return default
            if a.isdigit() and 1 <= int(a) <= len(options):
                return int(a) - 1

    def ask(self, title, text="", placeholder="", validate=None, back=True, secret=False, initial="", lines=None,
            auto=None):
        if not self.interactive:
            raise Failed("%s needs an answer" % title, "Run it in a terminal (or see ./glass --help for the flag).")
        self.p("")
        self.p(title)
        if text:
            self.p(text)
        while True:
            a = getpass.getpass("> ") if secret else input("> ")
            a = a.strip()
            err = validate(a) if validate else None
            if not err:
                return a
            self.p(glyph("fail", self.uni) + " " + err)

    def wait(self, check, every=1.0, timeout=None):
        end = time.time() + timeout if timeout else None
        st = self.s.get("status") or {}
        if st.get("text"):
            self.p("  " + st["text"])
        while True:
            v = check()
            if v:
                return v
            if end and time.time() > end:
                return None
            time.sleep(every)

    def failed(self, f, can_skip=False):
        self.p("%s %s" % (glyph("fail", self.uni), f.title))
        if f.text:
            self.p("  " + f.text)
        for ln in [t for t in f.tail if t.strip()][-3:]:
            self.p("  | " + ln.strip())
        return "quit"

    def finish(self, title, text="", lines=None, ok=True):
        self.p("")
        self.p("%s %s" % (glyph("done" if ok else "fail", self.uni), title))
        if text:
            self.p(text)
        for kind, t in lines or []:
            if kind == "blank":
                continue
            self.p("  " + t.replace("\t", "  "))

    def hold(self):
        return "close"

    def play(self, flow):
        code = 0
        try:
            flow(self)
        except Cancelled:
            code = self.exit_code or 130
        except Failed as f:
            self.failed(f)
            code = 1
        except KeyboardInterrupt:
            self.stop_running()
            self.p("Stopped. Run the same command again to pick up where it stopped.")
            code = 130
        self.p("The log: %s" % paths.short(self.log_path))
        self.close()
        return code


def steps(ui, plan, optional=()):
    """Runs each (key, fn) in order; when one stops, the person chooses: try again, skip (if optional) or quit."""
    for key, fn in plan:
        while True:
            ui.step(key)
            try:
                fn()
                break
            except Failed as f:
                a = ui.failed(f, can_skip=key in optional)
                if a == "retry":
                    continue
                if a == "skip":
                    ui.skip("Skipped")
                    break
                ui.error = f if a == "quit" else None
                ui.exit_code = 1 if a == "quit" else 130
                raise Cancelled()
