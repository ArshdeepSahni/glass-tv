"""./glass demo: the real interface with nothing real happening. For trying it, for testing how it looks at every
size, and for the website's recording. The words are the real commands' words; the TV, the address and the key are
made up. GLASS_DEMO_PACE=1.6 slows every step down (the website's recordings), so each one can be read."""
import os
import random
import string
import time

from . import build as bld
from .app import Failed, steps
from .flows import ALLOW, HOWTO, PROVIDERS, SETUP_STEPS, UPDATE_STEPS

TV_NAME = "living-room-tv"
TV_HOST = "192.168.1.20"
try:
    PACE = max(0.2, float(os.environ.get("GLASS_DEMO_PACE", "1")))
except ValueError:
    PACE = 1.0


def pause(ui, s):
    end = time.time() + s * PACE
    while time.time() < end:
        ui.check()
        time.sleep(0.05)


def fill(ui, seconds, label=lambda f: "%d%%" % (f * 100), text=None, frac0=0.0, frac1=1.0):
    n = max(1, int(seconds / 0.08))
    for i in range(n + 1):
        f = frac0 + (frac1 - frac0) * i / float(n)
        if text:
            ui.say(text=text(f))
        ui.progress(f, label(f))
        pause(ui, 0.08)


def setup(ui, opts, fail=None):
    rel = bld.version()[0]
    ui.begin("Setup", "Set up Glass on your TV",
             "About 15 minutes the first time. Nothing on the TV is deleted, and ./glass restore undoes it all.",
             SETUP_STEPS, version=rel)
    plan = [("computer", lambda: computer(ui)), ("tools", lambda: tools(ui)), ("find", lambda: find(ui)),
            ("allow", lambda: allow(ui)), ("assistant", lambda: assistant(ui)), ("build", lambda: build(ui, fail)),
            ("install", lambda: install(ui)), ("home", lambda: home(ui))]
    steps(ui, plan, optional=("assistant", "home"))
    finish(ui, rel)


def computer(ui):
    ui.say(title="Check this computer")
    pause(ui, 0.9)
    ui.done("macOS 15.1")


def tools(ui):
    ui.say(title="Get the tools that build Glass",
           text="Java 17 and Google's Android SDK. Anything missing goes into ~/.glass/toolchain; nothing is "
                "installed system-wide.")
    items = [["run", "Java 17", "downloading"], ["todo", "Android SDK", ""], ["todo", "Signing key", ""]]
    ui.say(items=[tuple(i) for i in items])
    fill(ui, 2.6, lambda f: "%d of 191 MB" % (f * 191))
    ui.say(progress=None)
    items[0] = ["done", "Java 17", "~/.glass/toolchain/jdk-17.0.13+11"]
    items[1] = ["run", "Android SDK", "downloading Google's tools"]
    ui.say(items=[tuple(i) for i in items])
    fill(ui, 1.8, lambda f: "%d of 157 MB" % (f * 157))
    ui.say(progress=None)
    ui.choose("Accept the Android SDK License?",
              "Building Glass needs Google's Android SDK, which comes with Google's license: "
              "https://developer.android.com/studio/terms", [("Accept", "and continue"), ("Stop here", "")])
    items[1][2] = "installing platform-tools, android-34, 34.0.0"
    ui.say(items=[tuple(i) for i in items])
    fill(ui, 3.0, lambda f: "Downloading" if f < 0.7 else "Unpacking")
    ui.say(progress=None)
    items[1] = ["done", "Android SDK", "API 34 · ~/.glass/toolchain/android-sdk"]
    items[2] = ["run", "Signing key", ""]
    ui.say(items=[tuple(i) for i in items])
    pause(ui, 0.7)
    items[2] = ["done", "Signing key", "new: ~/.glass/glass.keystore"]
    ui.say(items=[tuple(i) for i in items])
    pause(ui, 0.5)
    ui.done("Java 17 · SDK 34")


