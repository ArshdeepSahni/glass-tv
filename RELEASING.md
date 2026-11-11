# Releasing Glass

A release reaches every surface together, or none of them: the TVs' update service, GitHub's release page, the
website (the landing page and the docs), the README and the changelog all say the same version and the same date.
`tools/release.sh` does it. This is what has to be ready first, what it does, and what to do when something stops.

## Ready to release

- [ ] Every change people can see has its line under `## Unreleased` in `CHANGELOG.md`, below a title line ("Glass on
      more TVs."). No date: the release writes it.
- [ ] `launcher/app/build.gradle`: `versionCode` raised by one and `versionName` raised (a fix 1.0.2, a feature 1.1.0).
      The assistant's only when it changed.
- [ ] The site and the docs say it: `site/data/components.json` (with its picture), `site/data/screens.json`, the
      words in `site/build.py` (specs, permissions, troubleshooting, the FAQ, removing Glass), `docs/` and `DESIGN.md`.
- [ ] The README says it: `docs/readme/README.template.md`, then `python3 tools/readme.py`.
- [ ] Every screen that changed is drawn again: `python3 tools/scenes.py <name>` (and look at it), then
      `python3 tools/readme_pictures.py`; the films (`--films`) when a scene they show changed.
- [ ] The tests and `python3 tools/parity.py` pass, and everything is committed.

## Release

On the Mac, in the repository, run `tools/release.sh` (the owner's go-live script runs it when a release is due).
About 20 minutes. It:

1. checks what a script can of the list above (`tools/parity.py`), and that the version is new;
2. pushes what's committed and waits for GitHub's **Check** run: both apps build and the tests pass. Nothing public
   changes yet: while `## Unreleased` has lines, the **Site** run builds the site but doesn't deploy it;
3. writes today's date into the changelog (`## Unreleased` becomes `## <version>`), commits that, and tags it
   `v<version>`, at one moment;
4. pushes the tag and waits for GitHub's **Release** run: build, sign, publish to the update service, and the release
   page with the changelog's title, date and lines. The run stops before publishing when the changelog's date isn't
   the day of the tagged commit;
5. only then pushes the dated changelog to `main`: the Site run deploys the site and its docs, and GitHub shows the
   README that came with it;
6. runs `tools/verify-release.sh`: the same version and date in the changelog, the tag, the release page, the update
   service, the landing page, the docs and the README on GitHub.

Then on a TV: Settings › About this TV › Check for updates.

## When something stops

| Where | What it means | What to do |
|---|---|---|
| The checks, or the Check run | Nothing was released | Fix it, commit, run `tools/release.sh` again |
| The Release run, on a setup problem (a secret, Cloudflare: [docs/RUNBOOK.md](docs/RUNBOOK.md)) | The tag is on GitHub; the TVs, the site and the README are as they were | Fix it, run the failed run again on GitHub (`gh run rerun <id>`), then `tools/release.sh --finish` |
| The Release run, because of the code | The same | `tools/release.sh --abandon` (the lines go back under Unreleased and that version stays unused), fix it, raise the version, release |
| The Site run, or `tools/verify-release.sh` | The TVs have it, a surface lags | Run the Site run again on GitHub, then `tools/verify-release.sh` |

A pushed tag is never moved or deleted, and a published release is never deleted: that version is spent. A release
that turns out bad is undone by a newer one (a TV never installs an older version), and its lines say what it fixes.

## Where each surface comes from

| Surface | Made from | Checked by |
|---|---|---|
| The TVs' update service | The Release run: the APKs, and the version, date and lines from the changelog | The Release run asks it as a TV does; `tools/verify-release.sh` |
| GitHub's release page | The Release run: the changelog's title, date and lines | `tools/verify-release.sh` |
| The website and its docs | `site/build.py`, from the repository: the version from `build.gradle`, what's new from the changelog, the pictures from `tools/scenes.py` | `tools/parity.py` on every build; deployed only when nothing is unreleased; `tools/verify-release.sh` |
| The README | `docs/readme/README.template.md` (`tools/readme.py`), its pictures from the site's (`tools/readme_pictures.py`) | `tools/parity.py`; `tools/verify-release.sh` |
| The changelog | The lines under Unreleased, dated by `tools/release.sh` | `tools/parity.py`; the Release run's date check |
