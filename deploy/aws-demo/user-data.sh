#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# EC2 user-data (cloud-init) bootstrap for the qnop demo instance.
# Target AMI: Amazon Linux 2023 (arm64 or x86_64).
#
# Installs Docker + the compose plugin, clones the public qnop repository
# and hands over to deploy/aws-demo/install.sh, which sets up
# /opt/qnop-demo (secrets, cron, stack). Logs land in
# /var/log/cloud-init-output.log on the instance.
set -euxo pipefail

# cronie: AL2023 minimal ships without a cron daemon, but the 12-hourly
# reset job (install.sh) relies on /etc/cron.d.
dnf -y install docker git cronie
systemctl enable --now docker crond

# The compose plugin is not packaged in AL2023 — install the static binary,
# pinned to a version and verified against its SHA-256 (issue #925): a
# `releases/latest` download without a checksum would run whatever that URL
# serves as root. Bump all three values together, from the release's
# checksums.txt (https://github.com/docker/compose/releases).
COMPOSE_VERSION=v5.6.1
declare -A COMPOSE_SHA256=(
  [aarch64]=42cfa75f51ad0ccf290bad37899e0038a8c10a0069e85233ecc1d53e3ff1347d
  [x86_64]=47737f4236e6d3ccea7291995203f8f018058783b4c3a9469119811a0b5db8c0
)
ARCH=$(uname -m) # aarch64 on Graviton, x86_64 otherwise
[ -n "${COMPOSE_SHA256[$ARCH]:-}" ] || { echo "unsupported architecture: $ARCH" >&2; exit 1; }
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-${ARCH}" \
  -o /tmp/docker-compose
echo "${COMPOSE_SHA256[$ARCH]}  /tmp/docker-compose" | sha256sum -c -
install -m 0755 /tmp/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
rm -f /tmp/docker-compose
docker compose version

# Fetch the deployment scripts from the repository and install the stack.
rm -rf /opt/qnop-src
git clone --depth 1 https://github.com/qnophq/qnop.git /opt/qnop-src
/opt/qnop-src/deploy/aws-demo/install.sh
