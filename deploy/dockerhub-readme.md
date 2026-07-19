# qnop — Qualified Notes on Papers

**Document review your team actually wants to finish.**

**qnop** is a self-hosted, enterprise-grade document review system: upload a document (PDF today), invite reviewers or whole teams, mark up exact lines and regions in the browser, discuss in threads, accept or reject — new document versions re-anchor existing annotations, and the review is finalized once nothing is left open. This image is the AGPL **Community edition**: the REST API and the embedded web UI in a single container on port `8080`.

**Highlights**

- Line-precise PDF annotations with Markdown discussion threads and reactions
- Coordinated review workflow with versioning and annotation re-anchoring
- Teams and roles (admin / member / auditor, team leads), optional anonymized reviews
- Gamified review culture: streaks, scoreboards, achievements, player-card profiles
- Full audit trail, e-mail notifications, runtime-configurable branding and SMTP
- Local accounts or OIDC single sign-on; your documents stay on your infrastructure

- 🏠 Source: https://github.com/qnophq/qnop
- 📘 Deployment guide: https://github.com/qnophq/qnop/blob/main/docs/DEPLOYMENT.md
- 🐛 Issues: https://github.com/qnophq/qnop/issues
- 📦 GHCR mirror: `ghcr.io/qnophq/qnop-ce`

## Supported tags

| Tag | Description |
|---|---|
| `latest` | Latest stable release (semver, no pre-release) |
| `1.0.0`, `1.0` | Specific versions and minor aliases |
| `main`, `sha-*` | Rolling development build of the `main` branch (on [GHCR only](https://github.com/qnophq/qnop/pkgs/container/qnop-ce)) |

Multi-architecture: `linux/amd64`, `linux/arm64`.

## Quick start

qnop needs PostgreSQL and an S3-compatible object storage (e.g. MinIO). The repository ships a single-host [`deploy/docker-compose.yml`](https://github.com/qnophq/qnop/blob/main/deploy/docker-compose.yml) wiring all three:

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

The server **fails fast at startup** when a required secret is missing or still a placeholder — there are no insecure defaults. Put real values in an env file you keep out of version control.

**First login:** the initial `admin` password is printed to the container's stderr on first start (and written to `/tmp/qnop-admin-password.txt` inside the container):

```bash
docker compose logs qnop | tail -5
```

Open `http://localhost:8080`, log in, change the password.

## Operations

- **Health**: `GET /actuator/health` (anonymous) for load-balancer/orchestrator probes.
- **Version**: `GET /api/v1/config` reports the running version and edition.
- **JVM flags**: extend via `JDK_JAVA_OPTS`; the image runs as a non-root user.
- **TLS**: terminate at a reverse proxy and set `QNOP_HTTP_FORWARD_HEADERS_STRATEGY=framework`.

The full environment contract, backup and upgrade notes live in the [deployment guide](https://github.com/qnophq/qnop/blob/main/docs/DEPLOYMENT.md).

## License

[GNU AGPL-3.0-only](https://github.com/qnophq/qnop/blob/main/LICENSE). Network-use copyleft applies.
