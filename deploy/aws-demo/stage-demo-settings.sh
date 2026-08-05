#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Turn a freshly seeded database into the demo configuration (issue #710).
# Run AFTER loading testdata/db/seed.sql and BEFORE build-golden-state.sh.
# Everything secret (admin password, analytics endpoint) lives only in
# /opt/qnop-demo/.env on the instance — this script carries no secrets.
#
# What it does:
#   - deletes the seeded OIDC providers — the demo authenticates exclusively
#     with the published local demo accounts (their seeded secret would be
#     undecryptable on this host anyway)
#   - rotates the seeded admin password to $DEMO_ADMIN_PASSWORD and disables
#     the second seeded admin, so no admin credential from the public
#     repository works on the demo
#   - applies the demo settings: mail fully off, 5 MB / 1 MB upload limits,
#     self-registration + password reset off, review e-mails off, sign-in
#     banner with the public demo accounts, optional Umami usage tracking
set -euo pipefail

cd /opt/qnop-demo
set -a
. ./.env
set +a

BASE="${QNOP_DEMO_BASE_URL:-https://$QNOP_DEMO_DOMAIN}"
SEED_PASSWORD='Test-Pass-1234!'
: "${DEMO_ADMIN_PASSWORD:?add DEMO_ADMIN_PASSWORD to /opt/qnop-demo/.env}"

json_field() { python3 -c "import json,sys;print(json.load(sys.stdin)[sys.argv[1]])" "$1"; }

api() { # api <method> <path> <token> [json-body]
  local method="$1" path="$2" token="$3" body="${4:-}"
  curl -fsS -X "$method" "$BASE/api/v1$path" \
    -H "Authorization: Bearer $token" \
    ${body:+-H 'Content-Type: application/json' -d "$body"}
}

login() { # login <user> <password> → access token on stdout, fails silently
  curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"$1\",\"password\":\"$2\"}" 2>/dev/null | json_field accessToken
}

echo "Removing seeded OIDC providers (demo uses local accounts only)…"
docker compose exec -T postgres psql -U "${QNOP_DB_USERNAME:-qnop}" -d "${QNOP_DB_NAME:-qnop}" \
  -v ON_ERROR_STOP=1 -c "DELETE FROM oidc_provider;"

# Sign in as admin: prefer the rotated secret, fall back to the seed password
# right after a fresh seed load.
if TOKEN=$(login admin "$DEMO_ADMIN_PASSWORD"); then
  echo "Admin password already rotated."
else
  TOKEN=$(login admin "$SEED_PASSWORD")
  echo "Rotating the admin password away from the public seed value…"
  api POST /auth/change-password "$TOKEN" \
    "{\"currentPassword\":\"$SEED_PASSWORD\",\"newPassword\":\"$DEMO_ADMIN_PASSWORD\"}" > /dev/null
  TOKEN=$(login admin "$DEMO_ADMIN_PASSWORD")
fi

# The second seeded admin ('admin2', fixed seed id) also carries the public
# password — a disabled account cannot be taken over.
echo "Disabling the second seeded admin account…"
api PATCH /admin/users/a0000000-0000-0000-0000-000000000008 "$TOKEN" \
  '{"enabled":false}' > /dev/null

echo "Applying demo settings…"
SETTINGS=$(cat <<EOF
{"values":{
  "general.base_url": "$BASE",
  "smtp.enabled": "false",
  "smtp.host": "",
  "smtp.username": "",
  "smtp.password": "",
  "upload.document_max_file_size_mb": "5",
  "upload.attachment_max_file_size_mb": "1",
  "auth.self_registration_enabled": "false",
  "auth.password_reset_enabled": "false",
  "notifications.review_emails_enabled": "false",
  "banner.login_enabled": "true",
  "banner.login_severity": "info",
  "banner.login_text": "Public demo — all data resets every 12 hours. Sign in as member, nora, paul or auditor (read-only); the password for all of them is $SEED_PASSWORD"
}}
EOF
)
api PATCH /admin/settings "$TOKEN" "$SETTINGS" > /dev/null

if [ -n "${DEMO_TRACKING_HOST:-}" ] && [ -n "${DEMO_TRACKING_SITE_ID:-}" ]; then
  echo "Enabling Umami usage tracking…"
  api PATCH /admin/settings "$TOKEN" "$(cat <<EOF
{"values":{
  "tracking.enabled": "true",
  "tracking.provider": "umami",
  "tracking.host": "$DEMO_TRACKING_HOST",
  "tracking.site_id": "$DEMO_TRACKING_SITE_ID",
  "tracking.respect_dnt": "true",
  "tracking.consent_required": "false",
  "tracking.forward_client_ip": "anonymized"
}}
EOF
)" > /dev/null
else
  echo "DEMO_TRACKING_HOST / DEMO_TRACKING_SITE_ID not set — skipping usage tracking."
fi

echo "Demo configuration applied. Next: stage the example reviews, then run build-golden-state.sh"
