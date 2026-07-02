# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**qnop** — "Qualified Notes on Papers": an enterprise **document review** system. Reviewers (individual users or teams) mark up lines/regions of documents, comment, and run a coordinated review workflow (comments accepted/rejected → new document versions → finalized when no open annotations remain). Open-core: an AGPL Community edition plus commercial add-ons (e.g. AI features) and a possible SaaS.

**Scope of supported formats.** The focus is on reviewing **textual documents first — PDF, DOCX, and Markdown (`.md`)**. Other formats (e.g. images, and later possibly more) may follow once the text workflow is solid; such additional formats are a likely **Enterprise** feature rather than Community scope. Design the ingest/anchoring/rendering seams so a new format is an added implementation, not a core rewrite.

Read `docs/ARCHITECTURE.md` and `docs/adr/` first — they hold the binding decisions and rationale.

## Working rules (binding — see ADR-0008)

1. **Issue first** — every change starts with a GitHub issue.
2. **Never commit or push to `main`** — it is integration-only/protected (ruleset deferred until the repo is public or the org is on Team — see ADR-0018; convention is binding now).
3. **Feature branch → PR** — branch names follow Conventional Branch (rule 9): `feat/…`, `fix/…`, `chore/…`, `hotfix/…`, `release/…`; the PR references its issue.
4. **Claude attribution everywhere** — commits get a `Co-Authored-By: Claude <noreply@anthropic.com>` trailer; issues and PRs get an attribution line in the body: `🤖 Co-Author: Claude (Opus 4.x) via Claude Code`.
5. **Record important architecture decisions as ADRs** in `docs/adr/` (template in its README). Add the ADR in the same PR as the change.
6. **Sign the CLA** (`CLA.md`, ADR-0016) — enforced on PRs by the CLA-Assistant workflow; maintainers/bots are allowlisted.
7. **English everywhere in the project** — issues, PR descriptions, commit messages, documentation, ADRs, and code comments are written in English. This holds even when the working chat language is German: chat may be German, but anything that lands in the repo, an issue, or a PR is English.
8. **Clean copyright on every source file** — the copyright + SPDX header from the root `license-header.txt` (`Copyright (c) 2026-present devtank42 GmbH`, AGPL-3.0-only). Enforced for Java via Spotless; see ADR-0019. Run `./gradlew spotlessApply` before committing.
9. **Conventional Commits & Conventional Branches** — commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (`<type>: <subject>`, types `feat|fix|refactor|docs|test|chore|perf|ci|build`). Branch names follow [Conventional Branch](https://conventionalbranch.org/): `<type>/<kebab-description>`, type ∈ `{feat, fix, hotfix, release, chore}` (`feat`/`fix` are the accepted short forms of `feature`/`bugfix`), lowercase + hyphens only, optional issue number — e.g. `feat/issue-123-new-login`. `main`/`master`/`develop` carry no prefix.

Commits are signed off (`git commit -s`, DCO). See `CONTRIBUTING.md`.

## Current state — Phase 1 (identity & administration shipped)

The Spring Boot server **boots and runs** (PostgreSQL + Liquibase + JPA, ADR-0020) with the full identity & administration layer of epic #7 in place — `io.qnop.entity` (JPA model), `io.qnop.repository` (Spring Data), `io.qnop.service` (business services) and the framework-light `io.qnop.security` (crypto/key-derivation, ADR-0022) are all populated. **Shipped:** local login with JWT access + rotating refresh tokens and revocation (ADR-0026), OIDC/OAuth2 providers, self-registration, email verification and password reset, auth rate limiting (ADR-0027), users & teams, application settings (ADR-0025), mail templates, branding upload with SVG sanitization (ADR-0028; assets stored as Postgres `bytea`, ADR-0024), profile avatars (ADR-0031), ShedLock distributed scheduling (ADR-0029) and optimistic concurrency control (ADR-0030). The REST contract is OpenAPI-first (ADR-0021) and the `qnop-ui` SPA (login, profile, admin surfaces) consumes it; `/config` exposes the running edition.

**Genuinely still pending (do not assume they exist):** the document-review domain core — the review workflow state machine, annotation anchoring/re-anchoring, and the ingest pipeline. The `qnop-spi` extension-point boundary now carries its first published contract, the `StorageProvider` SPI, with the S3/MinIO Community default in `io.qnop.service.storage` (issue #243, ADR-0005/0036) — so MinIO **is now consumed** (object bytes for `document_version.storage_key`), with an upload-then-commit staging registry (`storage_object`) and orphan reaper. `docker-compose.yml` provides local Postgres (+ MinIO) for `bootRun`; the test suite spins up its own Postgres **and MinIO** via **Testcontainers** (Docker required).

## Stack

- **Backend**: Java 21, Gradle (Kotlin DSL) multi-module, Spring Boot 4.x (introduced in Phase 1). Convention plugin in `build-logic/`; dependency versions in `gradle/libs.versions.toml`; **project version in the root `VERSION` file** (single source of truth, read by the convention plugin).
- **Frontend**: Vite + React 19 + TypeScript + MaterialUI, package manager **pnpm** (`qnop-ui/`).
- **Persistence**: PostgreSQL + Liquibase; S3-compatible object storage (MinIO locally) for binary documents.
- **Quality**: Spotless (google-java-format + SPDX header), ArchUnit (layered boundaries), JUnit 5.
- **Dependencies**: self-hosted Renovate via GitHub Actions; org preset in public `qnophq/.github`, extended by `.github/renovate.json` (ADR-0017). Don't hand-bump deps; review Renovate PRs.

## Common commands

Backend (repo root):

```bash
./gradlew build              # compile + Spotless check + ArchUnit tests (the full gate)
./gradlew spotlessApply      # auto-format & insert SPDX headers (run before committing)
./gradlew test               # tests only
./gradlew :qnop-app:test --tests "io.qnop.architecture.ArchitectureRulesTest"   # a single test
./gradlew :qnop-core:build   # build one module
```

Frontend (`cd qnop-ui`):

```bash
pnpm install
pnpm generate:api   # openapi-generator-cli — regenerate the typed API client (needs a JDK)
pnpm dev            # generate:api + vite dev server
pnpm build          # generate:api + tsc -b + vite build
pnpm typecheck      # generate:api + tsc -b --noEmit
pnpm test           # vitest (watch); pnpm test:run for a one-shot run
pnpm lint           # eslint
pnpm format         # prettier --write
pnpm format:check
```

Local infra:

```bash
cp .env.example .env && docker compose up -d   # Postgres + MinIO (object storage, ADR-0005)
```

## Architecture (essentials)

Layered (Spring), enforced by ArchUnit. **Four modules** (ADR-0004):

```
qnop-app    @RestControllers + Spring Boot bootstrap (runnable) ──▶ qnop-api  (published REST contract)
   │ calls
   ▼
qnop-core   io.qnop.service ▸ io.qnop.repository ▸ io.qnop.entity ──▶ qnop-spi  (published plugin contract)
            io.qnop.security  (framework-light crypto / key derivation, ADR-0022)
```

- Layering rule (ArchUnit): `web → service → repository → entity`; controllers never touch repositories directly, and entities never leak to the web layer (the service maps them to `qnop-api` DTOs). `io.qnop.security` (crypto/key-derivation, ADR-0022) is a framework-light layer the service and web layers may use.
- JPA entities are the model — **no** separate pure-domain model, **no** domain↔entity mapper. Only entity⇄DTO mapping, in the service layer.
- **Guardrail:** keep the complex logic (re-anchoring, workflow state machine) as plain DB-free testable code in `io.qnop.service`, not inside `@Transactional` methods needing a live `EntityManager`.
- **Two published, versioned, Spring-free contracts** (ArchUnit-guarded as pure): `qnop-spi` = plugin boundary; `qnop-api` = public REST contract. See ADR-0003/0015.
- Commercial features are NOT in this repo; they live in a separate private `qnop-enterprise` repo that builds against the published `qnop-spi` artifact and plugs in via Spring `@AutoConfiguration` + `@ConditionalOnMissingBean` (classpath = edition). See ADR-0002/0003.

## License

GNU **AGPL-3.0** (see `LICENSE`). Network-use copyleft applies. Prefer permissive dependencies (Apache-2.0/MIT/BSD/MPL-2.0); never let a copyleft library contaminate the commercial add-on path — copyleft tools (e.g. LibreOffice for DOCX→PDF) are used **out-of-process** only. Every source file carries `SPDX-License-Identifier: AGPL-3.0-only`. See ADR-0007.
