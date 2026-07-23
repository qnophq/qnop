#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Smoke test (issues #207, #308): waits for the deployed qnop server to become
# healthy, seeds the shared fixtures from testdata/, and exercises the shipped
# surface over real HTTP against the built container image — base auth/admin, the
# full document-ingest pipeline through real MinIO + the scheduled extraction job,
# the review lifecycle (due date, overview, workflow), and auth session rotation.
# Fails fast (non-zero exit) on the first deviation.
#
# Run from the repo root, with the stack already up:
#   docker compose -f docker-compose.smoke.yml up -d --build
#   bash scripts/smoke-test.sh
#
# Needs bash, curl, jq, cmp and GNU date on PATH.
set -euo pipefail

BASE_URL="${SMOKE_BASE_URL:-http://localhost:8080}"
COMPOSE_FILE="${SMOKE_COMPOSE_FILE:-docker-compose.smoke.yml}"
CLEAN_FILE="testdata/db/clean.sql"
SEED_FILE="testdata/db/seed.sql"
SAMPLE_PDF="testdata/documents/sample.pdf"
SEED_USER="admin"
SEED_PASS="Test-Pass-1234!"
HEALTH_RETRIES=60
HEALTH_DELAY=5
EXTRACT_RETRIES=30
EXTRACT_DELAY=5

WORKDIR="$(mktemp -d)"
COOKIES="${WORKDIR}/cookies.txt"
trap 'rm -rf "${WORKDIR}"' EXIT

log() { echo "[smoke] $*"; }
fail() {
  echo "[smoke] FAIL: $*" >&2
  exit 1
}

# Authenticated helpers — the access token is set once login succeeds.
TOKEN=""
aget() { curl -sf "${BASE_URL}$1" -H "Authorization: Bearer ${TOKEN}"; }
acode() {
  # method path -> prints the HTTP status of an authenticated request (no -f)
  curl -s -o /dev/null -w '%{http_code}' -X "$1" "${BASE_URL}$2" \
    -H "Authorization: Bearer ${TOKEN}"
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

# 4) An unauthenticated call to a protected endpoint is rejected (security gate).
code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/users/me")"
[ "$code" = "401" ] || fail "unauthenticated GET /api/v1/users/me -> HTTP $code (expected 401)"
log "unauthenticated GET /api/v1/users/me -> 401"

# 5) Login as the seeded admin (covers seeded data + bcrypt + JWT issuance). The
#    rotating refresh token comes back as the HttpOnly qnop_refresh cookie, kept
#    in a jar so the session-rotation checks below can replay it.
login_json="$(
  curl -sf -c "$COOKIES" -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"${SEED_USER}\",\"password\":\"${SEED_PASS}\"}"
)"
TOKEN="$(echo "$login_json" | jq -r '.accessToken')"
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "login did not return an access token"
grep -q 'qnop_refresh' "$COOKIES" || fail "login did not set the qnop_refresh cookie"
log "POST /api/v1/auth/login -> access token + refresh cookie"

# 6) Authenticated admin query of the seeded users.
total="$(aget /api/v1/admin/users | jq -r '.total')"
[ "$total" -ge 8 ] 2>/dev/null || fail "GET /api/v1/admin/users total=${total} (expected >= 8 seeded users)"
log "GET /api/v1/admin/users -> total=${total}"

# 7) Current-user principal resolves from the JWT.
role="$(aget /api/v1/users/me | jq -r '.role')"
[ "$role" = "ADMIN" ] || fail "GET /api/v1/users/me role=${role} (expected ADMIN)"
log "GET /api/v1/users/me -> role=${role}"

# 8) Admin + user read surfaces (settings service, teams, per-user settings).
code="$(acode GET /api/v1/admin/settings)"
[ "$code" = "200" ] || fail "GET /api/v1/admin/settings -> HTTP $code (expected 200)"
code="$(acode GET /api/v1/admin/teams)"
[ "$code" = "200" ] || fail "GET /api/v1/admin/teams -> HTTP $code (expected 200)"
code="$(acode GET /api/v1/users/me/settings)"
[ "$code" = "200" ] || fail "GET /api/v1/users/me/settings -> HTTP $code (expected 200)"
log "GET admin/settings, admin/teams, users/me/settings -> 200"

# 9) Document ingest through real object storage (#243, #308). Upload the sample
#    PDF with a future due date (#295); the server stages it in MinIO and enqueues
#    the extraction job in one transaction.
due_at="$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%SZ')"
upload_json="$(
  curl -sf -X POST "${BASE_URL}/api/v1/documents" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "title=Smoke Review" \
    -F "dueAt=${due_at}" \
    -F "file=@${SAMPLE_PDF};type=application/pdf"
)"
doc_id="$(echo "$upload_json" | jq -r '.documentId')"
[ -n "$doc_id" ] && [ "$doc_id" != "null" ] || fail "upload did not return a documentId"
[ "$(echo "$upload_json" | jq -r '.versionNumber')" = "1" ] || fail "first upload was not version 1"
log "POST /api/v1/documents -> document ${doc_id} v1 (stored in MinIO)"

