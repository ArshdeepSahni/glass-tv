#!/usr/bin/env python3
"""Downloads the newest release from Glass's update service, as a TV does:   fetch.py DIR

Writes DIR/manifest.json and the APKs it lists (checked against their sizes and SHA-256), and prints the APKs in the
order they install (the assistant first, Glass last). Needs GLASS_UPDATE_URL and GLASS_UPDATE_TOKEN (tools/common.sh
reads them from ~/Desktop/tv/.updates.env). Prints names, versions and sizes only.
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Glass-TV', 'Authorization': 'Bearer ' + os.environ['GLASS_UPDATE_TOKEN']})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        sys.exit({401: 'The update service didn\'t accept the key', 404: 'Nothing has been released yet'}.get(e.code, f'The update service answered {e.code}'))
    except urllib.error.URLError as e:
        sys.exit(f'The update service didn\'t answer ({e.reason})')


def main():
    if len(sys.argv) != 2 or not os.environ.get('GLASS_UPDATE_URL') or not os.environ.get('GLASS_UPDATE_TOKEN'):
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    base = os.environ['GLASS_UPDATE_URL'].rstrip('/')
    raw = get(base + '/v1/manifest')
    m = json.loads(raw)
    with open(os.path.join(out, 'manifest.json'), 'wb') as f:
        f.write(raw)
    print(f'Glass {m["release"]} ({m.get("date", "")})', file=sys.stderr)
    for a in m['apps']:
        data = get(base + '/v1/apk/' + a['file'])
        if len(data) != a['size'] or hashlib.sha256(data).hexdigest() != a['sha256']:
            sys.exit(f'{a["file"]} didn\'t match its fingerprint')
        path = os.path.join(out, a['file'])
        with open(path, 'wb') as f:
            f.write(data)
        print(f'  {a["name"]} {a["version"]} (version {a["code"]}, {a["size"] // 1024} KB): checked', file=sys.stderr)
        print(path)


if __name__ == '__main__':
    main()
