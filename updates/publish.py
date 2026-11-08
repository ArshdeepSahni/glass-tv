#!/usr/bin/env python3
"""Glass's update service on Cloudflare: a Worker (glass-updates) in front of a KV namespace (glass-releases).

  publish.py check          says whether the Cloudflare token and account work, and if not, what Cloudflare says
  publish.py prepare        the account's workers.dev name and the namespace exist; prints url=<the service's address>
  publish.py deploy DIST    deploys the Worker, uploads DIST's APKs, then DIST/manifest.json (last), then drops old APKs
  publish.py verify DIST    asks the service the way a TV does and checks it gives back exactly what's in DIST

Needs CLOUDFLARE_API_TOKEN (an "Edit Cloudflare Workers" token), CLOUDFLARE_ACCOUNT_ID and GLASS_UPDATE_TOKEN.
Nothing secret is printed, and in GitHub Actions the service's address is masked in the log too.
Only Python's standard library: it runs the same in the workflow and on a Mac.
"""
import hashlib
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.cloudflare.com/client/v4'
WORKER = 'glass-updates'
NAMESPACE = 'glass-releases'
HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'Glass-Release'


class Refused(Exception):
    def __init__(self, text, status=0):
        super().__init__(text)
        self.status = status


def need(name):
    v = os.environ.get(name, '').strip()
    if not v:
        sys.exit(f'{name} is missing (tools/set-secrets.sh puts it in GitHub)')
    return v


def account():
    return need('CLOUDFLARE_ACCOUNT_ID')


def cf(method, path, body=None, ctype=None, raw=False):
    """One call to Cloudflare's API. raw: the bytes of a KV value (None when it isn't there)."""
    req = urllib.request.Request(f'{API}/accounts/{account()}{path}', data=body, method=method)
    req.add_header('Authorization', 'Bearer ' + need('CLOUDFLARE_API_TOKEN'))
    req.add_header('User-Agent', UA)
    if ctype:
        req.add_header('Content-Type', ctype)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                data = r.read()
            break
        except urllib.error.HTTPError as e:
            data = e.read()
            if raw and e.code == 404:
                return None
            if e.code in (429, 500, 502, 503, 504) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise Refused(f'{method} {path.split("?")[0]}: HTTP {e.code} {errors(data)}', e.code) from None
        except urllib.error.URLError as e:
            if attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise Refused(f'{method} {path.split("?")[0]}: {e.reason}') from None
    if raw:
        return data
    j = json.loads(data)
    if not j.get('success'):
        raise Refused(f'{method} {path.split("?")[0]}: {errors(data)}')
    return j


def errors(data):
    try:
        return '; '.join(f'{e.get("code")} {e.get("message")}' for e in json.loads(data).get('errors') or []) or 'no details'
    except ValueError:
        return 'no details'


def multipart(parts):
    """parts: (name, filename or None, content type, bytes) → (body, content type)."""
    b = 'glass' + secrets.token_hex(12)
    out = bytearray()
    for name, filename, ctype, data in parts:
        disp = f'form-data; name="{name}"' + (f'; filename="{filename}"' if filename else '')
        out += f'--{b}\r\nContent-Disposition: {disp}\r\nContent-Type: {ctype}\r\n\r\n'.encode()
        out += data + b'\r\n'
    out += f'--{b}--\r\n'.encode()
    return bytes(out), f'multipart/form-data; boundary={b}'


# ---- is the token any good? ---------------------------------------------------------------------------------------

def probe(url):
    """(HTTP status, JSON or None, WWW-Authenticate) for one GET with the token: never raises."""
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + need('CLOUDFLARE_API_TOKEN'), 'User-Agent': UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body, status, wa = r.read(), r.status, r.headers.get('WWW-Authenticate')
    except urllib.error.HTTPError as e:
        body, status, wa = e.read(), e.code, e.headers.get('WWW-Authenticate')
    except urllib.error.URLError as e:
        return 0, None, str(e.reason)
    try:
        return status, json.loads(body), wa
    except ValueError:
        return status, None, wa