# 10) The scheduled extraction job (real MinIO read + PDFBox) flips v1 to READY.
extraction=""
for i in $(seq 1 "$EXTRACT_RETRIES"); do
  extraction="$(aget "/api/v1/documents/${doc_id}/versions" | jq -r '.versions[0].extractionStatus')"
  [ "$extraction" = "READY" ] && break
  [ "$extraction" = "FAILED" ] && fail "extraction FAILED for the uploaded document"
  if [ "$i" -eq "$EXTRACT_RETRIES" ]; then
    fail "extraction did not reach READY within $((EXTRACT_RETRIES * EXTRACT_DELAY))s (last=${extraction})"
  fi
  sleep "$EXTRACT_DELAY"
done
log "extraction READY after the scheduled job ran"

# 11) The rendered representation parses from stored JSON and carries the text.
rendered="$(aget "/api/v1/documents/${doc_id}/versions/1/rendered")"
surfaces="$(echo "$rendered" | jq -r '.surfaces | length')"
[ "$surfaces" = "1" ] || fail "rendered document has ${surfaces} surfaces (expected 1)"
text="$(echo "$rendered" | jq -r '[.surfaces[0].textSpans[].text] | join("")')"
echo "$text" | grep -q "QNOP" || fail "rendered text '${text}' does not contain the fixture text"
log "GET .../rendered -> 1 surface, text '${text}'"

# 12) The original binary streams back byte-identical from object storage.
curl -sf "${BASE_URL}/api/v1/documents/${doc_id}/versions/1/original" \
  -H "Authorization: Bearer ${TOKEN}" -o "${WORKDIR}/original.pdf"
cmp -s "${WORKDIR}/original.pdf" "$SAMPLE_PDF" ||
  fail "streamed original is not byte-identical to the uploaded PDF"
log "GET .../original -> byte-identical to the upload"

# 13) Review due date set-at-create is visible, and the owner can clear it (#295).
due_seen="$(aget "/api/v1/documents/${doc_id}" | jq -r '.dueAt')"
[ "$due_seen" != "null" ] && [ -n "$due_seen" ] || fail "created document has no dueAt (expected the set value)"
curl -sf -X PATCH "${BASE_URL}/api/v1/documents/${doc_id}" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"dueAt":null}' >/dev/null
due_cleared="$(aget "/api/v1/documents/${doc_id}" | jq -r '.dueAt')"
[ "$due_cleared" = "null" ] || fail "PATCH did not clear dueAt (still ${due_cleared})"
log "due date set-at-create then cleared via PATCH"

# 14) The document appears in the caller's overview.
seen="$(aget "/api/v1/documents" | jq -r --arg id "$doc_id" '[.items[].id] | index($id)')"
[ "$seen" != "null" ] || fail "uploaded document is missing from GET /api/v1/documents"
log "GET /api/v1/documents -> lists the uploaded document"

# 15) Workflow: the offered Draft -> In review transition is authoritative.
transitions="$(aget "/api/v1/documents/${doc_id}/workflow" | jq -r '.allowedTransitions | join(",")')"
echo "$transitions" | grep -q "IN_REVIEW" || fail "IN_REVIEW not offered from Draft (got: ${transitions})"
curl -sf -X POST "${BASE_URL}/api/v1/documents/${doc_id}/workflow" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"targetState":"IN_REVIEW"}' >/dev/null
state="$(aget "/api/v1/documents/${doc_id}" | jq -r '.workflowState')"
[ "$state" = "IN_REVIEW" ] || fail "workflow state is ${state} after transition (expected IN_REVIEW)"
log "POST .../workflow -> Draft to In review"

# 16) Annotations list resolves against the READY version (empty is fine).
code="$(acode GET "/api/v1/documents/${doc_id}/annotations?version=1")"
[ "$code" = "200" ] || fail "GET .../annotations?version=1 -> HTTP $code (expected 200)"
log "GET .../annotations?version=1 -> 200"

# 16b) Global search federation (#540, ADR-0047) against the real deploy: the
#      uploaded review is found by title, a fresh annotation by its opening
#      text and a reply by its body — each in its own group with deep-link
#      facts — while short and anonymous queries stay dark.
found="$(aget "/api/v1/search?q=smoke" | jq -r --arg id "$doc_id" '[.reviews.items[].id] | index($id)')"
[ "$found" != "null" ] || fail "GET /api/v1/search?q=smoke does not list the uploaded review"

