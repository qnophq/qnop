# ADR-0002: Open-core distribution via polyrepo + published SPI artifact

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

qnop ships in two distributions: an AGPL-3.0 open-source Community edition with base features, and a commercial edition adding paid add-ons (e.g. AI reviewers, summarization, duplicate detection), with a possible SaaS offering later. We must keep commercial code cleanly separable from the AGPL core, with a boundary we can *prove* — AGPL's network-copyleft (§13) makes the linking boundary a legal question, not just an organizational one.

## Decision

- The **AGPL core lives in this public repository** (`qnophq/qnop`). It publishes a versioned, semantically-versioned `qnop-spi` artifact (and other core artifacts) to a Maven registry.
- **Commercial code lives in a separate private repository** (`qnop-enterprise`) that depends on the published `qnop-spi` artifact as a binary, versioned dependency. It is **not** included in this build (`settings.gradle.kts` lists core modules only).
- The commercial edition is assembled by a build that composes core artifacts + enterprise modules.

## Consequences

- The repo boundary *is* the compile-time boundary: what is in the public repo is AGPL, period — auditable and defensible.
- Loss of atomic cross-cutting commits across the boundary; we accept the DX cost for a clean license line.
- Forces stable, well-designed SPIs (see [ADR-0003](0003-agpl-boundary-is-the-spi.md)).

## Alternatives considered

- **Single monorepo with `core/` + `enterprise/` directories** (GitLab CE/EE style) — rejected as the default: higher accidental-contamination risk and harder to audit which code is AGPL, despite better DX.
- The linking question itself remains legally unsettled; the architecture is shaped so the riskiest add-ons (AI) can later move behind a **process** boundary — see [ADR-0012](0012-edition-vs-entitlements.md). Obtain IP-counsel sign-off before the first commercial release.
