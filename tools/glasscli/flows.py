"""The commands: setup, update, status, doctor, restore, and the menu.

Every step is safe to run again: a setup that stopped half-way picks up where it stopped, finding what's done.
"""
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from . import build as bld
from . import paths, system
from . import tv as tvmod
from .app import Cancelled, Failed, steps

HOWTO = [
    ("n", "1 On the TV: Settings › Device Preferences › About"),
    ("n", "2 Select Build seven times, until it says you're a developer"),
    ("n", "3 Back to Device Preferences › Developer options"),
    ("n", "4 Turn on USB debugging, and Network debugging if your TV has it"),
    ("p", "The TV and this computer have to be on the same network."),
]
ALLOW = [
    ("n", "1 On the TV, choose “Always allow from this computer”"),
    ("n", "2 Then choose OK"),
]

# The assistant's AI services: name, what it's like, the keys it needs (variable, what to call it), where to get one,
# and how to check a key works (GET with the key as a bearer token answers 200).
PROVIDERS = [
    ("Groq", "free · fastest · recommended", [("GROQ_API_KEY", "API key")], "console.groq.com/keys",
     "https://api.groq.com/openai/v1/models"),
    ("Cerebras", "free", [("CEREBRAS_API_KEY", "API key")], "cloud.cerebras.ai (API Keys)",
     "https://api.cerebras.ai/v1/models"),
    ("OpenRouter", "free models", [("OPENROUTER_API_KEY", "API key")], "openrouter.ai/settings/keys",
     "https://openrouter.ai/api/v1/key"),
    ("Cloudflare Workers AI", "free daily quota", [("CF_ACCOUNT_ID", "account ID"), ("CF_API_TOKEN", "API token")],
     "dash.cloudflare.com › AI › Workers AI › Use REST API",
     "https://api.cloudflare.com/client/v4/user/tokens/verify"),
]


class Ctx:
    def __init__(self, opts):
        self.opts = opts
        self.comp = None
        self.java = None
        self.sdk = None
        self.adb = None
        self.addr = None
        self.tv = None
        self.facts = None
        self.apks = {}
        self.asked_again = False


# ---- steps shared by the commands ----------------------------------------------------------------------------------

def step_computer(ui, c):
    ui.say(title="Check this computer")
    c.comp = system.computer()
    if c.comp["os"] == "other":
        raise Failed("This computer isn't supported yet",
                     "Glass's installer runs on macOS and Linux (on Windows, inside WSL).")
    have_tools = system.find_java() and not system.find_sdk()[1]
    need = 1 if have_tools else 4
    free = system.free_gb(paths.HOME)
    if free < need:
        raise Failed("Not enough space on this computer",
                     "Glass's tools need about %d GB; this computer has %.1f GB free. Free some space, then try "
                     "again." % (need, free))
    ui.say(text=c.comp["label"])
    ui.done(c.comp["short"])


def step_tools(ui, c):
    ui.say(title="Get the tools that build Glass",
           text="Java 17 and Google's Android SDK. Anything missing goes into %s; nothing is installed "
                "system-wide." % paths.short(paths.TOOLCHAIN))
    if c.comp is None:
        c.comp = system.computer()
    items = [["run", "Java 17", ""], ["todo", "Android SDK", ""], ["todo", "Signing key", ""]]
    show = lambda: ui.say(items=[tuple(i) for i in items])
    show()
    java = system.find_java()
    if not java:
        items[0][2] = "downloading"
        show()
        java = system.get_java(ui, c.comp)
        ui.say(progress=None)
    c.java = java
    items[0] = ["done", "Java %d" % system.java_major(java), paths.short(java)]
    items[1][0] = "run"
    show()
    sdk, missing = system.find_sdk()
    if missing:
        root = Path(sdk) if sdk else paths.TOOLCHAIN / "android-sdk"
        root.mkdir(parents=True, exist_ok=True)
        if not system.sdkmanager(root):
            items[1][2] = "downloading Google's tools"
            show()
            system.get_cmdline_tools(ui, c.comp, root)
        if not system.licenses_accepted(root):
            a = ui.choose("Accept the Android SDK License?",
                          "Building Glass needs Google's Android SDK, which comes with Google's license: %s"
                          % system.SDK_TERMS,
                          [("Accept", "and continue"), ("Stop here", "")])
            if a != 0:
                raise Cancelled()
        items[1][2] = "installing %s" % ", ".join(m.split(";")[-1] for m in missing)
        show()
        system.install_sdk_parts(ui, root, java, missing)
        ui.say(progress=None)
        sdk = str(root)
    c.sdk = sdk
    c.adb = system.find_adb(sdk)
    if not c.adb:
        raise Failed("adb is missing", "The Android SDK has no platform-tools. Run ./glass setup again.")
    items[1] = ["done", "Android SDK", "API 34 · %s" % paths.short(sdk)]
    items[2][0] = "run"
    show()
    ks, made = system.ensure_keystore(ui, java)
    items[2] = ["done", "Signing key", ("new: " if made else "") + paths.short(ks)]
    show()
    ui.done("Java %d · SDK 34" % system.java_major(java))