def find(ui):
    ui.say(title="Find your TV", text="Looking on this network for a TV with network debugging on.")
    fill(ui, 2.4, lambda f: "%d of 254" % (f * 254))
    ui.say(progress=None)
    a = ui.choose("Is this your TV?", "Found on this network, with network debugging on.",
                  [(TV_NAME, TV_HOST), ("Another address", "type it"), ("Look again", "")])
    if a == 1:
        from .tv import ADDRESS_HELP, validate_address
        ui.ask("Your TV's address", ADDRESS_HELP, placeholder="192.168.1.20", validate=validate_address, auto=TV_HOST)
    ui.say(text="Asking the TV at %s." % TV_HOST)
    pause(ui, 0.8)
    ui.done(TV_HOST)


def allow(ui):
    ui.say(title="Allow this computer", text="Connecting to the TV.")
    pause(ui, 0.9)
    ui.say(text="Your TV is asking whether to allow this computer.", lines=ALLOW,
           note="Nothing on the TV after a minute? Press Q, then run setup again.", clock=True)
    end = time.time() + 4.5 * PACE
    ui.wait(lambda: time.time() > end, every=0.2)
    ui.say(lines=[], note="")
    ui.done("Living Room TV")


def assistant(ui):
    ui.say(title="Glass Assistant")
    if ui.assume_yes or not getattr(ui, "interactive", True):
        pause(ui, 0.4)
        ui.skip("TV commands only")
        return
    while True:
        opts = [(n, d) for n, d, _, _, _ in PROVIDERS] + [("Skip", "TV commands only")]
        a = ui.choose("Give the assistant a way to answer questions?",
                      "It understands TV commands on its own (volume, play and pause, inputs, apps). To answer questions and "
                      "talk, it uses a free AI service: paste a key from one, or skip and add one later.", opts,
                      auto=len(PROVIDERS))
        if a is None or a == len(PROVIDERS):
            ui.skip("TV commands only")
            return
        name, _, keys, where, _ = PROVIDERS[a]
        got = 0
        for var, what in keys:
            fake = ("".join(random.choice("0123456789abcdef") for _ in range(32)) if var == "CF_ACCOUNT_ID" else
                    "gsk_" + "".join(random.choice(string.ascii_letters + string.digits) for _ in range(52)))
            how = "Copy it from %s" % where if var == "CF_ACCOUNT_ID" else "Make one at %s, copy it," % where
            v = ui.ask("Your %s %s" % (name, what), "%s and paste it here. It stays on this computer, in "
                       "~/.glass/.env." % how, secret=var != "CF_ACCOUNT_ID",
                       validate=lambda t: None if t and " " not in t else "Paste it as it is: no spaces", auto=fake)
            if v is None:
                break
            got += 1
        if got < len(keys):
            continue
        ui.say(text="Checking the key with %s." % name)
        pause(ui, 1.0)
        ui.done("%s key added" % name)
        return


def build(ui, fail=None):
    ui.say(title="Build Glass", items=[("run", "Glass", ""), ("todo", "Glass Assistant", "")])
    phases = ["Getting ready", "Preparing pictures and layouts", "Compiling", "Shrinking and optimising (R8)",
              "Converting to Android code", "Packaging and signing"]
    ui.say(title="Build Glass", text="Getting ready", progress=(None, ""),
           note="The first build downloads about 400 MB of tools and libraries; later ones take a minute or two.")
    pause(ui, 1.6)
    ui.say(note="")
    if fail == "build":
        fill(ui, 1.2, text=lambda f: "Preparing pictures and layouts", frac1=0.2)
        raise Failed("Building Glass stopped",
                     "Gradle couldn't download what it needs. Check that this computer is online, then try again.",
                     ["> Could not resolve com.android.tools.build:gradle:8.5.2.",
                      "   > Could not get resource 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.5.2/gradle-8.5.2.pom'.",
                      "      > Could not GET 'https://dl.google.com/...'. Unknown host 'dl.google.com'"])
    fill(ui, 6.5, text=lambda f: phases[min(len(phases) - 1, int(f * len(phases)))],
         label=lambda f: "%d%%" % (f * 99), frac1=0.99)
    ui.say(items=[("done", "Glass", "4.9 MB"), ("run", "Glass Assistant", "")], text="Getting ready")
    fill(ui, 2.4, text=lambda f: phases[min(len(phases) - 1, int(f * len(phases)))],
         label=lambda f: "%d%%" % (f * 99), frac1=0.99)
    ui.say(progress=None, items=[("done", "Glass", "4.9 MB"), ("done", "Glass Assistant", "164 KB")])
    pause(ui, 0.4)
    ui.done("%s · 4.9 MB" % bld.version()[0])


