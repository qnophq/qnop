#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Runs the Playwright suite (issue #725) inside the official Playwright image,
# so the screenshots come from one browser build and one set of fonts — the
# same in CI and on every developer machine. A baseline made on a host
# Chromium differs from the runner's by a few percent in antialiasing alone,
# which is more than a layout regression needs to hide behind.
#
# Prerequisites on the host: `pnpm install` and `pnpm generate:api` (the
# container reuses node_modules; the browsers ship with the image), and a
# backend the dev server can proxy to — `QNOP_API_URL`, default localhost:8080.
#
# Usage: scripts/e2e-docker.sh [playwright test args…]
#        scripts/e2e-docker.sh --update-snapshots=all   # regenerate baselines
set -euo pipefail
cd "$(dirname "$0")/.."

version="$(node -p "require('@playwright/test/package.json').version")"
image="mcr.microsoft.com/playwright:v${version}-noble"

exec docker run --rm --init --network host --ipc host \
  --user "$(id -u):$(id -g)" \
  -v "$(cd .. && pwd):/work" -w /work/qnop-ui \
  -e HOME=/tmp -e CI="${CI:-}" \
  -e QNOP_API_URL="${QNOP_API_URL:-http://localhost:8080}" \
  "$image" node node_modules/@playwright/test/cli.js test "$@"