def step_find(ui, c):
    """The TV's address: --tv, the one kept from last time, one found on the network, or typed."""
    ui.say(title="Find your TV")
    addr = tvmod.normalize(c.opts.tv) if c.opts.tv else tvmod.saved_address()
    mode = "check" if addr else "scan"
    while True:
        if mode == "check":
            host, port = addr.split(":")
            ui.say(title="Find your TV", text="Asking the TV at %s." % host, progress=None, lines=[])
            if tvmod._open(host, int(port), 3):
                c.addr = addr
                ui.done(host)
                return
            a = ui.choose("No answer from %s" % host, "Check that the TV is on, and that network debugging is on.",
                          [("Try again", ""), ("Look on this network", ""), ("Type another address", "")], lines=HOWTO)
            mode = ["check", "scan", "type"][a]
        elif mode == "scan":
            ui.say(title="Find your TV", text="Looking on this network for a TV with network debugging on.",
                   progress=(0.0, ""), lines=[])
            found = tvmod.scan(ui)
            ui.say(progress=None)
            if found:
                opts = [((n or "A TV"), h) for h, n in found]
                title = "Is this your TV?" if len(found) == 1 else "Which one is your TV?"
                a = ui.choose(title, "Found on this network, with network debugging on.",
                              opts + [("Another address", "type it"), ("Look again", "")])
                if a < len(found):
                    addr, mode = tvmod.normalize(found[a][0]), "check"
                else:
                    mode = "type" if a == len(found) else "scan"
            else:
                a = ui.choose("No TV found yet", "Turn on network debugging on the TV, then look again.",
                              [("Look again", ""), ("Type its address", "")], lines=HOWTO)
                mode = "scan" if a == 0 else "type"
        else:
            typed = ui.ask("Your TV's address", tvmod.ADDRESS_HELP, placeholder="192.168.1.20",
                           validate=tvmod.validate_address)
            if typed is None:
                mode = "scan"
                continue
            addr, mode = tvmod.normalize(typed), "check"


def step_allow(ui, c):
    ui.say(title="Allow this computer", text="Connecting to the TV.")
    t = tvmod.TV(ui, c.adb, c.addr)
    t.server()
    st = t.reconnect() if c.asked_again else t.connect()
    if st == "refused":
        raise Failed("The TV turned the connection down", "Network debugging looks off. On the TV: Device "
                     "Preferences › Developer options › USB debugging (and Network debugging), then try again.")
    if st == "unreachable":
        raise Failed("The TV doesn't answer", "Check that it's on, and on the same network as this computer.")
    if st == "unauthorized":
        ui.say(text="Your TV is asking whether to allow this computer.", lines=ALLOW,
               note="Nothing on the TV after a minute? Press Q, then run setup again.", clock=True)
        ok = ui.wait(lambda: t.state() == "device", every=1.0, timeout=150)
        ui.say(lines=[], note="")
        if not ok:
            c.asked_again = True
            raise Failed("The TV didn't allow this computer",
                         "Press R to ask again. If the TV said no before: Developer options › Revoke USB "
                         "debugging authorizations, then R.")
    f = t.facts()
    if f["sdk"] and f["sdk"] < 26:
        raise Failed("This TV is too old for Glass", "Glass needs Android TV 8 or newer; this one has Android %s."
                     % f["android"])
    if not f["tv"]:
        a = ui.choose("This looks like a phone or a tablet", "Glass is made for TVs: it may not work well here.",
                      [("Stop here", ""), ("Go on anyway", "")])
        if a == 0:
            raise Cancelled()
    c.tv, c.facts = t, f
    tvmod.save_address(c.addr)
    ui.say(text="%s · Android %s · %s" % (f["name"], f["android"], f["screen"]))
    ui.done(f["name"])


