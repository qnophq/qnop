# qnop — Qualified Notes on Papers

An enterprise **document review** system with a browser-first, maximally usable review experience.

Reviewers — individual users or whole teams — mark up lines and regions of a document (PDF or Word), comment, and run a coordinated review workflow: comments are accepted or rejected, changes produce new document versions, and a review is finalized once no open annotations remain.

> **Status: Phase 0 — project skeleton.** The structure, build, conventions and local infrastructure are in place; the domain core and the running server arrive in Phase 1. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and the roadmap there.

## Editions

qnop is open-core:

- **Community** (this repo) — **AGPL-3.0**, base features.
- **Enterprise** — commercial add-ons (e.g. AI reviewers, summarization, duplicate-annotation detection), built in a separate private repository against this repo's published `qnop-spi` artifact.
- A **SaaS** offering is a future possibility.

## Tech stack

- **Backend**: Java 21 · Gradle (Kotlin DSL) multi-module · Spring Boot 4.x (Phase 1)
- **Frontend**: Vite · React 19 · TypeScript · MaterialUI (`frontend/`)
- **Data**: PostgreSQL + Flyway · S3-compatible object storage (MinIO locally)

## Getting started

Requires JDK 21, Node 24 + pnpm, and Docker.

```bash
# Backend — compile + format check + architecture tests
./gradlew build

# Frontend
cd frontend && pnpm install && pnpm dev

# Local infrastructure (Postgres + MinIO; not yet consumed in Phase 0)
cp .env.example .env && docker compose up -d
```

## Repository layout

```
qnop-spi/            # published plugin contract (Spring-free)
qnop-api/            # published REST contract: DTOs + OpenAPI (Spring-free)
qnop-core/           # entity/ repository/ service/  (the Spring backend core)
qnop-web/            # @RestControllers + Spring Boot bootstrap (the runnable)
build-logic/         # Gradle convention plugins
frontend/            # Vite + React + MUI SPA
docs/ARCHITECTURE.md # the map
docs/adr/            # architecture decision records
```

## Contributing

Issue → feature branch → PR; never commit to `main`. All changes are reviewed and CI-gated. See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [ADRs](docs/adr/README.md).

## License

[GNU AGPL-3.0](LICENSE). The network-use copyleft applies: offering qnop over a network requires making the corresponding source available to its users.
