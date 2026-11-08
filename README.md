# Glass for Android TV

Glass is a home screen and an assistant for Android TV 9, built and tuned on a 720p Realme TV with about 1 GB of
memory (no root). It's two apps, signed with the same key:

| | Folder | Package | What it is |
|---|---|---|---|
| **Glass** | `launcher/` | `com.gathin.tvhome` | The desktop and dock, widgets, Control Center, Settings, Files, Glass's keyboard, the screen savers and wake animation, the phone remote (the TV serves it on the home network) and the key helper |
| **Glass Assistant** | `assistant/` | `com.gathin.glass.assistant` | What the remote's assistant button opens |

## How an update reaches the TV

```
tools/release.sh ─► tag v5.2 on GitHub ─► Actions: build, sign, check ─► Glass's update service on Cloudflare
                                                                          (Worker "glass-updates" + KV storage)
                                                                                         │
Settings › About this TV › Check for updates  ◄── the release's list and its APKs ──────┘
(or the phone remote's TV tab)                     (only with the TV's key)
```

- **Only when asked.** Glass never checks by itself: someone presses *Check for updates* on the TV, or *Check* on
  the phone's TV tab. Both show what's new before anything is installed.
- **Checked, then installed.** Everything is downloaded first and compared with the size and SHA-256 in the
  release's list; only then is it installed: the assistant first, Glass last. Glass's key helper installs it the way
  `adb install` would, so nothing asks on the screen; Glass restarts on the new version and says so. Without the key
  helper, the TV's own installer opens and asks.
- **Same key, never backwards.** Android only takes an update signed with the same key as the app on the TV, and
  this TV (a retail build) never installs an older version over a newer one. To undo a release, release a fixed one
  with a higher `versionCode`. The workflow refuses to publish a version older than the one already out.

## Releasing

1. Change what you like. Bump `versionCode` (+1) and `versionName` in `launcher/app/build.gradle` (and in
   `assistant/app/build.gradle` when the assistant changed). The first word of the launcher's `versionName` is the
   release (`5.2`).
2. Write what's new in `CHANGELOG.md`, under `## 5.2`: the `- ` lines are what the TV and the phone show.
3. Commit, then `tools/release.sh`. It tags `v5.2` and pushes; `gh run watch` follows the build (about ten minutes).
4. On the TV: Settings › About this TV › Check for updates › Install.

*Run workflow* on the Actions tab publishes what's on a branch the same way; it makes the release page only for a version
that's tagged already and has none (a tag run that stopped early).

## On this Mac

| | |
|---|---|
| `tools/build.sh` | Builds both apps into `dist/`, signed with this Mac's key (`launcher` or `assistant` for one) |
| `tools/install-tv.sh` | Installs `dist/` on the TV over the network (adb) and allows what Glass needs |
| `tools/install-tv.sh --service` | Installs the newest published release instead, exactly what the TVs download |
| `tools/install-tv.sh --setup` | Also sets up a TV that never had Glass: home screen, keyboard, assistant button, screen saver |
| `tools/set-secrets.sh` | Once, in Terminal: gives GitHub the secrets its workflow needs (below) |
| `tools/release.sh` | Tags the version and pushes it |

The tools find Java 17, the Android SDK, Gradle 8.9 and adb where this Mac has them (or `JAVA_HOME`,
`ANDROID_HOME`). The TV's address is asked once and kept in `.tv` here, which git ignores; the TV must be on, with
network debugging allowed for this Mac.

## Keys and privacy

Nothing secret or personal is in the repository; `.gitignore` keeps out `.env` files, signing keys, `.tv`, `dist/`
and APKs. What the release workflow needs lives in GitHub's encrypted secrets, set by `tools/set-secrets.sh`:

| Secret | What it is |
|---|---|
| `CLOUDFLARE_API_TOKEN` | A Cloudflare token from the *Edit Cloudflare Workers* template: deploys the Worker and fills its storage. No *Client IP Address Filtering*: GitHub's machines change address every run |
| `CLOUDFLARE_ACCOUNT_ID` | The Cloudflare account it's in |
| `GLASS_UPDATE_TOKEN` | The update service's key: built into Glass, and the Worker answers nothing without it |
| `ANDROID_KEYSTORE_B64` | This Mac's Android signing key, so builds on GitHub update the Glass already on the TV |
| `GLASS_ENV_FILE` | Optional, and best left out: the assistant's keys (`~/Desktop/tv/.env`). Without it, releases leave the assistant as it is and it's built on the Mac when it changes |

On the Mac, `~/Desktop/tv/.env` holds the assistant's keys and `~/Desktop/tv/.updates.env` the update service's
address and key (both readable by you only).

- The workflow runs only GitHub's own actions, pinned to exact commits, and gives each secret only to the steps that
  use it. The runner is a fresh machine each time, deleted after the run.
- An Android app can't hide a key: whatever keys an APK is built with can be read out of it. That's why the
  assistant's keys are best kept off GitHub (it's built on the Mac, as before), and why APKs are never attached to
  GitHub releases: they're only on the update service, which hands them to Glass's key alone. A release page has
  what's new and the SHA-256 of each APK.
- Anyone holding a copy of the Glass APK holds its key too, so keep the APKs to yourself.
- To change the update key: remove its line from `~/Desktop/tv/.updates.env`, run `tools/set-secrets.sh`, release,
  and install that release once with `tools/install-tv.sh` (TVs with the old key can't fetch the release that has
  the new one).

## What's where

```
launcher/                     Glass (Android, Gradle 8.9, AGP 8.5, Java 17, minSdk 26)
  app/src/main/java/…/Updates.java     checking, downloading, verifying, installing
  app/src/main/java/…/KeyDaemon.java   the key helper (the TV's shell user; installs only from Glass's updates folder)
  app/src/main/assets/phone/           the phone remote
assistant/                    Glass Assistant
updates/worker/index.js       the Cloudflare Worker: GET /v1/manifest, GET /v1/apk/<file>, with the key only
updates/manifest.py           the release's list, notes and SHA-256s from the APKs and CHANGELOG.md
updates/publish.py            deploys the Worker, uploads the APKs and the list, then asks it as a TV would
updates/fetch.py              downloads the newest release as a TV does (tools/install-tv.sh --service)
tools/                        the Mac's side
.github/workflows/release.yml the release workflow
```

Fonts, pictures and libraries that came from elsewhere are in [NOTICE.md](NOTICE.md).
