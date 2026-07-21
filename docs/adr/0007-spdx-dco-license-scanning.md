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

## Amendment (2026-07-16, license scanner still pending)

The full dependency-license scanner promised "once real dependencies land" (e.g. ScanCode/ORT or a Gradle license plugin) is still not wired into CI — the check in place remains the SPDX-header job. Tracked in issue #498.

## Amendment (2026-07-22, license scanner wired — issue #498)

The dependency-license scanner is now in CI. The [jk1 `dependency-license-report`](https://github.com/jk1/Gradle-License-Report) Gradle plugin is applied at the root (**not** wired into `check`/`build`); the CI `license-scan` job runs `checkLicense`, which fails on any dependency on a module's **`runtimeClasspath`** (i.e. what actually ships — test/compile-only licenses such as JUnit's EPL never gate the product) whose license falls outside the allowlist in `config/allowed-licenses.json`.

**Policy encoded in the allowlist:**

- **Tier 1 — permissive, allowed for any module:** Apache-2.0, MIT (incl. MIT-0), BSD-2/3-Clause, MPL-2.0, and EDL-1.0 (the Eclipse Distribution License, which is verbatim BSD-3-Clause). This is ADR-0007's allowlist above.
- **Tier 2 — reviewed per-module exceptions:** dependencies whose only licenses are copyleft but which are safe to ship under the AGPL/commercial split, each scoped to a single module and justified inline: the Jakarta spec APIs `jakarta.annotation-api` / `jakarta.transaction-api` (EPL-2.0 / GPL-2.0-**with-Classpath-Exception** — linking-neutral), `ch.qos.logback:logback-core`/`-classic` (dual EPL/LGPL logging library, used unmodified), `org.aspectj:aspectjweaver` (EPL-2.0, used unmodified), and `org.liquibase:liquibase-core` (FSL-1.1-ALv2 — source-available, converts to Apache-2.0; qnop uses it unmodified as a migration tool and is not a competing product, which the FSL permits).

Because jk1 treats a dependency as compliant when **at least one** of its licenses is allowed, scoping every non-permissive license to a single module keeps the gate strict: a new copyleft dependency that is not explicitly listed fails CI and forces a conscious review. The jk1 task is run with `--no-configuration-cache --no-parallel` (it resolves cross-module configurations at execution time, incompatible with the repo's config cache / parallel execution); this affects only the `license-scan` job.

Deeper provenance/SBOM tooling (ScanCode/ORT) remains a future option if a full SBOM or license-text audit is required; the CI gate here covers the contamination concern this ADR set out to address.

## Amendment (2026-07-22, vulnerability scanning: Trivy SBOM replaces OWASP; issue #496)

The **vulnerability** side of dependency scanning (distinct from the license side above) is now a dedicated `security.yml` workflow with two Trivy scans:

- **Trivy filesystem scan** (`fs`, scanners `vuln,secret`) over the whole repo, reporting to the code-scanning (Security) tab via SARIF. Reporting only — it does not fail the build.
- **Trivy CycloneDX-SBOM scan** — the active **backend CVE gate**. Trivy's filesystem scan cannot parse the Gradle Kotlin DSL to enumerate Java dependencies, so the build emits a CycloneDX SBOM of **qnop-app's resolved `runtimeClasspath`** (the `org.cyclonedx.bom` Gradle plugin, test configurations excluded so a test-tool CVE never gates a shipped artifact), which Trivy then scans: **a CRITICAL advisory fails the build; HIGH warns** (reported to the log and the Security tab).

**NVD_API_KEY decision.** The former OWASP Dependency-Check CI job (`backend-audit`, issue #195) required a (free) NVD API key to hit the NVD feed without crippling rate-limiting; without the `NVD_API_KEY` secret it exited early, so the only active dependency gate was the frontend `pnpm audit`. Rather than take on the operational burden of provisioning and rotating an NVD key, we **drop OWASP Dependency-Check entirely** (job, Gradle plugin, and version-catalog entry) in favour of Trivy, whose self-maintained vulnerability DB needs **no API key** and is therefore always active. The frontend `pnpm audit` gate is unchanged.

**Consequences.** The backend now has an always-on CVE gate with no secret to manage; newly-disclosed CVEs against unchanged code surface via a weekly scheduled run. Trivy's DB and OWASP's NVD-derived data can differ at the margins, and CVSS-7.0-but-not-CRITICAL advisories no longer hard-fail (they warn as HIGH) — an intentional trade to keep the gate actionable. Dependency-**license** scanning (the amendment above, issue #498) is a separate CI gate and is unaffected.