def step_assistant(ui, c):
    """Optional: a key for the assistant's AI service, kept in GLASS_HOME/.env (only on this computer)."""
    ui.say(title="Glass Assistant")
    env = paths.read_env(paths.ENV_FILE)
    for name, _, keys, _, _ in PROVIDERS:
        if all(env.get(k) for k, _ in keys):
            ui.done("%s key, in %s" % (name, paths.short(paths.ENV_FILE)))
            return
    if ui.assume_yes or not getattr(ui, "interactive", True):
        ui.skip("TV commands only")   # a key can only be pasted by someone: ./glass setup again adds one
        return
    while True:
        opts = [(n, d) for n, d, _, _, _ in PROVIDERS] + [("Skip", "TV commands only")]
        a = ui.choose("Give the assistant a way to answer questions?",
                      "It understands TV commands on its own (volume, play and pause, inputs, apps). To answer questions "
                      "and talk, it uses a free AI service: paste a key from one, or skip and add one later.", opts)
        if a is None or a == len(PROVIDERS):
            ui.skip("TV commands only")
            return
        name, _, keys, where, check_url = PROVIDERS[a]
        values = {}
        for var, what in keys:
            how = "Copy it from %s" % where if var == "CF_ACCOUNT_ID" else "Make one at %s, copy it," % where
            v = ui.ask("Your %s %s" % (name, what),
                       "%s and paste it here. It stays on this computer, in %s." % (how, paths.short(paths.ENV_FILE)),
                       secret=var != "CF_ACCOUNT_ID",
                       validate=lambda t: None if t and " " not in t else "Paste it as it is: no spaces")
            if v is None:
                break
            values[var] = v
        if len(values) < len(keys):
            continue
        ui.say(text="Checking the key with %s." % name)
        ok = key_works(ui, check_url, values.get(keys[-1][0]), values.get("CF_ACCOUNT_ID"))
        if not ok:
            b = ui.choose("%s didn't accept that key" % name, "Copy it again (the whole key), or keep it anyway.",
                          [("Paste it again", ""), ("Keep it anyway", ""), ("Skip", "TV commands only")])
            if b == 0:
                continue
            if b == 2:
                ui.skip("TV commands only")
                return
        env.update(values)
        paths.write_env(paths.ENV_FILE, env, "Glass Assistant's keys (./glass setup): yours only, never in the "
                                             "repository or a chat.")
        ui.done("%s key added" % name)
        return


def key_works(ui, url, token, account=None):
    """True if the service answers 200 to the key (sent to curl on its standard input, never on a command line)."""
    if not token or not shutil.which("curl"):
        return True
    urls = [url]
    if account and "cloudflare" in url:
        urls.append("https://api.cloudflare.com/client/v4/accounts/%s/tokens/verify" % account)
    for u in urls:
        cfg = 'header = "Authorization: Bearer %s"\nurl = "%s"\n' % (token, u)
        try:
            r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "15", "-K", "-"],
                               input=cfg.encode(), capture_output=True, timeout=20)
            code = r.stdout.decode().strip()
        except Exception:
            return True
        ui.log("   key check: %s" % code)
        if code == "200":
            return True
        if code in ("000", ""):
            return True   # offline: can't tell
    return False


