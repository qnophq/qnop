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
| `QNOP_ATTACHMENTS_MAX_IMAGE_MEGAPIXELS` | `50` | Image attachments larger than this (or 32768 px per side), or whose size cannot be read, are stored download-only: linked, never shown inline or embedded in exports |
| `QNOP_AUTH_OIDC_FRONTEND_BASE_URL` | *(empty)* | Public base URL the OIDC login flow redirects back to, when it differs from the request origin |

SMTP for e-mail notifications (verification, reset, review events) is configured at runtime under **Administration → Settings**, not via environment.

## First login

On first startup, qnop bootstraps the internal admin account `admin`:

- With `QNOP_ADMIN_PASSWORD` set, that value is the password (no forced change) — intended for automated environments. Because it stays valid, it must be strong: at least 12 characters (at most 72 bytes), not containing `admin`, using at least 8 different characters, and mixing three of lower case, upper case, digits and symbols — or a passphrase of 20+ characters. A weaker value **stops the startup** with an error (issue #905).
- Otherwise a **random one-time password** is printed to the container's **stderr** (bypassing the log framework) and written to `admin-password.txt` in a freshly created, owner-only directory `qnop-bootstrap-<random>` (mode 0700, file 0600) under the JVM temp directory (`/tmp` in the container). The file is never written through an existing path; where that cannot be guaranteed, only stderr carries the password. The log records the file's path, never the password. The file and its directory are deleted automatically within a minute after the generated password has been changed. Log in and change it — the account is flagged `password_change_required`.

```bash
docker compose logs qnop | tail -5        # or:
docker compose exec qnop sh -c 'cat /tmp/qnop-bootstrap-*/admin-password.txt'
```

## Health & operations

- **Health**: `GET /actuator/health` is anonymous — wire it into your load balancer / orchestrator probes. All other actuator endpoints require an ADMIN bearer token.
- **Version**: `GET /api/v1/config` reports the running version and edition.
- **JVM flags**: extend via `JDK_JAVA_OPTS`; the image defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` and runs as a non-root user.
- **TLS**: terminate TLS at a reverse proxy and set `QNOP_HTTP_FORWARD_HEADERS_STRATEGY=framework`. The refresh cookie is `Secure` — plain-HTTP deployments are for local evaluation only.
- **Backups**: the PostgreSQL database and the object-storage bucket together form the complete state; back them up as a pair.

## Office converter isolation

The image ships headless LibreOffice for DOCX ingest and the PDF annotation export. It parses documents uploaded by users, so treat it as the least-trusted code in the deployment. qnop applies these limits on every run (issue #908, ADR-0010 amendment):

- **Empty environment.** The converter process receives only `PATH`, `HOME`, `TMPDIR`, `LANG` and `SAL_USE_VCLPLUGIN`; none of the `QNOP_*` secrets, database or storage credentials are passed on. `HOME`, `TMPDIR` and the working directory point into a throwaway per-run directory that is deleted afterwards.
- **Hardened per-run profile.** Macro execution and the Python runtime are disabled, the macro security level is set to its highest value, active content and untrusted links are blocked, and Writer never updates links or fields on load. Some of these keys (notably `DisableActiveContent`) exist only in recent LibreOffice releases; older ones ignore them, so keep the image's LibreOffice current.
- **Whole-tree timeout.** When a run exceeds `QNOP_OFFICE_TIMEOUT` (default `60s`), the launcher and every process it started are killed.
- **Size caps.** `QNOP_OFFICE_MAX_INPUT_SIZE` (default `100MB`) bounds what is handed to the converter, `QNOP_OFFICE_MAX_OUTPUT_SIZE` (default `256MB`) bounds the PDF read back.
- **Bounded concurrency.** `QNOP_OFFICE_MAX_CONCURRENT` / `QNOP_OFFICE_MAX_WAIT` (ADR-0055).

What a subprocess cannot do on its own is change user, network or kernel limits — the converter still runs as the server's uid, in the server's network namespace. Recommended additional isolation, strongest first:

1. **Restrict the container's egress.** The qnop container only needs PostgreSQL, the object store, SMTP and (if used) the OIDC provider. A Kubernetes `NetworkPolicy` or firewall rule that allows exactly those keeps a converted document from reaching anything else.
2. **Bound the container's resources.** Set memory and PID limits (`--memory`, `--pids-limit`, or the Kubernetes equivalents) and keep the JVM's `-XX:+ExitOnOutOfMemoryError`; run with the runtime's default seccomp profile, `--cap-drop=ALL`, `no-new-privileges`, and a read-only root filesystem with a writable `/tmp`.
3. **Wrap the binary.** `QNOP_OFFICE_BINARY` may point at a wrapper script that applies per-process limits or namespaces before starting `soffice`. The wrapper runs with the same minimal environment (the server's `PATH` plus the per-run `HOME`, `TMPDIR` and `LANG`), so anything it calls must be on that `PATH` or given by absolute path. For example, with `util-linux` available and unprivileged user namespaces enabled on the host:

   ```sh
   #!/bin/sh
   # No network at all, CPU-time and file-size limits, then the real converter.
   exec prlimit --cpu=120 --fsize=536870912 -- unshare --user --map-root-user --net -- soffice "$@"
   ```

   Running the converter under a separate uid (for example `sudo -u <converter-user>` in the wrapper with a sudoers rule scoped to that single command) additionally keeps it from reading the server process's files and `/proc` entries.
4. **Disable it.** A deployment that does not need DOCX ingest or PDF export can set `QNOP_OFFICE_BINARY` to a non-existent path; both features then report themselves unavailable in `GET /api/v1/config`.

## Upgrades

1. Read the release notes of every version you skip: <https://github.com/qnophq/qnop/releases>.
2. Back up the database and the bucket.
3. Bump `QNOP_VERSION` and `docker compose up -d` — Liquibase applies schema migrations forward automatically. Downgrades are not supported; restore from backup instead.

## One-click / template deployments

- **Portainer**: add the raw URL of [`deploy/portainer-templates.json`](../deploy/portainer-templates.json) as an app-template source (*Settings → App Templates*); the template deploys the stack from [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) and prompts for the required secrets.
- **Coolify**: paste [`deploy/coolify/docker-compose.yml`](../deploy/coolify/docker-compose.yml) as a Docker-Compose service. Coolify generates the passwords and auth secrets via its magic variables; only `QNOP_AUTH_ENCRYPTION_SALT` must be set manually (hex — `openssl rand -hex 32`).

Both templates are starting points for a single host — the environment contract above stays authoritative.
