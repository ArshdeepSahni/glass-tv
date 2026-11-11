"""The TV, over its network debugging (adb): finding it, being allowed, what it is, installing and setting up.

Nothing here wakes a TV that's off, and nothing is changed on the TV that `restore` can't put back.
"""
import concurrent.futures
import ipaddress
import re
import socket
import threading
import time

from . import paths
from .app import Failed

PKG = "com.gathin.tvhome"
ASSISTANT = "com.gathin.glass.assistant"
PORT = 5555
KEYS_PORT = 8766       # Glass's key helper on the TV (127.0.0.1)
PHONE_PORT = 8765      # the phone remote

TURNED_OFF = "/data/local/tmp/glass-turned-off"   # on the TV: its own homes Glass turned off, for restore (and
#                                                    Glass turns them off again if the TV turns them on as it starts)
LOCATION_ON = "/data/local/tmp/glass-turned-on-location"   # on the TV: Location was off until the installer turned it on
LIMITED = "/data/local/tmp/glass-background-limited"      # on the TV: apps Glass keeps from running in the background
SETTINGS_BEFORE = "/data/local/tmp/glass-settings-before"  # on the TV: "<namespace> <name> <value>" Glass changed, as they were
ADDRESS_HELP = "On the TV: Settings › Network & Internet › your network. It looks like 192.168.1.20."


def normalize(addr):
    """'192.168.1.20' → '192.168.1.20:5555'; None when it isn't an address."""
    a = (addr or "").strip()
    if not a:
        return None
    host, _, port = a.partition(":")
    port = port or str(PORT)
    if not port.isdigit():
        return None
    try:
        ipaddress.ip_address(host)
    except ValueError:
        # a name (living-room-tv.local) has a letter in it; numbers and dots alone have to be a whole address
        if not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", host) or not re.search(r"[A-Za-z]", host):
            return None
    return "%s:%s" % (host, port)


def validate_address(text):
    return None if normalize(text) else "That isn't an address like 192.168.1.20"


def saved_address():
    try:
        a = paths.TV_FILE.read_text().strip().splitlines()[0]
        return normalize(a)
    except (OSError, IndexError):
        return None


def save_address(addr):
    import os
    old = os.umask(0o077)
    try:
        paths.TV_FILE.write_text(addr + "\n")
    finally:
        os.umask(old)


# ---- finding it ------------------------------------------------------------------------------------------------