def step_build(ui, c):
    ui.say(title="Build Glass", items=[("run", "Glass", ""), ("todo", "Glass Assistant", "")])
    system.ensure_keystore(ui, c.java)
    out = bld.build(ui, "launcher", c.java, c.sdk)
    c.apks["launcher"] = out
    size = out.stat().st_size / 1e6
    ui.say(title="Build Glass", items=[("done", "Glass", "%.1f MB" % size), ("run", "Glass Assistant", "")])
    out2 = bld.build(ui, "assistant", c.java, c.sdk)
    c.apks["assistant"] = out2
    ui.say(title="Build Glass", progress=None,
           items=[("done", "Glass", "%.1f MB" % size), ("done", "Glass Assistant", "%d KB" % (out2.stat().st_size / 1e3))])
    rel = bld.version()[0]
    ui.done("%s · %.1f MB" % (rel, size))


def step_install(ui, c):
    t = c.tv
    items = [["todo", "Glass Assistant", ""], ["todo", "Glass", ""], ["todo", "What Glass needs", ""],
             ["todo", "Remote helper", ""], ["todo", "Starting fast", ""]]

    def show(i=None, state="run", right=""):
        if i is not None:
            items[i][0], items[i][2] = state, right
        ui.say(title="Install on the TV", text="", items=[tuple(x) for x in items])

    for i, (which, pkg, label) in enumerate([("assistant", tvmod.ASSISTANT, "Glass Assistant"),
                                             ("launcher", tvmod.PKG, "Glass")]):
        apk = c.apks.get(which)
        if not apk:
            show(i, "skip", "not built")
            continue
        show(i, "run", "%.1f MB" % (apk.stat().st_size / 1e6))
        try:
            t.install(apk)
        except Failed as f:
            if f.title != "Another key":
                raise
            a = ui.choose("The TV has another copy of %s" % label,
                          "It's signed with a different key: an earlier Glass made on another computer, or the "
                          "original tvhome launcher. Remove it (with its settings) and install this one?",
                          [("Stop here", ""), ("Remove it and install this one", "")])
            if a != 1:
                raise Cancelled()
            t.uninstall(pkg)
            t.install(apk)
        show(i, "done", "installed")
    show(2)
    t.allow()
    show(2, "done", "")
    show(3)
    on = t.remote_helper()
    show(3, "done" if on else "warn", "on" if on else "not on: ./glass doctor")
    show(4, "run", "about a minute")
    t.compile()
    show(4, "done", "")
    v = t.version(tvmod.PKG)
    ui.done("Glass %s" % (v[0] if v else "installed"))


def step_home(ui, c):
    t = c.tv
    if t.home_now().startswith(tvmod.PKG) and c.opts.command != "setup":
        ui.done("Glass is home")
        return
    a = ui.choose("Make Glass your TV's home screen?",
                  "Home goes to Glass, with its keyboard, the assistant button and its screen saver. The TV's own "
                  "home stays installed: ./glass restore brings it back.",
                  [("Make Glass home", "recommended"), ("Not now", "")])
    if a != 0:
        ui.skip("Not now")
        return
    items = [["run", "Home screen", ""], ["todo", "Keyboard", ""], ["todo", "Assistant button", ""],
             ["todo", "Screen saver", ""], ["todo", "Glass's helper", ""]]

    def show():
        ui.say(title="Make Glass home", text="", items=[tuple(x) for x in items])

    show()
    items[0] = ["done" if t.make_home() else "warn", "Home screen", ""]
    items[1][0] = "run"
    show()
    items[1] = ["done" if t.keyboard() else "warn", "Keyboard", ""]
    items[2][0] = "run"
    show()
    items[2] = ["done" if t.assistant_button() else "skip", "Assistant button", ""]
    items[3][0] = "run"
    show()
    t.screen_saver()
    items[3] = ["done", "Screen saver", ""]
    items[4][0] = "run"
    show()
    if t.keys_up():
        items[4] = ["done", "Glass's helper", "running"]
    else:
        t.open("keys")
        ui.say(title="Allow Glass's helper",
               text="The TV asks once more, this time for Glass itself (glass@tv). Its helper lets your phone "
                    "press keys inside any app, and lets Glass install its updates.",
               lines=ALLOW, items=[], clock=True)
        ok = ui.wait(t.keys_up, every=2.0, timeout=120)
        ui.say(lines=[])
        items[4] = ["done", "Glass's helper", "running"] if ok else ["skip", "Glass's helper", "asks when needed"]
    show()
    if c.facts and c.facts.get("vendor") == "homwee":
        bloat = tvmod.debloat_list()
        off = t.disabled()
        todo = [p for p in bloat if p not in off]
        if todo:
            b = ui.choose("Turn off the TV's promotions?",
                          "On this TV model, %d of its own apps only show ads and promoted rows or run in the "
                          "background. They're turned off, not removed: ./glass restore turns them back on."
                          % len(bloat), [("Turn them off", "recommended"), ("Leave them", "")])
            if b == 0:
                ui.say(text="Turning off the TV's promotions")
                for i, p in enumerate(todo):
                    t.sh("pm disable-user --user 0 %s" % p)
                    ui.progress((i + 1) / float(len(todo)), "%d of %d" % (i + 1, len(todo)))
                ui.say(progress=None)
                ui.log("   turned off: %d of %d" % (len([p for p in todo if p in t.disabled()]), len(todo)))
    if t.awake():
        t.press_home()
    ui.done("Glass is home")


