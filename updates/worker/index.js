// Glass's update service: the newest release's list (the manifest) and its APKs, for TVs that hold the key.
//
// Everything is in one KV namespace, bound as GLASS_RELEASES: "manifest" (JSON) and "apk/<file>" (the APKs).
// GitHub's release workflow fills it (updates/publish.py); Glass reads it when someone asks it to check.
//
//   GET /v1/manifest     the manifest
//   GET /v1/apk/<file>   one APK the manifest lists
//
// Every request needs "Authorization: Bearer <key>", the key being the Worker's UPDATE_TOKEN secret (built into
// Glass by the same workflow). Anything else gets 401 or 404, and nothing is logged here.

const APK = /^\/v1\/apk\/([A-Za-z0-9._-]{1,80}\.apk)$/;

export default {
  async fetch(request, env) {
    if (request.method !== 'GET' && request.method !== 'HEAD') return say(405, 'GET only');
    const path = new URL(request.url).pathname;
    if (!path.startsWith('/v1/')) return say(404, 'not here');
    if (!(await allowed(request, env))) return say(401, 'a key is needed', { 'WWW-Authenticate': 'Bearer' });

    if (path === '/v1/manifest') {
      const m = await env.GLASS_RELEASES.get('manifest');
      if (m === null) return say(404, 'nothing released yet');
      return new Response(request.method === 'HEAD' ? null : m, {
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
      });
    }
    const apk = path.match(APK);
    if (apk) {
      const bytes = await env.GLASS_RELEASES.get('apk/' + apk[1], { type: 'arrayBuffer' });
      if (bytes === null) return say(404, 'no such file');
      return new Response(request.method === 'HEAD' ? null : bytes, {
        headers: {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': String(bytes.byteLength),
          'Cache-Control': 'no-store',
        },
      });
    }
    return say(404, 'not here');
  },
};

// The key, compared in constant time (as SHA-256 digests, so both sides are the same length).
async function allowed(request, env) {
  const want = env.UPDATE_TOKEN || '';
  const auth = request.headers.get('Authorization') || '';
  const got = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!want || !got) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(got)),
    crypto.subtle.digest('SHA-256', enc.encode(want)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

function say(status, text, headers = {}) {
  return new Response(text + '\n', { status, headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...headers } });
}
