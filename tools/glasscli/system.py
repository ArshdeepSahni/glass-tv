"""This computer, and the tools that build Glass: Java 17, the Android SDK, Gradle (the repository's wrapper).

Found where they already are, or fetched into GLASS_HOME/toolchain (nothing is installed system-wide and nothing
asks for an administrator's password). Downloads are checked against the checksum their publisher lists.
"""
import glob
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from . import paths
from .app import Failed

SDK_PLATFORM = "platforms;android-34"
SDK_BUILD_TOOLS = "build-tools;34.0.0"
SDK_PARTS = ["platform-tools", SDK_PLATFORM, SDK_BUILD_TOOLS]
REPO_XML = "https://dl.google.com/android/repository/repository2-3.xml"
SDK_TERMS = "https://developer.android.com/studio/terms"
ADOPTIUM = "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=%s&image_type=jdk&os=%s&vendor=eclipse"


# ---- this computer ---------------------------------------------------------------------------------------------

def computer():
    """{os: mac|linux|other, arch: aarch64|x64, label: 'macOS 15.1 · Apple silicon', wsl: bool}"""
    sysname = platform.system()
    m = platform.machine().lower()
    arch = "aarch64" if m in ("arm64", "aarch64") else "x64" if m in ("x86_64", "amd64") else m
    if sysname == "Darwin":
        try:
            arm = subprocess.run(["sysctl", "-in", "hw.optional.arm64"], capture_output=True, text=True,
                                 timeout=3).stdout.strip() == "1"
        except Exception:
            arm = arch == "aarch64"
        if arm:
            arch = "aarch64"
        ver = platform.mac_ver()[0] or ""
        return {"os": "mac", "arch": arch, "wsl": False, "short": "macOS %s" % ver,
                "label": "macOS %s · %s" % (ver, "Apple silicon" if arm else "Intel")}
    if sysname == "Linux":
        name = "Linux"
        try:
            for line in Path("/etc/os-release").read_text().splitlines():
                if line.startswith("PRETTY_NAME="):
                    name = line.split("=", 1)[1].strip().strip('"')
        except OSError:
            pass
        wsl = "microsoft" in platform.release().lower()
        short = name.replace(" LTS", "").replace("GNU/Linux ", "")
        return {"os": "linux", "arch": arch, "wsl": wsl, "short": short + (" (WSL)" if wsl else ""),
                "label": "%s%s · %s" % (name, " (WSL)" if wsl else "", "ARM" if arch == "aarch64" else "x86-64")}
    return {"os": "other", "arch": arch, "wsl": False, "label": sysname, "short": sysname}


def free_gb(path):
    p = Path(path)
    while not p.exists() and p != p.parent:
        p = p.parent
    try:
        return shutil.disk_usage(str(p)).free / 1e9
    except OSError:
        return 99.0


# ---- fetching --------------------------------------------------------------------------------------------------

def fetch_text(ui, url, timeout=40):
    """A small file from the web, as text (curl when it's here: it uses this computer's own certificates)."""
    ui.log("GET " + url)
    if shutil.which("curl"):
        r = subprocess.run(["curl", "-fsSL", "--max-time", str(timeout), url], capture_output=True, timeout=timeout + 5)
        if r.returncode == 0:
            return r.stdout.decode("utf-8", "replace")
        ui.log("   curl: " + r.stderr.decode("utf-8", "replace").strip()[:300])
    import urllib.request
    try:
        with urllib.request.urlopen(url, timeout=timeout) as f:
            return f.read().decode("utf-8", "replace")
    except Exception as e:
        raise Failed("Couldn't reach %s" % url.split("/")[2], "Check that this computer is online, then try again.",
                     [str(e)])