# ---- setup -----------------------------------------------------------------------------------------------------

SETUP_STEPS = [("computer", "This computer"), ("tools", "Tools"), ("find", "Your TV"), ("allow", "Allow this computer"),
               ("assistant", "The assistant"), ("build", "Build Glass"), ("install", "Install"), ("home", "Make it home")]


def setup(ui, opts):
    rel = bld.version()[0]
    ui.begin("Setup", "Set up Glass on your TV",
             "About 15 minutes the first time. Nothing on the TV is deleted, and ./glass restore undoes it all.",
             SETUP_STEPS, version=rel)
    c = Ctx(opts)
    plan = [("computer", lambda: step_computer(ui, c)), ("tools", lambda: step_tools(ui, c)),
            ("find", lambda: step_find(ui, c)), ("allow", lambda: step_allow(ui, c)),
            ("assistant", lambda: step_assistant(ui, c)), ("build", lambda: step_build(ui, c)),
            ("install", lambda: step_install(ui, c)), ("home", lambda: step_home(ui, c))]
    steps(ui, plan, optional=("assistant", "home"))
    finish_installed(ui, c)


def finish_installed(ui, c):
    rel = bld.version()[0]
    host = c.addr.split(":")[0] if c.addr else "the TV"
    try:
        if c.tv.awake():
            c.tv.open("phone")
    except Exception:
        pass
    lines = [("k", "On the TV\tPress Home on the remote."),
             ("k", "Your phone\tScan the code on the TV, or open http://%s:%d" % (host, tvmod.PHONE_PORT)),
             ("k", "Your key\t%s signs Glass. Keep a copy somewhere safe: updates need it."
              % paths.short(paths.keystore())),
             ("k", "Later\t./glass update · ./glass status · ./glass restore")]
    ui.finish("Glass %s is on your TV" % rel, "", lines)


# ---- update ----------------------------------------------------------------------------------------------------

def step_code(ui, c):
    ui.say(title="Get the newest Glass")
    before = bld.version()
    git = shutil.which("git")
    if not git or not (paths.ROOT / ".git").exists():
        ui.skip("Glass %s (not a git clone)" % before[0])
        return
    if ui.out([git, "-C", paths.ROOT, "status", "--porcelain", "--untracked-files=no"]).strip():
        ui.skip("Glass %s, with your changes" % before[0])
        return
    ui.say(text="Asking GitHub for anything newer.")
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    helper = ["-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential"] if shutil.which("gh") else []
    try:
        ui.run([git] + helper + ["-C", paths.ROOT, "pull", "--ff-only", "--quiet"], env=env, timeout=180,
               what="Couldn't get the newest Glass")
    except Failed as f:
        raise Failed(f.title, "Git couldn't pull from GitHub (offline, or this copy has changes of its own). Press S to "
                              "build what's here, or R to try again.", f.tail)
    after = bld.version()
    if after[2] != before[2]:
        ui.done("%s → %s" % (before[0], after[0]))
    else:
        ui.done("Glass %s, the newest" % after[0])


