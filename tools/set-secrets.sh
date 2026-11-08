#!/bin/bash
# Once, on your computer, in a terminal:   bash tools/set-secrets.sh
# (Only for publishing updates through your own GitHub and Cloudflare, so TVs update from Settings. Without it,
# ./glass update builds and installs updates from your computer.)
# Gives GitHub what its release workflow needs, as encrypted GitHub secrets of this repository:
#   CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID   asks for a Cloudflare API token (made from the "Edit Cloudflare
#                                                  Workers" template), finds its account
#   GLASS_UPDATE_TOKEN                             makes the update service's key (TVs need it to download)
#   ANDROID_KEYSTORE_B64                           this computer's Glass signing key, so GitHub's builds can update
#                                                  the Glass already on the TV (Android only takes an update signed
#                                                  alike); its certificate goes in updates/signing-cert.sha256
#   GLASS_ENV_FILE                                 only if you say yes: the assistant's keys, so releases update it too
# The update service's address and key are also kept in GLASS_HOME/.updates.env, for builds and installs on this
# computer. Nothing secret is shown or written in the repository; .secrets-status says how it went, without any of it.
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$ROOT"
STATUS="$ROOT/.secrets-status"
: > "$STATUS"
note() { echo "• $*"; echo "$*" | mask >> "$STATUS"; }
fail() { echo "✗ $*" >&2; echo "FAILED: $*" | mask >> "$STATUS"; exit 1; }
json() { python3 -c "import json,sys
try: j=json.load(sys.stdin)
except Exception: sys.exit(0)
$1"; }

command -v gh >/dev/null 2>&1 || fail "GitHub's gh isn't installed"
gh auth status >/dev/null 2>&1 || fail "gh isn't signed in (gh auth login)"
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" || fail "This folder isn't a GitHub repository yet"
note "GitHub repository: found"

# ---- Cloudflare ----------------------------------------------------------------------------------------------------
echo
echo "Cloudflare: dash.cloudflare.com › My Profile › API Tokens › Create Token › \"Edit Cloudflare Workers\" › Use template,"
echo "pick your account under Account Resources, leave Client IP Address Filtering empty (GitHub's machines change"
echo "address every run, so a filter shuts them out), then Continue › Create Token, and copy it."
printf 'Paste the token here, then press Return (nothing shows while you paste): '
read -rs CF; echo
CF="$(printf '%s' "$CF" | tr -d '[:space:]')"
[ -n "$CF" ] || fail "no token was pasted"
# the token goes to curl on its standard input, not on its command line (where other programs could see it)
cfapi() { local p="$1"; shift; printf 'header = "Authorization: Bearer %s"\n' "$CF" | curl -sS -m 30 -K - -H "Content-Type: application/json" "$@" "https://api.cloudflare.com/client/v4$p"; }

ACCS="$(cfapi '/accounts?per_page=50' | json 'print("\n".join(a["id"] + "\t" + a.get("name", "") for a in (j.get("result") or [])) if j.get("success") else "")' || true)"
N="$(printf '%s\n' "$ACCS" | grep -c . || true)"
if [ "$N" = 1 ]; then
  ACC="$(printf '%s' "$ACCS" | cut -f1)"
elif [ "$N" -gt 1 ]; then
  echo "This token reaches more than one Cloudflare account:"
  i=0; while IFS=$'\t' read -r _ name; do i=$((i + 1)); echo "  $i. $name"; done <<<"$ACCS"
  read -rp "Which one is Glass's? (number) " k
  ACC="$(printf '%s\n' "$ACCS" | sed -n "${k}p" | cut -f1)"
else
  fail "Cloudflare didn't accept that token, or it reaches no account (make it from the \"Edit Cloudflare Workers\" template)"
fi
[[ "$ACC" =~ ^[0-9a-f]{32}$ ]] || fail "no Cloudflare account was chosen"
note "Cloudflare token: works; account: chosen"

SUB="$(cfapi "/accounts/$ACC/workers/subdomain" | json 'print(((j.get("result") or {}).get("subdomain") or "") if j.get("success") else "")' || true)"
if [ -z "$SUB" ]; then
  SUB="glass-$(openssl rand -hex 4)"
  OK="$(cfapi "/accounts/$ACC/workers/subdomain" -X PUT --data "{\"subdomain\":\"$SUB\"}" | json 'print("yes" if j.get("success") else "")' || true)"
  [ "$OK" = yes ] || fail "The account has no workers.dev name and this token couldn't make one (Cloudflare › Workers & Pages makes one)"
  echo "  This account had no workers.dev name yet: it's now $SUB.workers.dev (rename it in Workers & Pages if you like)."
  note "workers.dev name: made"
else
  note "workers.dev name: there"
fi
URL="https://glass-updates.$SUB.workers.dev"

printf '%s' "$CF" | gh secret set CLOUDFLARE_API_TOKEN --repo "$REPO" >/dev/null
printf '%s' "$ACC" | gh secret set CLOUDFLARE_ACCOUNT_ID --repo "$REPO" >/dev/null
# short fingerprints (not the values): the release workflow's "token works" step prints the same, to compare
note "GitHub secrets: Cloudflare token and account, set (token ${#CF} characters, sha256 starts $(printf '%s' "$CF" | shasum -a 256 | cut -c1-8); account sha256 starts $(printf '%s' "$ACC" | shasum -a 256 | cut -c1-8))"
CF=""

# ---- the update service's key --------------------------------------------------------------------------------------
TOK="$(sed -n 's/^GLASS_UPDATE_TOKEN=//p' "$GLASS_UPDATES_FILE" 2>/dev/null | head -1 || true)"
[[ "$TOK" =~ ^[0-9a-f]{64}$ ]] || TOK="$(openssl rand -hex 32)"
(
  umask 077
  { echo "# Glass's update service (tools/set-secrets.sh): yours only, never in the repository or a chat."
    echo "GLASS_UPDATE_URL=$URL"
    echo "GLASS_UPDATE_TOKEN=$TOK"; } > "$GLASS_UPDATES_FILE"
)
chmod 600 "$GLASS_UPDATES_FILE"
printf '%s' "$TOK" | gh secret set GLASS_UPDATE_TOKEN --repo "$REPO" >/dev/null
TOK=""
note "Update key: set (GitHub, and $(short "$GLASS_UPDATES_FILE"))"

# ---- the signing key -----------------------------------------------------------------------------------------------
KS="$GLASS_KEYSTORE"
[ -f "$KS" ] || fail "This computer's Glass signing key isn't there ($(short "$KS")): ./glass setup makes it"
base64 < "$KS" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_B64 --repo "$REPO" >/dev/null
note "Signing key: set (the one the Glass on the TV is signed with)"
# its certificate (public: it's in every APK) is what the release workflow checks each build against
tools_ready
CERT="$("$JAVA_HOME/bin/keytool" -list -v -keystore "$KS" -storepass android 2>/dev/null | grep -m1 'SHA256:' | sed 's/.*SHA256: //' | tr -d ':' | tr 'A-F' 'a-f')"
[[ "$CERT" =~ ^[0-9a-f]{64}$ ]] || fail "Couldn't read the signing key's certificate"
if [ "$(tr -d ' \n' < updates/signing-cert.sha256 2>/dev/null)" != "$CERT" ]; then
  echo "$CERT" > updates/signing-cert.sha256
  note "updates/signing-cert.sha256: written for this key (commit it, so releases are checked against it)"
else
  note "updates/signing-cert.sha256: already this key's"
fi

# ---- the assistant's keys: only when you say so --------------------------------------------------------------------
if [ -f "$GLASS_ENV" ]; then
  echo
  echo "Releases can update the assistant too. For that GitHub needs the assistant's keys ($(short "$GLASS_ENV")) as a"
  echo "secret: GitHub keeps it encrypted and only the release workflow reads it. Without it, releases update Glass"
  echo "and leave the assistant as it is on the TV."
  read -rp "Give GitHub the assistant's keys? [y/N] " yn
  case "$yn" in
    [yY]*) gh secret set GLASS_ENV_FILE --repo "$REPO" < "$GLASS_ENV" >/dev/null; note "Assistant keys: set (you said yes)" ;;
    *) gh secret delete GLASS_ENV_FILE --repo "$REPO" >/dev/null 2>&1 || true; note "Assistant keys: not given (you said no)" ;;
  esac
else
  note "Assistant keys: none on this computer"
fi
note "All set"
echo
echo "Done. Back in the chat, say it's done."