def download(ui, url, dest, size=0, checksum=None, algo="sha256", what="it"):
    """url → dest, showing progress; checked against checksum. Returns dest."""
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    if part.exists():
        part.unlink()
    ui.log("GET %s → %s" % (url, paths.short(dest)))
    mb = size / 1e6 if size else 0

    def show(got):
        if size:
            ui.progress(min(1.0, got / size), "%d of %d MB" % (got / 1e6, mb))
        else:
            ui.progress(None, "%d MB" % (got / 1e6))

    if shutil.which("curl"):
        p = subprocess.Popen(["curl", "-fsSL", "--retry", "3", "--connect-timeout", "20", "-o", str(part), url],
                             stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, start_new_session=True)
        with ui.lock:
            ui.proc = p
        while p.poll() is None:
            ui.check()
            show(part.stat().st_size if part.exists() else 0)
            time.sleep(0.25)
        with ui.lock:
            ui.proc = None
        ui.check()
        if p.returncode != 0:
            err = p.stderr.read().decode("utf-8", "replace").strip()
            ui.log("   curl: " + err)
            raise Failed("Couldn't download %s" % what, "Check that this computer is online, then try again.", [err])
    else:
        import urllib.request
        try:
            with urllib.request.urlopen(url, timeout=60) as f, open(part, "wb") as out:
                got = 0
                last = 0
                while True:
                    ui.check()
                    chunk = f.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
                    got += len(chunk)
                    if time.time() - last > 0.2:
                        show(got)
                        last = time.time()
        except Failed:
            raise
        except Exception as e:
            raise Failed("Couldn't download %s" % what, "Check that this computer is online, then try again.", [str(e)])
    if checksum:
        h = hashlib.new(algo)
        with open(part, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest().lower() != checksum.lower():
            part.unlink()
            raise Failed("%s didn't arrive whole" % what[:1].upper() + what[1:],
                         "Its checksum doesn't match what its publisher lists. Try again.")
    part.rename(dest)
    return dest


def unzip(zpath, dest):
    """Unzips keeping each file's permissions (Python's zipfile drops them, and the SDK's tools must stay runnable)."""
    with zipfile.ZipFile(zpath) as z:
        for info in z.infolist():
            out = Path(dest) / info.filename
            if not str(out.resolve()).startswith(str(Path(dest).resolve())):
                continue   # nothing outside dest
            if info.is_dir():
                out.mkdir(parents=True, exist_ok=True)
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(out, "wb") as dst:
                shutil.copyfileobj(src, dst)
            mode = (info.external_attr >> 16) & 0o777
            if mode:
                os.chmod(out, mode)


# ---- Java 17 ---------------------------------------------------------------------------------------------------

def java_major(home):
    java = Path(home) / "bin" / "java"
    if not java.exists():
        return 0
    try:
        r = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=20)
    except Exception:
        return 0
    m = re.search(r'version "(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
    if not m:
        return 0
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 and m.group(2) else major


def find_java():
    """A Java 17–21 home already on this computer, or None."""
    cands = []
    if os.environ.get("JAVA_HOME"):
        cands.append(os.environ["JAVA_HOME"])
    jt = paths.TOOLCHAIN / "java_home.txt"
    if jt.is_file():
        cands.append(jt.read_text().strip())
    cands += sorted(glob.glob(str(paths.TOOLCHAIN / "jdk-17*" / "Contents" / "Home")))
    cands += sorted(glob.glob(str(paths.TOOLCHAIN / "jdk-17*")))
    if Path("/usr/libexec/java_home").exists():
        try:
            r = subprocess.run(["/usr/libexec/java_home", "-v", "17"], capture_output=True, text=True, timeout=10)
            if r.returncode == 0:
                cands.append(r.stdout.strip())
        except Exception:
            pass
    cands += ["/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
              "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"]
    cands += sorted(glob.glob("/usr/lib/jvm/*17*")) + sorted(glob.glob("/usr/lib/jvm/*21*"))
    seen = set()
    for c in cands:
        if not c or c in seen:
            continue
        seen.add(c)
        if 17 <= java_major(c) <= 21:
            return c
    return None


def get_java(ui, comp):
    """Eclipse Temurin 17 into the toolchain folder. Returns its home."""
    os_name = "mac" if comp["os"] == "mac" else "linux"
    meta = json.loads(fetch_text(ui, ADOPTIUM % (comp["arch"], os_name)))
    if not meta:
        raise Failed("No Java 17 for this computer", "Eclipse Temurin has no Java 17 for %s." % comp["label"])
    pkg = meta[0]["binary"]["package"]
    name = meta[0].get("release_name", "Java 17")
    ui.say(text="Java %s (Eclipse Temurin), %d MB, into %s." % (name.replace("jdk-", ""), pkg["size"] / 1e6,
                                                                  paths.short(paths.TOOLCHAIN)))
    tgz = download(ui, pkg["link"], paths.TOOLCHAIN / pkg["name"], pkg.get("size", 0), pkg.get("checksum"),
                   "sha256", "Java")
    ui.say(text="Unpacking Java…", progress=(None, ""))
    with tarfile.open(tgz) as t:
        top = t.getnames()[0].split("/")[0]
        dest = paths.TOOLCHAIN
        for m in t.getmembers():
            if m.name.startswith("/") or ".." in m.name.split("/"):
                continue
            t.extract(m, dest)
    tgz.unlink()
    home = paths.TOOLCHAIN / top
    if (home / "Contents" / "Home").is_dir():
        home = home / "Contents" / "Home"
    if java_major(home) != 17:
        raise Failed("Java didn't unpack as it should", "Try again; the log says more.")
    (paths.TOOLCHAIN / "java_home.txt").write_text(str(home) + "\n")
    return str(home)


# ---- the Android SDK -------------------------------------------------------------------------------------------

def sdk_has(root, part):
    root = Path(root)
    if part == "platform-tools":
        return (root / "platform-tools" / "adb").exists()
    return (root / part.replace(";", "/")).is_dir()


def find_sdk():
    """(root, missing parts) for the best SDK on this computer; root None when there's none."""
    cands = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), str(paths.TOOLCHAIN / "android-sdk"),
             str(Path.home() / "Library" / "Android" / "sdk"), str(Path.home() / "Android" / "Sdk")]
    best = None
    for c in cands:
        if not c or not Path(c).is_dir():
            continue
        missing = [p for p in SDK_PARTS if not sdk_has(c, p)]
        if "platform-tools" in missing and (paths.TOOLCHAIN / "platform-tools" / "adb").exists():
            missing.remove("platform-tools")   # the first Glass computer keeps adb next to the SDK
        if best is None or len(missing) < len(best[1]):
            best = (c, missing)
        if not missing:
            break
    return best if best else (None, list(SDK_PARTS))


