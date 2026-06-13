# qnop — Architecture Overview

qnop ("Qualified Notes on Papers") is an enterprise **document review** system. Reviewers (individual users or teams) mark up lines/regions of a document, comment, and run a coordinated review workflow: comments are accepted or rejected, changes produce new document versions, and a review finalizes when no open annotations remain.

This document is the map. The binding decisions and their rationale live as [ADRs](adr/README.md); this overview links to them rather than repeating them.

## Distribution model (open-core)

qnop ships in two editions:

- **Community** — AGPL-3.0, base features. This repository.
- **Enterprise** — commercial add-ons (e.g. AI reviewers, summarization, duplicate-annotation detection), in a **separate private repository** that builds against this repo's published `qnop-spi` artifact.
- A **SaaS** offering is a later possibility.

The seam between them is the SPI ([ADR-0002](adr/0002-open-core-via-polyrepo-and-published-spi.md), [ADR-0003](adr/0003-agpl-boundary-is-the-spi.md)).

## Backend module map

Ports-and-adapters (hexagonal), with module dependencies enforced by ArchUnit ([ADR-0004](adr/0004-hexagonal-architecture-enforced-by-archunit.md)):

```
            ┌─────────────┐
            │  qnop-spi   │  pure interfaces + DTOs  ← the AGPL/commercial boundary
            └─────────────┘
                   ▲
            ┌─────────────┐
            │ qnop-domain │  entities, value objects, workflow state machine (framework-free)
            └─────────────┘
                   ▲
          ┌──────────────────┐
          │ qnop-application │  use cases + ports (interfaces the adapters implement)
          └──────────────────┘
              ▲   ▲   ▲   ▲   ▲
   ┌──────────┘   │   │   │   └──────────┐
┌────────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│persistence │ │storage │ │ document │ │ security │ │   web    │   adapters
└────────────┘ └────────┘ └──────────┘ └──────────┘ └──────────┘
              ▲   ▲   ▲   ▲   ▲
            ┌─────────────────┐
            │    qnop-app     │  composition root / Spring Boot bootstrap (Community build)
            └─────────────────┘
```

| Module | Responsibility |
|--------|----------------|
| `qnop-spi` | Extension-point interfaces + DTOs. Published artifact; consumed by the enterprise repo. No logic. |
| `qnop-domain` | Entities, value objects, the review workflow state machine. No Spring/JPA/web. |
| `qnop-application` | Use cases; defines ports (repository & provider interfaces); orchestrates SPI calls. |
| `qnop-persistence` | JPA adapters + Flyway migrations (PostgreSQL). |
| `qnop-storage` | `StorageProvider` default adapter over the S3 API. |
| `qnop-document` | Text extraction (PDFBox/POI), conversion, annotation anchoring. |
| `qnop-security` | Authn/authz, user & team model. |
| `qnop-web` | REST controllers, DTO mapping, OpenAPI. |
| `qnop-app` | The only wiring point; builds the Community server; hosts the ArchUnit test. |

## Frontend

A Vite + React + TypeScript + MaterialUI SPA (`frontend/`) talking to the Spring REST API. Enterprise UI will be separated mirroring the backend ([ADR-0014](adr/0014-frontend-enterprise-separation.md)).

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
- **Phase 1** — domain core + workflow state machine; SPI interfaces + Community defaults; bootable Spring Boot server (Postgres/Flyway/S3, `/api/edition`); ingest pipeline; basic anchoring; auth.
- **Phase 2** — fuzzy re-anchoring, real-time presence (Redis), caching.
- **Phase 3+** — enterprise AI modules, license entitlements, search/pgvector as needed.

## Stack summary

| Layer | Choice |
|-------|--------|
| Backend | Java 21, Spring Boot 4.x (Phase 1), Gradle (Kotlin DSL) multi-module |
| Persistence | PostgreSQL + Flyway; S3/MinIO object storage |
| Frontend | Vite, React 19, TypeScript, MaterialUI |
| Build/quality | Convention plugins, Spotless (google-java-format), ArchUnit, JUnit 5 |
| CI | GitHub Actions: backend build + ArchUnit + Spotless, frontend lint/build, SPDX scan |
