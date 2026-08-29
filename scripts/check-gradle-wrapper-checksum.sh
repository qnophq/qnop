#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Verify that gradle/wrapper/gradle-wrapper.properties pins a
# distributionSha256Sum matching the checksum Gradle publishes for the pinned
# distributionUrl (issue #763).
#
# Why: the pin (issue #194) is what protects a cold `./gradlew` run from a
# substituted distribution — setup-gradle validates the wrapper JAR, not the
# distribution ZIP. Renovate's gradle-wrapper manager bumps distributionUrl on a
# new Gradle release but leaves the checksum untouched, so the pair silently
# drifts apart and every Gradle-invoking job then dies on an opaque
# "Verification of Gradle distribution failed!". This fails first, and prints
# the value to paste in.
#
# This checks that the pin *matches its URL*; it is a freshness check, not a
# second trust anchor — the pin itself is what does the security work, locally,
# on every wrapper run.
#
# Run from the repo root:
#   scripts/check-gradle-wrapper-checksum.sh [--fix] [path/to/gradle-wrapper.properties]
#
# --fix (issue #769) rewrites the distributionSha256Sum line to the published
# value instead of failing, and touches nothing else in the file. The guard and
# the fixer share this one script so their notion of "the pinned URL" and "the
# published checksum" can never drift apart. Exit status is 0 whether the pin
# already matched or was just corrected; the caller reads `git diff` to learn
# which.

set -euo pipefail

FIX=0
if [[ "${1:-}" == "--fix" ]]; then
  FIX=1
  shift
fi
PROPS="${1:-gradle/wrapper/gradle-wrapper.properties}"

fail() {
  echo "::error file=${PROPS}::$1"
  shift
  # Remaining args are plain-text detail lines; annotations collapse newlines.
  for line in "$@"; do echo "  $line"; done
  exit 1
}

[[ -f "$PROPS" ]] || fail "$PROPS not found"

# Java .properties: take the last assignment and unescape the URL's `https\://`.
read_prop() {
  sed -n "s/^$1=//p" "$PROPS" | tail -n 1 | tr -d '\r' | sed 's/\\:/:/g'
}

url="$(read_prop distributionUrl)"
pinned="$(read_prop distributionSha256Sum | tr '[:upper:]' '[:lower:]')"

[[ -n "$url" ]] || fail "distributionUrl is missing"

[[ -n "$pinned" ]] || fail \
  "distributionSha256Sum is missing" \
  "The pin from issue #194 is what protects a cold ./gradlew run against a" \
  "substituted Gradle distribution. Restore it rather than removing this check."

published="$(curl -fsSL --retry 3 --retry-delay 2 "${url}.sha256" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')" \
  || fail "could not fetch ${url}.sha256"

[[ "$published" =~ ^[0-9a-f]{64}$ ]] || fail \
  "${url}.sha256 did not return a SHA-256 digest" \
  "Got: ${published:0:100}"

if [[ "$pinned" != "$published" && "$FIX" == 1 ]]; then
  # Replace the value on the existing line only; every other line stays byte
  # for byte, so the resulting diff is exactly one line.
  sed -i "s/^distributionSha256Sum=.*$/distributionSha256Sum=${published}/" "$PROPS"
  echo "distributionSha256Sum corrected to the checksum published for ${url##*/}"
  echo "  was: $pinned"
  echo "  now: $published"
  exit 0
fi

if [[ "$pinned" != "$published" ]]; then
  fail \
    "distributionSha256Sum does not match the checksum published for distributionUrl" \
    "distributionUrl: $url" \
    "pinned:          $pinned" \
    "published:       $published" \
    "" \
    "Fix by setting in $PROPS:" \
    "  distributionSha256Sum=$published"
fi

echo "distributionSha256Sum matches the checksum published for ${url##*/}"
