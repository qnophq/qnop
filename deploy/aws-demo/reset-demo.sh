#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# The 12-hourly demo job (cron, see install.sh). One mechanism covers both
# requirements of issue #710:
#
#   1. auto-deploy — refresh the stack definition from main (the image is
#      pinned by digest there and bumped by reviewed Renovate PRs, #925),
#      then pull its images before every reset
#   2. data reset  — restore the golden state captured by
#      build-golden-state.sh (full pg_dump + object-storage bucket mirror)
#
# Without a golden state yet, it only updates the images (safe no-op reset).
set -euo pipefail

cd /opt/qnop-demo
set -a
. ./.env
set +a

DB="${QNOP_DB_NAME:-qnop}"
DB_USER="${QNOP_DB_USERNAME:-qnop}"
BUCKET="${QNOP_S3_BUCKET:-qnop-documents}"

echo "[$(date -u +%FT%TZ)] reset starting"
# Take the compose file (and with it the pinned image digest) from main. Only
# that file is refreshed; the scripts change when an operator re-runs install.sh.
# A new file must validate against this host's .env first (e.g. a key it does
# not have yet); otherwise the current one stays and the reset carries on.
if [ -d /opt/qnop-src/.git ]; then
  NEW_COMPOSE=/opt/qnop-src/deploy/aws-demo/docker-compose.yml
  if ! git -C /opt/qnop-src pull --ff-only --quiet; then
    echo "WARN: could not update /opt/qnop-src — keeping the current stack definition"
  elif ! docker compose -f "$NEW_COMPOSE" --project-directory /opt/qnop-demo \
      --env-file /opt/qnop-demo/.env config -q; then
    echo "WARN: docker-compose.yml from main does not validate against .env — keeping the current one (re-run install.sh?)"
  else
    install -m 0644 "$NEW_COMPOSE" /opt/qnop-demo/
  fi
fi
docker compose pull --quiet

if [ ! -f golden/db.dump ]; then
  echo "No golden state captured yet — updating images only."
  docker compose up -d
  exit 0
fi

# Stop the app so no connections or uploads race the restore. The restart
# also clears the application-settings cache, which qnop only reads at boot.
docker compose stop qnop

docker compose exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE);" \
  -c "CREATE DATABASE \"$DB\" OWNER \"$DB_USER\";"
docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB" --no-owner < golden/db.dump

# Mirror the golden bucket back — removes uploads visitors left behind.
docker compose --profile tools run --rm mc \
  "mc alias set demo http://minio:9000 \"\$QNOP_S3_ACCESS_KEY\" \"\$QNOP_S3_SECRET_KEY\" \
   && mc mb --ignore-existing demo/\$QNOP_S3_BUCKET \
   && mc mirror --overwrite --remove /golden/bucket demo/\$QNOP_S3_BUCKET"

docker compose up -d
docker image prune -f > /dev/null
echo "[$(date -u +%FT%TZ)] reset done"
