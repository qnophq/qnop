# Deploying qnop

How to run the released qnop container ([`qnophq/qnop-ce`](https://hub.docker.com/r/qnophq/qnop-ce) on Docker Hub, mirrored to `ghcr.io/qnophq/qnop-ce`; ADR-0040). One image serves the REST API **and** the embedded web UI on port `8080`.

The fastest start is [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) — the released image plus PostgreSQL and MinIO on a single host:

```bash
cd deploy
QNOP_VERSION=1.0.0 \
QNOP_DB_PASSWORD="$(openssl rand -base64 24)" \
QNOP_S3_ACCESS_KEY=qnop \
QNOP_S3_SECRET_KEY="$(openssl rand -base64 24)" \
QNOP_AUTH_JWT_SECRET="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_KEY="$(openssl rand -base64 48)" \
QNOP_AUTH_ENCRYPTION_SALT="$(openssl rand -hex 32)" \
docker compose up -d
```

In practice, put those values in an env file you keep out of version control and pass `--env-file`.

Besides the release tags, every green CI build of `main` publishes a snapshot image to GHCR only, under the moving `ghcr.io/qnophq/qnop-ce:main` tag (immutable `sha-*` tags exist for pinning) — to run one, point the compose `image:` at the GHCR path. Snapshots are for evaluation and pre-release testing — production deployments pin a release version.

## Environment contract

All configuration is `QNOP_`-prefixed. The server **fails fast at startup** when a required secret is missing or still a placeholder (ADR-0022).

### Required

| Variable | Purpose |
|----------|---------|
| `QNOP_AUTH_JWT_SECRET` | HMAC secret for access tokens (≥ 32 chars) |
| `QNOP_AUTH_ENCRYPTION_KEY` | Key material for at-rest encryption of stored secrets (≥ 32 chars) |
| `QNOP_AUTH_ENCRYPTION_SALT` | Salt for the key derivation (**hex-encoded**, ≥ 16 chars — e.g. `openssl rand -hex 32`) |
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

## Audit-trail integrity

The audit trail (`audit_event`) protects itself inside the database (ADR-0043, issue #928): rows cannot be updated, they can be deleted only together with their review (the document cascade, which records an `audit.chain.pruned` tombstone), and every row is sealed into a SHA-256 hash chain when its transaction commits. The **Audit chain verification** job (`/admin/scheduler`, nightly at 05:00, also runnable on demand) recomputes the chain; a break fails the job, writes an `audit.chain.broken` event to the audit trail, and sets the Prometheus gauge `qnop_audit_chain_intact` to `0` — alert on that (it is `NaN` only until the job has run once anywhere). PostgreSQL 14 or newer is required (the migration refuses older versions), and audit-writing transactions must run under the default `READ COMMITTED` isolation — do not set `default_transaction_isolation` to anything stricter for the qnop database.

These guards stop mistakes and a compromised application, but **whoever owns the schema can switch them off**. Two optional measures close that gap:

**1. Run the application as a restricted role.** Migrate as the schema owner and let the application connect as a role that cannot alter the schema or touch the audit trail beyond appending to it:

```sql
-- once, as a superuser / the owner
CREATE ROLE qnop_app LOGIN PASSWORD '…';
GRANT USAGE ON SCHEMA public TO qnop_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO qnop_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO qnop_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM qnop_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON audit_chain_head FROM qnop_app;
-- tables created by later migrations (run as the owner):
ALTER DEFAULT PRIVILEGES FOR ROLE qnop IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO qnop_app;
ALTER DEFAULT PRIVILEGES FOR ROLE qnop IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO qnop_app;
```

Keep the owner's credentials out of the running application. The recommended pattern is a **separate one-off migration step** per upgrade: run the same image once with `QNOP_DB_USERNAME`/`QNOP_DB_PASSWORD` set to the owner — it applies the migrations at startup — and stop it once it reports started; then run the service itself with the restricted role and migrations switched off:

```bash
QNOP_DB_USERNAME=qnop_app            # the application's runtime connection
QNOP_DB_PASSWORD=…
SPRING_LIQUIBASE_ENABLED=false       # migrations ran in the one-off step
```

Only if a separate step is impractical, let the service migrate with the owner pair `SPRING_LIQUIBASE_USER` / `SPRING_LIQUIBASE_PASSWORD` next to the restricted runtime credentials — weaker, because a compromised application process then holds the owner's credentials too.

The chain-sealing and tombstone triggers run with the owner's rights (`SECURITY DEFINER`), and PostgreSQL runs the review-deletion cascade as the table owner, so deleting a review keeps working without granting `DELETE` on `audit_event`. Re-run the `REVOKE` lines if you ever re-grant on all tables.

**2. Ship the checkpoints out of the database.** Each successful verification logs `Audit chain intact: … head seq=<n> hash=<h>` (logger `io.qnop.service.audit.AuditChainVerificationService`). Forward these lines — or the whole application log — to an append-only store the database credentials cannot reach (your log platform, object storage with retention lock, syslog on another host). Someone who rewrites the trail and recomputes the chain cannot make an earlier logged hash reappear at its logged position: compare a stored checkpoint with `SELECT row_hash FROM audit_event WHERE chain_seq = <n>` to prove the trail up to that point is unchanged. (If row `<n>` has since been deleted with its review, an `audit.chain.pruned` tombstone lists its range; compare against the nearest surviving later checkpoint instead.)

## Upgrades

1. Read the release notes of every version you skip: <https://github.com/qnophq/qnop/releases>.
2. Back up the database and the bucket.
3. Bump `QNOP_VERSION` and `docker compose up -d` — Liquibase applies schema migrations forward automatically. Downgrades are not supported; restore from backup instead.

## One-click / template deployments

- **Portainer**: add the raw URL of [`deploy/portainer-templates.json`](../deploy/portainer-templates.json) as an app-template source (*Settings → App Templates*); the template deploys the stack from [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) and prompts for the required secrets.
- **Coolify**: paste [`deploy/coolify/docker-compose.yml`](../deploy/coolify/docker-compose.yml) as a Docker-Compose service. Coolify generates the passwords and auth secrets via its magic variables; only `QNOP_AUTH_ENCRYPTION_SALT` must be set manually (hex — `openssl rand -hex 32`).

Both templates are starting points for a single host — the environment contract above stays authoritative.
