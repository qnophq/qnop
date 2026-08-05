#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Capture the CURRENT demo content as the golden state that reset-demo.sh
# restores every 12 hours. Run this once after staging the demo data
# (seeded users + example reviews), and again whenever the demo content
# should change.
#
#   /opt/qnop-demo/golden/db.dump  — full pg_dump (custom format)
#   /opt/qnop-demo/golden/bucket/  — mirror of the document bucket
set -euo pipefail

cd /opt/qnop-demo
set -a
. ./.env
set +a

DB="${QNOP_DB_NAME:-qnop}"
DB_USER="${QNOP_DB_USERNAME:-qnop}"

mkdir -p golden/bucket

echo "Dumping database '$DB'…"
docker compose exec -T postgres pg_dump -U "$DB_USER" -Fc "$DB" > golden/db.dump.tmp
mv golden/db.dump.tmp golden/db.dump

echo "Mirroring bucket '$QNOP_S3_BUCKET'…"
docker compose --profile tools run --rm mc \
  "mc alias set demo http://minio:9000 \"\$QNOP_S3_ACCESS_KEY\" \"\$QNOP_S3_SECRET_KEY\" \
   && mc mirror --overwrite --remove demo/\$QNOP_S3_BUCKET /golden/bucket"

echo "Golden state captured:"
ls -lh golden/db.dump
du -sh golden/bucket
