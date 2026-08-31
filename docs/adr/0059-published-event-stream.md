# ADR-0059: Published review-event stream — a curated catalogue in qnop-spi

- **Status:** Accepted
- **Date:** 2026-08-31
- **Deciders:** devtank42 (with Claude)

## Context

Webhooks are built as an enterprise extension (qnop-ee#18), and an extension attaches only through published seams (ADR-0049). Today no supported way exists for an extension to learn that anything happened (issue #685). Two internal streams looked reusable and are not:

- **`ReviewNotificationSink`** is *recipient-shaped* — the notification service resolves who should hear about an event and offers each recipient to the sinks. A webhook fires once per event, not once per interested human; reusing the sink would mean hand-rolled dedup and duplicate deliveries the first time two participants care about one annotation.
- **The audit trail** is the broadest stream, but its `eventType` strings were written for operators reading a compliance log. Publishing them freezes them; renaming `scheduler.job.dry_run` would become a breaking change.

## Decision

### A curated catalogue, not an exposed internal stream

`qnop-spi` grows a fourth published contract, `io.qnop.spi.event`:

- **`PublishedEventListener`** — `void on(PublishedEvent)`; all registered listeners hear every event.
- **`PublishedEvent`** — `type`, `occurredAt`, `documentId`, `actorId`, `attributes` (string map of identifiers).
- **`PublishedEventTypes`** — the catalogue: eight stable dotted names mapped from the internal `ReviewEvent` hierarchy (`review.annotation.created`, `review.comment.added`, `review.annotation.decided`, `review.annotation.dismissed`, `review.workflow.changed`, `review.version.uploaded`, `review.participant.added`, `review.deleted`).

The catalogue is deliberately short: adding a name is a compatible (minor) change, removing or renaming one is breaking — nothing enters without a consumer. The mapper in the core is exhaustive over the sealed internal hierarchy, so a new internal event is a compile error there: entering the published catalogue is always a deliberate decision, never an accident.

### Identifiers only

A published event says *what happened to which object*. Annotation bodies, document titles and every other piece of customer content stay out — whether a consumer may see the subject is a permission question answered by the API (qnop-ee#19), not by the event stream. The one temptation, `ReviewDeleted.title`, is explicitly dropped in the mapping.

### Isolation is the core's guarantee

`PublishedEventDispatcher` bridges the internal stream: `@TransactionalEventListener(AFTER_COMMIT)` (a rolled-back action is never announced), `@Async` on a dedicated bounded executor (never the request thread), each listener individually wrapped (one that throws costs itself, never its siblings or the action). The executor's full-queue policy is **drop with a warning**, not CallerRuns: the sibling notification executor may fall back to the committing thread for mail, but an extension's slow listener must never be able to slow a request. Delivery is therefore best-effort in-process; a consumer needing durability or retries (the webhook forwarder) queues on its own side — retry, signing and SSRF policy all stay in the extension.

### Beside the sinks, not absorbing them

`ReviewNotificationSink` remains internal and recipient-shaped; the published stream is event-shaped. The two answer different questions ("who should hear, on which channel?" vs "what happened?") and converge on nothing but their source. Absorbing the sinks into the published seam is explicitly not attempted — it would be generalisation ahead of a second consumer (ADR-0049).

## Consequences

- `qnop-spi` publishes four contracts; semver discipline (ADR-0015/0046) applies — the catalogue's growth rides minor versions.
- qnop-ee#18 implements webhooks as one `PublishedEventListener` bean plus its own delivery machinery — no community change.
- #602's live-feed seam can consume the same stream for its event needs; its audience/identity facades remain its own scope.
- Consumers must ignore unknown types — recorded in the listener contract, so catalogue growth cannot break them.

## Alternatives considered

- **Publish the audit trail** — rejected: operator-facing names would freeze; the compliance log is not an API.
- **Reuse `ReviewNotificationSink`** — rejected: recipient-shaped; a channel would have to pretend to be a recipient and dedup by hand.
- **CallerRuns backpressure** — rejected for this seam: it converts a slow extension into slow requests, violating the isolation constraint that motivated the seam.
