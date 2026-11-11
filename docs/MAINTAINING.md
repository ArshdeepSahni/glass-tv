# Maintaining Glass

What the [README](../README.md) leaves out: your own update service, releasing, the scripts under `./glass`, where keys and settings live, and what's where in this repository. When something stops, [RUNBOOK.md](RUNBOOK.md) says what to do.

## The installer

`install.sh` is the one-line installer the website gives. Before it downloads anything it checks the computer (macOS or Linux, Python 3.8 or newer, curl or wget) and says what to do about anything missing, instead of stopping part-way; then it downloads Glass into `~/glass-tv` (no git needed) and starts `./glass`. `./glass` does the same checks for Python, and on Windows it points to WSL.

One screen walks through setup: it gets Java 17 and the Android SDK into `~/.glass` (nothing system-wide, no administrator password), finds the TV on your network, waits while you allow this computer on the TV, builds Glass with your own signing key, installs it, and makes it the TV's home screen. Logs go to `~/.glass/logs`; the screen only shows what's happening and what to do. How it looks and behaves is in [design/installer.md](design/installer.md).

| | |
|---|---|
| `./glass` | Setup the first time; afterwards, a menu of the commands below |
| `./glass setup` | The whole setup. Safe to run again: it picks up where it stopped |
| `./glass update` | Gets the newest Glass (`git pull` when this is a clone), builds it and installs it, keeping its settings |
| `./glass status` | Glass and the TV at a glance: versions, home screen, helpers, memory, crashes |
| `./glass doctor` | Finds what's off (home screen, remote helper, keyboard, assistant button) and fixes it |
| `./glass restore` | Removes Glass and its assistant and brings back the TV's original experience |
| `./glass demo` | The whole interface with nothing real happening |

`--tv <ip>` skips the search, `--yes` takes every recommended answer, `--plain` prints plain lines (used automatically when there's no terminal).

**The signing key** (`~/.glass/glass.keystore`) is the one thing to keep: Android installs an update only when it's signed with the same key as the Glass on the TV. Keep a copy somewhere safe.

## Updates from the TV's own Settings (optional)

By default a TV updates from the computer that set it up (`./glass update`). Glass can also update itself, from
Settings › About this TV › Check for updates, from your own update service: your fork on GitHub builds each release
and publishes it to a Cloudflare Worker that only your TVs can read.

```
tools/release.sh ─► tag v5.2 on GitHub ─► Actions: build, sign, check ─► Glass's update service on Cloudflare
                                                                          (Worker "glass-updates" + KV storage)
                                                                                         │
Settings › About this TV › Check for updates  ◄── the release's list and its APKs ──────┘
(or the phone remote's TV tab)                     (only with the TV's key)
```

To set it up, once: a Cloudflare account and an API token from the *Edit Cloudflare Workers* template, GitHub's `gh`
signed in to your fork, then `bash tools/set-secrets.sh` in a terminal. It gives the fork its secrets (below), writes
`updates/signing-cert.sha256` for your key (commit it), and keeps the service's address and key in
`~/.glass/.updates.env`. Then `./glass update` once, so the TV has a Glass that knows the service.

- **Only when asked.** Glass never checks by itself: someone presses *Check for updates* on the TV, or *Check* on
  the phone's TV tab. Both show what's new before anything is installed.
- **Checked, then installed.** Everything is downloaded first and compared with the size and SHA-256 in the
  release's list; only then is it installed: the assistant first, Glass last. Glass's key helper installs it the way
  `adb install` would, so nothing asks on the screen; Glass restarts on the new version and says so. Without the key
  helper, the TV's own installer opens and asks.
- **Same key, never backwards.** Android only takes an update signed with the same key as the app on the TV, and
  a retail TV never installs an older version over a newer one. To undo a release, release a fixed one with a higher
  `versionCode`. The workflow refuses to publish a version older than the one already out.

### Releasing

1. Write what's new under `## Unreleased` in `CHANGELOG.md`: a title line, then `- ` lines (what the TV and the phone
   show). Bump `versionCode` (+1) and `versionName` in `launcher/app/build.gradle` (and in
   `assistant/app/build.gradle` when the assistant changed). The first word of the launcher's `versionName` is the
   release (`1.0.2`).
2. Bring the site, the docs, the README's template and the pictures up to date in the same commit.
3. `tools/release.sh`. It dates the changelog, tags, waits for the build, then puts the site and the README out after
   the update service has the release, and checks that every surface says the same version and date (about 20
   minutes).
4. On the TV: Settings › About this TV › Check for updates › Install.

[RELEASING.md](../RELEASING.md) has the checklist, each step, and what to do when one stops. *Run workflow* on the
Actions tab publishes what's on a branch the same way; it makes the release page only for a version that's tagged
already and has none (a tag run that stopped early).

## The tools underneath

`./glass` is the front door; these do the same jobs as plain scripts, for scripting and for the release workflow's
maintainer:

| | |
|---|---|
| `tools/build.sh` | Builds both apps into `dist/`, signed with this computer's Glass key (`launcher` or `assistant` for one) |
| `tools/install-tv.sh` | Installs `dist/` on the TV over the network (adb) and allows what Glass needs |
| `tools/install-tv.sh --service` | Installs the newest published release instead, exactly what the TVs download |
| `tools/install-tv.sh --setup` | Also sets up a TV that never had Glass: home screen, keyboard, assistant button, screen saver |
| `tools/set-secrets.sh` | Once, in a terminal: gives your fork the secrets its release workflow needs |
| `tools/release.sh` | Releases everywhere at once: the update service, GitHub's release page, the site and the README ([RELEASING.md](../RELEASING.md)) |
| `tools/verify-release.sh` | Checks a release says the same version and date on every surface |
| `tools/tv.sh` | The TV's upkeep: `status`, `check` (the debloat again after a TV update or reset), `restore-stock` |