def check():
    """What Cloudflare makes of the token and the account (nothing secret printed: lengths, short hashes, verdicts)."""
    tok, acc = need('CLOUDFLARE_API_TOKEN'), account()
    print(f'token: {len(tok)} characters, plain letters/digits/-/_: {"yes" if re.fullmatch(r"[A-Za-z0-9_-]+", tok) else "NO"}, '
          f'sha256 starts {hashlib.sha256(tok.encode()).hexdigest()[:8]}')
    print(f'account ID: {"32 hex characters" if re.fullmatch(r"[0-9a-f]{32}", acc) else "NOT 32 hex characters"}, '
          f'sha256 starts {hashlib.sha256(acc.encode()).hexdigest()[:8]}')
    good, located = True, False
    for name, path in (('verify (a user token)', '/user/tokens/verify'), ('verify (an account token)', f'/accounts/{acc}/tokens/verify'),
                       ('the accounts it reaches', '/accounts?per_page=50'), ('the workers.dev name', f'/accounts/{acc}/workers/subdomain')):
        status, j, wa = probe(API + path)
        ok = isinstance(j, dict) and bool(j.get('success'))
        said = ''
        if isinstance(j, dict):
            said = '; '.join(f'{e.get("code")} {e.get("message")}' for e in (j.get('errors') or []) + (j.get('messages') or []))
        more = ''
        if ok and 'verify' in name:
            more = f', status {(j.get("result") or {}).get("status")}'
        if ok and 'accounts' in name:
            ids = [a.get('id') for a in j.get('result') or []]
            more = f', {len(ids)} account(s), the chosen one among them: {"yes" if acc in ids else "NO"}'
        if ok and 'workers.dev' in name:
            more = ', there' if (j.get('result') or {}).get('subdomain') else ', none yet'
        print(f'{name}: HTTP {status}{more}' + (f' ({said})' if said else '') + (f' [WWW-Authenticate: {wa}]' if wa else ''))
        located |= '9109' in said
        if 'workers.dev' in name:
            good = ok or status == 404
    if not good and located:
        sys.exit('The token only works from certain IP addresses (its Client IP Address Filtering), and GitHub\'s machines '
                 'aren\'t among them: in Cloudflare, edit the token and remove that filter (its value stays the same)')
    if not good:
        sys.exit('The Cloudflare token or account doesn\'t work from here: run tools/set-secrets.sh with a current token')


# ---- the address and the namespace -------------------------------------------------------------------------------

def subdomain(create):
    """The account's workers.dev name. An account that never had one gets a plain one (glass-<random>), which can be
    renamed later in Cloudflare's dashboard (Workers & Pages ▸ Subdomain)."""
    try:
        sub = (cf('GET', '/workers/subdomain').get('result') or {}).get('subdomain')
    except Refused as e:
        # anything but "there is none" stops here, so an existing name is never replaced
        none = e.status == 404 or (e.status == 400 and ('10007' in str(e) or re.search(r'\bno(t)?\b.*subdomain', str(e), re.I)))
        if not none:
            raise
        sub = None
    if sub or not create:
        return sub
    sub = 'glass-' + secrets.token_hex(4)
    cf('PUT', '/workers/subdomain', json.dumps({'subdomain': sub}).encode(), 'application/json')
    print('This Cloudflare account had no workers.dev name: it now has one (see Workers & Pages ▸ Subdomain).', file=sys.stderr)
    return sub


def namespace(create=True):
    page = 1
    while True:
        j = cf('GET', f'/storage/kv/namespaces?per_page=100&page={page}')
        for ns in j.get('result') or []:
            if ns.get('title') == NAMESPACE:
                return ns['id']
        info = j.get('result_info') or {}
        if page >= (info.get('total_pages') or 1):
            break
        page += 1
    if not create:
        return None
    return cf('POST', '/storage/kv/namespaces', json.dumps({'title': NAMESPACE}).encode(), 'application/json')['result']['id']


def url(create=False):
    sub = subdomain(create)
    if not sub:
        sys.exit('This Cloudflare account has no workers.dev name yet: run publish.py prepare first')
    return f'https://{WORKER}.{sub}.workers.dev'


def say_output(key, value, secret=False):
    """A step's output in GitHub Actions (masked in the log when asked), else a plain line."""
    out = os.environ.get('GITHUB_OUTPUT')
    if out:
        if secret:
            print(f'::add-mask::{value}')
        with open(out, 'a') as f:
            f.write(f'{key}={value}\n')
    else:
        print(f'{key}={value}')


def prepare():
    u = url(create=True)
    namespace()
    say_output('url', u, secret=True)


# ---- deploying -----------------------------------------------------------------------------------------------------

