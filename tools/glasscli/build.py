"""Building Glass and its assistant with Gradle (the repository's wrapper), with progress in plain words."""
import json
import os
import re
import shutil
from pathlib import Path

from . import paths
from .app import Failed

# Gradle's tasks, in the order they come, as what's happening (the first match wins)
PHASES = [
    (r"preBuild|generate|Manifest|BuildConfig|AidlFiles|Renderscript|Shaders", "Getting ready"),
    (r"Resources|Assets|mergeRes|processRes|Pictures|Jni", "Preparing pictures and layouts"),
    (r"compile.*Java|compile.*Kotlin|javac", "Compiling"),
    (r"minify|R8|Proguard|shrink", "Shrinking and optimising (R8)"),
    (r"dex|Dex", "Converting to Android code"),
    (r"package|zipalign|sign|Sign", "Packaging and signing"),
    (r"assemble", "Finishing"),
]
STATE_FILE = "build-state.json"


def version():
    """(release, versionName, versionCode) from launcher/app/build.gradle."""
    text = (paths.ROOT / "launcher" / "app" / "build.gradle").read_text()
    name = re.search(r"versionName\s+'([^']+)'", text)
    code = re.search(r"versionCode\s+(\d+)", text)
    vn = name.group(1) if name else "?"
    return vn.split()[0], vn, int(code.group(1)) if code else 0


def gradle_env(java_home, sdk):
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["ANDROID_HOME"] = str(sdk)
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
    gh = paths.TOOLCHAIN / "gradle-home"
    if "GRADLE_USER_HOME" not in env:
        env["GRADLE_USER_HOME"] = str(gh)
    env["GLASS_ENV"] = str(paths.ENV_FILE)
    env["GLASS_UPDATES_FILE"] = str(paths.UPDATES_FILE)
    env["GLASS_KEYSTORE"] = str(paths.keystore())
    return env


def gradle_cmd(project_dir):
    local = paths.TOOLCHAIN / "gradle-8.9" / "bin" / "gradle"
    if local.exists():
        return [str(local)]
    w = Path(project_dir) / "gradlew"
    try:
        os.chmod(w, 0o755)
    except OSError:
        pass
    return [str(w)]


def _state():
    try:
        return json.loads((paths.HOME / STATE_FILE).read_text())
    except (OSError, ValueError):
        return {}


def _save_state(st):
    try:
        (paths.HOME / STATE_FILE).write_text(json.dumps(st))
    except OSError:
        pass


def build(ui, which, java_home, sdk):
    """Builds 'launcher' or 'assistant' (assembleRelease) and copies the APK to dist/. Returns its path."""
    project = paths.ROOT / which
    release, _, _ = version()
    env = gradle_env(java_home, sdk)
    st = _state()
    expected = st.get("tasks-" + which) or (58 if which == "launcher" else 46)
    seen = {"n": 0, "phase": "Getting Gradle and the libraries Glass needs", "first": True}
    label = "Glass" if which == "launcher" else "Glass Assistant"

    def on_line(line):
        m = re.match(r"> Task (\S+)", line)
        if m:
            if seen["first"]:
                seen["first"] = False
                ui.say(note="")
            seen["n"] += 1
            task = m.group(1).rsplit(":", 1)[-1]
            for pat, words in PHASES:
                if re.search(pat, task):
                    seen["phase"] = words
                    break
            ui.say(text=seen["phase"],
                   progress=(min(0.99, seen["n"] / float(expected)), "%d%%" % min(99, 100 * seen["n"] // expected)))
            return
        d = re.search(r"(\d+)%", line)
        if seen["first"] and d and ("..." in line or "Downloading" in line):
            ui.say(text="Getting Gradle 8.9 (first time only)", progress=(int(d.group(1)) / 100.0, d.group(1) + "%"))
        elif seen["first"] and line.startswith("Downloading https://services.gradle.org"):
            ui.say(text="Getting Gradle 8.9 (first time only)", progress=(0.0, "0%"))

    first_time = not (Path(env["GRADLE_USER_HOME"]) / "caches").is_dir()
    ui.say(title="Build %s" % label, text="Getting ready", progress=(None, ""),
           note="The first build downloads about 400 MB of tools and libraries; later ones take a minute or two."
           if first_time else "")
    try:
        ui.run(gradle_cmd(project) + ["--no-daemon", "--console=plain", "assembleRelease"], cwd=project, env=env,
               on_line=on_line, timeout=3600, what="Building %s stopped" % label)
    except Failed as f:
        raise explain(f, label)
    st["tasks-" + which] = max(10, seen["n"])
    _save_state(st)
    apk = project / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.exists():
        raise Failed("Building %s made no APK" % label, "Gradle finished but left nothing to install. Try again; "
                                                        "the log says more.")
    paths.DIST.mkdir(parents=True, exist_ok=True)
    out = paths.DIST / ("glass-%s-%s.apk" % (release, which))
    shutil.copyfile(apk, out)
    return out


def explain(f, label):
    """Gradle's usual failures, in plain words."""
    text = "\n".join(f.tail).lower()
    if "could not resolve" in text or "could not get" in text or "unknown host" in text or "could not download" in text:
        return type(f)("Building %s stopped" % label,
                       "Gradle couldn't download what it needs. Check that this computer is online, then try again.",
                       f.tail)
    if "keystore" in text and ("not found" in text or "was tampered" in text or "failed to read key" in text):
        return type(f)("Building %s stopped: the signing key" % label,
                       "Glass's signing key (%s) couldn't be read." % paths.short(paths.keystore()), f.tail)
    if "license" in text and "not accepted" in text:
        return type(f)("Building %s stopped" % label, "The Android SDK License isn't accepted on this computer. "
                                                       "Run ./glass setup again to accept it.", f.tail)
    if "no space left" in text:
        return type(f)("Building %s stopped" % label, "This computer is out of disk space. Free a few GB, then try "
                                                       "again.", f.tail)
    if "sdk location not found" in text:
        return type(f)("Building %s stopped" % label, "Gradle couldn't find the Android SDK. Run ./glass doctor.",
                       f.tail)
    return f
