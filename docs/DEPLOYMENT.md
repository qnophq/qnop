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

All configuration is `QNOP_`-prefixed. The server **fails fast at startup** when a required secret is missing or still a placeholder (ADR-0022), and when `QNOP_DB_PASSWORD` / `QNOP_S3_SECRET_KEY` still hold the public local-development defaults (issue #922; only the `dev` profile used by `bootRun` tolerates them — never set `SPRING_PROFILES_ACTIVE=dev` in a deployment).

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
| `QNOP_AUTH_RATELIMIT_TRUSTEDPROXYCIDRS` | *(empty)* | Comma-separated CIDRs of your reverse proxies; see [Behind a reverse proxy](#behind-a-reverse-proxy) |
| `QNOP_MANAGEMENT_PORT` | *(blank)* | Serve Actuator (health, metrics) on its own port; see [Metrics](#metrics) |
| `QNOP_METRICS_SCRAPE_TOKEN` | *(blank)* | Long-lived bearer token for a Prometheus scrape (≥ 32 chars); see [Metrics](#metrics) |
| `QNOP_HTTP_SHUTDOWN_GRACE` | `30s` | Graceful-shutdown drain window (align your orchestrator's stop grace period) |
| `QNOP_ATTACHMENTS_MAX_IMAGE_MEGAPIXELS` | `50` | Image attachments larger than this (or 32768 px per side), or whose size cannot be read, are stored download-only: linked, never shown inline or embedded in exports |
| `QNOP_HTTP_CLIENT_PROXY_HOST` / `_PORT` / `_NON_PROXY_HOSTS` | *(unset)* | Explicit egress proxy for OIDC and usage-tracking requests — a weaker mode; see [Outbound proxy](#outbound-proxy-oidc-and-usage-tracking) |
| `QNOP_AUTH_OIDC_FRONTEND_BASE_URL` | *(empty)* | Public base URL the OIDC login flow redirects back to, when it differs from the request origin |

SMTP for e-mail notifications (verification, reset, review events, team invitations) is configured at runtime under **Administration → Settings**, not via environment. `smtp.enabled` is the master switch for all outgoing mail. `notifications.review_emails_enabled` only covers review activity: team invitations (#948) are not covered by it and are governed by the SMTP master switch plus each user's own opt-out.

## First login

On first startup, qnop bootstraps the internal admin account `admin`:

- With `QNOP_ADMIN_PASSWORD` set, that value is the password (no forced change) — intended for automated environments. Because it stays valid, it must be strong: at least 12 characters (at most 72 bytes), not containing `admin`, using at least 8 different characters, and mixing three of lower case, upper case, digits and symbols — or a passphrase of 20+ characters. A weaker value **stops the startup** with an error (issue #905).
- Otherwise a **random one-time password** is printed to the container's **stderr** (bypassing the log framework) and written to `admin-password.txt` in a freshly created, owner-only directory `qnop-bootstrap-<random>` (mode 0700, file 0600) under the JVM temp directory (`/tmp` in the container). The file is never written through an existing path; where that cannot be guaranteed, only stderr carries the password. The log records the file's path, never the password. The file and its directory are deleted automatically within a minute after the generated password has been changed. Log in and change it — the account is flagged `password_change_required`.

```bash
docker compose logs qnop | tail -5        # or:
docker compose exec qnop sh -c 'cat /tmp/qnop-bootstrap-*/admin-password.txt'
```

## Health & operations

- **Health**: `GET /actuator/health` is anonymous — wire it into your load balancer / orchestrator probes. All other actuator endpoints require an ADMIN bearer token, except the metrics scrape where you opt in (see [Metrics](#metrics)).
- **Version**: `GET /api/v1/config` reports the running version and edition.
- **JVM flags**: extend via `JDK_JAVA_OPTS`; the image defaults to `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` and runs as a non-root user.
- **TLS**: terminate TLS at a reverse proxy and set `QNOP_HTTP_FORWARD_HEADERS_STRATEGY=framework`. The refresh cookie is `Secure` — plain-HTTP deployments are for local evaluation only.
- **Backups**: the PostgreSQL database and the object-storage bucket together form the complete state; back them up as a pair.

## Metrics

`GET /actuator/prometheus` publishes metrics in the Prometheus text format (ADR-0037, ADR-0065): request rates and latency histograms, JVM, connection pool, Tomcat threads, log-event counts, and qnop's own `qnop_*` series such as the job-queue depth and the audit-chain status. Every series carries `application="qnop"`; `qnop_info` reports the running version and edition.

By default the scrape needs an ADMIN access token, which is short-lived and so of little use to an unattended Prometheus. Choose one of two ways to let a scraper in — both are off until you set them:

**A management port (preferred).** Set `QNOP_MANAGEMENT_PORT` (for example `8081`). Actuator then listens on that port instead of the application port, and a scrape arriving there needs no credential. **Do not publish this port**: keep it on the internal network, reachable by Prometheus and your orchestrator's probes only.

`/actuator/health` moves with it, so **every health check must follow**, or the instance is reported unhealthy. `deploy/docker-compose.yml` does this by itself. The other templates (`deploy/aws-demo`, `deploy/coolify`, `deploy/portainer-templates.json`) and your own load-balancer or Kubernetes probes hard-code port 8080 and need the new port.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: qnop
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['qnop:8081']
```

**A scrape token.** Where Prometheus can only reach the application port, set `QNOP_METRICS_SCRAPE_TOKEN` to a random value of at least 32 characters without whitespace (`openssl rand -hex 32`); anything else stops the instance from starting. The token opens `GET /actuator/prometheus` and nothing else, and it is not an admin: health details stay hidden from it. To rotate it, replace the value and restart.

Use one or the other. With a management port set, the scrape is no longer served on the application port at all, so a token sent there gets a 404.

```yaml
scrape_configs:
  - job_name: qnop
    metrics_path: /actuator/prometheus
    scheme: https
    authorization:
      credentials_file: /etc/prometheus/qnop-scrape-token
    static_configs:
      - targets: ['qnop.example.com']
```

With several instances, counters and timers are per instance (`sum` them); gauges that describe the database, such as `qnop_jobs` and `qnop_audit_chain_intact`, are the same on every instance (take the `max`).

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
REVOKE ALL ON audit_redaction_context FROM qnop_app;
REVOKE ALL ON audit_cascade_context, audit_prune_context FROM qnop_app;
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

The chain-sealing and tombstone triggers run with the owner's rights (`SECURITY DEFINER`), and PostgreSQL runs the review-deletion cascade as the table owner, so deleting a review keeps working without granting `DELETE` on `audit_event`. Re-run the `REVOKE` lines if you ever re-grant on all tables. Deleting a user pseudonymises the audit entries that name them (ADR-0043 amendment, #952): a trigger on `qnop_user` runs the `SECURITY DEFINER` redaction, so the restricted role needs no `UPDATE` on `audit_event`, no `EXECUTE` grant and no access to `audit_redaction_context` (the migration revokes it). The same holds for `audit_cascade_context` and `audit_prune_context` (ADR-0043 amendment, #959): the document-deletion trigger records the documents being deleted there and the tombstone trigger announces its `audit.chain.pruned` event there, both with the owner's rights, and the guards trust only that state. Never grant any of these three tables to the application role — mind blanket statements such as `GRANT … ON ALL TABLES IN SCHEMA`, and re-run their `REVOKE` after one. Each erasure appears in the trail as an `audit.chain.redacted` event listing the changed rows with their original and redacted-content hashes, plus a `user.erased` event attributed to the deleting admin; the verification accepts exactly those rows, and a checkpoint taken before the erasure still matches, because redaction never changes a stored hash.

**2. Ship the checkpoints out of the database.** Each successful verification logs `Audit chain intact: … head seq=<n> hash=<h>` (logger `io.qnop.service.audit.AuditChainVerificationService`). Forward these lines — or the whole application log — to an append-only store the database credentials cannot reach (your log platform, object storage with retention lock, syslog on another host). Someone who rewrites the trail and recomputes the chain cannot make an earlier logged hash reappear at its logged position: compare a stored checkpoint with `SELECT row_hash FROM audit_event WHERE chain_seq = <n>` to prove the trail up to that point is unchanged. (If row `<n>` has since been deleted with its review, an `audit.chain.pruned` tombstone lists its range; compare against the nearest surviving later checkpoint instead.)

## Behind a reverse proxy

The auth rate limits (login, refresh, registration, password reset — ADR-0027) and usage tracking key on the client IP. qnop only believes `X-Forwarded-For` when the TCP peer is one of the CIDRs in `QNOP_AUTH_RATELIMIT_TRUSTEDPROXYCIDRS` (property `qnop.auth.rate-limit.trusted-proxy-cidrs`).

- **No proxy (default, empty list):** `X-Forwarded-For` is ignored and the connection address is used. Leave it empty when clients connect to qnop directly — any non-empty value there lets a client choose its own address.
- **Behind a proxy:** list the address ranges of **every** proxy hop in front of qnop (load balancer, ingress, CDN egress ranges), and nothing else — e.g. `QNOP_AUTH_RATELIMIT_TRUSTEDPROXYCIDRS=10.0.0.0/8,172.28.0.0/16`. Never list a range clients can connect from. If the list is left empty behind a proxy, every client appears to come from the proxy and shares a single rate-limit bucket.
- **How the client is chosen:** the header is read **right to left**. Entries written by your trusted proxies are skipped; the first address outside the list is the client. Anything to the left of it was supplied by the client and is never used. An entry that is not an IP literal stops the walk at the last trusted hop. This is correct for proxies that *append* to the header (nginx `$proxy_add_x_forwarded_for`, AWS ALB, GCP and Azure load balancers, Traefik) and for proxies that *replace* it (Caddy's default).
- Make sure the proxy closest to the client does not trust an incoming `X-Forwarded-For` blindly unless it, too, sits behind trusted infrastructure.
- `QNOP_HTTP_FORWARD_HEADERS_STRATEGY=framework` is a separate setting: it controls scheme/host for redirects and cookies, not the rate-limit IP.

**Content-Security-Policy and caching.** qnop sets the browser app's `Content-Security-Policy` itself, with a nonce that is new for every `index.html` response (ADR-0040, #913). Do not add or rewrite a CSP header at the proxy, and do not cache or rewrite the HTML: `index.html` is sent `Cache-Control: no-store`; a proxy that caches it anyway hands every visitor the same nonce, and one that rewrites the policy or the HTML separately leaves the two out of step (the UI then renders unstyled). The content-hashed files under `/assets/` are sent as `immutable` and may be cached freely.

E-mail verification (`POST /api/v1/auth/verify-email`) has its own per-network limit, `qnop.auth.rate-limit.verify-email.*` (default 20 requests per 15 minutes); raise it if many people verify from behind one shared egress address. Besides the per-IP limits, login, registration, password-reset request and password reset are also throttled per account / e-mail address / reset token, so spreading attempts over many addresses does not lift the limit. Failed logins are counted per account and client network (IPv4 /24, IPv6 /56), and per recognised device: a successful login sets an `HttpOnly` `qnop_device` cookie (path `/api/v1/auth/login`, valid for `qnop.auth.rate-limit.login-device-cookie-lifetime`, default 30 days), and a browser that carries one is never held back by failures elsewhere. A much higher per-account ceiling bounds failures from unrecognised devices across all networks; a `WARN` line `Login ceiling reached for account …` means it was hit — worth a look. Rotating `QNOP_AUTH_JWT_SECRET` invalidates all device cookies. All limits are tunable under `qnop.auth.rate-limit.*` (see ADR-0027).

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

## Usage tracking (analytics backends)

Usage tracking is off by default and configured under *Administration → Settings → Usage tracking* (ADR-0056). The browser never talks to the analytics backend and loads no analytics script: qnop builds each measurement itself and posts it to `/t/c/…` on its own origin, and the server checks it against the exact shape qnop sends before forwarding it with the visitor's user agent and — per `tracking.forward_client_ip` — a truncated, exact or no `X-Forwarded-For` (issue #910).

- **Transport.** `tracking.host` must be `https://` unless it is a private address (an IP literal in a private range, `localhost`, `*.local`, `*.internal`). Reaching a private host additionally needs `QNOP_TRACKING_ALLOW_PRIVATE_HOST=true` (`qnop.tracking.allow-private-host`).
- **Visitor addresses through the proxy.** Every measurement reaches the backend from qnop's server, so the backend has to take the visitor's address from `X-Forwarded-For` — otherwise it counts every visitor as one (more precisely: as one per browser type, since the user agent is still forwarded).
  - **Matomo** uses the header only when told to: add `proxy_client_headers[] = HTTP_X_FORWARDED_FOR` to the `[General]` section of its `config/config.ini.php`. Only do this if requests reach Matomo exclusively through qnop or another proxy you control, since the header is otherwise caller-supplied. Without it, Matomo records qnop's address for every visit.
  - **Umami and Plausible** read `X-Forwarded-For` out of the box; nothing to configure.
  - **PostHog**'s capture service takes the event's `ip` from `X-Forwarded-For` out of the box. It does not keep the request's user agent, and qnop sends no browser properties, so PostHog shows no browser or OS breakdown for qnop's events.
  - **Pirsch** — known limitation, not verified against a live instance: its identification-code endpoints (`/hit`, `/event`) are designed to be called from the browser, and its documented proxy setups use a server-side API client instead. Through qnop, Pirsch may attribute every visitor to qnop's address and collapse unique-visitor counts. A server-side access-token mode is tracked in issue #961; until then, prefer another backend if unique-visitor counts matter.
- **What "anonymised" means for the counts.** With `tracking.forward_client_ip=anonymized` (the default) visitors are told apart by their /24 (IPv4) or /64 (IPv6) network plus user agent: two people in the same office network with the same browser count as one visitor.
- **Plausible custom events.** Plausible stores the four events (`review_created`, `annotation_created`, `review_finalized`, `export_generated`) as they arrive, but per Plausible's documentation its dashboard lists a custom event only once it has been added as a goal: under *Site settings → Goals*, add a custom-event goal for each of the four names. Set `tracking.site_id` to the site's domain exactly as registered in Plausible.
- **PostHog region.** An empty host means PostHog's US cloud. For an EU project set `tracking.host` to `https://eu.i.posthog.com`. Unique-user counts in PostHog are per page load by design (no persistent id is kept), so read page views and events rather than "unique users".
- **Verified backend versions** (issue #944, against live local instances through qnop's proxy): Matomo 5.14.2, Umami 3.5.0, Plausible Community Edition v3.2.1, and PostHog's capture service (ingest edge only — the full self-hosted stack was not run). Pirsch is cloud-only and was not tested.

## Outbound proxy (OIDC and usage tracking)

qnop's requests to OIDC providers (discovery, token, user-info, JWK set, and GitHub's `/user/emails` lookup during GitHub login) and to the usage-tracking backend go through SSRF-guarded clients (ADR-0056): they resolve the destination themselves, refuse private/loopback/metadata addresses (unless `QNOP_AUTH_OIDC_ALLOW_PRIVATE_DISCOVERY_URIS` / `QNOP_TRACKING_ALLOW_PRIVATE_HOST` allow them), and connect only to the address they checked. They **ignore** the JVM proxy settings (`-Dhttps.proxyHost`, `-Dhttp.proxyHost`, `-Dhttp.nonProxyHosts`).

If those destinations are reachable only through a mandatory egress proxy, opt in explicitly (deployment configuration only — there is no admin-UI setting for this):

| Variable | Property | Meaning |
|----------|----------|---------|
| `QNOP_HTTP_CLIENT_PROXY_HOST` | `qnop.http-client.proxy.host` | Proxy host name or IP literal. Unset = direct connections (the default). |
| `QNOP_HTTP_CLIENT_PROXY_PORT` | `qnop.http-client.proxy.port` | Proxy port; required with the host. |
| `QNOP_HTTP_CLIENT_PROXY_NON_PROXY_HOSTS` | `qnop.http-client.proxy.non-proxy-hosts` | Comma-separated destinations that stay direct: exact host names (`idp.corp.example`), `*.suffix` (`*.internal.corp.example`, subdomains only) or IPv6 literals. Case-insensitive; a trailing dot is ignored; no ports. Must not cover the proxy host itself. |

The proxy is spoken to over plain HTTP (`CONNECT` tunnels for `https://` destinations, so TLS to the IdP is end-to-end). **Proxy authentication is not supported.** A misconfiguration (host without port, port without host, a host with a scheme or path, an invalid non-proxy entry) stops the server at startup. When proxy mode is active, the server logs one `WARN` line naming the proxy's `host:port`.

**This is a weaker mode — understand the trade-off:**

- For proxied destinations qnop no longer sees the address it connects to: the proxy resolves the name. The connection-time address check cannot apply. qnop still applies the lexical check on every request (no `localhost`/`*.local`/`*.internal`, no private, loopback or metadata IP literals, no non-canonical numeric hosts), but a public-looking name that resolves to an internal address is **not** caught by qnop.
- **The proxy's egress ACL becomes the remaining control.** Configure it to refuse internal ranges and cloud metadata addresses (`169.254.169.254`, `fd00:ec2::254`, …) for qnop's traffic, ideally to allow only your IdP and analytics hosts.
- Destinations in the non-proxy list keep the full guard (resolved by qnop, every address checked, connection pinned). Prefer listing internal IdPs there over sending them through the proxy.
- The proxy host itself is trusted configuration and is exempt from the address checks (it usually lives on a private network). If its name resolves to several addresses, each is tried in turn.

## Upgrades

1. Read the release notes of every version you skip: <https://github.com/qnophq/qnop/releases>.
2. Back up the database and the bucket.
3. Bump `QNOP_VERSION` and `docker compose up -d` — Liquibase applies schema migrations forward automatically. Downgrades are not supported; restore from backup instead.

## One-click / template deployments

- **Portainer**: add the raw URL of [`deploy/portainer-templates.json`](../deploy/portainer-templates.json) as an app-template source (*Settings → App Templates*); the template deploys the stack from [`deploy/docker-compose.yml`](../deploy/docker-compose.yml) and prompts for the required secrets.
- **Coolify**: paste [`deploy/coolify/docker-compose.yml`](../deploy/coolify/docker-compose.yml) as a Docker-Compose service. Coolify generates the passwords and auth secrets via its magic variables; only `QNOP_AUTH_ENCRYPTION_SALT` must be set manually (hex — `openssl rand -hex 32`).

Both templates are starting points for a single host — the environment contract above stays authoritative.
