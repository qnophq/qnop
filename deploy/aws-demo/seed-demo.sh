#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Load the shared demo dataset (testdata/db/clean.sql + seed.sql) into the demo
# database (issue #710) and harden it IN THE SAME TRANSACTION (issue #924):
#
#   - the seeded 'admin' password (public in this repository) is replaced by the
#     bcrypt hash of $DEMO_ADMIN_PASSWORD from /opt/qnop-demo/.env
#   - every other seeded ADMIN account is disabled
#   - the seeded OIDC providers are deleted (local demo accounts only)
#
# The running app never sees a committed state in which an admin account
# accepts the published seed password — not even while the stack is serving
# on 80/443. Run BEFORE stage-demo-settings.sh.
#
#   sudo /opt/qnop-demo/seed-demo.sh [seed-dir]   (default: /opt/qnop-demo/seed)
set -euo pipefail

cd /opt/qnop-demo
set -a
. ./.env
set +a

SEED_DIR="${1:-/opt/qnop-demo/seed}"
DB="${QNOP_DB_NAME:-qnop}"
DB_USER="${QNOP_DB_USERNAME:-qnop}"
: "${DEMO_ADMIN_PASSWORD:?add DEMO_ADMIN_PASSWORD to /opt/qnop-demo/.env}"
for f in clean.sql seed.sql; do
  [ -f "$SEED_DIR/$f" ] || { echo "missing $SEED_DIR/$f (re-run install.sh)" >&2; exit 1; }
  # The single-transaction guarantee breaks if a seed file ends the transaction
  # itself — refuse rather than briefly expose the published admin password.
  if grep -qiE '^[[:space:]]*(begin|commit|end|rollback|start[[:space:]]+transaction|savepoint|release)\b' "$SEED_DIR/$f"; then
    echo "$SEED_DIR/$f contains transaction control — refusing to seed" >&2
    exit 1
  fi
done

# bcrypt the admin password with Postgres' bundled pgcrypto, in the maintenance
# database so the extension never lands in the qnop schema. The password reaches
# psql through the environment (\getenv), not through a SQL literal, and is
# passed to docker by NAME only (exported by `set -a` above), so it never shows
# up in a process's argv.
ADMIN_HASH=$(docker compose exec -T -e DEMO_ADMIN_PASSWORD postgres \
  psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -qtA <<'SQL'
\getenv admin_password DEMO_ADMIN_PASSWORD
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt(:'admin_password', gen_salt('bf', 10));
SQL
)
ADMIN_HASH=$(printf '%s\n' "$ADMIN_HASH" | tail -n 1)
case "$ADMIN_HASH" in
  '$2a$10$'*) ;;
  *) echo "could not compute the admin password hash" >&2; exit 1 ;;
esac

echo "Loading the demo dataset and rotating seeded admin credentials (one transaction)…"
{
  echo 'BEGIN;'
  cat "$SEED_DIR/clean.sql" "$SEED_DIR/seed.sql"
  # Quoted heredoc: no shell expansion; the hash arrives as a psql variable.
  # The SELECT divides by zero — aborting the whole transaction under
  # ON_ERROR_STOP — if the admin row was not rotated.
  cat <<'SQL'
UPDATE qnop_user SET password_hash = :'admin_hash' WHERE username = 'admin';
SELECT 1 / count(*) FROM qnop_user WHERE username = 'admin' AND password_hash = :'admin_hash';
UPDATE qnop_user SET enabled = false WHERE role = 'ADMIN' AND username <> 'admin';
DELETE FROM oidc_provider;
COMMIT;
SQL
} | docker compose exec -T postgres \
  psql -U "$DB_USER" -d "$DB" -v ON_ERROR_STOP=1 -v admin_hash="$ADMIN_HASH" -q >/dev/null

# Settings are read at boot, so restart the app onto the freshly seeded rows.
docker compose restart qnop
echo "Seeded. Next: run stage-demo-settings.sh"
