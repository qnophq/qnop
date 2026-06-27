#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Smoke test (issue #207): waits for the deployed qnop server to become healthy,
# seeds the shared fixtures from testdata/, and exercises the base functionality
# over real HTTP. Fails fast (non-zero exit) on the first deviation.
#
# Run from the repo root, with the stack already up:
#   docker compose -f docker-compose.smoke.yml up -d --build
#   bash scripts/smoke-test.sh
set -euo pipefail

BASE_URL="${SMOKE_BASE_URL:-http://localhost:8080}"
COMPOSE_FILE="${SMOKE_COMPOSE_FILE:-docker-compose.smoke.yml}"
CLEAN_FILE="testdata/db/clean.sql"
SEED_FILE="testdata/db/seed.sql"
SEED_USER="admin"
SEED_PASS="Test-Pass-1234!"
HEALTH_RETRIES=60
HEALTH_DELAY=5

log() { echo "[smoke] $*"; }
fail() {
  echo "[smoke] FAIL: $*" >&2
  exit 1
}

# 1) Readiness — covers the container boot, DB connectivity and Liquibase.
log "waiting for ${BASE_URL}/actuator/health ..."
for i in $(seq 1 "$HEALTH_RETRIES"); do
  status="$(curl -sf "${BASE_URL}/actuator/health" 2>/dev/null | jq -r '.status' 2>/dev/null || true)"
  if [ "$status" = "UP" ]; then
    log "health UP after $((i * HEALTH_DELAY - HEALTH_DELAY))s"
    break
  fi
  if [ "$i" -eq "$HEALTH_RETRIES" ]; then
    fail "server did not become healthy within $((HEALTH_RETRIES * HEALTH_DELAY))s"
  fi
  sleep "$HEALTH_DELAY"
done

# 2) Seed the running database with the shared fixtures (schema is migrated by
#    now). Clean first: on first boot the app bootstraps an initial 'admin' user,
#    which would collide with the seeded admin — so wipe the app tables, then load
#    the deterministic dataset (same clean+seed contract as the IT suite, #163).
log "cleaning + seeding ${SEED_FILE}"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U qnop -d qnop <"$CLEAN_FILE" >/dev/null
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U qnop -d qnop <"$SEED_FILE" >/dev/null
log "seeded"

# 3) Public bootstrap config endpoint.
code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/config")"
[ "$code" = "200" ] || fail "GET /api/v1/config -> HTTP $code (expected 200)"
log "GET /api/v1/config -> 200"

# 4) Login as the seeded admin (covers seeded data + bcrypt + JWT issuance).
token="$(
  curl -sf -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"${SEED_USER}\",\"password\":\"${SEED_PASS}\"}" |
    jq -r '.accessToken'
)"
[ -n "$token" ] && [ "$token" != "null" ] || fail "login did not return an access token"
log "POST /api/v1/auth/login -> access token issued"

# 5) Authenticated admin query of the seeded users.
total="$(
  curl -sf "${BASE_URL}/api/v1/admin/users" -H "Authorization: Bearer ${token}" |
    jq -r '.total'
)"
[ "$total" -ge 8 ] 2>/dev/null || fail "GET /api/v1/admin/users total=${total} (expected >= 8 seeded users)"
log "GET /api/v1/admin/users -> total=${total}"

# 6) Current-user principal resolves from the JWT.
role="$(
  curl -sf "${BASE_URL}/api/v1/users/me" -H "Authorization: Bearer ${token}" |
    jq -r '.role'
)"
[ "$role" = "ADMIN" ] || fail "GET /api/v1/users/me role=${role} (expected ADMIN)"
log "GET /api/v1/users/me -> role=${role}"

log "ALL SMOKE CHECKS PASSED"
