# ADR-0033: Durable async job execution on Postgres

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

The document-review core introduces the first real background work in qnop:

- **Ingest extraction** — turning an uploaded binary into the canonical `RenderedDocument` ([ADR-0032](0032-document-representation-and-rendering-pipeline.md)) is too slow to block the upload response.
- **Re-anchoring** — on a new version, every open annotation is re-resolved against the new text layer ([ADR-0009](0009-multi-layer-annotation-anchoring.md)); we decided this runs **asynchronously** so the version is visible immediately while anchors "catch up".
- **Inter-version diff** — computed off the extracted layers ([ADR-0034](0034-inter-version-diff.md)), potentially large.

These are **data-critical**: losing a re-anchoring job would leave annotations stranded `PENDING` forever. We have PostgreSQL and ShedLock ([ADR-0029](0029-distributed-scheduler-locks.md)); Redis and a message broker are deliberately deferred ([ADR-0013](0013-redis-and-search-deferred.md)).

## Decision

**A durable, Postgres-backed job queue using the transactional-outbox pattern — no Redis, no broker.**

- A `job` table holds work items with `type`, `payload` (jsonb), `status` (`PENDING → RUNNING → DONE | FAILED`), `attempts`, `run_after`, and an optimistic `version`.
- **Enqueue is transactional with the triggering write.** Uploading a version writes the `DocumentVersion` *and* its extraction job in the same DB transaction — the job cannot be lost if the request succeeds, and cannot fire if it rolls back.
- A **worker poll-loop** claims due `PENDING` jobs (`SELECT … FOR UPDATE SKIP LOCKED`), runs the handler, and marks `DONE`/`FAILED` with **retry + capped exponential backoff**; the poller's scheduling is coordinated across instances by **ShedLock** (ADR-0029), so multiple app nodes don't double-run the cron tick.
- **Handlers are idempotent.** A job may run more than once (crash after work, before commit); each handler is written to be safe on replay (e.g. re-anchoring recomputes placements deterministically).
- It is a **generic** facility (`JobType` + a registered handler per type), not re-anchoring-specific — extraction, re-anchoring, and diff are its first three consumers.

## Consequences

- A version upload returns immediately; extraction and re-anchoring complete in the background and surface their results (placement lifecycle, diff cache) when done.
- Jobs survive restarts and crashes — the durability the data-criticality demands; `@Async`/in-memory executors cannot offer this.
- No new infrastructure: reuses Postgres + ShedLock, keeping ADR-0013 intact. The trade-off is more in-house code (table, poller, retry/backoff, idempotency) than an off-the-shelf queue, and polling latency in the seconds range (fine for ingest/re-anchoring; not a low-latency bus).
- The workflow couples cleanly: a version with `PENDING` placements is "not yet decidable", so `FINALIZED` (zero open annotations, [ADR-0011](0011-review-workflow-state-model.md)) is only reachable once its jobs complete.
- When real-time presence arrives (Phase 2) and Redis enters the stack, a broker-backed queue can supersede this for low-latency needs; durable bulk work can stay here.

## Alternatives considered

- **Spring `@Async` + thread pool.** Rejected: in-memory; a crash/restart mid-job loses the work and strands annotations `PENDING` permanently — unacceptable for re-anchoring.
- **Redis / RabbitMQ / Kafka queue.** Rejected for now: pulls a broker into the On-Prem story early, against ADR-0013; Postgres-backed queues are well-proven at this scale (`FOR UPDATE SKIP LOCKED`).
- **Spring Batch / Quartz.** Rejected: heavier than needed; we want a small, explicit, idempotent job table, not a scheduling framework.
