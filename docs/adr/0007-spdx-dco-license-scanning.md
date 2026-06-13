# ADR-0007: SPDX headers, DCO, license scanning in CI

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

An open-core business under AGPL-3.0 depends on (a) being able to prove which code is AGPL, (b) retaining the right to relicense the core's own contributions commercially, and (c) never letting a copyleft dependency contaminate a commercial add-on. These guarantees are cheap to establish now and expensive to retrofit.

## Decision

- **SPDX headers**: every source file carries `SPDX-License-Identifier: AGPL-3.0-only`. Enforced for Java via Spotless ([ADR-0006](0006-gradle-kotlin-dsl-jdk21.md)) and checked across `.java`/`.kts` by the `license-headers` CI job.
- **DCO**: contributions are signed off (`Signed-off-by:`); see `CONTRIBUTING.md`. (A CLA may be added if required to preserve commercial relicensing.)
- **License scanning**: dependency-license scanning runs in CI to block incompatible (copyleft) transitive dependencies from entering the commercial build path. In Phase 0 there are effectively no third-party dependencies yet, so this is implemented as the SPDX-header check; a full scanner (e.g. ScanCode/ORT or a Gradle license plugin) is wired in once real dependencies land.
- **Dependency policy**: prefer permissive licenses (Apache-2.0/MIT/BSD/MPL-2.0). Copyleft tools (e.g. LibreOffice) are only used **out-of-process** (no linking).

## Consequences

- The AGPL boundary stays provable and auditable.
- Slight per-file and per-PR overhead (header, sign-off).

## Alternatives considered

- **No header/scan policy** — rejected: risks silent contamination and loss of relicensing rights.
