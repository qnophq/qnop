# ADR-0055: Bounding out-of-process conversions — per instance, with a bounded wait

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** devtank42 (with Claude)

## Context

Two features convert documents through a LibreOffice subprocess (ADR-0007/0010): the DOCX ingest (#343) renders an upload into the PDF a review is read on, and the annotation export (#639, ADR-0052) renders its Word report to PDF. Nothing capped how many of those subprocesses ran at once.

The two call sites carry very different risk:

- **Export** runs **synchronously in the request thread**. N concurrent export requests start N LibreOffice processes, bounded only by the servlet thread pool. It is authenticated and not free to trigger, but a handful of reviewers exporting a large review at the same moment is an ordinary Monday, not an attack.
- **Ingest** runs on the durable job queue (ADR-0033). `JobQueuePoller.poll()` claims a batch and executes it in a sequential loop under `@Scheduled(fixedDelay)`, so that path is already effectively one conversion at a time per instance.

Each run is deliberately expensive: a fresh `-env:UserInstallation` profile because LibreOffice refuses to start against a profile another instance holds — a cold start every time, not a warm pool — a temp directory written and deleted, and an office suite's worth of resident memory. The existing 60s timeout bounds *one* conversion; it says nothing about how many start.

The failure this invites is not a clean error. It is a container meeting its memory limit, or conversions slowing each other past the deadline so that requests fail which would have succeeded had they simply queued.

## Decision

**A semaphore in front of the converter, sized by `qnop.office.max-concurrent` (default 2), with a bounded wait (`qnop.office.max-wait`, default 30s) after which the conversion is refused.**

### A decorator, not a semaphore inside the converter

`ThrottledOfficeConverter` implements `OfficeConverter` and wraps the real one; `OfficeConverterConfiguration` makes it `@Primary`, so both call sites get the limit by asking for the interface they already asked for. It is unit-testable without an office suite installed — the thing being tested is the gate, not LibreOffice — and the limit holds for any converter this ever runs.

Only conversions queue. `isAvailable()` passes straight through: config, the export-format list and every upload check ask it on ordinary requests that convert nothing, and queueing those behind a conversion would spread one slow export across pages that have nothing to do with it.

The semaphore is **fair**, so a steady stream of exports cannot starve whoever has waited longest.

### Waiting is bounded, and a refusal is transient

Queueing is kinder than failing — right up to the point where a request thread is held so long that the caller has given up. Past `max-wait`, `OfficeConverterBusyException` says so.

It is deliberately **not permanent** (`OfficeConversionException.isPermanent()` stays false), and that single bit is what makes one exception serve both call sites:

- the **export** answers `503 EXPORT_BUSY` with `Retry-After` — nothing is broken, and a 500 would read like a defect;
- an **ingest job** propagates it and comes back under the queue's own backoff, rather than failing a version over one busy minute.

Its own exception type, not a message on the general conversion failure, because the web layer has to tell "too many at once" apart from "the converter failed" and matching on text is not a contract.

### The limit is per instance

A semaphore bounds this JVM. Two instances behind a load balancer may each run their maximum, so a deployment's true ceiling is `max-concurrent × instances` — which is the number an operator must size memory against.

Bounding a *deployment* was considered and rejected as out of scope. It needs shared state; ShedLock (ADR-0029) is a mutual-exclusion lock for scheduled jobs, not a counting semaphore, and would fit badly. Redis is deferred (ADR-0013). Per-instance is the honest limit qnop can enforce today, and it is written down here rather than left implied.

### Zero is not a way to switch it off

`max-concurrent` below 1 reads as "unconfigured" and yields the default. There is no unbounded setting, because unbounded is the failure this exists to prevent. `max-wait: 0s` *is* honoured — an operator may legitimately prefer a fast refusal to a held thread.

### Pressure is visible before it becomes refusals

`qnop.office.conversions{state="active"|"waiting"}` and `qnop.office.conversion.limit` are published as gauges (ADR-0037). Without them, an instance running permanently at its limit is invisible until it starts refusing exports: the queue does its job silently right up to the moment it cannot.

## Consequences

**Positive.** A burst of exports queues instead of starting an unbounded number of office processes, which is the difference between slow and out-of-memory. The expensive path stays bounded without any call site knowing. One transient exception yields the right behaviour in both places: 503 for a human waiting on a download, a retry for a job that has time.

**Negative.** An export can now be refused where it previously would have been served (slowly) — the trade the bounded wait makes deliberately. A conversion may wait up to `max-wait` while holding a request thread. The default of 2 is a starting point, not a measurement.

**Neutral.** The ingest path barely changes, since the poller already serialized it; the limit is insurance for the day that loop becomes concurrent.

## Not decided here

**Making the export asynchronous** — a job plus a download-when-ready flow. It may well be the better answer eventually, and it is a different discussion from bounding the process count.

## Related

- **ADR-0007** — copyleft tools out-of-process only; why this is a subprocess at all
- **ADR-0010** — DOCX representation via conversion, the other call site
- **ADR-0052** — the annotation export, whose PDF format is the exposed path
- **ADR-0033** — the durable job queue whose backoff absorbs a transient refusal
- **ADR-0029** — ShedLock, and why it does not extend this limit across instances
- **ADR-0037** — the metrics surface the gauges join
