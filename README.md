# qnop — Qualified Notes on Papers

An enterprise **document review** system with a browser-first, maximally usable review experience.

Reviewers — individual users or whole teams — mark up passages of a document, discuss them, and run a coordinated review workflow: annotations are raised and resolved, changes produce new document versions, and a review is finalized once no open annotations remain. qnop reviews **PDF documents today**; DOCX and Markdown follow the same ingest pipeline later ([ADR-0010](docs/adr/0010-docx-representation-strategy.md), [ADR-0032](docs/adr/0032-document-representation-and-rendering-pipeline.md)).

qnop is open-core: this repository is the **AGPL-3.0 Community edition**; commercial add-ons live in a separate private repository built against the published `qnop-spi` contract.

## Getting started

Requires JDK 21, Node 24 + pnpm, and Docker. **Docker must be running for the backend test suite** — tests boot real PostgreSQL and MinIO containers via Testcontainers (ADR-0020).

```bash
# Local infrastructure (Postgres for bootRun + MinIO)
cp .env.example .env && docker compose up -d

# Backend — compile + format check + architecture & context tests
./gradlew build

# Run the server (uses the docker-compose Postgres).
# Unlike `docker compose`, Gradle's bootRun does NOT auto-load .env, and the server
# fails fast unless QNOP_AUTH_JWT_SECRET / QNOP_AUTH_ENCRYPTION_KEY / QNOP_AUTH_ENCRYPTION_SALT
# hold real values (>= 32 chars). Replace the CHANGE_ME placeholders in .env, then export it:
set -a; source .env; set +a
./gradlew :qnop-app:bootRun

# Frontend
cd qnop-ui && pnpm install && pnpm dev
```

Architecture, module map, and stack: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · decisions: [`docs/adr/`](docs/adr/README.md)

## Contributing

Issue → feature branch → PR; never commit to `main`. All changes are reviewed and CI-gated. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[GNU AGPL-3.0](LICENSE). The network-use copyleft applies: offering qnop over a network requires making the corresponding source available to its users.
