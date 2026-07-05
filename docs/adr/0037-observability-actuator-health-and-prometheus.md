# ADR-0037: Observability — Actuator health, job-queue health indicator, Prometheus metrics

- **Status:** Accepted
- **Date:** 2026-07-05
- **Deciders:** qnop core team

## Context

The server already boots with Spring Boot Actuator and exposes `/actuator/health` for container probes, but there was no operational visibility into the durable async job queue (ADR-0033) — the one background subsystem whose failure is silent from the outside. A wedged poller/reaper, a growing backlog, or jobs stranded `RUNNING` by a dead worker would not surface until a review's extraction or re-anchoring never completed. There were also no scrapeable metrics. This is the observability foundation flagged for Phase 2 (issue #348); it deliberately stays small and self-hosted (no external APM), consistent with the deferral posture of ADR-0013.

## Decision

- **Health.** A `JobQueueHealthIndicator` contributes the job queue to `/actuator/health`: `DOWN` when a `RUNNING` job is stranded past the reaper's stale threshold (a dead worker or a wedged poller/reaper), otherwise `UP`, with the per-state depth counts as details. It is **not** wired into the liveness/readiness groups, so a stale-queue `DOWN` is an alerting signal, never a pod-restart trigger.
- **Metrics.** `micrometer-registry-prometheus` (runtime-only) backs `/actuator/prometheus`. A `JobQueueMetrics` `MeterBinder` publishes one `qnop.jobs` gauge per lifecycle `state` (`pending`, `running`, `stale`, `failed`), read live at scrape time.
- **The counts live behind a service.** `JobService.queueStats()` returns a primitive `QueueStats` record; the actuator components (in the `app` layer) never touch the repository or the entity enum, keeping the ArchUnit layering intact.
- **Access.** `/actuator/health` stays public (probes) but reveals details only `when_authorized`; every other management endpoint — the Prometheus scrape included — is `ROLE_ADMIN`-only, so an ops Prometheus scrapes with an admin token. No separate management port is introduced yet.

## Consequences

- Operators get queue depth, staleness, and failure counts both as a health signal and as Prometheus time series, with no new infrastructure.
- Metrics are not anonymously readable; a scrape needs an admin credential (or a future dedicated management port / network policy).
- **Deferred:** extraction/processing latency histograms — they need a metrics seam into the extraction handler in `qnop-core` (a Micrometer dependency there) and are a clean follow-up now that the registry is in place. Tracked as the remaining part of issue #348.
