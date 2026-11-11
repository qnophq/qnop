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

Rotating `QNOP_AUTH_JWT_SECRET` invalidates all sessions. The encryption key can be rotated without losing stored secrets — see [Rotating the encryption key](#rotating-the-encryption-key).

`QNOP_AUTH_JWT_SECRET` and `QNOP_AUTH_ENCRYPTION_KEY` must contain **at least 8 distinct characters** besides being ≥ 32 characters long (since #927). Generated secrets (`openssl rand …`) always pass; a degenerate value such as one repeated character now **fails at startup** — check yours before upgrading.

### Stored secrets at rest

Stored secrets (the SMTP password and other sensitive settings, OIDC client secrets) are encrypted with AES-256-GCM under a key derived from `QNOP_AUTH_ENCRYPTION_KEY` + `QNOP_AUTH_ENCRYPTION_SALT`, and every value records which key wrote it (ADR-0022, amendment #927). Related optional variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | A second key, accepted **for decryption only** — used while rotating (same strength rule as the current key) |
| `QNOP_AUTH_ENCRYPTION_SALT_PREVIOUS` | *(blank = same as current salt)* | The salt belonging to that second key, if it differs |
| `QNOP_AUTH_SECRETS_WRITE_FORMAT` | `v2` | `v2`: write the current format and re-encrypt older values on start. `legacy`: keep writing the pre-#927 format and skip the re-encryption (staged upgrades, see below) |

With `v2`, every start re-encrypts any stored secret that is in the pre-#927 format or was written under a key other than the current one, and records a **Secrets re-encrypted** entry in the audit trail.

If a stored secret cannot be decrypted with any configured key (the old key was dropped too early, or the value was altered), qnop still starts: the secret is treated as **unset**, an error is logged, an OIDC provider with such a secret is not offered for login, and a **Secrets unreadable** audit entry names the setting or OIDC provider (never the value; recorded again only when that list changes). The stored ciphertext is kept, so setting the right key as `QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS` and restarting recovers it; otherwise an administrator enters the secret again. A failure of the re-encryption pass itself (e.g. a database hiccup) never stops the instance either: it is logged, audited as **Secret re-encryption failed**, and retried on the next start.

### Upgrading to a release with versioned secrets (#927)

Releases before #927 **cannot read** the new format. Once a stored secret has been re-encrypted, an older instance reads it as missing (OIDC logins through that provider fail; an older instance with a stored SMTP password fails at its next startup). Plan the upgrade accordingly:

- **Single instance.** Take a **database backup**, then upgrade as usual. It just works: existing secrets are read in the old format and re-encrypted on the first start. From then on the change is **one-way** — rolling back to an earlier release requires restoring that backup (or re-entering the stored secrets after the rollback).
- **Several instances (rolling upgrade), or if you want to keep a rollback window:**
  1. Take a database backup.
  2. Roll out the new release with `QNOP_AUTH_SECRETS_WRITE_FORMAT=legacy` on **every** instance. New and old instances now write and read the same old format; nothing is re-encrypted, and rolling back is still possible.
  3. Once no instance of an earlier release is left and you no longer need the rollback, roll out `QNOP_AUTH_SECRETS_WRITE_FORMAT=v2` (or remove the variable). The first instance to start re-encrypts; instances still on `legacy` read the new values fine. From here on, rollback again means restoring the backup.

### Rotating the encryption key

Generate a new key (`openssl rand -base64 48`; optionally a new salt with `openssl rand -hex 32`). Then use one of the two procedures.

**All at once** (you can stop every instance):

1. Stop all instances.
2. Set `QNOP_AUTH_ENCRYPTION_KEY` (and `QNOP_AUTH_ENCRYPTION_SALT`) to the new values, and move the old values to `QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS` (and `QNOP_AUTH_ENCRYPTION_SALT_PREVIOUS`, if the salt changed).
3. Start the instances. The first one re-encrypts all stored secrets; check the audit trail (or the log line `Re-encrypted … under the current encryption key`) and confirm there is no **Secrets unreadable** entry.
4. Remove the two `*_PREVIOUS` variables and restart.

**Rolling** (no downtime; every phase is a normal rolling restart, finish each before starting the next):

1. **Introduce the new key as the previous one** — keep `QNOP_AUTH_ENCRYPTION_KEY` = old key, set `QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS` = new key (and `…_SALT_PREVIOUS` = new salt if it differs). Every instance can now read both keys; nothing is rewritten.
2. **Swap** — set `QNOP_AUTH_ENCRYPTION_KEY` = new key and `QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS` = old key (salts likewise). The first restarted instance re-encrypts under the new key; instances still in phase 1 read the new values. Check the audit trail as above.
3. **Drop the old key** — remove the `*_PREVIOUS` variables.

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

## One-click / template deployments

- **Portainer**: add the raw URL of [`deploy/portainer-templates.json`](../deploy/portainer-templates.json) as an app-template source (*Settings → App Templates*); the template deploys the stack from [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) and prompts for the required secrets.
- **Coolify**: paste [`deploy/coolify/docker-compose.yml`](../deploy/coolify/docker-compose.yml) as a Docker-Compose service. Coolify generates the passwords and auth secrets via its magic variables; only `QNOP_AUTH_ENCRYPTION_SALT` must be set manually (hex — `openssl rand -hex 32`).

Both templates are starting points for a single host — the environment contract above stays authoritative.
