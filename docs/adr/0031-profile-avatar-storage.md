# ADR-0031: Profile-avatar storage location

- **Status:** Accepted
- **Date:** 2026-06-26
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Issue #117 adds per-user **profile pictures** (avatars): a user uploads/replaces/
removes their own, and an admin can do the same for any user. The initials
avatar (`UserAvatar`) remains the fallback when none is set.

Two existing ADRs bracket the choice of where the bytes live:

- **ADR-0005** sends **binary review documents** to S3-compatible object storage
  through a `StorageProvider` SPI (AWS SDK v2, `endpointOverride` + path-style),
  with an upload-then-commit pattern and an orphan reaper. That seam is
  **mandated but entirely unbuilt** — no SDK dependency is wired, no client bean,
  no `qnop.s3.*` configuration, no bucket provisioning, no reaper. MinIO runs in
  local `docker-compose` but the application never talks to it.
- **ADR-0024** keeps **branding assets** (a handful of small, config-like logo
  blobs) in PostgreSQL `bytea` in `application_asset`, deliberately *out* of
  object storage to keep backups atomic and avoid the orphan-reaper lifecycle. It
  explicitly scopes itself to "small, bounded, config-like" assets and warns that
  "large/numerous binary assets must **not** follow this pattern."

Avatars sit between the two: **small and bounded** like branding, but **one per
user and user-generated** ("numerous") rather than a fixed set of config slots.
Neither ADR covers them, so #117 forces a conscious decision.

## Decision

**Profile-avatar bytes live in PostgreSQL `bytea`, in a dedicated `user_avatar`
table (one row per user), reached through a small `AvatarStorage` port.**

- `user_avatar` has `user_id` as primary key and a foreign key to `qnop_user`
  with **`ON DELETE CASCADE`** — deleting a user atomically deletes their avatar,
  so there is **no orphan-reaper concern** (the very lifecycle problem ADR-0024
  cites against object storage). The row also carries `content_type`, `content`
  (`bytea`), `sha256` (HTTP ETag / cache-buster), `size_bytes`, `width`/`height`,
  and `updated_at`/`updated_by` audit columns. Postgres-only `CHECK` domains and
  the FK live in Liquibase (migration `0009`), not JPA annotations (ADR-0020).
- All access goes through an **`AvatarStorage`** interface in `qnop-core`
  (`io.qnop.service.avatar`) with a single `PostgresAvatarStorage` implementation.
  The rest of the backend (validation, endpoints, DTOs) and the entire frontend
  depend only on the port, so the storage backend is swappable without touching
  them.
- A hard **1 MiB** size cap and a **PNG/JPEG/WebP** allow-list (content sniffed
  from magic bytes, **SVG excluded** as an avatar XSS surface), reusing the
  branding validation toolkit (`ImageDimensions`, magic-byte sniffing).

Rationale:

- **Bounded and per-user, not unbounded.** One small, replaceable image per user
  (current-only, no history) with a hard 1 MiB cap is closer to branding than to
  versioned review documents. At realistic user counts this is tens of MB of
  `bytea`, which Postgres handles comfortably.
- **Lifecycle simplicity.** The `ON DELETE CASCADE` FK removes the avatar with
  the user — none of ADR-0005's upload-then-commit/reaper machinery is needed.
- **Backup atomicity & no new infrastructure.** Avatars are captured by a DB
  backup; shipping #117 needs no S3 wiring, bucket provisioning, or MinIO
  integration in CI. It reuses the proven, tested branding upload pipeline.
- **Right-sized scope.** Building the full ADR-0005 `StorageProvider` SPI for a
  ~50 KB avatar is premature; that seam is better lit up by its actual driver —
  the PDF/document vertical slice — which needs streaming, range requests, and
  large payloads.

## Consequences

- A DB backup captures avatars; no separate object-storage backup is needed.
- The `AvatarStorage` port is the single seam to re-home avatars in object
  storage later (a second adapter) once ADR-0005's `StorageProvider` SPI is built
  for review documents — without changing endpoints, DTOs, or the frontend.
- `updated_by` is a plain audit column (no FK): an admin who set another user's
  avatar can still be deleted; the breadcrumb is best-effort.
- The read path `GET /api/v1/users/{id}/avatar` is **public** (`permitAll`), like
  branding — an `<img>` element cannot attach a bearer token. Avatars are
  low-sensitivity display pictures; the URL carries a `?v=<token>` cache-buster
  and serves `byte[]` with an ETag/304. This does not extend ADR-0005's stance on
  document access, which stays authenticated.
- Server-side downscaling is not introduced now: the client crops to a canonical
  square before upload, and the backend bounds dimensions and caps size. True
  server-side thumbnailing (and WebP decode, which `ImageIO` lacks) is deferred.

## Alternatives considered

- **Build the ADR-0005 `StorageProvider` SPI + S3 now, avatars as first
  consumer.** Architecturally the long-term home for per-user binaries and it
  would de-risk the document slice, but it is a large unbuilt scope (SPI +
  Community default, bucket provisioning, upload-then-commit + reaper, presigned
  URLs, MinIO in CI) for a tiny avatar. Deferred; the `AvatarStorage` port keeps
  the migration path open.
- **A `bytea` column on `qnop_user`.** Rejected: it would load avatar bytes on
  every user query that hydrates the entity; a dedicated 1:1 table keeps the hot
  user row lean.
- **Reuse `application_asset` (branding's table).** Rejected: its schema is
  slot-keyed and config-scoped; per-user avatars want a `user_id`-keyed table
  with a cascading FK.
