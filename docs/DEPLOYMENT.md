# Deploying qnop

How to run the released qnop container (`ghcr.io/qnophq/qnop-ce`, ADR-0040). One image serves the REST API **and** the embedded web UI on port `8080`.

The fastest start is [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) — the released image plus PostgreSQL and MinIO on a single host:

```bash
cd deploy
QNOP_VERSION=1.0.0 \
QNOP_DB_PASSWORD="$(openssl rand -base64 24)" \
QNOP_S3_ACCESS_KEY=qnop \
QNOP_S3_SECRET_KEY="$(openssl rand -base64 24)" \
QNOP_AUTH_JWT_SECRET="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_KEY="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_SALT="$(openssl rand -base64 48)" \
docker compose up -d
```

In practice, put those values in an env file you keep out of version control and pass `--env-file`.

Besides the release tags, every green CI build of `main` publishes a snapshot image under the moving `main` tag (immutable `sha-*` tags exist for pinning). Snapshots are for evaluation and pre-release testing — production deployments pin a release version.

## Environment contract

All configuration is `QNOP_`-prefixed. The server **fails fast at startup** when a required secret is missing or still a placeholder (ADR-0022).

### Required

| Variable | Purpose |
|----------|---------|
| `QNOP_AUTH_JWT_SECRET` | HMAC secret for access tokens (≥ 32 chars) |
| `QNOP_AUTH_ENCRYPTION_KEY` | Key material for at-rest encryption of stored secrets (≥ 32 chars) |
| `QNOP_AUTH_ENCRYPTION_SALT` | Salt for the key derivation (≥ 32 chars) |
| `QNOP_DB_HOST` / `QNOP_DB_PORT` / `QNOP_DB_NAME` / `QNOP_DB_USERNAME` / `QNOP_DB_PASSWORD` | PostgreSQL connection (Liquibase migrates the schema on startup) |
| `QNOP_S3_ENDPOINT` / `QNOP_S3_BUCKET` / `QNOP_S3_ACCESS_KEY` / `QNOP_S3_SECRET_KEY` | S3-compatible object storage for document binaries (leave `QNOP_S3_ENDPOINT` empty for AWS S3) |

Rotating `QNOP_AUTH_JWT_SECRET` invalidates all sessions; rotating the encryption key/salt makes previously encrypted values (e.g. stored OIDC client secrets) unreadable — plan key rotation deliberately.

### Common optional

| Variable | Default | Purpose |
|----------|---------|---------|
| `QNOP_S3_REGION` | `us-east-1` | Storage region |
| `QNOP_S3_PATH_STYLE_ACCESS` | `true` | Required `true` for MinIO; `false` for AWS S3 |
| `QNOP_S3_AUTO_CREATE_BUCKET` | `false` | Create the bucket on first start |
| `QNOP_ADMIN_PASSWORD` | *(random)* | Fixed initial admin password (see bootstrap below) |
| `QNOP_UPLOAD_MULTIPART_LIMIT_MB` | `55` | HTTP multipart ceiling (keep above the document size limit) |
| `QNOP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Only needed when a browser client runs on a *different* origin; the embedded UI is same-origin and needs none |
| `QNOP_HTTP_FORWARD_HEADERS_STRATEGY` | `none` | Set `framework` behind a trusted reverse proxy so `X-Forwarded-*` drive scheme/host |
| `QNOP_HTTP_SHUTDOWN_GRACE` | `30s` | Graceful-shutdown drain window (align your orchestrator's stop grace period) |
| `QNOP_AUTH_OIDC_FRONTEND_BASE_URL` | *(empty)* | Public base URL the OIDC login flow redirects back to, when it differs from the request origin |

SMTP for e-mail notifications (verification, reset, review events) is configured at runtime under **Administration → Settings**, not via environment.

## First login

On first startup, qnop bootstraps the internal admin account `admin`:

- With `QNOP_ADMIN_PASSWORD` set, that value is the password (no forced change) — intended for automated environments.
- Otherwise a **random one-time password** is printed to the container's **stderr** (bypassing the log framework) and written to `/tmp/qnop-admin-password.txt` (mode 0600) inside the container. Log in and change it — the account is flagged `password_change_required`.

```bash
docker compose logs qnop | tail -5        # or:
docker compose exec qnop cat /tmp/qnop-admin-password.txt
```

## Health & operations

- **Health**: `GET /actuator/health` is anonymous — wire it into your load balancer / orchestrator probes. All other actuator endpoints require an ADMIN bearer token.
- **Version**: `GET /api/v1/config` reports the running version and edition.
- **JVM flags**: extend via `JDK_JAVA_OPTS`; the image defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` and runs as a non-root user.
- **TLS**: terminate TLS at a reverse proxy and set `QNOP_HTTP_FORWARD_HEADERS_STRATEGY=framework`. The refresh cookie is `Secure` — plain-HTTP deployments are for local evaluation only.
- **Backups**: the PostgreSQL database and the object-storage bucket together form the complete state; back them up as a pair.

## Upgrades

1. Read the release notes of every version you skip: <https://github.com/qnophq/qnop/releases>.
2. Back up the database and the bucket.
3. Bump `QNOP_VERSION` and `docker compose up -d` — Liquibase applies schema migrations forward automatically. Downgrades are not supported; restore from backup instead.