def install(ui):
    items = [["todo", "Glass Assistant", ""], ["todo", "Glass", ""], ["todo", "What Glass needs", ""],
             ["todo", "Remote helper", ""], ["todo", "Starting fast", ""]]
    timing = [("0.2 MB", 1.0), ("4.9 MB", 2.4), ("", 0.8), ("", 1.2), ("about a minute", 2.2)]
    for i, (right, secs) in enumerate(timing):
        items[i] = ["run", items[i][1], right]
        ui.say(title="Install on the TV", text="", items=[tuple(x) for x in items])
        pause(ui, secs)
        items[i] = ["done", items[i][1], "installed" if i < 2 else "on" if i == 3 else ""]
    ui.say(items=[tuple(x) for x in items])
    pause(ui, 0.4)
    ui.done("Glass %s" % bld.version()[1].split()[0])


def home(ui):
    a = ui.choose("Make Glass your TV's home screen?",
                  "Home goes to Glass, with its keyboard, the assistant button and its screen saver. The TV's own "
                  "home stays installed: ./glass restore brings it back.",
                  [("Make Glass home", "recommended"), ("Not now", "")])
    if a != 0:
        ui.skip("Not now")
        return
    names = ["Home screen", "Keyboard", "Assistant button", "Screen saver", "Glass's helper"]
    items = [["todo", n, ""] for n in names]
    for i in range(4):
        items[i][0] = "run"
        ui.say(title="Make Glass home", text="", items=[tuple(x) for x in items])
        pause(ui, 0.6)
        items[i][0] = "done"
    ui.say(title="Allow Glass's helper",
           text="The TV asks once more, this time for Glass itself (glass@tv). Glass uses it to install its own "
                "updates and, if you connect a phone as a remote, to press keys for it in any app.", lines=ALLOW,
           items=[], clock=True)
    end = time.time() + 3.5 * PACE
    ui.wait(lambda: time.time() > end, every=0.2)
    items[4] = ["done", "Glass's helper", "running"]
    ui.say(title="Make Glass home", text="", lines=[], items=[tuple(x) for x in items])
    pause(ui, 0.5)
    ui.done("Glass is home")


def finish(ui, rel):
    ui.finish("Glass %s is on your TV" % rel, "", [
        ("k", "On the TV\tPress Home on the remote."),
        ("k", "Your phone\tScan the code on the TV, or open http://%s:8765" % TV_HOST),
        ("k", "Your key\t~/.glass/glass.keystore signs Glass. Keep a copy somewhere safe: updates need it."),
        ("k", "Later\t./glass update · ./glass status · ./glass restore")])


def update(ui, opts):
    rel = bld.version()[0]
    ui.begin("Update", "Update Glass on your TV", "Builds the newest Glass on this computer and puts it on the "
             "TV. Its settings stay as they are.", UPDATE_STEPS, version=rel)

    def code():
        ui.say(title="Get the newest Glass", text="Asking GitHub for anything newer.")
        pause(ui, 1.4)
        ui.done("Glass %s" % rel)

    def quick(result, secs=0.8):
        def f():
            pause(ui, secs)
            ui.done(result)
        return f

    plan = [("code", code), ("tools", quick("Java 17 · SDK 34")), ("find", quick(TV_HOST)),
            ("allow", quick("Living Room TV")), ("build", lambda: build(ui)), ("install", lambda: install(ui))]
    steps(ui, plan, optional=("code",))
    ui.finish("Glass %s is on your TV" % rel, "Its settings are as they were.",
              [("k", "On the TV\tGlass restarts on the new version and says so.")])


