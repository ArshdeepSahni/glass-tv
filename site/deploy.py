#!/usr/bin/env python3
"""Puts the website on Cloudflare: a Worker named "www" serving the built site as its static assets, on the account's
workers.dev address (a custom domain can be added to it in Cloudflare's dashboard).

  site/deploy.py DIST     uploads DIST (made by site/build.py), deploys it, and says where it is

Needs CLOUDFLARE_API_TOKEN (an "Edit Cloudflare Workers" token) and CLOUDFLARE_ACCOUNT_ID: the same secrets as the
update service. Only files that changed are uploaded. Standard library only.
"""
import base64
import hashlib
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "updates"))
import publish as cf  # noqa: E402  (the update service's Cloudflare helpers: cf(), multipart(), subdomain())

SCRIPT = "www"
# The Worker itself only answers what isn't a file (a page that isn't there); everything else is served as an asset.
WORKER = b"""export default {
  async fetch(request, env) {
    return env.ASSETS.fetch(request);
  }
};
"""
HEADERS = """/*
  Strict-Transport-Security: max-age=31536000
  Cross-Origin-Opener-Policy: same-origin
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  Content-Security-Policy: default-src 'self'; img-src 'self' data: https://avatars.githubusercontent.com; media-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self' 'unsafe-inline'; connect-src 'self' https://api.github.com; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'
  Cache-Control: public, max-age=0, must-revalidate
/assets/*
  Cache-Control: public, max-age=31536000, immutable
/sw.js
  Cache-Control: no-cache
"""
mimetypes.add_type("application/json", ".json")
mimetypes.add_type("image/webp", ".webp")
mimetypes.add_type("video/mp4", ".mp4")


def files(dist):
    out = {}
    for root, _, names in os.walk(dist):
        for n in names:
            p = os.path.join(root, n)
            out["/" + os.path.relpath(p, dist).replace(os.sep, "/")] = p
    return out


def digest(path):
    with open(path, "rb") as f:
        data = f.read()
    ext = os.path.splitext(path)[1].lstrip(".")
    return hashlib.sha256(base64.b64encode(data) + ext.encode()).hexdigest()[:32], len(data)


def upload(jwt, parts):
    body, ctype = cf.multipart(parts)
    req = urllib.request.Request("%s/accounts/%s/workers/assets/upload?base64=true" % (cf.API, cf.account()),
                                 data=body, method="POST")
    req.add_header("Authorization", "Bearer " + jwt)
    req.add_header("Content-Type", ctype)
    req.add_header("User-Agent", cf.UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise cf.Refused("assets upload: HTTP %d %s" % (e.code, cf.errors(e.read())), e.code) from None


def deploy(dist):
    found = files(dist)
    # the headers go with the deploy's settings, never as a file anyone can fetch: these, and the build's own
    # (the pages' Markdown copies pointing search engines at the pages themselves)
    extra = found.pop("/_headers", None)
    headers = HEADERS + (open(extra, encoding="utf-8").read() if extra else "")
    manifest, by_hash = {}, {}
    for route, path in sorted(found.items()):
        h, size = digest(path)
        manifest[route] = {"hash": h, "size": size}
        by_hash[h] = path
    total = sum(v["size"] for v in manifest.values())
    print("site: %d files, %.1f MB" % (len(manifest), total / 1e6))
    session = cf.cf("POST", "/workers/scripts/%s/assets-upload-session" % SCRIPT,
                    json.dumps({"manifest": manifest}).encode(), "application/json")["result"]
    jwt = session["jwt"]
    buckets = session.get("buckets") or []
    sent = 0
    for bucket in buckets:
        parts = []
        for h in bucket:
            path = by_hash[h]
            with open(path, "rb") as f:
                b64 = base64.b64encode(f.read())
            parts.append((h, h, mimetypes.guess_type(path)[0] or "application/octet-stream", b64))
        status, j = upload(jwt, parts)
        sent += len(bucket)
        if status == 201 or (j.get("result") or {}).get("jwt"):
            jwt = (j.get("result") or {}).get("jwt") or jwt
    print("site: %d files uploaded, %d unchanged" % (sent, len(manifest) - sent))
    meta = {
        "main_module": "index.js",
        "compatibility_date": "2025-01-01",
        "assets": {"jwt": jwt, "config": {"html_handling": "auto-trailing-slash", "not_found_handling": "404-page",
                                           "_headers": headers}},
        "bindings": [{"name": "ASSETS", "type": "assets"}],
    }
    body, ctype = cf.multipart([("metadata", None, "application/json", json.dumps(meta).encode()),
                                ("index.js", "index.js", "application/javascript+module", WORKER)])
    cf.cf("PUT", "/workers/scripts/%s" % SCRIPT, body, ctype)
    cf.cf("POST", "/workers/scripts/%s/subdomain" % SCRIPT, json.dumps({"enabled": True}).encode(), "application/json")
    sub = cf.subdomain(False)
    where = "https://%s.%s.workers.dev" % (SCRIPT, sub) if sub else "(the account has no workers.dev name)"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("The site is live: %s\n" % where)
        print("site: deployed (its address is on this run's summary page)")
    else:
        print("site: deployed at " + where)


def address():
    """The site's address, for the build's canonical links, sitemap and share card: GLASS_SITE_URL when it's set (a
    custom domain), else the account's workers.dev name. In Actions it's masked in the log and handed to the next
    steps; it's only ever shown on the run's summary page."""
    url = os.environ.get("GLASS_SITE_URL", "").rstrip("/")
    if not url:
        sub = cf.subdomain(False)
        url = "https://%s.%s.workers.dev" % (SCRIPT, sub) if sub else ""
    if not url:
        return
    if os.environ.get("GITHUB_ACTIONS"):
        print("::add-mask::" + url)
    env = os.environ.get("GITHUB_ENV")
    if env:
        with open(env, "a") as f:
            f.write("GLASS_SITE_URL=%s\n" % url)
    print("site: its address is known")


if __name__ == "__main__":
    if sys.argv[1:] == ["--address"]:
        try:
            address()
        except cf.Refused as e:
            print("Cloudflare refused: %s (the site is built without its address)" % e)
        sys.exit(0)
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit("site/deploy.py DIST   (DIST from site/build.py), or site/deploy.py --address")
    try:
        deploy(sys.argv[1])
    except cf.Refused as e:
        sys.exit("Cloudflare refused: %s" % e)
