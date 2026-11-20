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

## Amendment (2026-11-20, the reaper claims a row before it deletes the object — issue #987)

The decision above deletes the object before its row, and the reaper ran in the scheduler gate's single transaction. A commit that changed a row between the reaper's read and the end of that transaction made the row delete fail its optimistic-lock check; the transaction rolled back, but the object deletes could not. The result was a `COMMITTED` row whose object was gone. Two refinements close this:

- **Claim, then delete, one orphan per transaction.** The reaper is self-transactional (the mechanism of issue #577). For each candidate it first deletes the row conditionally — still `PENDING` and still older than the cutoff — and deletes the object only if that claim removed the row, while the row lock is still held. A commit or a stage of the same content that arrives meanwhile waits for the lock and then finds no row: `commit` logs it, and `stage` inserts a new row and uploads again. One orphan that fails is rolled back alone and retried on the next run; the run is then reported as failed. `qnop.storage.orphans.reaped` counts only the orphans actually removed.
- **A reused `PENDING` row is restaged.** When `stage()` reuses an existing `PENDING` row (dedup, or a poisoned row from #289), it sets `created_at` to now. For a `PENDING` row, `created_at` is therefore the time it was last staged, and an upload between stage and commit is never older than the grace period because of an older upload of the same content.

What remains is an upload whose own stage and commit lie further apart than the grace period (default 1h). The reaper may take its row; the commit then logs the missing row, as before. The storage-consistency scan (ADR-0044) reports such a reference.

## Amendment (2026-11-20, deleting content an upload is reusing — issue #992)

Committed objects are deleted when the last review that references them is deleted or purged (`ReviewDeletionService`, ADR-0050). The orphan remediation (ADR-0044) also deletes bucket objects. Keys are content-addressed, so an upload of identical content dedups onto the same key. Before this change, such an upload could reuse a `COMMITTED` row whose object a concurrent deletion was about to remove. The deletion looked up references once, then deleted the object and its row with no lock that `stage` or `commit` respected. The upload's new version then pointed at an object that no longer existed. Four refinements close this:

- **A dedup hit is recorded.** When `stage()` reuses a `COMMITTED` row, it sets the new column `storage_object.last_staged_at` to now, in a conditional update. The upload references the key only once its own domain row is written, and this mark is how a deletion learns about it in the meantime.
- **A deletion locks the key, checks again, and defers to a recent stage.** `StorageService.deleteUnlessReferenced(key, isReferenced)` runs in a transaction of its own. It takes the key's lock and the row lock, then asks the caller's reference check again, so a reference committed since the caller first looked keeps the object. If nothing references the key, the outcome depends on the row:
  - A `PENDING` row belongs to the reaper, and is left alone.
  - A `COMMITTED` row staged within the reaper's grace period is handed back to the reaper. It becomes `PENDING`, with `created_at` set to `last_staged_at`. The upload's commit makes it `COMMITTED` again; an upload that never commits leaves it to the reaper.
  - Otherwise the row is removed first, and then the object.

  The plain `StorageService.delete(key)` is gone.
- **A commit locks the row first.** `commit` takes the row lock before its conditional update. A commit that arrives while a deletion hands the row back therefore waits, and then commits the row as `PENDING`. Without the lock, it would judge the row `COMMITTED` from before the hand-back and leave it to the reaper, although the upload's version now references it.
- **A key without a row is guarded by an advisory lock.** A row lock cannot protect a key that has no row, such as a bucket orphan. Inserting a key's first row in `stage`, deleting a key in `deleteUnlessReferenced`, and remediating an orphan each take a transaction-scoped PostgreSQL advisory lock on the key (`pg_advisory_xact_lock(hashtextextended(key, 0))`). A stage that would insert the row waits until the deletion is done, and then uploads the object again.

`stage` now registers in a fixed order: mark a `COMMITTED` row, restage a `PENDING` row, or insert a new one. When the row changes between these steps, it tries again, at most five times.

The limit is the same as for the reaper: an upload whose stage and commit lie further apart than the grace period can still lose a reused object to a deletion.
