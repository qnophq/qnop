# ADR-0050: Purging archived reviews — irreversible deletion with a shared-object guard

- **Status:** Accepted
- **Date:** 2026-07-25
- **Deciders:** devtank42 (with Claude)

## Context

ADR-0011's amendment for issue #576 added an archive flag: a review finalized or cancelled longer than `review.archive_after_days` leaves the active lists but stays a fully readable record. That is a half-lifecycle — cold records accumulate forever, storage costs accrue, and a retention policy that never deletes is not a policy. Issue #577 closes it: after `review.purge_archived_after_days`, an archived review is deleted **permanently and irreversibly**.

This is the first and only code path in qnop that destroys user data with no recovery, which shapes every decision below. Three problems are specific to this product:

1. **Storage keys are shared.** Keys are content-addressed sha-256 and deduplicated instance-wide (ADR-0005/0036). The very same object can back versions or attachments of *other* documents — two teams uploading the identical PDF get one object. Deleting a purged review's keys naively would break surviving documents.
2. **The audit trail dies with the document.** `audit_event.document_id` is `ON DELETE CASCADE`, so a purge erases exactly the record that would explain it. "Where did that review go?" must remain answerable.
3. **The scheduler gate owns one transaction per job** (ADR-0045). A purge that ran inside it would either be one giant transaction — a crash leaving nothing done after minutes of work — or would defer its storage-registry commits to the end of the run, while the objects themselves are already gone.

## Decision

**A dry-run-capable `reviewPurge` scheduler job that ships disabled, deletes one review per transaction, and deletes a storage object only after proving nothing else references it.**

- **Double opt-in.** `review.purge_archived_after_days` defaults to `180` (ADR-0025 machinery, `0` disables, same convention as #576) **and** the job ships **disabled**. Nothing is destroyed until an operator deliberately enables it on `/admin/scheduler`. This required two additions to ADR-0045's catalogue: `SchedulerJobDefinition.enabledByDefault`, honoured by `SchedulerJobBootstrap` when seeding and by the gate wherever a row is missing, and a matching correction to "fail-open" — the gate now falls open to *the catalogue's default*, not to an unconditional yes, because failing open into an irreversible delete is not fail-safe.

- **DB first, storage second — the crash-safety contract.** Each review's aggregate is deleted and **committed**, and only then are its storage keys considered. A crash in between leaves an unreferenced object: harmless, and the storage-consistency scan (ADR-0044) sweeps it. The reverse order would recreate the #575 hole — rows referencing objects that no longer exist.

- **One transaction per review.** A run interrupted halfway leaves reviews either untouched or completely gone, never half-deleted. This is why the job declares `selfTransactional` and the gate invokes it with **no** enclosing transaction. Eligibility is re-checked inside each per-review transaction rather than trusted from the batch read, so a review unarchived between the two is not purged anyway.

- **The shared-key guard.** After the aggregate commit, the purged review's candidate keys are checked against `document_version` and `document_attachment` — reusing ADR-0044's `findVersionRefsByStorageKeyIn` / `findAttachmentRefsByStorageKeyIn` rather than new queries, batched over the whole key set. A key any surviving row references is **left alone**; only the rest go via `StorageService.delete` (object + registry row). Checking *after* the commit is what makes it correct: the purged document's own rows are already gone and cannot make a key look shared.

- **The aggregate delete is the schema's job.** All seven FKs referencing `document` are `ON DELETE CASCADE` (versions, participants, annotations, audit events, visits, version diffs, attachments), and the deeper tables cascade transitively (comments, placements, reactions, mentions). So the DB side is `delete(document)`, verified by `DocumentReviewSchemaIT.cascadesAggregateOnDocumentDelete` — no hand-written deletion order to drift from the schema.

- **One SYSTEM audit event per run**, `review.purged`, carrying the counts plus the purged ids **and titles** (capped at 50, with a `truncated` count). Since the per-document trail is gone, this run-level row is the only surviving trace — titles, not just ids, are what make it useful. Scheduled runs audit here even though ADR-0045 exempts scheduled runs generally: one row per purge run is not noise, it is the record of a destruction.

## Consequences

- **Positive.** The lifecycle is complete and the retention policy real. Deduplicated storage stays correct: a shared object survives the purge of one of its referrers. The double opt-in plus dry-run means an operator can see exactly what would go — including how many objects are genuinely exclusive — before enabling anything. Per-review transactions make a long run safely interruptible.
- **Neutral.** A concurrent upload can re-reference a key between the check and the delete. Accepted rather than locked away: `StorageService.stage` verifies a dedup hit and re-uploads from the buffered bytes when the object is missing (#575), so the race self-heals instead of yielding a key that points at nothing. Locking the whole content-addressed namespace to close a window this narrow is not worth the contention.
- **Negative / deferred.** The `job` queue (ADR-0033) carries document ids inside its jsonb `payload` and has **no** FK to `document`, so a purge cannot be blocked by it — but it also leaves any queued job for a purged review behind. A review archived ≥180 days has no in-flight extraction work in practice, so this is an accepted gap rather than a solved problem; the worker fails and dead-letters if it ever happens. There is also no **manual** "purge now" for owners or admins: #576 shipped manual archive/unarchive because archiving is reversible, and a one-click irreversible destructor deserves its own confirmation UX. Finally, a purge is invisible to participants by design — no notification; the review was archived long ago, and purging is an operator-level lifecycle event.

## Alternatives considered

- **Purge as a workflow state (`PURGED`) rather than a deletion.** Rejected — it is soft deletion under another name and delivers none of the point: the rows and the storage objects stay, so the retention policy still never frees anything.
- **Delete storage objects before the DB aggregate.** Rejected: it recreates the #575 failure mode (rows referencing vanished objects) in the window between the two, and that failure is user-visible (a document that cannot serve its binary) while the opposite leak is invisible and swept automatically.
- **Reference-count storage objects** (a counter on `storage_object`). Rejected as premature: the two `IN`-clause queries answer the same question against the actual referrers, cannot drift from them, and reuse code ADR-0044 already needed. A counter would add a write to every upload path to save two reads on a nightly sweep.
- **One transaction for the whole run.** Rejected: a failure after 199 of 200 reviews would roll back all of them while their storage objects are already deleted — the worst of both orderings.
- **Keeping ADR-0045's gate transaction and using `REQUIRES_NEW` per review.** Rejected: the outer transaction would stay open for the whole run holding a connection, and `StorageService.delete` (a `@Transactional` bean) would join *it* rather than commit per object — so registry rows would commit at the end of the run while objects vanished immediately. The `selfTransactional` opt-out states the intent instead of working around the wrapper.
- **Purging by `closed_at` instead of `archived_at`.** Rejected: it would let a review skip the archive stage entirely if both windows elapsed before a sweep ran. Chaining the purge to `archived_at` guarantees a review is always archived first, so the archive window is a real grace period.
