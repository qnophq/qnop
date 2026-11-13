#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Turn a freshly seeded database into the demo configuration (issue #710).
# Run AFTER seed-demo.sh and BEFORE build-golden-state.sh.
# Everything secret (admin password, analytics endpoint) lives only in
# /opt/qnop-demo/.env on the instance — this script carries no secrets.
#
# seed-demo.sh already did the credential hardening in the seed transaction
# (issue #924): the seeded admin password is rotated to $DEMO_ADMIN_PASSWORD,
# every other seeded admin is disabled and the seeded OIDC providers are gone,
# so no admin credential from the public repository ever works on the demo.
#
# What this script does: applies the demo settings — mail fully off, 5 MB /
# 1 MB upload limits, self-registration + password reset off, review e-mails
# off, sign-in banner with the public demo accounts, optional Umami usage
# tracking.
set -euo pipefail

cd /opt/qnop-demo
set -a
. ./.env
set +a

BASE="${QNOP_DEMO_BASE_URL:-https://$QNOP_DEMO_DOMAIN}"
# The shared password of the PUBLIC demo accounts (member, nora, …) — shown in
# the sign-in banner. Admin accounts never accept it on the demo.
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

# Sign in with the rotated admin password only. The public seed password is
# never tried: seed-demo.sh replaced it before the seed became visible.
if ! TOKEN=$(login admin "$DEMO_ADMIN_PASSWORD"); then
  echo "Admin sign-in with DEMO_ADMIN_PASSWORD failed — load the data with seed-demo.sh first." >&2
  exit 1
fi

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
  "tracking.respect_dnt": "false",
  "tracking.consent_required": "false",
  "tracking.track_privileged_roles": "true",
  "tracking.forward_client_ip": "anonymized"
}}
EOF
)" > /dev/null
else
  echo "DEMO_TRACKING_HOST / DEMO_TRACKING_SITE_ID not set — skipping usage tracking."
fi

echo "Demo configuration applied. Next: stage the example reviews, then run build-golden-state.sh"
