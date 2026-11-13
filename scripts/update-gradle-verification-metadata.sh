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
# Gradle never prunes entries, so the file only grows. `--fresh` drops every
# <component> (the <configuration> block stays) and records the build's current
# dependency set from scratch — removing stale versions. Use it deliberately:
# the result re-trusts EVERY artifact as served today, so review it like a new
# baseline, never as part of an automated run.
#
# Usage: scripts/update-gradle-verification-metadata.sh [--fresh]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

METADATA=gradle/verification-metadata.xml
if [[ "${1:-}" == "--fresh" ]]; then
  python3 -I - "$METADATA" <<'PY'
import re, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    text = f.read()
text, n = re.subn(r"<components>.*?</components>", "<components>\n   </components>", text, flags=re.S)
if n != 1:
    sys.exit(f"{path}: expected exactly one <components> section")
with open(path, "w", encoding="utf-8") as f:
    f.write(text)
PY
  # A warm Gradle cache serves processed module metadata, so parent POMs and BOMs
  # are not re-read and would be missing from a from-scratch file. Resolve into
  # an empty user home instead; only the (checksum-pinned) wrapper distribution
  # is shared to save the download.
  REAL_GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
  GRADLE_USER_HOME="$(mktemp -d)"
  export GRADLE_USER_HOME
  trap 'rm -rf "$GRADLE_USER_HOME"' EXIT
  if [[ -d "$REAL_GRADLE_HOME/wrapper" ]]; then
    ln -s "$REAL_GRADLE_HOME/wrapper" "$GRADLE_USER_HOME/wrapper"
  fi
  echo "--fresh: dropped all recorded components; regenerating from scratch with an empty cache."
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--fresh]" >&2
  exit 2
fi

# Resolve everything any CI job resolves: all configurations of every project
# (init script), Spotless's formatter, the license report and the CycloneDX
# SBOM. Configuration cache and parallel execution are off because the license
# report and the init task do not support them (same as the CI license-scan job).
# --rerun-tasks: an up-to-date or cached task resolves nothing, and an artifact
# that is not resolved is not recorded.
./gradlew --no-configuration-cache --no-parallel --rerun-tasks \
  --init-script gradle/verification/resolve-all.init.gradle.kts \
  --write-verification-metadata sha256 \
  resolveAllForVerification spotlessCheck checkLicense \
  :qnop-app:cyclonedxBom

echo "$METADATA regenerated — review and commit the diff."
