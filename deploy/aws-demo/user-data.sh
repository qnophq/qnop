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

dnf -y install docker git
systemctl enable --now docker

# The compose plugin is not packaged in AL2023 — install the static binary.
ARCH=$(uname -m) # aarch64 on Graviton, x86_64 otherwise
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

# Fetch the deployment scripts from the repository and install the stack.
rm -rf /opt/qnop-src
git clone --depth 1 https://github.com/qnophq/qnop.git /opt/qnop-src
/opt/qnop-src/deploy/aws-demo/install.sh
