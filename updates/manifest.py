#!/usr/bin/env python3
"""Glass's release list (the manifest TVs read), its notes and fingerprints, from the built APKs and CHANGELOG.md.

  manifest.py version             release=5.1 and code=20, from launcher/app/build.gradle (for the workflow)
  manifest.py build DIST          DIST/manifest.json, DIST/notes.md and DIST/SHA256SUMS.txt, for the APKs in DIST:
                                  glass-<release>-launcher.apk and, when built, glass-<release>-assistant.apk

What's new comes from CHANGELOG.md (tools/changelog.py reads it): the "- " lines under "## <release>", and its title
and day, which tools/release.sh wrote when it tagged the release. On a tag, the build stops unless that day is the day
the tagged commit was made, so the date on the TVs, the release page, the site and the changelog is one date. Each
APK's package and version are read from the APK itself (aapt2, from the Android SDK) and must agree with its
build.gradle.
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import changelog  # noqa: E402  (tools/changelog.py: CHANGELOG.md, read the one way every tool shares)
APPS = {
    'launcher': {'pkg': 'com.gathin.tvhome', 'name': 'Glass', 'gradle': 'launcher/app/build.gradle'},
    'assistant': {'pkg': 'com.gathin.glass.assistant', 'name': 'Glass Assistant', 'gradle': 'assistant/app/build.gradle'},
}


def gradle_version(rel):
    with open(os.path.join(ROOT, rel)) as f:
        s = f.read()
    code = re.search(r'^\s*versionCode\s+(\d+)', s, re.M)
    name = re.search(r'''^\s*versionName\s+['"]([^'"]+)['"]''', s, re.M)
    if not code or not name:
        sys.exit(f'{rel}: no versionCode / versionName')
    return int(code.group(1)), name.group(1)


def plain(version_name):
    """'5.1 glass updates' → '5.1' (the words are for the people reading the source)."""
    return version_name.split(' ')[0]


def aapt2():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ''
    found = sorted(glob.glob(os.path.join(sdk, 'build-tools', '*', 'aapt2')), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r'[.-]', p.split(os.sep)[-2])])
    return found[-1] if found else None


def badging(apk):
    tool = aapt2()
    if not tool:
        return None
    out = subprocess.run([tool, 'dump', 'badging', apk], capture_output=True, text=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    return (m.group(1), int(m.group(2)), m.group(3)) if m else None


def section(release):
    """The release's section in CHANGELOG.md: its title, its day and its "- " lines (at most 12 go to the TVs)."""
    s = changelog.find(release)
    if s is None or not s.items:
        sys.exit(f'CHANGELOG.md has no "- " lines under "## {release}": tools/release.sh makes that section from '
                 f'"## Unreleased"')
    if s.date is None:
        sys.exit(f'CHANGELOG.md\'s {release} has no day on its title line: tools/release.sh writes it as it tags')
    return s


def notes(release):
    return section(release).items[:12]


def tagged_day():
    """The day the commit being built was made, in the time zone it was made in (tools/release.sh dates the changelog
    with that same day, a moment before it tags)."""
    out = subprocess.run(['git', '-C', ROOT, 'log', '-1', '--format=%cd', '--date=format:%Y-%m-%d'],
                         capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else ''


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def build(dist):
    code, name = gradle_version(APPS['launcher']['gradle'])
    release = plain(name)
    apps = []
    for kind in ('assistant', 'launcher'):   # the order the TV installs them in: Glass itself last
        info = APPS[kind]
        path = os.path.join(dist, f'glass-{release}-{kind}.apk')
        if not os.path.isfile(path):
            if kind == 'launcher':
                sys.exit(f'{path} is missing')
            continue
        want_code, want_name = gradle_version(info['gradle'])
        b = badging(path)
        if b and (b[0] != info['pkg'] or b[1] != want_code or b[2] != want_name):
            sys.exit(f'{os.path.basename(path)} says {b}, its build.gradle says {want_code} / {want_name}')
        apps.append({
            'pkg': info['pkg'], 'name': info['name'], 'version': plain(want_name), 'code': want_code,
            'file': os.path.basename(path), 'size': os.path.getsize(path), 'sha256': sha256(path),
        })
    sec = section(release)
    what = sec.items[:12]
    if os.environ.get('GITHUB_REF_TYPE') == 'tag':
        made = tagged_day()
        if made and made != sec.date.isoformat():
            sys.exit(f'CHANGELOG.md dates {release} {changelog.human(sec.date)}, but its tag is on a commit made on '
                     f'{made}: tag with tools/release.sh, which dates the changelog as it tags')
    manifest = {
        'schema': 1,
        'release': release,
        'date': sec.date.isoformat(),
        'notes': what,
        'apps': apps,
    }
    with open(os.path.join(dist, 'manifest.json'), 'w') as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
        f.write('\n')
    with open(os.path.join(dist, 'SHA256SUMS.txt'), 'w') as f:
        for a in apps:
            f.write(f'{a["sha256"]}  {a["file"]}\n')
    with open(os.path.join(dist, 'notes.md'), 'w') as f:
        f.write(f'**{sec.title}.** {changelog.human(sec.date)}.\n\n')
        f.write(f'What\'s new in Glass {release}:\n\n' + ''.join(f'- {n}\n' for n in what))
        f.write('\nOn the TV: Settings › About this TV › Check for updates (or the phone remote\'s TV tab).\n\n')
        f.write('The APKs are on the update service only (the assistant\'s carries its keys). What the TVs download:\n\n')
        for a in apps:
            f.write(f'- `{a["file"]}`: {a["name"]} {a["version"]} (version {a["code"]}, {a["size"] // 1024} KB), SHA-256 `{a["sha256"]}`\n')
    print(f'Glass {release}: ' + ', '.join(f'{a["name"]} {a["version"]} ({a["code"]})' for a in apps))


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == 'version':
        code, name = gradle_version(APPS['launcher']['gradle'])
        print(f'release={plain(name)}')
        print(f'code={code}')
    elif len(sys.argv) >= 3 and sys.argv[1] == 'build':
        build(sys.argv[2])
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
