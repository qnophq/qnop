# qnop — Architecture Overview

qnop ("Qualified Notes on Papers") is an enterprise **document review** system. Reviewers (individual users or teams) mark up lines/regions of a document, comment, and run a coordinated review workflow: comments are accepted or rejected, changes produce new document versions, and a review finalizes when no open annotations remain.

**Supported formats — PDF today, text first.** qnop reviews **PDF documents**; DOCX and Markdown are decided-but-pending additions on the same pipeline ([ADR-0010](adr/0010-docx-representation-strategy.md), [ADR-0032](adr/0032-document-representation-and-rendering-pipeline.md)). Other formats (e.g. images) may follow once the text workflow is solid and are a likely **Enterprise** feature rather than Community scope. The ingest → extract/anchor → render pipeline is designed so a new format is an added implementation behind the existing seams ([ADR-0009](adr/0009-multi-layer-annotation-anchoring.md)), not a core rewrite.

This document is the map. The binding decisions and their rationale live as [ADRs](adr/README.md); this overview links to them rather than repeating them.

## Distribution model (open-core)

qnop ships in two editions:

- **Community** — AGPL-3.0, base features. This repository.
- **Enterprise** — commercial add-ons (e.g. AI reviewers, summarization, duplicate-annotation detection), in a **separate private repository** that builds against this repo's published `qnop-spi` artifact.
- A **SaaS** offering is a later possibility.

The seam between them is the SPI ([ADR-0002](adr/0002-open-core-via-polyrepo-and-published-spi.md), [ADR-0003](adr/0003-agpl-boundary-is-the-spi.md)).

## Backend module map

Layered architecture — `web → service → repository → entity` — enforced by ArchUnit ([ADR-0004](adr/0004-layered-architecture-enforced-by-archunit.md)), with two published, Spring-free contracts:

```
qnop-app    @RestControllers + Spring Boot bootstrap (the runnable)   ──▶  qnop-api
  │  calls                                                                  (published REST
  ▼                                                                          contract: DTOs+OpenAPI)
qnop-core
  io.qnop.service     business logic · workflow state machine ·         ──▶  qnop-spi
                      anchoring · entity⇄DTO mapping · SPI defaults           (published plugin
  io.qnop.repository  Spring Data repositories                                contract)
  io.qnop.entity      JPA entities — the model
  io.qnop.security    crypto + validated-config primitives — the Security
                      layer, reachable only from Service and Web (ADR-0022)
```

| Module | Spring? | Responsibility |
|--------|---------|----------------|
| `qnop-spi` | no | Published plugin contract: extension-point interfaces + DTOs. Consumed by the enterprise repo. |
| `qnop-api` | no | Published REST contract: request/response DTOs + OpenAPI. Split into `qnop-api-model` (Spring-free DTOs) + `qnop-api-endpoint` (generated Spring MVC interfaces) per [ADR-0021](adr/0021-openapi-first-contract-tooling.md). Consumed externally and by `qnop-core`/`qnop-app`. |
| `qnop-core` | yes | `io.qnop.entity` (JPA entities = the model), `io.qnop.repository` (Spring Data), `io.qnop.service` (business logic, workflow state machine, anchoring, entity⇄DTO mapping, SPI default beans), `io.qnop.security` (crypto + validated-config primitives — the ArchUnit *Security* layer, reachable only from Service and Web, per [ADR-0022](adr/0022-security-crypto-foundation.md)). |
| `qnop-app` | yes | `io.qnop.web` (`@RestController`s implementing `qnop-api`) + `io.qnop.bootstrap` (Spring Boot main/config). The runnable Community module; hosts the ArchUnit test. |

### Published artifacts

Two modules are versioned, externally-consumable stability surfaces:

- **`qnop-spi`** — the *plugin* contract (extension points implemented by the commercial edition).
- **`qnop-api`** — the *REST* contract (DTOs + OpenAPI for third-party integrators and a generated frontend/SDK client), with `/api/v1` URL versioning and a deprecation policy ([ADR-0015](adr/0015-published-rest-api-contract-module.md)). OpenAPI-first: a single `openapi.yaml` generates the Spring-free DTOs (`qnop-api-model`) and the Spring MVC interfaces (`qnop-api-endpoint`) the controllers implement ([ADR-0021](adr/0021-openapi-first-contract-tooling.md)).