They find Java 17, the Android SDK, Gradle 8.9 and adb in `GLASS_HOME/toolchain` (or `GLASS_TOOLCHAIN`,
`JAVA_HOME`, `ANDROID_HOME`, the usual places). The TV's address is kept in `.tv` here, which git ignores.

## Keys and privacy

Everything Glass keeps on your computer is in one folder, `GLASS_HOME`: `~/.glass` (on the computer Glass was first
made on, `~/Desktop/tv`). Deleting it removes it all, apart from this repository.

| In `GLASS_HOME` | What it is |
|---|---|
| `glass.keystore` | The key Glass is signed with (readable by you only). Keep a copy |
| `.env` | The assistant's AI service keys, if you gave it one (readable by you only) |
| `.updates.env` | Your update service's address and key, if you set one up (readable by you only) |
| `toolchain/` | Java 17, the Android SDK, Gradle and its cache |
| `logs/` | What the installer did, one file per run |

Nothing secret or personal is in the repository; `.gitignore` keeps out `.env` files, signing keys, `.tv`, `dist/`
and APKs. For your own update service, the release workflow's secrets live in GitHub's encrypted secrets:

| Secret | What it is |
|---|---|
| `CLOUDFLARE_API_TOKEN` | A Cloudflare token from the *Edit Cloudflare Workers* template: deploys the Worker and fills its storage. No *Client IP Address Filtering*: GitHub's machines change address every run |
| `CLOUDFLARE_ACCOUNT_ID` | The Cloudflare account it's in |
| `GLASS_UPDATE_TOKEN` | The update service's key: built into Glass, and the Worker answers nothing without it |
| `ANDROID_KEYSTORE_B64` | Your signing key, so builds on GitHub update the Glass already on the TV |
| `GLASS_ENV_FILE` | Optional, and best left out: the assistant's keys. Without it, releases leave the assistant as it is and `./glass update` builds it on your computer |

- The workflow runs only GitHub's own actions, pinned to exact commits, and gives each secret only to the steps that
  use it. The runner is a fresh machine each time, deleted after the run.
- An Android app can't hide a key: whatever keys an APK is built with can be read out of it. That's why the
  assistant's keys are best kept off GitHub, and why APKs are never attached to GitHub releases: they're only on the
  update service, which hands them to Glass's key alone. A release page has what's new and the SHA-256 of each APK.
- Anyone holding a copy of your Glass APK holds the keys built into it, so keep your APKs to yourself.
- To change the update key: remove its line from `GLASS_HOME/.updates.env`, run `tools/set-secrets.sh`, release,
  and install that release once with `./glass update` (TVs with the old key can't fetch the release that has the
  new one).

## What's where

```
AGENTS.md                     the rules every change follows (CLAUDE.md loads it for Claude Code)
RELEASING.md                  releasing: the checklist, the steps, and what to do when one stops
glass                         the installer (./glass), Python 3 standard library: tools/glasscli/
launcher/                     Glass (Android, Gradle 8.9, AGP 8.5, Java 17, minSdk 26)
  app/src/main/java/…/Updates.java     checking, downloading, verifying, installing
  app/src/main/java/…/KeyDaemon.java   the key helper (the TV's shell user; installs only Glass's updates; turns off only Extras)
  app/src/main/java/…/SwitcherSheet.java  hold Home: the open apps
  app/src/main/assets/phone/           the phone remote
  app/src/test/                        unit tests (the Check workflow runs them)
assistant/                    Glass Assistant
updates/worker/index.js       the Cloudflare Worker: GET /v1/manifest, GET /v1/apk/<file>, with the key only
updates/manifest.py           the release's list, notes and SHA-256s from the APKs and CHANGELOG.md
updates/publish.py            checks the token, deploys the Worker, uploads the APKs and the list, asks it as a TV would
updates/fetch.py              downloads the newest release as a TV does (tools/install-tv.sh --service)
updates/signing-cert.sha256   the certificate releases must be signed with (tools/set-secrets.sh writes it)
tools/                        the scripts underneath; tools/parity.py, the checks that keep the site, the docs, the
                              README and the TV in step (run by every site build)
tv/debloat-list.txt           for the Realme TV model only: its packages kept disabled (tools/tv.sh check)
DESIGN.md                     Glass's design language: colour, type, motion, voice
docs/design/installer.md      the installer's layout and behaviour
docs/RUNBOOK.md               releasing, undoing, when a workflow stops, keys, the TV
.github/workflows/check.yml   every push: both apps build, the tests pass, the installer runs
.github/workflows/release.yml a version tag: build, sign, check, publish, release page
.github/workflows/site.yml    the website: built and put on Cloudflare when it changes
site/                         the website (site/build.py), and docs/readme/ the README's template and pictures
```

Fonts, pictures and libraries that came from elsewhere are in [NOTICE.md](../NOTICE.md).

## The website

`site/` builds the website (`python3 site/build.py`), and the Site workflow puts it on Cloudflare whenever it changes, with the same two Cloudflare secrets as the update service; the site's address is on that run's summary page. It deploys only what's released: while `CHANGELOG.md` has lines under `## Unreleased`, the run builds the site and leaves the live one as it is, and the release puts it out. Every build also runs `tools/parity.py`, so a picture older than its screen, a README that isn't its template, or anything private stops it. How it's made is in [design/site.md](design/site.md). The README is made from `readme/README.template.md` (`tools/readme.py`), and its pictures from the site's own (`tools/readme_pictures.py`).