ann_json="$(
  curl -sf -X POST "${BASE_URL}/api/v1/documents/${doc_id}/annotations" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d '{"versionNumber":1,"comment":"Zephyr clause needs rework."}'
)"
ann_id="$(echo "$ann_json" | jq -r '.id')"
[ -n "$ann_id" ] && [ "$ann_id" != "null" ] || fail "annotation create did not return an id"
curl -sf -X POST "${BASE_URL}/api/v1/annotations/${ann_id}/comments" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"body":"Zephyr wording agreed."}' >/dev/null

sr="$(aget "/api/v1/search?q=zephyr")"
[ "$(echo "$sr" | jq -r '.annotations.total')" = "1" ] ||
  fail "search?q=zephyr annotations.total=$(echo "$sr" | jq -r '.annotations.total') (expected 1)"
echo "$sr" | jq -r '.annotations.items[0].excerpt' | grep -q "Zephyr clause" ||
  fail "annotation hit lacks its opening-text excerpt"
[ "$(echo "$sr" | jq -r --arg id "$ann_id" '.annotations.items[0].annotationId == $id')" = "true" ] ||
  fail "annotation hit does not carry the created annotation's deep-link id"
[ "$(echo "$sr" | jq -r '.comments.total')" = "1" ] ||
  fail "search?q=zephyr comments.total=$(echo "$sr" | jq -r '.comments.total') (expected 1)"

users_total="$(aget "/api/v1/search?q=mia" | jq -r '.users.total')"
[ "$users_total" -ge 1 ] 2>/dev/null || fail "search?q=mia users.total=${users_total} (expected >= 1 seeded)"
alpha_viewable="$(aget "/api/v1/search/teams?q=alpha" | jq -r '.items[0].viewable')"
[ "$alpha_viewable" = "true" ] || fail "admin sees team Alpha as viewable=${alpha_viewable} (expected true)"

short_total="$(aget "/api/v1/search?q=a" |
  jq -r '.reviews.total + .annotations.total + .comments.total + .users.total + .teams.total')"
[ "$short_total" = "0" ] || fail "short query returned ${short_total} hits (expected 0)"
code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/search?q=smoke")"
[ "$code" = "401" ] || fail "unauthenticated search -> HTTP $code (expected 401)"
log "global search: review by title, annotation/comment by text, principals, short+anon dark"

# 17) Multi-version fixtures (#370): five doc1 uploads all extract READY through
#     the scheduled job, and every version keeps serving its own rendering.
DOC1_DIR="testdata/documents/doc1"
upload_json="$(
  curl -sf -X POST "${BASE_URL}/api/v1/documents" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "title=Fixture Lifecycle" \
    -F "file=@${DOC1_DIR}/test-dummy-v1.pdf;type=application/pdf"
)"
doc1_id="$(echo "$upload_json" | jq -r '.documentId')"
[ -n "$doc1_id" ] && [ "$doc1_id" != "null" ] || fail "doc1 upload did not return a documentId"
for v in 2 3 4 5; do
  curl -sf -X POST "${BASE_URL}/api/v1/documents/${doc1_id}/versions" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "file=@${DOC1_DIR}/test-dummy-v${v}.pdf;type=application/pdf" >/dev/null
done
for i in $(seq 1 "$EXTRACT_RETRIES"); do
  statuses="$(aget "/api/v1/documents/${doc1_id}/versions" | jq -r '[.versions[].extractionStatus] | join(",")')"
  case "$statuses" in *FAILED*) fail "doc1 extraction FAILED (${statuses})" ;; esac
  ready_count="$(echo "$statuses" | tr ',' '\n' | grep -c READY || true)"
  [ "$ready_count" = "5" ] && break
  if [ "$i" -eq "$EXTRACT_RETRIES" ]; then
    fail "doc1: only ${ready_count}/5 versions READY within $((EXTRACT_RETRIES * EXTRACT_DELAY))s (${statuses})"
  fi
  sleep "$EXTRACT_DELAY"
done
latest="$(aget "/api/v1/documents/${doc1_id}" | jq -r '.latestVersionNumber')"
[ "$latest" = "5" ] || fail "doc1 latestVersionNumber=${latest} (expected 5)"
v5_text="$(aget "/api/v1/documents/${doc1_id}/versions/5/rendered" | jq -r '[.surfaces[].textSpans[].text] | join(" ")')"
echo "$v5_text" | grep -q "TEST-DUMMY-V5" || fail "doc1 v5 rendering lacks its TEST-DUMMY-V5 marker"
v1_text="$(aget "/api/v1/documents/${doc1_id}/versions/1/rendered" | jq -r '[.surfaces[].textSpans[].text] | join(" ")')"
echo "$v1_text" | grep -q "TEST-DUMMY-V1" || fail "doc1 v1 rendering lacks its TEST-DUMMY-V1 marker"
log "doc1 fixtures: 5 versions uploaded, all READY, v1/v5 serve their own text"

