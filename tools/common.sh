# Shared by the tools (sourced, not run): this Mac's Java, Android SDK, Gradle and adb, and the TV's address.
# Nothing personal is kept in the repository: the TV's address is in .tv next to the README (never committed), the
# assistant's keys in ~/Desktop/tv/.env and the update service's address and key in ~/Desktop/tv/.updates.env.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TVDIR="${GLASS_HOME:-$HOME/Desktop/tv}"
# this Mac's build tools (java_home.txt, android-sdk, gradle-8.9, gradle-home, platform-tools): GLASS_TOOLCHAIN,
# else ~/Desktop/tv/toolchain (the first setup kept them in ~/Desktop/tv/_bridge, still looked at)
TOOLCHAIN="${GLASS_TOOLCHAIN:-$TVDIR/toolchain}"
[ -d "$TOOLCHAIN/android-sdk" ] || [ ! -d "$TVDIR/_bridge/android-sdk" ] || TOOLCHAIN="$TVDIR/_bridge"
export GLASS_ENV="${GLASS_ENV:-$TVDIR/.env}"
export GLASS_UPDATES_FILE="${GLASS_UPDATES_FILE:-$TVDIR/.updates.env}"
PKG=com.gathin.tvhome
AS=com.gathin.glass.assistant

say() { echo "• $*"; }
die() { echo "✗ $*" >&2; exit 1; }
short() { printf '%s' "${1/#$HOME/~}"; }
# for anything that may be read by someone else: no addresses, no hardware ids, no home folder
mask() {
  sed -E 's/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/<mac>/g; s/[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(:[0-9]+)?/<ip>/g; s/[A-Za-z0-9-]+\.workers\.dev/<service>.workers.dev/g' | sed "s#$HOME#~#g"
}

# Java 17, the Android SDK and Gradle's cache, from what's on this Mac
tools_ready() {
  if [ -z "${JAVA_HOME:-}" ]; then
    if [ -s "$TOOLCHAIN/java_home.txt" ]; then JAVA_HOME="$(cat "$TOOLCHAIN/java_home.txt")"
    elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    elif command -v brew >/dev/null 2>&1 && [ -d "$(brew --prefix openjdk@17 2>/dev/null)" ]; then JAVA_HOME="$(brew --prefix openjdk@17)"
    else die "Java 17 isn't installed (brew install openjdk@17)"; fi
  fi
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
  if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$TOOLCHAIN/android-sdk" ]; then ANDROID_HOME="$TOOLCHAIN/android-sdk"
    elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"
    else die "The Android SDK isn't installed (or set ANDROID_HOME)"; fi
  fi
  export ANDROID_HOME
  if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d "$TOOLCHAIN/gradle-home" ]; then export GRADLE_USER_HOME="$TOOLCHAIN/gradle-home"; fi
  return 0
}

# Gradle 8.9: the copy on this Mac when there is one, else the wrapper (which fetches it once)
gradle_run() {
  if [ -x "$TOOLCHAIN/gradle-8.9/bin/gradle" ]; then "$TOOLCHAIN/gradle-8.9/bin/gradle" --no-daemon --console=plain -q "$@"
  else bash ./gradlew --no-daemon --console=plain -q "$@"; fi
}

# The update service's address and key (GLASS_UPDATE_URL, GLASS_UPDATE_TOKEN), from ~/Desktop/tv/.updates.env
updates_env() {
  [ -s "$GLASS_UPDATES_FILE" ] || die "$(short "$GLASS_UPDATES_FILE") isn't there yet: run tools/set-secrets.sh first"
  GLASS_UPDATE_URL="$(sed -n 's/^GLASS_UPDATE_URL=//p' "$GLASS_UPDATES_FILE" | head -1)"
  GLASS_UPDATE_TOKEN="$(sed -n 's/^GLASS_UPDATE_TOKEN=//p' "$GLASS_UPDATES_FILE" | head -1)"
  [ -n "$GLASS_UPDATE_URL" ] && [ -n "$GLASS_UPDATE_TOKEN" ] || die "$(short "$GLASS_UPDATES_FILE") is missing the address or the key"
  export GLASS_UPDATE_URL GLASS_UPDATE_TOKEN
}

# The TV's address: GLASS_TV, else .tv here, else asked once (and kept in .tv, which git ignores)
tv_address() {
  if [ -n "${GLASS_TV:-}" ]; then echo "$GLASS_TV"; return; fi
  if [ -s "$ROOT/.tv" ]; then head -1 "$ROOT/.tv"; return; fi
  [ -t 0 ] || die "The TV's address isn't known: put it in $(short "$ROOT")/.tv (like 192.168.1.20:5555), or set GLASS_TV"
  local a
  read -rp "The TV's address (on the TV: Settings › Network, like 192.168.1.20): " a
  case "$a" in *:*) ;; *) a="$a:5555" ;; esac
  (umask 077; printf '%s\n' "$a" > "$ROOT/.tv")
  echo "$a"
}

# adb, connected to the TV (it has to be on: nothing here wakes it)
adb_ready() {
  ADB="$(command -v adb || true)"
  if [ -z "$ADB" ]; then
    for c in "$TOOLCHAIN/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
      if [ -x "$c" ]; then ADB="$c"; break; fi
    done
  fi
  [ -n "$ADB" ] || die "adb isn't installed (brew install android-platform-tools)"
  TV="$(tv_address)"
  "$ADB" connect "$TV" >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6; do
    [ "$("$ADB" -s "$TV" get-state 2>/dev/null)" = device ] && return 0
    sleep 2
  done
  die "The TV doesn't answer: is it on and on this network, with network debugging on and this Mac allowed?"
}
A() { "$ADB" -s "$TV" shell "$@"; }
awake() { A dumpsys power 2>/dev/null | tr -d '\r' | grep -q 'mWakefulness=Awake'; }
