# qnop — Architecture Overview

qnop ("Qualified Notes on Papers") is an enterprise **document review** system. Reviewers (individual users or teams) mark up lines/regions of a document, comment, and run a coordinated review workflow: comments are accepted or rejected, changes produce new document versions, and a review finalizes when no open annotations remain.

**Supported formats — text first.** The initial scope is reviewing **textual documents: PDF, DOCX, and Markdown (`.md`)**. Other formats (e.g. images, and possibly more later) may follow once the text workflow is solid; those additional formats are a likely **Enterprise** feature rather than Community scope. The ingest → extract/anchor → render pipeline is designed so a new format is an added implementation behind the existing seams (cf. [ADR-0009](adr/0009-multi-layer-annotation-anchoring.md), [ADR-0010](adr/0010-docx-representation-strategy.md)), not a core rewrite.

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
```

| Module | Spring? | Responsibility |
|--------|---------|----------------|
| `qnop-spi` | no | Published plugin contract: extension-point interfaces + DTOs. Consumed by the enterprise repo. |
| `qnop-api` | no | Published REST contract: request/response DTOs + OpenAPI. Consumed externally and by `qnop-core`/`qnop-app`. |
| `qnop-core` | yes | `io.qnop.entity` (JPA entities = the model), `io.qnop.repository` (Spring Data), `io.qnop.service` (business logic, workflow state machine, anchoring, entity⇄DTO mapping, SPI default beans). |
| `qnop-app` | yes | `io.qnop.web` (`@RestController`s implementing `qnop-api`) + `io.qnop.bootstrap` (Spring Boot main/config). The runnable Community module; hosts the ArchUnit test. |

### Published artifacts

Two modules are versioned, externally-consumable stability surfaces:

- **`qnop-spi`** — the *plugin* contract (extension points implemented by the commercial edition).
- **`qnop-api`** — the *REST* contract (DTOs + OpenAPI for third-party integrators and a generated frontend/SDK client), with `/api/v1` URL versioning and a deprecation policy ([ADR-0015](adr/0015-published-rest-api-contract-module.md)).

## Frontend

A Vite + React + TypeScript + MaterialUI SPA (`qnop-ui/`) talking to the Spring REST API. Enterprise UI will be separated mirroring the backend ([ADR-0014](adr/0014-frontend-enterprise-separation.md)).

## Persistence topology

- **PostgreSQL** — relational data: users, teams, documents (metadata), versions, reviews, annotations (anchor payload as `jsonb`), workflow state, audit log.
- **S3-compatible object storage** — binary documents, never in Postgres ([ADR-0005](adr/0005-binary-documents-in-object-storage.md)).
- **Redis / OpenSearch / pgvector** — deliberately deferred ([ADR-0013](adr/0013-redis-and-search-deferred.md)).

## Core concepts (to be implemented in Phase 1)

- **Immutable versions** — a content change creates a new `DocumentVersion`; old versions are never mutated.
- **Annotation anchoring** — multi-layer (text-quote + position + layout), with per-version `AnnotationPlacement` so annotations survive re-versioning ([ADR-0009](adr/0009-multi-layer-annotation-anchoring.md)).
- **Review workflow** — explicit domain state machine; `FINALIZED` only when zero open annotations ([ADR-0011](adr/0011-review-workflow-state-model.md)).
- **DOCX handling** — strategy still open ([ADR-0010](adr/0010-docx-representation-strategy.md)).

## Phase roadmap

- **Phase 0 (this milestone)** — repo conventions, ADRs, governance; compiling Gradle multi-module skeleton (modules are placeholders); local infra (`docker-compose`); frontend shell; CI. **No domain code, SPI, or bootable server yet.**
- **Phase 1** — domain core + workflow state machine; SPI interfaces + Community defaults; bootable Spring Boot server (Postgres/Liquibase/S3, `/api/edition`); ingest pipeline; basic anchoring; auth.
- **Phase 2** — fuzzy re-anchoring, real-time presence (Redis), caching.
- **Phase 3+** — enterprise AI modules, license entitlements, search/pgvector as needed.

## Stack summary

| Layer | Choice |
|-------|--------|
| Backend | Java 21, Spring Boot 4.x (Phase 1), Gradle (Kotlin DSL) multi-module |
| Persistence | PostgreSQL + Liquibase; S3/MinIO object storage |
| Frontend | Vite, React 19, TypeScript, MaterialUI |
| Build/quality | Convention plugins, Spotless (google-java-format), ArchUnit, JUnit 5 |
| CI | GitHub Actions: backend build + ArchUnit + Spotless, frontend lint/build, SPDX scan |