UPDATE_STEPS = [("code", "Newest Glass"), ("tools", "Tools"), ("find", "Your TV"), ("allow", "Allow this computer"),
                ("build", "Build Glass"), ("install", "Install")]


def update(ui, opts):
    rel = bld.version()[0]
    ui.begin("Update", "Update Glass on your TV", "Builds the newest Glass on this computer and puts it on the TV. "
             "Its settings stay as they are.", UPDATE_STEPS, version=rel)
    c = Ctx(opts)
    plan = [("code", lambda: step_code(ui, c)), ("tools", lambda: step_tools(ui, c)),
            ("find", lambda: step_find(ui, c)), ("allow", lambda: step_allow(ui, c)),
            ("build", lambda: step_build(ui, c)), ("install", lambda: step_install(ui, c))]
    steps(ui, plan, optional=("code",))
    rel = bld.version()[0]
    ui.finish("Glass %s is on your TV" % rel, "Its settings are as they were.",
              [("k", "On the TV\tGlass restarts on the new version and says so.")])


# ---- status and doctor -----------------------------------------------------------------------------------------

def quiet_tv(ui, opts):
    """(TV or None, why) without asking anything."""
    adb = system.find_adb(system.find_sdk()[0])
    addr = tvmod.normalize(opts.tv) if opts.tv else tvmod.saved_address()
    if not adb:
        return None, "The tools aren't here yet: ./glass setup"
    if not addr:
        return None, "No TV yet: ./glass setup"
    t = tvmod.TV(ui, adb, addr)
    t.server()
    st = t.connect()
    if st == "device":
        return t, ""
    if st == "unauthorized":
        return None, "This computer isn't allowed on the TV yet: ./glass setup"
    if st == "refused":
        return None, "The TV turned the connection down: is network debugging on?"
    return None, "The TV at %s doesn't answer: is it on?" % addr.split(":")[0]


def tv_rows(ui, t):
    rel, _, code = bld.version()
    f = t.facts()
    rows = [("done", "TV", "%s · Android %s · %s" % (f["name"], f["android"], f["screen"]))]
    g = t.version(tvmod.PKG)
    if not g:
        rows.append(("fail", "Glass", "not installed: ./glass setup"))
        return rows, f
    newer = code > g[1]
    rows.append(("warn" if newer else "done", "Glass",
                 "%s%s" % (g[0], " · %s is here: ./glass update" % rel if newer else "")))
    a = t.version(tvmod.ASSISTANT)
    rows.append(("done" if a else "skip", "Glass Assistant", a[0] if a else "not installed"))
    home = t.home_now().startswith(tvmod.PKG)
    rows.append(("done" if home else "warn", "Home screen", "Glass" if home else "the TV's own"))
    helper = "HomeKeyService" in t.sh("settings get secure enabled_accessibility_services")
    rows.append(("done" if helper else "fail", "Remote helper", "on" if helper else "off: ./glass doctor"))
    kb = "GlassKeyboard" in t.sh("settings get secure default_input_method")
    rows.append(("done" if kb else "warn", "Keyboard", "Glass" if kb else "the TV's own"))
    keys = t.keys_up()
    rows.append(("done" if keys else "info", "Glass's helper", "running" if keys else "starts when it's needed"))
    total, avail = t.memory()
    if total:
        rows.append(("info", "Memory", "%d MB free of %d MB" % (avail, total)))
    n = t.crashes()
    rows.append(("done" if n == 0 else "warn", "Crashes", "none since the TV started" if n == 0 else
                 "%d since the TV started" % n))
    return rows, f


