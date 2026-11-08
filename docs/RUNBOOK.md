# Runbook

What to do, and what to do when something goes wrong. Commands run on your computer, in the repository.
`GLASS_HOME` is `~/.glass` (`~/Desktop/tv` on the computer Glass was first made on).

## A release

1. Make the change. Bump `versionCode` (+1) and `versionName` in `launcher/app/build.gradle` (the first word of
   `versionName` is the release: `5.3`). The assistant's own version only when the assistant changed.
2. Add `## 5.3 — <a few words>` to `CHANGELOG.md`, with `- ` lines in plain words: they're what the TV and the phone
   show as what's new.
3. Commit (the **Check** workflow builds both apps and runs the tests on every push), then `tools/release.sh`.
4. `gh run watch` follows the **Release** workflow (about ten minutes; a few with the build cache warm).
5. On the TV: Settings › About this TV › Check for updates › Install. Glass restarts and says it's updated.

Without an update service of your own, `./glass update` builds what's here and installs it over the network.

## Undoing a release

The TV never goes back to an older version, so a bad release is undone by a newer one: revert the change, bump the
version again, release. The workflow refuses to publish a version older than the one already out.

## When the Release workflow stops

| Step that failed | What it means | What to do |
|---|---|---|
| Secrets are there | A secret is missing | `bash tools/set-secrets.sh` in Terminal |
| The tag is that version | The tag and `versionName` disagree | Tag with `tools/release.sh`, which reads the version |
| The Cloudflare token works | Cloudflare refuses the token; its log says why | Expired or rolled: `set-secrets.sh` with a current one. "Cannot use the access token from location": remove the token's *Client IP Address Filtering* in Cloudflare (Edit token) |
| Signed with the TV's key | An APK isn't signed like the Glass on the TV | Check `ANDROID_KEYSTORE_B64` is this computer's signing key, and `updates/signing-cert.sha256` its certificate (`set-secrets.sh` uploads the one and writes the other) |
| Publish to the update service | Older than what's published, or Cloudflare refused | Bump `versionCode`; or read the error and run *Run workflow* again |
| Ask it the way a TV does | The service didn't show the new release in time | Usually a slow first deploy: *Run workflow* on the Actions tab publishes it again |

A run that stopped before *Publish* changed nothing: TVs still see the previous release.

## Keys

| Key | Where | To change it |
|---|---|---|
| Cloudflare API token | GitHub secret | Roll it in Cloudflare, then `set-secrets.sh` and paste the new one |
| Update key | GitHub secret, `GLASS_HOME/.updates.env`, inside Glass | Delete its line in `.updates.env`, `set-secrets.sh`, release, then `./glass update` once (TVs with the old key can't fetch the release that has the new one) |
| Signing key | `GLASS_HOME/glass.keystore` (`~/.android/debug.keystore` on the first computer), GitHub secret | Can't be changed without uninstalling Glass from the TV (its settings go with it): keep it, and keep a copy |
| Assistant's keys | `GLASS_HOME/.env` only | `./glass setup` asks for one; or edit `.env`, then `./glass update` |

Keep the signing key backed up somewhere private: without it no update will ever install over the Glass on the TV.

## The assistant

Its keys never leave your computer, so it isn't part of GitHub's releases. When it changes: bump its version in
`assistant/app/build.gradle`, then `./glass update`.

## The TV

| When | Do |
|---|---|
| Something looks wrong | `./glass doctor` (or `./glass status` to look) |
| Glass is gone (factory reset) | `./glass setup` |
| A TV update or factory reset undid the debloat (the Realme model) | `tools/tv.sh check` |
| Back to the TV as it shipped | `./glass restore` (removes Glass and its assistant) |

The TV's network debugging has to stay on: Glass's key helper uses it to install updates without asking and to let
the phone remote press keys inside apps. The TV asks once to allow each computer, and once for Glass itself.

## On Cloudflare

One Worker, `glass-updates`, and one KV namespace, `glass-releases` (the release list and its APKs, old ones
removed at each release). Both are made and updated by the Release workflow; nothing to do by hand. The Worker
answers only requests that carry the update key.
