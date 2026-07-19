# qnop — Qualified Notes on Papers

**Document review your team actually wants to finish.**

qnop is a self-hosted, enterprise-grade **document review system**. Upload a document, invite reviewers — individual users or whole teams — and run a coordinated review: passages are marked up right in the browser, discussed in threads, accepted or rejected; changes produce new document versions, and the review is finalized once no open annotation remains. The goal: replace the e-mail-attachment-and-comment-spreadsheet workflow with one place where reviews are precise, accountable — and genuinely satisfying to complete.

qnop reviews **PDF documents today**; DOCX and Markdown follow the same ingest pipeline later ([ADR-0010](docs/adr/0010-docx-representation-strategy.md), [ADR-0032](docs/adr/0032-document-representation-and-rendering-pipeline.md)).

## Why qnop

- **Line-precise annotations.** Mark exact lines and regions of a rendered PDF, comment in Markdown threads, react, and jump between document and discussion — annotations stay anchored to what they mean.
- **A real review workflow.** Open → discussed → accepted/rejected; new document versions **re-anchor existing annotations** instead of orphaning them, and a review can only be finalized when nothing is left open.
- **Teams, roles & privacy.** Global `ADMIN` / `MEMBER` / `AUDITOR` roles plus per-team leads; reviews can run with **anonymized reviewer identities** when the process demands it.
- **Reviews as a sport.** Streaks, scoreboards, achievements and player-card profiles turn review throughput into something visible — and finishing reviews into a habit, not a chore.
- **Fully accountable.** Every relevant action lands in the audit trail, inspectable by the dedicated auditor role.
- **Enterprise sign-in.** Local accounts with self-registration and e-mail verification, or OIDC single sign-on; JWT sessions with rotating refresh tokens and rate-limited auth endpoints.
- **Yours to run.** One container (REST API + embedded web UI), PostgreSQL, and any S3-compatible object storage. Your documents never leave your infrastructure. Tenant branding, SMTP and mail templates are configured at runtime in the admin area.

qnop is open-core: this repository is the **AGPL-3.0 Community edition**; commercial add-ons live in a separate private repository built against the published `qnop-spi` contract.

## Installation

The released image is **[`qnophq/qnop-ce`](https://hub.docker.com/r/qnophq/qnop-ce)** on Docker Hub (multi-arch: amd64/arm64), mirrored to `ghcr.io/qnophq/qnop-ce`. qnop needs PostgreSQL and an S3-compatible object storage — the repository ships a single-host [`deploy/docker-compose.yml`](deploy/docker-compose.yml) wiring all three:

```bash
curl -fsSLO https://raw.githubusercontent.com/qnophq/qnop/main/deploy/docker-compose.yml
QNOP_VERSION=latest \
QNOP_DB_PASSWORD="$(openssl rand -base64 24)" \
QNOP_S3_ACCESS_KEY=qnop \
QNOP_S3_SECRET_KEY="$(openssl rand -base64 24)" \
QNOP_AUTH_JWT_SECRET="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_KEY="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_SALT="$(openssl rand -hex 32)" \
docker compose up -d
```

The server **fails fast** when a required secret is missing or still a placeholder — there are no insecure defaults. In practice, keep the values in an env file outside version control.

**First login:** on first start qnop bootstraps the `admin` account and prints its one-time password to the container's stderr:

```bash
docker compose logs qnop | tail -5
```

Open `http://localhost:8080`, sign in, change the password.

The full environment contract, TLS/reverse-proxy guidance, backups and upgrade notes live in the [deployment guide](docs/DEPLOYMENT.md). Rolling development builds of `main` are published to GHCR only, as `ghcr.io/qnophq/qnop-ce:main`, for evaluation — production deployments pin a release version.

## Developing

Requires JDK 21, Node 24 + pnpm, and Docker. **Docker must be running for the backend test suite** — tests boot real PostgreSQL and MinIO containers via Testcontainers (ADR-0020).

```bash
# Local infrastructure (Postgres for bootRun + MinIO)
cp .env.example .env && docker compose up -d

# Backend — compile + format check + architecture & context tests
./gradlew build

# Run the server (uses the docker-compose Postgres).
# Unlike `docker compose`, Gradle's bootRun does NOT auto-load .env, and the server
# fails fast unless QNOP_AUTH_JWT_SECRET / QNOP_AUTH_ENCRYPTION_KEY / QNOP_AUTH_ENCRYPTION_SALT
# hold real values. Replace the CHANGE_ME placeholders in .env, then export it:
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