def status(ui, opts):
    ui.begin("Status", "Glass on your TV", "", version=bld.version()[0])
    while True:
        ui.board([])
        ui.say(title="Asking the TV", text="", progress=(None, ""))
        t, why = quiet_tv(ui, opts)
        ui.say(progress=None)
        if not t:
            ui.board([("fail", "TV", why)])
        else:
            rows, _ = tv_rows(ui, t)
            ui.board(rows)
        with ui.lock:
            ui.s["hold_keys"] = [("R", "refresh")]
        if ui.hold() != "r":
            return


def doctor(ui, opts):
    checks = [("tools", "Tools"), ("tv", "The TV answers"), ("glass", "Glass"), ("home", "Home screen"),
              ("helper", "Remote helper"), ("keyboard", "Keyboard"), ("assistant", "Assistant button"),
              ("keys", "Glass's helper"), ("crashes", "Crashes"), ("key", "Signing key")]
    ui.begin("Doctor", "Check Glass and fix what's off", "Looks at this computer and the TV, then offers to fix "
             "what it can. Nothing changes without asking.", checks, version=bld.version()[0])
    fixes = []
    ui.step("tools", "Tools")
    java = system.find_java()
    sdk, missing = system.find_sdk()
    if java and not missing:
        ui.done("Java %d · SDK 34" % system.java_major(java))
    else:
        ui.mark("fail", "missing: run ./glass setup")
    ui.step("key", "Signing key")
    ks = paths.keystore()
    ui.mark("done" if ks.is_file() else "warn", paths.short(ks) if ks.is_file() else
            "none yet (made by the first build)")
    ui.step("tv", "The TV answers")
    t, why = quiet_tv(ui, opts)
    if not t:
        ui.mark("fail", why)
        for k, _ in checks:
            st = ui._step(k)
            if st and st["state"] == "todo":
                ui.mark("skip", "", key=k)
        ui.finish("The TV needs a look first", why, [], ok=False)
        return
    ui.done(t.host)
    rel, _, code = bld.version()
    ui.step("glass", "Glass")
    g = t.version(tvmod.PKG)
    if not g:
        ui.mark("fail", "not installed: ./glass setup")
    elif code > g[1]:
        ui.mark("warn", "%s on the TV · %s here: ./glass update" % (g[0], rel))
    else:
        ui.done(g[0])
    ui.step("home", "Home screen")
    if t.home_now().startswith(tvmod.PKG):
        ui.done("Glass")
    else:
        ui.mark("warn", "the TV's own")
        fixes.append(("Make Glass home", t.make_home, "home"))
    ui.step("helper", "Remote helper")
    if "HomeKeyService" in t.sh("settings get secure enabled_accessibility_services"):
        ui.done("on")
    else:
        ui.mark("fail", "off")
        fixes.append(("Turn on the remote helper", t.remote_helper, "helper"))
    ui.step("keyboard", "Keyboard")
    if "GlassKeyboard" in t.sh("settings get secure default_input_method"):
        ui.done("Glass")
    else:
        ui.mark("warn", "the TV's own")
        fixes.append(("Use Glass's keyboard", t.keyboard, "keyboard"))
    ui.step("assistant", "Assistant button")
    if "GlassVoiceService" in t.sh("settings get secure voice_interaction_service"):
        ui.done("Glass Assistant")
    elif t.version(tvmod.ASSISTANT):
        ui.mark("warn", "not Glass Assistant")
        fixes.append(("Give the button to Glass Assistant", t.assistant_button, "assistant"))
    else:
        ui.mark("skip", "the assistant isn't installed")
    ui.step("keys", "Glass's helper")
    ui.done("running") if t.keys_up() else ui.mark("info", "starts when it's needed")
    ui.step("crashes", "Crashes")
    n = t.crashes()
    ui.done("none since the TV started") if n == 0 else ui.mark("warn", "%d since the TV started" % n)
    if not fixes:
        ui.finish("All good", "Glass and the TV look as they should.", [])
        return
    a = ui.choose("Fix %s?" % ("this" if len(fixes) == 1 else "these %d things" % len(fixes)),
                  " · ".join(f[0] for f in fixes), [("Fix", "recommended"), ("Leave it as it is", "")])
    if a != 0:
        ui.finish("Nothing changed", "Run ./glass doctor again any time.", [], ok=True)
        return
    ui.say(title="Fixing", items=[("todo", f[0], "") for f in fixes])
    done = []
    for i, (label, fn, key) in enumerate(fixes):
        ui.say(items=[("done", f[0], "") for f in fixes[:i]] + [("run", label, "")] +
               [("todo", f[0], "") for f in fixes[i + 1:]])
        try:
            fn()
            done.append(label)
            ui.mark("done", "fixed", key=key)
        except Exception as e:
            ui.log("fix failed: %s" % e)
    ui.finish("Fixed" if len(done) == len(fixes) else "Fixed %d of %d" % (len(done), len(fixes)),
              " · ".join(done), [])


