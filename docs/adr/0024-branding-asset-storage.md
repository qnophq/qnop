# ADR-0024: Branding-asset storage location

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Epic #7 ports plugwerk's branding domain. Issue #15 introduces three
operator-uploadable branding slots — `logo_light`, `logo_dark`, `logomark`
(`BrandingSlot`) — each holding a single small asset (SVG/PNG/WebP, capped in
the application layer at a few hundred KB). These assets are served on
authentication-free surfaces (login page, OG metadata) and need an
ETag/cache-buster on the read path (the SHA-256 serves both, issue #23).

qnop already provisions S3-compatible object storage (MinIO locally) — but per
the architecture that store is reserved for **binary review documents** (the
large, per-document payloads of the core review workflow). Branding assets are a
different kind of data: a handful of small, config-like blobs that belong with
the rest of the application configuration.

The choice is where the branding bytes live: in object storage alongside review
documents, or in PostgreSQL.

## Decision

Branding bytes live in **PostgreSQL**, in a dedicated `application_asset` table
with a `bytea` `content` column (Liquibase migration `0005`). One row per slot,
enforced by a `(slot)` unique constraint: re-uploading a slot replaces the
current row — the contract is "current asset", not history.

Rationale:

- **Backup/restore atomicity.** Branding is application configuration; keeping it
  in the database means a DB backup is a complete restore, with no second store
  to keep in sync.
- **Separation of concerns.** Object storage stays dedicated to review documents.
  Mixing tiny config blobs into the document store invites lifecycle bugs — most
  concretely an orphan-reaper that GCs any stored object without a matching
  document row would silently delete unreferenced branding files. (plugwerk hit
  exactly this; see its ADR-0037 as prior art.)
- **Small, bounded payloads.** With a low slot count and per-slot size caps,
  `bytea` storage and retrieval is cheap and avoids a network round-trip to the
  object store on the (cacheable) read path.

The Postgres-only invariants — the `slot` and `content_type` CHECK domains, the
`(slot)` uniqueness, and the no-cascade `uploaded_by → qnop_user` foreign key —
live in Liquibase, not JPA annotations (ADR-0020). The `slot` column stores the
snake_case `BrandingSlot.dbValue` via an `AttributeConverter`, keeping the
persisted values aligned with the CHECK domain and with the URL form used in
issue #23.

## Consequences

- A DB backup captures branding; no separate object-storage backup is needed for
  it.
- Large/numerous binary assets must **not** follow this pattern — review
  documents stay in object storage. This decision is scoped to small, bounded,
  config-like branding assets.
- `uploaded_by` does not cascade: a user who uploaded a branding asset cannot be
  deleted until the asset row is reassigned or removed. Acceptable because
  branding rows are few and admin-managed.
- SVG sanitization, size caps, SHA-256/size computation, and the upload/read
  endpoints are deferred to the branding service work (issue #23); #15 is schema
  + entity + repository only.

## Alternatives considered

- **Object storage (MinIO/S3) for branding too.** Rejected: couples branding to
  the review-document store's lifecycle (orphan reaping), and splits application
  configuration across two backup domains for no benefit at this size.
- **Filesystem directory.** Rejected: not horizontally scalable, and reintroduces
  the same backup-split and orphan-lifecycle concerns as object storage.