def status(ui, opts):
    ui.begin("Status", "Glass on your TV", "", version=bld.version()[0])
    ui.board([])
    ui.say(title="Asking the TV", text="", progress=(None, ""))
    pause(ui, 1.6)
    ui.say(progress=None)
    rel = bld.version()[0]
    ui.board([("done", "TV", "Living Room TV · Android 9 · 1280×720"), ("done", "Glass", rel),
              ("done", "Glass Assistant", rel), ("done", "Home screen", "Glass"), ("done", "Remote helper", "on"),
              ("done", "Keyboard", "Glass"), ("done", "Glass's helper", "running"),
              ("info", "Memory", "341 MB free of 908 MB"), ("done", "Crashes", "none since the TV started")])
    with ui.lock:
        ui.s["hold_keys"] = [("R", "refresh")]
    ui.hold()


def doctor(ui, opts):
    checks = [("tools", "Tools"), ("tv", "The TV answers"), ("glass", "Glass"), ("home", "Home screen"),
              ("helper", "Remote helper"), ("keyboard", "Keyboard"), ("assistant", "Assistant button"),
              ("keys", "Glass's helper"), ("crashes", "Crashes"), ("key", "Signing key")]
    ui.begin("Doctor", "Check Glass and fix what's off", "Looks at this computer and the TV, then offers to fix "
             "what it can. Nothing changes without asking.", checks, version=bld.version()[0])
    results = {"tools": ("done", "Java 17 · SDK 34"), "key": ("done", "~/.glass/glass.keystore"),
               "tv": ("done", TV_HOST), "glass": ("done", bld.version()[0]), "home": ("warn", "the TV's own"),
               "helper": ("fail", "off"), "keyboard": ("done", "Glass"), "assistant": ("done", "Glass Assistant"),
               "keys": ("info", "starts when it's needed"), "crashes": ("done", "none since the TV started")}
    for k in ["tools", "key", "tv", "glass", "home", "helper", "keyboard", "assistant", "keys", "crashes"]:
        ui.step(k, dict(checks)[k])
        pause(ui, 0.45)
        ui.mark(*results[k])
    a = ui.choose("Fix these 2 things?", "Make Glass home · Turn on the remote helper",
                  [("Fix", "recommended"), ("Leave it as it is", "")])
    if a != 0:
        ui.finish("Nothing changed", "Run ./glass doctor again any time.", [])
        return
    fixes = ["Make Glass home", "Turn on the remote helper"]
    for i, key in enumerate(("home", "helper")):
        ui.say(title="Fixing", text="", items=[("done", f, "") for f in fixes[:i]] + [("run", fixes[i], "")] +
               [("todo", f, "") for f in fixes[i + 1:]])
        pause(ui, 1.2)
        ui.mark("done", "fixed", key=key)
    ui.finish("Fixed", " · ".join(fixes), [])


def restore(ui, opts):
    from .flows import RESTORE_STEPS
    ui.begin("Restore", "Remove Glass",
             "The TV goes back to its original home screen, keyboard and assistant button. Glass and its "
             "assistant are removed. Your other apps and their data stay.",
             RESTORE_STEPS, version=bld.version()[0])
    ui.step("tv", "Your TV")
    pause(ui, 0.8)
    ui.done("Living Room TV")
    a = ui.choose("Remove Glass from this TV?",
                  "Glass's settings, widgets and pictures sent from phones go with it. You can set it up again "
                  "later with ./glass setup.", [("Keep Glass", ""), ("Remove Glass and restore the TV", "")], auto=1)
    if a != 1:
        for k in ("home", "apps", "remove"):
            ui.mark("skip", "", key=k)
        ui.finish("Nothing changed", "Glass stays on the TV.", [])
        return
    for k, name, res in (("home", "The TV's own home", "back"), ("apps", "The TV's own apps", "all on"),
                         ("remove", "Remove Glass", "Glass Assistant and Glass removed")):
        ui.step(k, name)
        pause(ui, 1.0)
        ui.done(res)
    ui.finish("Glass is removed", "The TV's own home is back. Restart the TV from its own menu (not the wall switch) "
              "so it keeps the changes.", [])


FLOWS = {"setup": setup, "update": update, "status": status, "doctor": doctor, "restore": restore}
