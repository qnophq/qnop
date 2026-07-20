# ADR-0045: Scheduler-jobs dashboard — an operator gate in front of the maintenance sweeps

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** devtank42 (with Claude)

## Context

qnop runs five `@Scheduled` maintenance sweeps, each fronted by a ShedLock `@SchedulerLock` (ADR-0029): the e-mail-verification, password-reset, refresh-token and revoked-access-token purges, and the storage orphan reaper (ADR-0036). Until now these were fire-and-forget: no operator could see whether they run, when they last ran, or pause one that misbehaves — and no one could trigger a purge on demand. Issue #524 asks for an admin dashboard at `/admin/scheduler` that lists the sweeps with their cron and last-run outcome and offers three controls: enable/disable, a dry-run toggle (for the reaper), and run-now.

The two internal poller/reaper jobs of the durable job queue (ADR-0033) are **deliberately excluded** — they are engine internals, not operator-facing maintenance. So the catalogue is exactly those five sweeps.

Three design questions drove the decision:

1. **Where does per-job operator state live?** The cron, display name and dry-run capability are static (code); the enabled flag, dry-run setting and last-run outcome are mutable (an admin changes them, a run produces them).
2. **How do the sweeps route through the operator state without a Spring self-invocation trap** — the classic problem where an in-bean call bypasses the `@Transactional`/gate proxy?
3. **How are operator actions audited** without drowning the trail?

## Decision

**A `SchedulerService` gate, a `scheduler_job` row per catalogued job, and a static `SchedulerJobCatalog`.**

- **Static vs. mutable split.** `SchedulerJobCatalog` holds the five `SchedulerJobDefinition`s (id, display name, description, cron, dry-run capability) as a closed, in-code set. The `scheduler_job` table (migration 0017) holds only what changes: `enabled`, `dry_run`, and the last run's outcome (`last_run_at`, `last_outcome`, `last_trigger`, `last_detail`). The primary key is the natural `jobId` — the same string used as the `@SchedulerLock` name — so the id is a single source of truth: the catalogue constants double as the compile-time-constant lock names on the sweep methods. Rows are seeded idempotently at start-up (`SchedulerJobBootstrap`); an existing row's settings survive a restart.

- **The gate owns transactions programmatically, not with `@Transactional`.** Each sweep's `@Scheduled`/`@SchedulerLock` method now just calls `schedulerService.runScheduled(jobId)`. The gate reads the operator state, runs the work, and records the outcome as **three independent units** via a `TransactionTemplate`. The unit of work is a `SchedulerWork` runnable **registered by the owning service** (`SchedulerJobBinding`) and invoked inside the gate's own transaction — so there is no self-invocation caveat (the runnable is called from a different bean), and `SchedulerService` depends on no sweep service (no dependency cycle). Recording the outcome in a **separate** transaction from the work means a work rollback can never erase a failure record.

- **Fail-open.** If the state read throws (a broken `scheduler_job` table, a transient DB error), the gate runs the sweep anyway. Maintenance keeps the system healthy; a stuck control table must never silently stop it.

- **Run-now is an explicit override.** `runNow` runs the sweep regardless of `enabled`, under the job's ShedLock lock (acquired programmatically) so it never overlaps a scheduled run or a second manual trigger — a contended lock is a `409`. A work failure is reported as a `FAILURE` outcome on the returned job, not a 5xx: the admin sees what happened rather than a stack trace.

- **Audit only operator actions.** Setting changes (`scheduler.job.updated`) and manual runs (`scheduler.job.run`) are written to the SYSTEM audit stream (ADR-0043). Scheduled runs are **not** audited — five rows a day forever would drown the trail — their outcome lives on the `scheduler_job` row instead.

Authorization is the existing central rule: `/api/v1/admin/**` requires `ADMIN`. The frontend adds an `/admin/scheduler` page (a job card per sweep: cron chip, enabled switch, dry-run switch for the reaper, run-now, and a last-run outcome badge).

## Consequences

- **Positive.** Operators get visibility and control with no new infrastructure (no Redis, no scheduler engine) — one Postgres table and a thin gate. The static/mutable split keeps the catalogue in code (type-safe, no migration to add a job's metadata) while settings persist. The transaction discipline keeps the complex logic DB-free-testable (the guardrail): `SchedulerServiceTest` exercises enable/disable, fail-open, dry-run guard, locking and audit with a mock transaction manager and no database.
- **Neutral.** The dashboard shows each job's **default** cron; an operator who overrides `qnop.s3.reaper-cron` sees the default string, not the override. Acceptable — the cron is informational, and the effective-configuration page (issue #522) is the authority on overrides.
- **Negative / deferred.** There is no per-job run **history** (only the most recent outcome) and no scheduled-run auditing; if compliance later needs a full run log, it is an additive table, not a redesign. Editing a cron from the UI is out of scope — crons stay in configuration.

## Alternatives considered

- **`@Transactional` on the gate + `REQUIRES_NEW` for the outcome.** Rejected: the sweep methods calling their own gate would hit the Spring self-invocation trap, and `REQUIRES_NEW` self-calls silently don't start a new transaction. The programmatic `TransactionTemplate` sidesteps both.
- **A row (or setting) per job with the cron stored in the DB.** Rejected as premature: crons are deployment configuration, not per-instance operator state; storing them invites drift from the `@Scheduled` annotations that actually fire.
- **Auditing every scheduled run.** Rejected: it floods the AUDITOR trail with routine noise; the `scheduler_job` row already carries the last outcome, and only operator-initiated actions are audit-worthy.