def find_adb(sdk=None):
    for c in [paths.TOOLCHAIN / "platform-tools" / "adb", Path(sdk) / "platform-tools" / "adb" if sdk else None,
              shutil.which("adb")]:
        if c and Path(c).exists():
            return str(c)
    return None


def sdkmanager(root):
    for c in [Path(root) / "cmdline-tools" / "latest" / "bin" / "sdkmanager"] + \
             [Path(p) for p in sorted(glob.glob(str(Path(root) / "cmdline-tools" / "*" / "bin" / "sdkmanager")))] + \
             [Path(root) / "tools" / "bin" / "sdkmanager"]:
        if c.exists():
            return str(c)
    return None


def get_cmdline_tools(ui, comp, root):
    """Google's command-line tools (sdkmanager) into root/cmdline-tools/latest."""
    host = "macosx" if comp["os"] == "mac" else "linux"
    xml = fetch_text(ui, REPO_XML)
    tree = ET.fromstring(xml)
    pick = None
    for pkg in tree.iter("remotePackage"):
        if pkg.get("path") != "cmdline-tools;latest":
            continue
        ch = pkg.find("channelRef")
        stable = ch is None or ch.get("ref") == "channel-0"
        for arc in pkg.iter("archive"):
            ho = arc.find("host-os")
            if ho is not None and ho.text == host:
                comp_el = arc.find("complete")
                cand = (comp_el.find("url").text, int(comp_el.find("size").text), comp_el.find("checksum").text)
                if stable or pick is None:
                    pick = cand
        if pick and stable:
            break
    if not pick:
        raise Failed("Couldn't find Google's Android tools", "Google's list of Android tools had none for this "
                                                             "computer. Try again later.")
    url, size, sha1 = pick
    zpath = download(ui, "https://dl.google.com/android/repository/" + url, Path(root) / url, size, sha1, "sha1",
                     "the Android tools")
    tmp = Path(root) / "cmdline-tools" / ".unpack"
    if tmp.exists():
        shutil.rmtree(tmp)
    unzip(zpath, tmp)
    zpath.unlink()
    latest = Path(root) / "cmdline-tools" / "latest"
    if latest.exists():
        shutil.rmtree(latest)
    shutil.move(str(tmp / "cmdline-tools"), str(latest))
    shutil.rmtree(tmp, ignore_errors=True)
    return sdkmanager(root)


def licenses_accepted(root):
    return (Path(root) / "licenses" / "android-sdk-license").is_file()


def install_sdk_parts(ui, root, java_home, parts):
    """sdkmanager puts the parts in root, showing its progress."""
    sm = sdkmanager(root)
    env = dict(os.environ, JAVA_HOME=java_home, ANDROID_HOME=str(root))
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    if not licenses_accepted(root):
        ui.run([sm, "--sdk_root=%s" % root, "--licenses"], env=env, feed="y\n", timeout=300,
               what="Accepting the Android SDK License stopped")
    state = {"n": 0}
    total = len(parts)

    def on_line(line):
        m = re.search(r"(\d+)%\s*(.*)", line)
        if m:
            pct = int(m.group(1)) / 100.0
            what = m.group(2).strip().rstrip(".") or "Working"
            if what.lower().startswith("unzipping"):
                what = "Unpacking"
            elif what.lower().startswith("downloading"):
                what = "Downloading"
            ui.progress((state["n"] + pct) / total, what)
            if pct >= 1.0 and "install" in line.lower():
                state["n"] = min(total - 1, state["n"] + 1)

    ui.run([sm, "--sdk_root=%s" % root] + parts, env=env, on_line=on_line, timeout=3600,
           what="Installing the Android SDK stopped")


# ---- the signing key -------------------------------------------------------------------------------------------

def ensure_keystore(ui, java_home):
    """The key Glass is signed with: made once (a standard debug-style key) when there's none."""
    ks = paths.keystore()
    if ks.is_file():
        return ks, False
    keytool = Path(java_home) / "bin" / "keytool"
    ks.parent.mkdir(parents=True, exist_ok=True)
    ui.run([keytool, "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android", "-alias",
            "AndroidDebugKey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname",
            "CN=Glass,O=Glass,C=US", "-storetype", "PKCS12"], what="Making the signing key stopped")
    os.chmod(ks, 0o600)
    return ks, True


def cert_sha256(ui, java_home, ks=None):
    """The SHA-256 of the signing certificate (what Android compares), lowercase hex, or ''."""
    ks = ks or paths.keystore()
    keytool = Path(java_home) / "bin" / "keytool"
    text = ui.out([keytool, "-list", "-v", "-keystore", ks, "-storepass", "android", "-alias", "AndroidDebugKey"])
    m = re.search(r"SHA256:\s*([0-9A-F:]{95})", text)
    return m.group(1).replace(":", "").lower() if m else ""