def deploy(dist):
    manifest_path = os.path.join(dist, 'manifest.json')
    with open(manifest_path, 'rb') as f:
        manifest_bytes = f.read()
    m = json.loads(manifest_bytes)
    ns = namespace()

    # never backwards: Android won't install an older Glass over a newer one anyway
    old = cf('GET', f'/storage/kv/namespaces/{ns}/values/manifest', raw=True)
    if old:
        try:
            before = {a['pkg']: a['code'] for a in json.loads(old).get('apps', [])}
        except ValueError:
            before = {}
        for a in m['apps']:
            if a['pkg'] in before and a['code'] < before[a['pkg']]:
                sys.exit(f'{a["name"]} {a["version"]} (version {a["code"]}) is older than the published one ({before[a["pkg"]]}): bump versionCode')
        if all(a['pkg'] in before and a['code'] == before[a['pkg']] for a in m['apps']):
            print('Note: nothing in this release is newer than the published one; TVs will say "Up to date".')

    # 1. the Worker, with the namespace and the TVs' key
    with open(os.path.join(HERE, 'worker', 'index.js'), 'rb') as f:
        code = f.read()
    meta = {
        'main_module': 'index.js',
        'compatibility_date': '2024-11-01',
        'bindings': [
            {'type': 'kv_namespace', 'name': 'GLASS_RELEASES', 'namespace_id': ns},
            {'type': 'secret_text', 'name': 'UPDATE_TOKEN', 'text': need('GLASS_UPDATE_TOKEN')},
        ],
    }
    body, ctype = multipart([('metadata', None, 'application/json', json.dumps(meta).encode()),
                             ('index.js', 'index.js', 'application/javascript+module', code)])
    cf('PUT', f'/workers/scripts/{WORKER}', body, ctype)
    cf('POST', f'/workers/scripts/{WORKER}/subdomain', json.dumps({'enabled': True, 'previews_enabled': False}).encode(), 'application/json')
    print('Worker deployed')

    # 2. the APKs, then the manifest (a TV that sees the new list finds its files already there)
    for a in m['apps']:
        path = os.path.join(dist, a['file'])
        with open(path, 'rb') as f:
            data = f.read()
        if len(data) != a['size'] or hashlib.sha256(data).hexdigest() != a['sha256']:
            sys.exit(f'{a["file"]} isn\'t what manifest.json says')
        key = urllib.parse.quote('apk/' + a['file'], safe='')
        body, ctype = multipart([('value', a['file'], 'application/vnd.android.package-archive', data),
                                 ('metadata', None, 'application/json', json.dumps({'size': a['size'], 'sha256': a['sha256']}).encode())])
        cf('PUT', f'/storage/kv/namespaces/{ns}/values/{key}', body, ctype)
        print(f'uploaded {a["file"]} ({a["size"] // 1024} KB)')
    body, ctype = multipart([('value', 'manifest.json', 'application/json', manifest_bytes)])
    cf('PUT', f'/storage/kv/namespaces/{ns}/values/manifest', body, ctype)
    print(f'manifest: Glass {m["release"]} ({", ".join(a["name"] + " " + str(a["code"]) for a in m["apps"])})')

    # 3. APKs no list names any more
    keep = {'apk/' + a['file'] for a in m['apps']}
    j = cf('GET', f'/storage/kv/namespaces/{ns}/keys?prefix=apk/&limit=1000')
    for k in j.get('result') or []:
        if k['name'] not in keep:
            cf('DELETE', f'/storage/kv/namespaces/{ns}/values/{urllib.parse.quote(k["name"], safe="")}')
            print(f'removed the old {k["name"][4:]}')


# ---- checking, as a TV ---------------------------------------------------------------------------------------------

def tv_get(u, key=True):
    req = urllib.request.Request(u, headers={'User-Agent': 'Glass-TV'})
    if key:
        req.add_header('Authorization', 'Bearer ' + need('GLASS_UPDATE_TOKEN'))
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except urllib.error.URLError:
        return 0, b''


def verify(dist):
    base = url()
    with open(os.path.join(dist, 'manifest.json'), 'rb') as f:
        want = json.load(f)
    # a new Worker (or a new workers.dev name) takes a moment to answer everywhere; KV a moment to reach the edge
    deadline = time.time() + 6 * 60
    while True:
        status, body = tv_get(base + '/v1/manifest')
        try:
            if status == 200 and json.loads(body) == want:
                break
        except ValueError:
            pass
        if time.time() > deadline:
            sys.exit(f'The service doesn\'t give the new list yet (HTTP {status}). Run publish.py verify again in a few minutes.')
        time.sleep(10)
    print('the list: as published')
    status, _ = tv_get(base + '/v1/manifest', key=False)
    if status != 401:
        sys.exit(f'Without the key the service answered {status} (401 expected)')
    print('without the key: refused')
    for a in want['apps']:
        status, data = tv_get(base + '/v1/apk/' + a['file'])
        if status != 200 or len(data) != a['size'] or hashlib.sha256(data).hexdigest() != a['sha256']:
            sys.exit(f'{a["file"]}: the service gives something else (HTTP {status}, {len(data)} bytes)')
        print(f'{a["file"]}: the same bytes')


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ('check', 'prepare', 'deploy', 'verify') or (sys.argv[1] in ('deploy', 'verify') and len(sys.argv) < 3):
        sys.exit(__doc__)
    try:
        if sys.argv[1] == 'check':
            check()
        elif sys.argv[1] == 'prepare':
            prepare()
        elif sys.argv[1] == 'deploy':
            deploy(sys.argv[2])
        else:
            verify(sys.argv[2])
    except Refused as e:
        sys.exit(f'Cloudflare refused: {e}')


if __name__ == '__main__':
    main()
