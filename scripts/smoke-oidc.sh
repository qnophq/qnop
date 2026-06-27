#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# OIDC login smoke test against Keycloak (issue #225). Drives the full
# authorization-code flow over HTTP and asserts that a real deployment can log a
# user in via an OIDC provider end-to-end:
#
#   admin token -> register+enable the Keycloak provider (discover/create/enable)
#   -> initiate /oauth2/authorization/{id} -> Keycloak login form -> callback
#   -> assert success redirect to the SPA + qnop_refresh cookie + JIT provisioning.
#
# Designed to run in a container that SHARES the app's network namespace
# (network_mode: service:app), so `localhost:8080` is the app (a redirect host
# the realm already whitelists) and `keycloak:8080` resolves exactly as the app
# sees it — keeping the OIDC issuer consistent on both channels. The database must
# already be seeded (scripts/smoke-test.sh runs first in CI).
set -euo pipefail

APP="${SMOKE_APP_URL:-http://localhost:8080}"
KC="${SMOKE_KC_URL:-http://keycloak:8080}"
ISSUER="${KC}/realms/qnop"
FRONTEND_BASE="http://localhost:5173"

ADMIN_USER="admin"
ADMIN_PASS="Test-Pass-1234!"
KC_USER="alice"
KC_PASS="password"
KC_EMAIL="alice@qnop.test"
CLIENT_ID="qnop-local"
CLIENT_SECRET="local-dev-secret-do-not-use-in-prod"

JAR="$(mktemp)"
TMP="$(mktemp -d)"
trap 'rm -rf "$JAR" "$TMP"' EXIT

log() { echo "[oidc-smoke] $*"; }
fail() {
  echo "[oidc-smoke] FAIL: $*" >&2
  exit 1
}
# Location header value of a saved response (CR-stripped).
location() { grep -i '^location:' "$1" | tr -d '\r' | sed -E 's/^[Ll]ocation:[[:space:]]*//'; }

wait_for() {
  local name="$1" url="$2" ok=
  for _ in $(seq 1 60); do
    if curl -sf "$url" >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 3
  done
  [ -n "$ok" ] || fail "$name did not become ready in time ($url)"
}

# 0) Wait for the app and the Keycloak realm.
wait_for "app" "${APP}/actuator/health"
log "app healthy"
wait_for "keycloak realm" "${ISSUER}/.well-known/openid-configuration"
log "keycloak realm ready"

# 1) Admin token (the DB was seeded by smoke-test.sh).
token="$(
  curl -sf -X POST "${APP}/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}" | jq -r '.accessToken'
)"
[ -n "$token" ] && [ "$token" != "null" ] || fail "admin login failed (was the DB seeded by smoke-test.sh first?)"
AUTH=(-H "Authorization: Bearer ${token}")
log "admin token acquired"

# 2) Discover the Keycloak issuer (proves app->Keycloak reachability + SSRF allowance).
disc="$(
  curl -sf "${AUTH[@]}" -X POST "${APP}/api/v1/admin/oidc-providers/discover" \
    -H 'Content-Type: application/json' -d "{\"issuerUri\":\"${ISSUER}\"}" || true
)"
[ -n "$(echo "$disc" | jq -r '.authorizationUri // empty')" ] || fail "issuer discovery failed: ${disc}"
log "issuer discovered"

# 3) Create + enable the provider (created disabled).
pid="$(
  curl -sf "${AUTH[@]}" -X POST "${APP}/api/v1/admin/oidc-providers" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"keycloak\",\"providerType\":\"OIDC\",\"clientId\":\"${CLIENT_ID}\",\"clientSecret\":\"${CLIENT_SECRET}\",\"issuerUri\":\"${ISSUER}\",\"scope\":\"openid email profile\"}" |
    jq -r '.id'
)"
[ -n "$pid" ] && [ "$pid" != "null" ] || fail "provider creation failed"
curl -sf "${AUTH[@]}" -X PATCH "${APP}/api/v1/admin/oidc-providers/${pid}" \
  -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null || fail "enabling the provider failed"
log "provider ${pid} created + enabled"

# 4) Initiate the authorization request -> 302 to Keycloak's authorization endpoint.
curl -s -c "$JAR" -b "$JAR" -o /dev/null -D "${TMP}/a" "${APP}/oauth2/authorization/${pid}"
auth_url="$(location "${TMP}/a")"
case "$auth_url" in
  *"/protocol/openid-connect/auth"*) : ;;
  *) fail "no Keycloak authorization redirect (got: ${auth_url:-<none>})" ;;
esac
log "authorization request initiated"

# 5) Fetch the Keycloak login page and extract the form POST action.
curl -s -c "$JAR" -b "$JAR" -o "${TMP}/login.html" "$auth_url"
form_action="$(
  grep -oiE '<form[^>]+id="kc-form-login"[^>]+action="[^"]+"' "${TMP}/login.html" |
    sed -E 's/.*action="([^"]+)".*/\1/' | sed 's/&amp;/\&/g'
)"
if [ -z "$form_action" ]; then
  form_action="$(
    grep -oiE '<form[^>]+action="[^"]+"' "${TMP}/login.html" | head -1 |
      sed -E 's/.*action="([^"]+)".*/\1/' | sed 's/&amp;/\&/g'
  )"
fi
[ -n "$form_action" ] || fail "could not find the Keycloak login form action"
log "login form parsed"

# 6) Submit credentials -> 302 back to the app's callback with the authorization code.
curl -s -c "$JAR" -b "$JAR" -o /dev/null -D "${TMP}/c" \
  --data-urlencode "username=${KC_USER}" \
  --data-urlencode "password=${KC_PASS}" \
  --data-urlencode "credentialId=" \
  "$form_action"
callback="$(location "${TMP}/c")"
case "$callback" in
  *"/login/oauth2/code/${pid}"*) : ;;
  *) fail "Keycloak did not redirect to the app callback (got: ${callback:-<none>}) — bad credentials?" ;;
esac
log "authenticated at Keycloak"

# 7) Hit the callback -> the app exchanges the code, provisions the user, sets the
#    refresh cookie, and redirects to the SPA on success (or /login?error=… on failure).
curl -s -c "$JAR" -b "$JAR" -o /dev/null -D "${TMP}/d" "$callback"
final="$(location "${TMP}/d")"
case "$final" in
  *error*) fail "OIDC login redirected to an error page: ${final}" ;;
  "${FRONTEND_BASE}"*) : ;;
  *) fail "unexpected post-login redirect: ${final:-<none>}" ;;
esac
if ! grep -i '^set-cookie:' "${TMP}/d" | grep -q 'qnop_refresh='; then
  fail "no qnop_refresh cookie was issued on OIDC login"
fi
log "OIDC login completed (refresh cookie set, redirect=${final})"

# 8) The Keycloak user was JIT-provisioned as a local account.
total="$(curl -sf "${AUTH[@]}" "${APP}/api/v1/admin/users?q=${KC_EMAIL}" | jq -r '.total')"
[ "$total" -ge 1 ] 2>/dev/null || fail "${KC_EMAIL} was not provisioned (total=${total})"
log "JIT-provisioned ${KC_EMAIL}"

log "ALL OIDC SMOKE CHECKS PASSED"
