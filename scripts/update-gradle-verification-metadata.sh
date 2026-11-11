#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Regenerate gradle/verification-metadata.xml (Gradle dependency verification,
# issue #925) after a dependency or plugin change.
#
# Every Gradle run verifies each resolved artifact against the SHA-256 recorded
# in that file and fails on a mismatch or on an artifact it does not know. A
# dependency bump therefore has to update the file in the same change: run this
# from the repo root and commit the result. Renovate branches get it done by
# .github/workflows/gradle-verification-metadata.yml.
#
# Review the diff: new entries are trust-on-first-use (the checksum of what the
# repository served today); a CHANGED checksum for an existing version is the
# signal dependency verification exists for — do not accept it without a reason.
#
# Usage: scripts/update-gradle-verification-metadata.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Resolve everything any CI job resolves: all configurations of every project
# (init script), Spotless's formatter, the license report and the CycloneDX
# SBOM. Configuration cache and parallel execution are off because the license
# report and the init task do not support them (same as the CI license-scan job).
./gradlew --no-configuration-cache --no-parallel \
  --init-script gradle/verification/resolve-all.init.gradle.kts \
  --write-verification-metadata sha256 \
  resolveAllForVerification spotlessCheck checkLicense \
  :qnop-app:cyclonedxBom

echo "gradle/verification-metadata.xml regenerated — review and commit the diff."
