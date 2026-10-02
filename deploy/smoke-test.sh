#!/usr/bin/env bash
# Checks a running deployment end to end through the proxy.
# Usage: deploy/smoke-test.sh https://writeproof.example.com   (add -k via INSECURE=1 for localhost)
set -euo pipefail
base="${1:?usage: $0 <base url>}"
curl_opts=(-sS --max-time 15)
[[ "${INSECURE:-0}" == "1" ]] && curl_opts+=(-k)
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok   $*"; }

health=$(curl "${curl_opts[@]}" "$base/actuator/health") || fail "health request"
grep -q '"UP"' <<<"$health" || fail "health is not UP: $health"
ok "API healthy through the proxy"

headers=$(curl "${curl_opts[@]}" -D - -o /tmp/writeproof-index.html "$base/")
for h in "strict-transport-security: max-age=31536000" "content-security-policy: frame-ancestors 'none'" \
         "x-content-type-options: nosniff" "referrer-policy: no-referrer" "cache-control: no-cache"; do
  grep -qi "^$h" <<<"$headers" || fail "missing header: $h"
done
ok "security and cache headers on the app"
grep -q "require-trusted-types-for 'script'" /tmp/writeproof-index.html || fail "production CSP meta missing"
ok "production CSP with Trusted Types"

grep -qi "^HTTP/[0-9.]* 200" < <(curl "${curl_opts[@]}" -D - -o /dev/null "$base/letters") || fail "SPA route"
ok "SPA routes fall back to the app"

bundle=$(grep -o 'main-[A-Z0-9]\{8\}\.js' /tmp/writeproof-index.html | head -1)
grep -qi "^cache-control: public, max-age=31536000, immutable" < <(curl "${curl_opts[@]}" -D - -o /dev/null "$base/$bundle") \
  || fail "hashed bundle not cached immutably"
ok "hashed bundles cached immutably"

key=$(openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=')
status=$(curl "${curl_opts[@]}" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "{\"publicKey\":\"$key\"}" "$base/api/accounts")
[[ "$status" == "201" ]] || fail "register returned $status"
ok "API reachable: account registered"

checkpoint=$(curl "${curl_opts[@]}" "$base/api/ledger/checkpoint")
grep -q '"signature"' <<<"$checkpoint" || fail "no signed ledger checkpoint: $checkpoint"
ok "signed ledger checkpoint is public"

# Only /actuator/health is proxied; anything else under /actuator falls through to the web app.
body=$(curl "${curl_opts[@]}" "$base/actuator/env")
! grep -q 'propertySources\|activeProfiles' <<<"$body" || fail "/actuator/env is exposed"
ok "other actuator endpoints are not exposed"

if [[ "$base" == https://* ]]; then
  http_base="http://${base#https://}"
  code=$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "$http_base/")
  [[ "$code" == "308" || "$code" == "301" ]] || fail "HTTP did not redirect to HTTPS ($code)"
  ok "HTTP redirects to HTTPS"
fi
echo "Smoke test passed."
