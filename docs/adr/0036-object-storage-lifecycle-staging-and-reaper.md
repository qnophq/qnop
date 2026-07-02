# ADR-0036: Object-storage lifecycle — staging registry + orphan reaper

- **Status:** Accepted
- **Date:** 2026-07-02
- **Deciders:** qnop core team

## Context

ADR-0005 puts binary documents in S3-compatible object storage behind a `StorageProvider` SPI, and mandates an "upload-then-commit pattern with a reaper for orphaned objects" — but not *how*. Object storage and PostgreSQL cannot share a transaction: if we upload a blob and then the domain row that references it (e.g. `document_version.storage_key`) fails to commit, the object is orphaned; conversely, deleting the DB row does not delete the object. We need a mechanism that (a) never leaves an object no process can find, and (b) is testable independently of the document domain (this is foundation issue #243, landing before the ingest wiring in #245).

## Decision

- A **`storage_object` staging registry** table records every uploaded object with a lifecycle status: `PENDING` (uploaded, not yet referenced by a committed domain row) → `COMMITTED` (referenced, must be kept).
- **Upload-then-commit** in `StorageService`: `stage()` durably inserts the `PENDING` row **before** the object is put to storage; the caller persists its domain row referencing the returned key and then calls `commit(key)`.
- **Content-addressed keys**: the key is derived from the content SHA-256 (`sha256/<xx>/<full-hex>`), so `object_key` is unique and identical content deduplicates to one object and one row. The hash doubles as `document_version.content_hash`.
- An **orphan reaper** (`@Scheduled` + ShedLock, ADR-0029) deletes `PENDING` rows — and their objects — older than a configurable grace period (`qnop.s3.reaper-grace-period`, default 1h). The object is deleted before its row, so a crash mid-sweep just retries (deletes are idempotent).
- Garbage-collection of **committed** objects (when a version is deleted) is out of scope here and handled with the document domain later.

## Consequences

- No object can outlive knowledge of it: a `PENDING` row exists before the blob, so the reaper always has something to reclaim; a crash after upload leaves a reclaimable row, not an invisible object.
- The reaper is a trivial indexed query (`status = 'PENDING' AND created_at < cutoff`) and never enumerates the bucket or needs to know every consumer table.
- One extra table and one extra write per upload; content-addressing gives free dedup and immutability, aligning with the append-only version model (ADR-0011/0034).

## Alternatives considered

- **Bucket-listing reaper** (no table): list objects and delete those unreferenced by any domain table older than their age. Rejected: couples the reaper to every present and future consumer, scales poorly on large buckets, and is racy without a per-object grace signal.
- **Single-transaction upload** (put inside the DB transaction): impossible to make consistent — a commit failure after the put still orphans the object, with no row to reclaim it.
- **Move-on-commit via a staging prefix** (`staging/…` → final key): avoids a table but adds a server-side copy per commit and still needs an age-based sweep; the registry is simpler and DB-native (consistent with the Postgres job queue, ADR-0033).
