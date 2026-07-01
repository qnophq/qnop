# qnop — Qualified Notes on Papers

An enterprise **document review** system with a browser-first, maximally usable review experience.

Reviewers — individual users or whole teams — mark up lines and regions of a document, comment, and run a coordinated review workflow: comments are accepted or rejected, changes produce new document versions, and a review is finalized once no open annotations remain.

The focus is on **textual documents first — PDF, Word (DOCX), and Markdown**. Support for further formats (for example images) may follow once the text review workflow is solid, and such formats are a likely **Enterprise** feature rather than part of the Community scope.

> **Status: Phase 1 — identity & administration layer shipped.** The server boots (PostgreSQL + Liquibase + JPA) with the full identity/auth subsystem — local login with JWT access + rotating refresh tokens, revocation, OIDC/OAuth2 providers, self-registration, email verification and password reset, rate limiting — plus users & teams, application settings, mail templates, branding upload (with SVG sanitization) and profile avatars. The document-review domain (ingest, annotation anchoring, the review workflow state machine) is Phase 2. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and the roadmap there.

## Editions

qnop is open-core:

- **Community** (this repo) — **AGPL-3.0**, base features.
- **Enterprise** — commercial add-ons (e.g. AI reviewers, summarization, duplicate-annotation detection), built in a separate private repository against this repo's published `qnop-spi` artifact.
- A **SaaS** offering is a future possibility.

## Tech stack

- **Backend**: Java 21 · Gradle (Kotlin DSL) multi-module · Spring Boot 4.x (Phase 1)
- **Frontend**: Vite · React 19 · TypeScript · MaterialUI (`qnop-ui/`)
- **Data**: PostgreSQL + Liquibase · S3-compatible object storage (MinIO locally)

## Getting started

Requires JDK 21, Node 24 + pnpm, and Docker. **Docker must be running for the backend test suite** — tests boot a real PostgreSQL via Testcontainers (ADR-0020).

```bash
# Local infrastructure (Postgres for bootRun + MinIO)
cp .env.example .env && docker compose up -d

# Backend — compile + format check + architecture & context tests
./gradlew build

# Run the server (uses the docker-compose Postgres)
./gradlew :qnop-app:bootRun

# Frontend
cd qnop-ui && pnpm install && pnpm dev
```

## Repository layout

```
qnop-spi/            # published plugin contract (Spring-free)
qnop-api/            # published REST contract: DTOs + OpenAPI (Spring-free)
qnop-core/           # entity/ repository/ service/  (the Spring backend core)
qnop-app/            # @RestControllers + Spring Boot bootstrap (the runnable)
build-logic/         # Gradle convention plugins
qnop-ui/             # Vite + React + MUI SPA
docs/ARCHITECTURE.md # the map
docs/adr/            # architecture decision records
```

## Contributing

Issue → feature branch → PR; never commit to `main`. All changes are reviewed and CI-gated. See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [ADRs](docs/adr/README.md).

## License

[GNU AGPL-3.0](LICENSE). The network-use copyleft applies: offering qnop over a network requires making the corresponding source available to its users.
