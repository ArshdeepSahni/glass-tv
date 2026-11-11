# AGENTS.md

Glass is a home screen and assistant for Android TV, with its installer (`./glass`), its update service and its
website. One maintainer releases it; agents do much of the work. These rules always apply, to every change. When a
task and a rule disagree, follow the rule and say so. On the owner's computer, a CONTEXT.md beside this file holds the
whole story (it's kept out of git): read it once first.

## Where things are

| | |
|---|---|
| `launcher/` | Glass, the home screen (Java 17, Gradle 8.9, minSdk 26; laid out in 1280 × 720 design px) |
| `assistant/` | Glass Assistant |
| `glass`, `install.sh`, `tools/glasscli/` | the installer |
| `site/` | the website and docs: `site/build.py`, words and lists in `site/data/*.json`, pictures in `site/assets/` |
| `tools/scenes.py`, `tools/scenes/` | the TV's pictures and films, drawn from the launcher's own layout |
| `docs/readme/` | the README's template and pictures |
| `CHANGELOG.md` | what's new, in the words the TV shows |
| `RELEASING.md`, `docs/MAINTAINING.md`, `docs/RUNBOOK.md` | releasing, maintaining, and what to do when something stops |

## Commands

```sh
cd launcher && ./gradlew testDebugUnitTest assembleDebug   # Glass builds and its tests pass
python3 tools/parity.py                # changelog, pictures, README, nothing private: before every commit
python3 site/build.py /tmp/glass-site  # the website, then the same checks
python3 tools/scenes.py [name]         # draw the TV's pictures again (half a minute; --films for the films)
python3 tools/readme_pictures.py       # the README's pictures, from the site's
python3 tools/readme.py                # README.md from docs/readme/README.template.md
tools/release.sh                       # the only way to release (RELEASING.md)
tools/verify-release.sh                # one version and one date on every surface
```

## Rules

**One release, every surface, together**

1. A change people can see ships in the same commit as everything that shows it: its line under `## Unreleased` in
   `CHANGELOG.md`, the site's words (`site/build.py`, `site/data/*.json`), the docs (`docs/`, `DESIGN.md`), the
   README's template, and its pictures drawn again.
2. Release only with `tools/release.sh`. Never tag, publish or deploy by hand: it puts out the update service, GitHub's
   release page, the site, the docs and the README together, in that order, then checks them.
3. The site never runs ahead of the TVs: it isn't deployed while `## Unreleased` has lines (`site/deploy.py`), so a docs
   fix goes live at once only when nothing is waiting to be released.
4. A release is done when `tools/verify-release.sh` passes. When it doesn't, fix forward with a new version.

**The changelog and versions**

5. Write new lines only under `## Unreleased`: a title line, then `- ` lines in plain words for people watching TV.
   Never type a date: `tools/release.sh` writes the day it releases.
6. Never rewrite a released section. Correct a wrong fact (a date, a name) in a commit that says so.
7. A fix raises the last number (1.0.2), a feature or a new Settings section the middle one (1.1.0), and every release
   raises `versionCode` by one. The assistant's version moves only when the assistant changed.

**Pictures and words**

8. Pictures are drawn from the launcher's layout, never captured from a TV. When a screen changes, change its scene in
   `tools/scenes/tv.js` in the same commit, run `python3 tools/scenes.py <name>`, look at it, then
   `python3 tools/readme_pictures.py`. `tools/parity.py` fails while a picture is older than its screen.
9. A new screen or feature gets its entry and picture in `site/data/components.json`, and its words wherever the site
   or the docs describe that part: specs, permissions, troubleshooting, the FAQ, removing Glass.
10. Change the README only through `docs/readme/README.template.md`, then `python3 tools/readme.py`.
11. Use sample content only in pictures, recordings, docs and tests (`Sample.java`: Home, 192.168.1.20, Living Room TV).

**Before you push, after you release**

12. Run the tests and `python3 tools/parity.py`, and push only when both pass. GitHub's Check run runs them again.
13. Report a release in one line: the version, the date, and each surface OK or not (`tools/verify-release.sh`).

**Safety and privacy**

14. Never print, commit or put in chat: keys, tokens, IP or MAC addresses, the site's or the update service's address,
    account or owner names, device names or IDs, Wi-Fi names, or home folders (write `~`). Never open `.env` or
    `.updates.env`.
15. Never force-push, rewrite pushed history, move or delete a tag, or delete or replace a published release. A pushed
    version is spent.
16. Ask the owner first before anything that can't be undone or that changes settings: GitHub or Cloudflare settings,
    secrets, `.github/workflows/`, the licence, deleting anything public; on a TV, turning apps off, uninstalling, or
    waking or taking over a TV someone may be watching. Act without asking on everything else the checks cover.
17. Never log what people say to the assistant, on the TV or in a tool: lengths only.

**How to work**

18. Leave pushing and releasing to the owner unless asked: say what's ready and which command puts it live.
19. When two rules, or a rule and a request, conflict: do the safe part, follow the stricter rule, and say which.
20. When a mistake gets past these rules, add a one-line rule here or a check to `tools/parity.py` in the same change.