# ---- restore ---------------------------------------------------------------------------------------------------

RESTORE_STEPS = [("tv", "Your TV"), ("home", "The TV's own home"), ("apps", "The TV's own apps"),
                 ("remove", "Remove Glass")]


def restore(ui, opts):
    ui.begin("Restore", "Back to the TV as it shipped",
             "Glass and Glass Assistant are removed, with their settings, and the TV gets its own home screen "
             "back. Your other apps and their data stay.", RESTORE_STEPS, version=bld.version()[0])
    ui.step("tv", "Your TV")
    t, why = quiet_tv(ui, opts)
    if not t:
        raise Failed("The TV doesn't answer", why)
    f = t.facts()
    ui.done(f["name"])
    a = ui.choose("Remove Glass from this TV?",
                  "Glass's settings, widgets and pictures sent from phones go with it. You can set it up again "
                  "later with ./glass setup.", [("Keep Glass", ""), ("Remove Glass and restore the TV", "")])
    if a != 1:
        for k, _ in RESTORE_STEPS[1:]:
            ui.mark("skip", "", key=k)
        ui.finish("Nothing changed", "Glass stays on the TV.", [])
        return
    ui.step("home", "The TV's own home")
    t.sh("pm enable com.google.android.tvlauncher")
    t.sh("cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity")
    t.sh("settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0")
    t.sh("settings delete secure voice_interaction_service; settings delete secure assistant; "
         "settings delete secure screensaver_components")
    t.sh("ime reset")
    ui.done("back")
    ui.step("apps", "The TV's own apps")
    off = t.disabled()
    back = [p for p in tvmod.debloat_list(include_optional=True) if p in off]
    for i, p in enumerate(back):
        t.sh("pm enable %s" % p)
        ui.progress((i + 1) / float(len(back)), "%d of %d" % (i + 1, len(back)))
    t.sh("settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; "
         "settings delete global animator_duration_scale")
    ui.say(progress=None)
    ui.done("%d turned back on" % len(back) if back else "all on")
    ui.step("remove", "Remove Glass")
    t.press_home()
    gone = []
    if t.uninstall(tvmod.ASSISTANT):
        gone.append("Glass Assistant")
    if t.uninstall(tvmod.PKG):
        gone.append("Glass")
    t.sh("sync")
    ui.done(" and ".join(gone) + " removed" if gone else "not on the TV")
    ui.finish("The TV is as it shipped", "Restart it from its own menu (not the wall switch) so it keeps the changes.",
              [])


# ---- the menu --------------------------------------------------------------------------------------------------

def menu(ui, opts):
    rel = bld.version()[0]
    ui.begin("Glass", "Glass %s" % rel, "This computer set up Glass on the TV at %s." % (
        (tvmod.saved_address() or "?").split(":")[0]))
    a = ui.choose("What would you like to do?", "",
                  [("Update Glass", "build the newest and put it on the TV"),
                   ("Status", "how Glass and the TV are doing"),
                   ("Check and fix", "find what's off and fix it"),
                   ("Set up again", "the whole setup"),
                   ("Back to the TV as it shipped", "remove Glass"),
                   ("Quit", "")], back=True)
    return [None, "update", "status", "doctor", "setup", "restore", None][(a if a is not None else 5) + 1]