# 18) Story fixture (#370): the known v1->v2 word edit ("letzten" -> "einsamen")
#     surfaces in the inter-version diff computed over the real extractions.
DOC2_DIR="testdata/documents/doc2"
upload_json="$(
  curl -sf -X POST "${BASE_URL}/api/v1/documents" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "title=Fixture Story" \
    -F "file=@${DOC2_DIR}/scifi-story-v1.pdf;type=application/pdf"
)"
doc2_id="$(echo "$upload_json" | jq -r '.documentId')"
[ -n "$doc2_id" ] && [ "$doc2_id" != "null" ] || fail "doc2 upload did not return a documentId"
curl -sf -X POST "${BASE_URL}/api/v1/documents/${doc2_id}/versions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "file=@${DOC2_DIR}/scifi-story-v2.pdf;type=application/pdf" >/dev/null
for i in $(seq 1 "$EXTRACT_RETRIES"); do
  statuses="$(aget "/api/v1/documents/${doc2_id}/versions" | jq -r '[.versions[].extractionStatus] | join(",")')"
  case "$statuses" in *FAILED*) fail "doc2 extraction FAILED (${statuses})" ;; esac
  ready_count="$(echo "$statuses" | tr ',' '\n' | grep -c READY || true)"
  [ "$ready_count" = "2" ] && break
  if [ "$i" -eq "$EXTRACT_RETRIES" ]; then
    fail "doc2: only ${ready_count}/2 versions READY within $((EXTRACT_RETRIES * EXTRACT_DELAY))s (${statuses})"
  fi
  sleep "$EXTRACT_DELAY"
done
diff_json="$(aget "/api/v1/documents/${doc2_id}/diff?from=1&to=2")"
echo "$diff_json" | jq -e '[.changes[].fromText] | any(contains("letzten"))' >/dev/null ||
  fail "doc2 diff misses the removed word 'letzten'"
echo "$diff_json" | jq -e '[.changes[].toText] | any(contains("einsamen"))' >/dev/null ||
  fail "doc2 diff misses the inserted word 'einsamen'"
log "doc2 fixtures: v1->v2 diff reports the letzten -> einsamen edit"

# 19) Auth session rotation: the refresh cookie mints a fresh access token, and
#     after logout it is revoked (rotation + revocation, ADR-0026). /auth/refresh
#     and /auth/logout are CSRF-protected by a double-submit cookie token (#175):
#     the server sets a (non-HttpOnly) XSRF-TOKEN cookie that must be echoed back
#     as the X-XSRF-TOKEN header. Prime it from a response's Set-Cookie header
#     (into the jar via -c), with a jar fallback, so the cookie and header match.
csrf_hdrs="$(
  curl -sf -D - -o /dev/null -b "$COOKIES" -c "$COOKIES" \
    -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/users/me"
)"
XSRF="$(printf '%s' "$csrf_hdrs" | tr -d '\r' |
  sed -n 's/^[Ss]et-[Cc]ookie: *XSRF-TOKEN=\([^;]*\).*/\1/p' | tail -1)"
[ -n "$XSRF" ] || XSRF="$(awk '$6=="XSRF-TOKEN"{v=$7} END{print v}' "$COOKIES")"
if [ -z "$XSRF" ]; then
  echo "[smoke] cookie jar contents:" >&2
  cat "$COOKIES" >&2
  fail "no XSRF-TOKEN cookie was issued (double-submit CSRF token, #175)"
fi

refresh_json="$(
  curl -sf -b "$COOKIES" -c "$COOKIES" -H "X-XSRF-TOKEN: ${XSRF}" \
    -X POST "${BASE_URL}/api/v1/auth/refresh"
)"
new_token="$(echo "$refresh_json" | jq -r '.accessToken')"
[ -n "$new_token" ] && [ "$new_token" != "null" ] || fail "refresh did not return an access token"
log "POST /api/v1/auth/refresh -> rotated access token"

code="$(
  curl -s -o /dev/null -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" \
    -H "X-XSRF-TOKEN: ${XSRF}" -X POST "${BASE_URL}/api/v1/auth/logout"
)"
[ "$code" = "204" ] || fail "POST /api/v1/auth/logout -> HTTP $code (expected 204)"

# Replay the (revoked) family — CSRF still satisfied, so this reaches the auth
# check and must be rejected as unauthorized rather than blocked earlier.
code="$(
  curl -s -o /dev/null -w '%{http_code}' -b "$COOKIES" \
    -H "X-XSRF-TOKEN: ${XSRF}" -X POST "${BASE_URL}/api/v1/auth/refresh"
)"
[ "$code" = "401" ] || fail "refresh after logout -> HTTP $code (expected 401 — token revoked)"
log "logout revokes the refresh family (replay -> 401)"

log "ALL SMOKE CHECKS PASSED"
