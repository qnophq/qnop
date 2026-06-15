# ADR-0029: Distributed scheduler locks for @Scheduled jobs (ShedLock)

- **Status:** Accepted
- **Date:** 2026-06-16
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Several maintenance jobs run via Spring `@Scheduled`: the email-verification,
password-reset, refresh-token (#43) and revoked-token (#43) cleanup sweeps. Spring
`@Scheduled` fires **per application instance** with no cross-instance
coordination — in a multi-instance deployment every node runs each job
concurrently.

Today all four jobs are idempotent bulk `DELETE … WHERE expires_at < now()`, so
concurrent execution is correct but redundant (wasted DB load, row-lock
contention). That changes the moment a **non-idempotent** scheduled job lands
(e.g. mail digests, billing, report generation) — those would be duplicated once
per instance. The current deployment target is single-node (ADR-0013 defers Redis
and horizontal scaling), so this is not an active defect, but coordination must be
in place **before** scaling out or adding the first side-effecting scheduled job.

## Decision

Adopt **ShedLock** to guarantee each scheduled job runs **at most once per tick
across all instances**, backed by the **existing PostgreSQL** via
`JdbcTemplateLockProvider` (no Redis, consistent with ADR-0013).

- ShedLock **7.x** — the line tested against Spring 7.0 / Spring Boot 4.x, JVM 17+.
- A `shedlock` table (Liquibase migration `0006`) holds one row per named job.
- `SchedulingConfiguration` (qnop-app) declares the `LockProvider` with
  `usingDbTime()` so the **database clock is authoritative** (no inter-node skew).
- `@EnableSchedulerLock(interceptMode = PROXY_METHOD)` — wraps the annotated bean
  methods so the lock applies uniformly (including direct invocation).
- Each sweep carries `@SchedulerLock(name = …, lockAtMostFor = "PT5M")`. The
  `@SchedulerLock` annotation lives in `qnop-core` (on the service methods); the
  `LockProvider` that makes it real is wired in `qnop-app`.

`lockAtMostFor` is a safety net that releases the lock if an instance dies
mid-job; `lockAtLeastFor` stays at the default `PT0S` because the jobs are
idempotent, so there is no harm in a fast job releasing early.

## Consequences

- Scheduled jobs are safe under horizontal scaling, and the codebase is ready for
  non-idempotent scheduled work without a duplicate-execution footgun.
- One small dependency (ShedLock) and one infrastructure table; no new runtime
  service (reuses PostgreSQL).
- New scheduled jobs MUST add `@SchedulerLock` with a unique `name` to be
  coordinated — an unannotated `@Scheduled` method still runs per instance. This
  is a convention to enforce in review.
- `usingDbTime()` requires the lock provider to read DB time; negligible overhead
  on a once-daily job.

## Alternatives considered

- **PostgreSQL advisory locks** (`pg_try_advisory_lock`). Zero dependencies and
  Postgres-native, but hand-rolled: no lock metadata/visibility, manual key
  management, and no framework integration with `@Scheduled`. ShedLock gives the
  same guarantee with a documented annotation model and is easy to extend to many
  jobs.
- **Redis / Redisson lock.** Rejected: pulls in Redis, which ADR-0013 explicitly
  defers; PostgreSQL already satisfies the requirement.
- **Externalising jobs** (k8s CronJob / a designated leader instance). Cleaner
  separation but adds operational surface and couples cleanup to the deployment
  platform; revisit only if scheduled work grows substantially.
- **Leaving it (idempotent + single-node).** Correct today but a latent footgun
  for the first non-idempotent job and for scaling; the chosen option removes the
  hazard cheaply.
