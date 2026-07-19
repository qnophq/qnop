# ADR-0044: Storage-consistency scan & remediation, and the `StorageProvider.list` SPI extension

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** devtank42 (with Claude)

## Context

Object storage and the database can drift apart. A document version or attachment row can reference a `storage_key` whose object is gone (data loss — the document can no longer be rendered or downloaded), and an object can linger in the bucket that no row references (cost/leak — left over from a crashed ingest, a failed cleanup, or an out-of-band write). The existing orphan reaper (ADR-0036) only sees the *staging registry*: it deletes uploaded-but-uncommitted `storage_object` rows past a grace period. It cannot see a committed-then-orphaned object, because such an object has no registry row at all, nor can it detect a missing binary.

We want an admin dashboard (issue #523, adapted from Plugwerk's `/admin/storage-consistency`) that reconciles the two and lets an admin remediate — without a heavy new architecture and without endangering review integrity.

## Decision

**1. Extend the `StorageProvider` SPI with a streaming `list(prefix)` — as a `default` method.** The orphan direction needs to enumerate the bucket, which the SPI (`put`/`get`/`exists`/`delete`) could not do. `list` returns a lazy `Stream<StorageListing>` (`key`, `size`, `lastModified`). It is declared `default` (throwing `UnsupportedOperationException`), so it is a **backward-compatible SemVer-minor** change: existing implementors — including any commercial add-on — keep compiling unchanged, and a provider that cannot enumerate its store degrades gracefully. The S3/MinIO Community default overrides it via paginated `ListObjectsV2` and, unlike the other operations, does **not** run discovered keys through the content-addressed key guard (issue #337) — the scan's purpose is to find keys that do not match.

**2. The scan is a pure, streamed diff.** `StorageConsistencyService` loads the referenced set — the union of `document_version.storage_key`, `document_attachment.storage_key` and the `storage_object` registry keys (**including PENDING**, so an in-flight upload never looks like an orphan; avatars/branding are Postgres `bytea`, out of scope) — then *streams* the bucket (never materialising it) through a DB-free static `partition()` that yields orphaned objects and missing keys, with a `max-keys-per-scan` **circuit breaker** that aborts a pathological bucket as HTTP **409**. Missing keys are enriched with document context by a second pure mapper. The bucket stream runs outside any transaction so a long scan never pins a DB connection (the ADR-0004 guardrail).

**3. Remediation deletes orphans only, each re-checked in its own transaction.** `POST /admin/storage-consistency/orphans/delete` deletes given keys; each key is re-checked against all three reference sources inside a `REQUIRES_NEW` transaction immediately before deletion, so a key referenced again since the scan (e.g. a new upload reusing the content, which registers a staging row) is **skipped and reported**, never deleted. Idempotent; partial progress persists. **Missing binaries are report-only** — a missing object is data loss the admin resolves through the existing document/review flows (restore from backup, or explicitly delete the document), never through a destructive shortcut here. The endpoints live under `/api/v1/admin/**` (ADMIN, central gate) and use a request body for keys because content-addressed keys contain slashes.

**4. The reaper is extended to committed-namespace orphans, guarded.** Beyond the admin-triggered dashboard, a scheduled `StorageOrphanReaper` reaps bucket-wide orphans automatically — but **opt-in** (`orphanReaperEnabled`, default off), **dry-run by default**, only touching orphans older than a generous grace period (≫ the longest plausible ingest), bounded per tick, holding a ShedLock (ADR-0029), and deleting through the same in-transaction re-check path.

**5. Remediation is audited as a SYSTEM-scope `audit_event`.** A bucket-wide action is not document-scoped, so each deletion is recorded through the `AuditEvent.system(...)` factory introduced by the audit-scope generalisation (issue #524, ADR-0043): `scope = SYSTEM`, `document_id = null`, event type `storage.orphan.deleted` (actor = the admin, or null for the reaper), plus a structured log line. That change made `document_id` nullable and added a `scope` discriminator with a DB check keeping the two in lock-step, so this ADR builds on it rather than touching the schema itself. Such SYSTEM events also surface in the org-wide audit trail (ADR-0042) with no document title — the intended compliance behaviour.

## Consequences

- **Easier:** committed-namespace orphans and missing binaries are finally visible and (for orphans) remediable; the reaper can clean the bucket automatically once an operator opts in; the SPI grows without breaking implementors.
- **Harder / accepted:** the scan lists the whole bucket, so it is an admin/scheduled operation, not a hot path; the in-tx re-check narrows but cannot fully close the race with an external object store (accepted — idempotent, best-effort, matches Plugwerk); a missing binary is surfaced but not auto-repairable here by design.
- **Deferred:** Micrometer counters for the reaper (structured SLF4J for now, matching the existing reaper); CSV/JSON export of the report. (The SYSTEM audit scope this feature needs is no longer deferred — it landed with issue #524 / ADR-0043.)

## Alternatives considered

- **Registry-only reconciliation (no `list`).** Rejected: blind to out-of-band and committed-then-orphaned objects — the honest feature needs a bucket listing.
- **`list` as an abstract method.** Rejected: it would force every implementor (including Enterprise add-ons) to implement it — a breaking, major-style change; the `default` keeps it a true minor.
- **Deleting missing-binary rows (as Plugwerk deletes a release row).** Rejected: annotations anchor to versions (ADR-0009) and the workflow depends on version history (ADR-0011); a `document_version` must never be blindly deleted.
- **SLF4J-only remediation audit.** Rejected in favour of `audit_event` so the actions are queryable and appear in the compliance trail — enabled by the nullable `document_id`.
