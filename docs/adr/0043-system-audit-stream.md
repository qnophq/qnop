# ADR-0043: System-audit stream — generalising the audit trail beyond documents

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** devtank42 (with Claude)

## Context

The `audit_event` log (issue #244, ADR-0011) has been **strictly per-document** since it shipped: `document_id` is `NOT NULL`, an `ON DELETE CASCADE` foreign key ties every row to a `document`, and the AUDITOR read surface (ADR-0042) reports the org-wide *document*-review trail. ADR-0042 drew an explicit boundary and **deferred** a "future system-audit stream (logins, settings, user admin — out of scope)".

The scheduler-jobs dashboard (issue #524) is the first feature that needs to record **org-level operator actions with no document**: an admin enabling/disabling a maintenance job, overriding its dry-run flag, or triggering a run-now. These are genuine audit events (who, what, old→new state) that belong in the same trail an AUDITOR/ADMIN already reads — but they have no `document_id`, so they cannot be written to `audit_event` as it stood.

Three options were considered (see issue #524 planning): (A) keep the events out of `audit_event`, recording operator state on the `scheduler_job` row only; (B) generalise `audit_event` with a nullable `document_id` and an explicit scope discriminator; (C) stand up a separate `system_audit_event` table and reader.

## Decision

**Option B.** `audit_event` becomes a two-scope trail:

- `document_id` is now **nullable**. The existing `fk_audit_event_document` (`ON DELETE CASCADE`) is unchanged — a null simply never participates in the cascade.
- A new **`scope`** column (`VARCHAR(16)`, `NOT NULL`, default `DOCUMENT`) discriminates `DOCUMENT` (the original per-document trail) from `SYSTEM` (org-level operator actions). The default backfills every pre-existing row correctly. A DB **check constraint** (`ck_audit_event_scope_document`) keeps the two in lock-step: a `DOCUMENT` row must have a document, a `SYSTEM` row must not — the discriminator and the foreign key can never disagree.
- `0011` shipped in v1.0.0, so this is a **new ALTER changeset** (`0016-audit-scope-schema.yaml`), not a fold into the released one.
- `AuditEvent.system(eventType, actorId, detail)` is the factory for `SYSTEM` rows; the existing 4-arg constructor keeps writing `DOCUMENT` rows unchanged, so none of the ~10 existing call sites move.
- The AUDITOR read surface (ADR-0042) is unchanged in shape: the org-wide list already returns every row; it now simply also returns `SYSTEM` rows, each carrying `scope` and a null `documentId`/`documentTitle`. Actor identity still resolves to the **real** display name (the ADR-0038 anonymity bypass for compliance applies identically). The `eventType` for scheduler actions is `scheduler.job.{enabled,dry_run,run_now}`.

## Consequences

- **Positive:** the deferred system-audit stream is now real, with one reader, one table, one exposure model — no parallel infrastructure (rejecting C). Compliance sees operator actions and document actions in one place. Future system events (settings changes, user admin) reuse `AuditEvent.system(...)` with no further schema change.
- **Negative / trade-offs:** `audit_event` is no longer "the document trail" — readers must not assume a non-null `document_id`. The check constraint adds a small write-time cost and must be kept in sync if scopes are ever added. Rejecting A means a schema change on a released table, but A would have split the audit story across two mechanisms, which is exactly what ADR-0042 anticipated folding back together.
- **Follow-up:** the AUDITOR UI (issue #466) may add an explicit scope filter/label; not required for the events to appear.