## Frontend

A Vite + React + TypeScript + MaterialUI SPA (`qnop-ui/`) talking to the Spring REST API. Enterprise UI extensions load at runtime as ESM modules against a published `qnop-ui-spi` ([ADR-0014](adr/0014-frontend-enterprise-separation.md), finalized by [ADR-0039](adr/0039-enterprise-packaging-and-runtime-extensions.md)).

## Persistence topology

- **PostgreSQL** — relational data: users, teams, documents (metadata), versions, reviews, annotations (anchor payload as `jsonb`), workflow state, audit log.
- **S3-compatible object storage** — binary documents, never in Postgres ([ADR-0005](adr/0005-binary-documents-in-object-storage.md)).
- **Durable job queue on Postgres** — extraction, re-anchoring and mail jobs run through a `job` table with SKIP LOCKED workers ([ADR-0033](adr/0033-durable-async-job-execution-on-postgres.md)); scheduled work coordinates via ShedLock ([ADR-0029](adr/0029-distributed-scheduler-locks.md)).
- **Object-storage staging** — uploads stage through a `storage_object` registry with an orphan reaper ([ADR-0036](adr/0036-object-storage-lifecycle-staging-and-reaper.md)).
- **Redis / OpenSearch / pgvector** — deliberately deferred ([ADR-0013](adr/0013-redis-and-search-deferred.md)).

## Core concepts

- **Immutable versions** — a content change creates a new `DocumentVersion`; old versions are never mutated. Uploads flow through the canonical ingest/extraction pipeline ([ADR-0032](adr/0032-document-representation-and-rendering-pipeline.md)) on the durable job queue ([ADR-0033](adr/0033-durable-async-job-execution-on-postgres.md)).
- **Annotation anchoring** — multi-layer (text-quote + position + layout), with per-version `AnnotationPlacement` and fuzzy re-anchoring across versions ([ADR-0009](adr/0009-multi-layer-annotation-anchoring.md)); inter-version diffs power the compare view ([ADR-0034](adr/0034-inter-version-diff.md)).
- **Review workflow** — explicit domain state machine; `FINALIZED` only when zero open annotations ([ADR-0011](adr/0011-review-workflow-state-model.md)); per-review anonymity and thread participation policies ([ADR-0038](adr/0038-per-review-privacy.md)).
- **DOCX handling** — converts to PDF on ingest and reuses the canonical pipeline ([ADR-0010](adr/0010-docx-representation-strategy.md), not yet implemented).

## Current state & deferred

**Shipped:** the identity & administration layer (local JWT auth with rotating refresh tokens, OIDC providers, self-registration/verification/reset, rate limiting, users & teams, settings, mail templates, branding, avatars) and the full **PDF review vertical** — ingest + extraction jobs, anchoring and re-anchoring, the review workflow state machine, inter-version diff, review notifications, dashboard, and the complete review UI. `qnop-spi` publishes two contracts (`StorageProvider`, `DocumentExtractor`) with Community defaults.

**Deferred:** DOCX/Markdown ingest ([ADR-0010](adr/0010-docx-representation-strategy.md)), Redis/search ([ADR-0013](adr/0013-redis-and-search-deferred.md)), enterprise runtime extensions and their packaging ([ADR-0039](adr/0039-enterprise-packaging-and-runtime-extensions.md)).

## Stack summary

| Layer | Choice |
|-------|--------|
| Backend | Java 21, Spring Boot 4.x, Gradle (Kotlin DSL) multi-module |
| Persistence | PostgreSQL + Liquibase; S3/MinIO object storage |
| Frontend | Vite, React 19, TypeScript, MaterialUI |
| Build/quality | Convention plugins, Spotless (google-java-format), ArchUnit, JUnit 5 |
| CI | GitHub Actions (`.github/workflows/ci.yml`): backend build (ArchUnit + Spotless), frontend lint/format/test/build + audit, docker-compose smoke deploy (incl. OIDC login), SPDX scan. Security scanning (`security.yml`): Trivy filesystem scan (vuln + secret → Security tab) and a Trivy CycloneDX-SBOM scan of qnop-app's runtime classpath (CRITICAL fails, HIGH warns) — the backend CVE gate (issue #496, ADR-0007) |