def my_address():
    """This computer's address on the home network (no packet is sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def _open(host, port, timeout):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _name(host):
    box = {}

    def look():
        try:
            box["n"] = socket.gethostbyaddr(host)[0]
        except OSError:
            pass
    t = threading.Thread(target=look, daemon=True)
    t.start()
    t.join(1.2)
    n = box.get("n", "")
    return n.split(".")[0] if n and not n[0].isdigit() else ""


def scan(ui):
    """Addresses on this network (its /24) with network debugging open, with a name when the router knows one."""
    me = my_address()
    if not me or not me.count(".") == 3:
        return []
    net = ipaddress.ip_network(me + "/24", strict=False)
    hosts = [str(h) for h in net.hosts() if str(h) != me]
    found = []
    done = [0]
    lock = threading.Lock()

    def probe(h):
        ok = _open(h, PORT, 0.7)
        with lock:
            done[0] += 1
            if ok:
                found.append(h)
        return ok

    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
        futs = [ex.submit(probe, h) for h in hosts]
        while not all(f.done() for f in futs):
            ui.check()
            ui.progress(done[0] / len(hosts), "%d of %d" % (done[0], len(hosts)))
            time.sleep(0.15)
    ui.progress(1.0, "%d of %d" % (len(hosts), len(hosts)))
    found.sort(key=lambda h: int(h.rsplit(".", 1)[1]))
    return [(h, _name(h)) for h in found]


# ---- talking to it ---------------------------------------------------------------------------------------------

class TV:
    def __init__(self, ui, adb, address):
        self.ui = ui
        self.adb = adb
        self.addr = address
        self.host = address.split(":")[0]

    def a(self, *args, timeout=30):
        return self.ui.out([self.adb, "-s", self.addr] + list(args), timeout=timeout)

    def sh(self, cmd, timeout=30):
        return self.a("shell", cmd, timeout=timeout).strip()

    def server(self):
        self.ui.out([self.adb, "start-server"], timeout=30)

    def state(self):
        out = self.ui.out([self.adb, "-s", self.addr, "get-state"], timeout=10).strip().lower()
        if "unauthorized" in out:
            return "unauthorized"
        if out.endswith("device"):
            return "device"
        if "offline" in out:
            return "offline"
        return "gone"

    def connect(self):
        """'device', 'unauthorized' (the TV is asking), 'refused' (network debugging off), 'unreachable'."""
        out = self.ui.out([self.adb, "connect", self.addr], timeout=25).lower()
        if "refused" in out:
            return "refused"
        if "timed out" in out or "no route" in out or "unreachable" in out or "cannot resolve" in out \
                or ("failed to connect" in out and "authenticate" not in out):
            return "unreachable"
        for _ in range(3):
            st = self.state()
            if st in ("device", "unauthorized"):
                return st
            time.sleep(1)
        return "unauthorized" if "authenticate" in out else "unreachable"

    def reconnect(self):
        self.ui.out([self.adb, "disconnect", self.addr], timeout=10)
        return self.connect()

    def prop(self, name):
        return self.sh("getprop " + name)

    def facts(self):
        f = {}
        props = self.sh("getprop", timeout=20)
        for k, v in re.findall(r"\[([^\]]+)\]: \[([^\]]*)\]", props):
            f[k] = v
        maker = f.get("ro.product.manufacturer", "")
        model = f.get("ro.product.model", "")
        brand = f.get("ro.product.brand", "")
        name = model if maker.lower() in model.lower() else ("%s %s" % (brand or maker, model)).strip()
        mem = self.sh("cat /proc/meminfo")
        m = re.search(r"MemTotal:\s+(\d+)", mem)
        size = self.sh("wm size")
        sm = re.findall(r"(\d+)x(\d+)", size)
        feats = self.sh("pm list features")
        return {
            "name": name or "Android TV",
            "android": f.get("ro.build.version.release", "?"),
            "sdk": int(f.get("ro.build.version.sdk", "0") or 0),
            "abi": f.get("ro.product.cpu.abi", ""),
            "ram_mb": int(m.group(1)) // 1024 if m else 0,
            "screen": "%s×%s" % sm[-1] if sm else "",
            "tv": "android.software.leanback" in feats,
            "vendor": "homwee" if "com.homwee" in self.sh("pm list packages com.homwee") else "",
        }

    def version(self, pkg):
        out = self.sh("dumpsys package %s" % pkg)
        name = re.search(r"versionName=(\S+)", out)
        code = re.search(r"versionCode=(\d+)", out)
        if not name:
            return None
        return name.group(1), int(code.group(1)) if code else 0

    def awake(self):
        return "mWakefulness=Awake" in self.sh("dumpsys power")

    def install(self, apk):
        """Installs (or updates) one APK. Raises Failed with plain words for the usual refusals."""
        out = self.a("install", "-r", str(apk), timeout=600)
        if "Success" in out:
            return
        m = re.search(r"(INSTALL_[A-Z_]+)", out)
        code = m.group(1) if m else ""
        if code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE":
            raise Failed("Another key", "The TV has a copy signed with another key.", [out.strip()[-300:]])
        if code == "INSTALL_FAILED_VERSION_DOWNGRADE":
            raise Failed("The TV has a newer version", "Android never puts an older version over a newer one. "
                         "Update this copy of Glass (git pull), then try again.", [out.strip()[-300:]])
        if code == "INSTALL_FAILED_INSUFFICIENT_STORAGE":
            raise Failed("The TV is out of space", "Remove an app or two on the TV, then try again.")
        raise Failed("The TV didn't install it", "Try again; the log says more.", out.strip().splitlines()[-3:])

    def uninstall(self, pkg):
        return "Success" in self.a("uninstall", pkg, timeout=120)

    # -- what Glass needs, and making it home
    def allow(self):
        self.sh("appops set %s WRITE_SETTINGS allow; appops set %s REQUEST_INSTALL_PACKAGES allow; "
                "pm grant %s android.permission.WRITE_SECURE_SETTINGS; pm grant %s android.permission.READ_EXTERNAL_STORAGE; "
                "pm grant %s android.permission.ACCESS_FINE_LOCATION" % ((PKG,) * 5))
        if "package:" in self.sh("pm path %s" % ASSISTANT):
            self.sh("pm grant %s android.permission.RECORD_AUDIO" % ASSISTANT)

    def remote_helper(self):
        """Glass's remote helper (an accessibility service): on, and bound again to the new Glass. True if on."""
        svc = PKG + "/.HomeKeyService"
        cur = self.sh("settings get secure enabled_accessibility_services")
        parts = [p for p in cur.split(":") if p and p != "null"]
        others = [p for p in parts if "HomeKeyService" not in p]
        self.sh("settings put secure enabled_accessibility_services '%s'" % ":".join(others))
        time.sleep(2)
        self.sh("settings put secure enabled_accessibility_services '%s'; settings put secure accessibility_enabled 1"
                % ":".join(others + [svc]))
        time.sleep(2)
        return "HomeKeyService" in self.sh("settings get secure enabled_accessibility_services")

    def compile(self):
        self.sh("cmd package compile -m speed -f %s; cmd package compile -m speed -f %s" % (PKG, ASSISTANT), timeout=300)

    def home_now(self):
        out = self.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        return out.strip().splitlines()[-1] if out.strip() else ""

    def homes(self):
        """Every home screen on the TV, as [(priority, "package/activity")]. A built-in home can carry a priority no
        installed app can have, and then Android sends Home to it whatever is chosen as the home screen."""
        out = self.sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        found, pri = [], None
        for ln in out.splitlines():
            m = re.search(r"priority=(-?\d+)", ln)
            if m:
                pri = int(m.group(1))
            elif pri is not None and "/" in ln.strip():
                found.append((pri, ln.strip()))
                pri = None
        return found

    def turned_off(self):
        """The TV's own homes Glass turned off (kept on the TV itself, so restore works from any computer)."""
        out = self.sh("cat %s 2>/dev/null" % TURNED_OFF)
        return [p for p in dict.fromkeys(ln.strip() for ln in out.splitlines()) if re.fullmatch(r"[A-Za-z0-9_.]+", p)]

    def make_home(self):
        """Glass as the TV's home screen. First it's simply chosen as home; on a TV whose own home outranks every app
        (Xiaomi, Google TV and others), that home is turned off: never uninstalled, and noted on the TV so ./glass
        restore turns it back on. The fallback home Android keeps for emergencies is never touched. True when Home,
        a restart and the phone's Home all open Glass."""
        comp = "%s/.GlassHomeActivity" % PKG
        self.sh("cmd package set-home-activity --user 0 %s" % comp)
        if self.home_now().startswith(PKG):
            return True
        for strict in (True, False):
            homes = self.homes()
            mine = max([p for p, c in homes if c.startswith(PKG + "/")] or [0])
            over = sorted({c.split("/")[0] for p, c in homes if not c.startswith(PKG + "/") and p > -1000
                           and (p > mine if strict else p >= mine)})
            for pkg in over:
                if "disabled" in self.sh("pm disable-user --user 0 %s" % pkg):
                    self.sh("echo %s >> %s" % (pkg, TURNED_OFF))
            self.sh("cmd package set-home-activity --user 0 %s" % comp)
            if self.home_now().startswith(PKG):
                return True
        return False

    def own_homes_back(self):
        """Restore: the TV's own homes Glass turned off, on again, and the first of them home again. Their names."""
        back = self.turned_off()
        for pkg in back:
            self.sh("pm enable %s" % pkg)
        self.sh("rm -f %s" % TURNED_OFF)
        return back

    def location_on(self):
        """Android shows apps the Wi-Fi networks around only while Location is on. True when it's on."""
        if self.sdk() >= 29:
            return self.sh("settings get secure location_mode").strip() not in ("", "0", "null")
        return self.sh("settings get secure location_providers_allowed").strip() not in ("", "null")

    def location(self):
        """Location on, so Control Center can list nearby Wi-Fi. Its device-only kind: the network kind sends Google's
        consent screen to the TV and stays off until someone agrees there, and a TV has no GPS, so this finds no one's
        position. Android 9 counts any allowed provider as Location on, which is all its Wi-Fi asks for."""
        if not self.location_on():
            if self.sdk() >= 29:
                self.sh("cmd location set-location-enabled true 2>/dev/null; settings put secure location_mode 3")
            else:
                self.sh("settings put secure location_providers_allowed +gps")
            if self.location_on():
                self.sh("touch %s" % LOCATION_ON)   # restore turns it off again
        return self.location_on()

    def location_back(self):
        """Restore: Location off again, if Glass's installer is what turned it on."""
        if "yes" not in self.sh("[ -f %s ] && echo yes" % LOCATION_ON):
            return
        if self.sdk() >= 29:
            self.sh("cmd location set-location-enabled false 2>/dev/null; settings put secure location_mode 0")
        else:
            self.sh("settings put secure location_providers_allowed -gps")
        self.sh("rm -f %s" % LOCATION_ON)

    def speed_back(self):
        """Restore: apps Glass kept from running in the background may again, and the settings it changed for speed
        (Settings ▸ Speed & memory) as they were. The apps it turned off come back with the TV's homes (TURNED_OFF)."""
        for p in [ln.strip() for ln in self.sh("cat %s 2>/dev/null" % LIMITED).splitlines()]:
            if re.fullmatch(r"[A-Za-z0-9_.]+", p):
                self.sh("cmd appops set %s RUN_ANY_IN_BACKGROUND allow" % p)
        for ln in self.sh("cat %s 2>/dev/null" % SETTINGS_BEFORE).splitlines():
            m = re.fullmatch(r"(global|secure|system) ([A-Za-z0-9_.]+) ([A-Za-z0-9_.:-]*)", ln.strip())
            if m:
                self.sh("settings put %s %s %s" % m.groups() if m.group(3) else "settings delete %s %s" % m.groups()[:2])
        self.sh("rm -f %s %s" % (LIMITED, SETTINGS_BEFORE))

    def bluetooth(self):
        """"on", "off", or "failing": the TV's Bluetooth keeps stopping (its service crashing as it starts), which
        leaves a Bluetooth remote on infrared, without its microphone. Only a restart of the TV clears that."""
        d = self.sh("dumpsys bluetooth_manager")
        if re.search(r"enabled:\s*true", d) and re.search(r"state:\s*ON\b", d):
            return "on"
        loops = self.sh("logcat -d -b main,system -t 4000 2>/dev/null | grep -c 'Entering STATE_OFF but mEnabled is true'",
                        timeout=20).strip()
        return "failing" if loops.isdigit() and int(loops) >= 2 else "off"

    def restart(self):
        """Restarts the TV (as its own menu does). It answers again about a minute later."""
        self.a("reboot", timeout=15)
        return True

    def sdk(self):
        try:
            return int(self.sh("getprop ro.build.version.sdk").strip() or 0)
        except ValueError:
            return 0

    def keyboard(self):
        for _ in range(20):
            if "GlassKeyboard" in self.sh("ime list -a -s"):
                break
            time.sleep(1)
        self.sh("ime enable %s/.GlassKeyboard; ime set %s/.GlassKeyboard" % (PKG, PKG))
        return "GlassKeyboard" in self.sh("settings get secure default_input_method")

    def assistant_button(self):
        if "GlassVoiceService" not in self.sh("dumpsys package %s" % ASSISTANT):
            return False
        self.sh("settings put secure voice_interaction_service %s/.GlassVoiceService; "
                "settings put secure assistant %s/.GlassVoiceService" % (ASSISTANT, ASSISTANT))
        return True

    def screen_saver(self):
        self.sh("settings put secure screensaver_enabled 1; settings put secure screensaver_components %s/.ClockDream" % PKG)
        self.sh("cmd notification allow_listener %s/%s.ThreadListener" % (PKG, PKG))

    def open(self, what):
        """Opens one of Glass's places on the TV (keys: start the key helper; phone: the code for a phone)."""
        self.sh("am start -n %s/.GlassHomeActivity --es open %s" % (PKG, what))

    def keys_up(self):
        """Glass's key helper is running (listening on the TV's 127.0.0.1:8766)."""
        hexport = ":%04X" % KEYS_PORT
        out = self.sh("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")
        return any(hexport in ln.split()[1] and ln.split()[3] == "0A" for ln in out.splitlines()[1:]
                   if len(ln.split()) > 3)

    def crashes(self):
        out = self.sh("logcat -d -b crash", timeout=20)
        return len(re.findall(r"Process: com\.gathin", out))

    def memory(self):
        mem = self.sh("cat /proc/meminfo")
        t = re.search(r"MemTotal:\s+(\d+)", mem)
        a = re.search(r"MemAvailable:\s+(\d+)", mem)
        return (int(t.group(1)) // 1024 if t else 0, int(a.group(1)) // 1024 if a else 0)

    def disabled(self):
        return set(re.findall(r"package:(\S+)", self.sh("pm list packages -d")))

    def press_home(self):
        self.sh("input keyevent 3")


# ---- the debloat list (only for the TV model it was made on) ---------------------------------------------------

def debloat_list(include_optional=False):
    out = []
    try:
        for line in (paths.ROOT / "tv" / "debloat-list.txt").read_text().splitlines():
            t = line.strip()
            if not t or t.startswith("#"):
                continue
            if "[optional]" in t and not include_optional:
                continue
            out.append(t.split()[0])
    except OSError:
        pass
    return out
