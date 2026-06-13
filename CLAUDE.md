# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**qnop** — "Qualified Notes on Papers": an enterprise **document review** system. Reviewers (individual users or teams) mark up lines/regions of PDF/DOCX documents, comment, and run a coordinated review workflow (comments accepted/rejected → new document versions → finalized when no open annotations remain). Open-core: an AGPL Community edition plus commercial add-ons (e.g. AI features) and a possible SaaS.

Read `docs/ARCHITECTURE.md` and `docs/adr/` first — they hold the binding decisions and rationale.

## Working rules (binding — see ADR-0008)

1. **Issue first** — every change starts with a GitHub issue.
2. **Never commit or push to `main`** — it is integration-only/protected.
3. **Feature branch → PR** — branches `feat/…`, `fix/…`, `docs/…`, `chore/…`; the PR references its issue.
4. **Claude attribution everywhere** — commits get a `Co-Authored-By: Claude <noreply@anthropic.com>` trailer; issues and PRs get an attribution line in the body (e.g. `🤖 Mitarbeit: Claude … via Claude Code`).
5. **Record important architecture decisions as ADRs** in `docs/adr/` (template in its README). Add the ADR in the same PR as the change.

Commits follow Conventional Commits and are signed off (`git commit -s`, DCO). See `CONTRIBUTING.md`.

## Current state — Phase 0

A compiling **skeleton**, not a running app. Deliberately deferred to **Phase 1** (do not assume they exist): the domain core + workflow state machine, the SPI interfaces + Community defaults, and the bootable Spring Boot server (`/api/edition`, Postgres/Flyway/S3 wiring). Backend modules currently hold only `package-info` placeholders. The frontend is a shell and does **not** call the backend yet. `docker-compose.yml` is prepared but not yet consumed.

## Stack

- **Backend**: Java 21, Gradle (Kotlin DSL) multi-module, Spring Boot 4.x (introduced in Phase 1). Convention plugin in `build-logic/`; versions in `gradle/libs.versions.toml`.
- **Frontend**: Vite + React 19 + TypeScript + MaterialUI, package manager **pnpm** (`frontend/`).
- **Persistence**: PostgreSQL + Flyway; S3-compatible object storage (MinIO locally) for binary documents.
- **Quality**: Spotless (google-java-format + SPDX header), ArchUnit (hexagonal boundaries), JUnit 5.

## Common commands

Backend (repo root):

```bash
./gradlew build              # compile + Spotless check + ArchUnit tests (the full gate)
./gradlew spotlessApply      # auto-format & insert SPDX headers (run before committing)
./gradlew test               # tests only
./gradlew :qnop-web:test --tests "io.qnop.architecture.ArchitectureRulesTest"   # a single test
./gradlew :qnop-core:build   # build one module
```

Frontend (`cd frontend`):

```bash
pnpm install
pnpm dev            # vite dev server
pnpm build          # tsc -b && vite build
pnpm lint           # eslint
pnpm format         # prettier --write
pnpm format:check
```

Local infra:

```bash
cp .env.example .env && docker compose up -d   # Postgres + MinIO (not yet consumed by the app)
```

## Architecture (essentials)

Layered (Spring), enforced by ArchUnit. **Four modules** (ADR-0004):

```
qnop-web    @RestControllers + Spring Boot bootstrap (runnable) ──▶ qnop-api  (published REST contract)
   │ calls
   ▼
qnop-core   io.qnop.service ▸ io.qnop.repository ▸ io.qnop.entity ──▶ qnop-spi  (published plugin contract)
```

- Layering rule (ArchUnit): `web → service → repository → entity`; controllers never touch repositories directly, and entities never leak to the web layer (the service maps them to `qnop-api` DTOs).
- JPA entities are the model — **no** separate pure-domain model, **no** domain↔entity mapper. Only entity⇄DTO mapping, in the service layer.
- **Guardrail:** keep the complex logic (re-anchoring, workflow state machine) as plain DB-free testable code in `io.qnop.service`, not inside `@Transactional` methods needing a live `EntityManager`.
- **Two published, versioned, Spring-free contracts** (ArchUnit-guarded as pure): `qnop-spi` = plugin boundary; `qnop-api` = public REST contract. See ADR-0003/0015.
- Commercial features are NOT in this repo; they live in a separate private `qnop-enterprise` repo that builds against the published `qnop-spi` artifact and plugs in via Spring `@AutoConfiguration` + `@ConditionalOnMissingBean` (classpath = edition). See ADR-0002/0003.

## License

GNU **AGPL-3.0** (see `LICENSE`). Network-use copyleft applies. Prefer permissive dependencies (Apache-2.0/MIT/BSD/MPL-2.0); never let a copyleft library contaminate the commercial add-on path — copyleft tools (e.g. LibreOffice for DOCX→PDF) are used **out-of-process** only. Every source file carries `SPDX-License-Identifier: AGPL-3.0-only`. See ADR-0007.
