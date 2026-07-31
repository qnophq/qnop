# ADR-0054: Logging and the diagnostic context

- **Status:** Accepted
- **Date:** 2026-07-31
- **Deciders:** qnop core team; bigpuritz, devtank42 (with Claude)

## Context

A user reports that something failed yesterday afternoon. Before this decision there was no way to find their request: no request id, no user attribution, and nothing tying an async job back to what caused it. Measured on `main` at the time: 98 log statements across 35 files out of 187 service classes, **no** logging configuration at all, and **zero** MDC usage.

Two facts made this worse than the numbers suggest. Boot's default console pattern prints no MDC, so populating one without touching the format would have been invisible work. And personal data was already in the logs — `ReviewDeletionService` wrote the review *title*, which is user content and routinely carries a name.

Logs are also the one place in this system with no access control, no retention policy and no subject-access route. Whatever lands there is outside every mechanism the rest of the product uses to protect it.

## Decision

### The context carries ids, and the pattern prints them

`LogContext` owns the MDC keys: `requestId`, `userId`, `method`, `path`, `documentId`, `jobId`, `jobType`. `logging.pattern.correlation` renders the three that earn their width on every line; the rest are there for structured output.

Values are set at the **edges**, not sprinkled through the code:

- `RequestLogContextFilter` mints the request id, before Spring Security so a rejected login still has one, and returns it as `X-Request-Id` so a user can quote a number.
- `UserLogContextFilter` adds the caller, after Security so there is one to add. Two filters rather than one, because no single filter can be on both sides of authentication.
- `DocumentLogContextInterceptor` adds the review from the parsed URI template — one place covering every `/documents/{documentId}/…` route, including the ones added later.
- The job poller and `JobService` scope `jobId` and `jobType`, because async work has no request behind it and would otherwise log unattributed.

### Output is text by default and structured on demand

`logging.structured.format.console` is empty for a developer at a terminal and set to `ecs` (or `logstash`/`gelf`) in a deployment, where every line becomes one JSON object with the MDC as fields. Boot ships this; no encoder dependency enters the build.

### No personal data. Ever.

Ids yes; names, e-mail addresses, review titles, annotation text, file names and slugs no. A UUID is still personal data, but it is pseudonymous: resolving it requires database access someone has to be entitled to, and a leaked log file does not read as a list of people. Free text offers none of that, because whatever a user typed is whatever a user typed.

Client IP addresses are not logged either. They are personal data, and "reconstruct this failure" does not need them.

`LogPrivacyTest` enforces this by scanning the sources for log calls whose arguments reach a user-entered accessor. Deliberately a source scan and not an ArchUnit rule: ArchUnit sees that a class calls `getTitle()` and that it calls `Logger.info()`, never that the first is an argument to the second — the data flow is invisible in the dependency model.

**The scan is a net, not a proof.** It was written against accessors and therefore did not see `MailService` logging its recipient, because the address was already in a local named `to` — found by reading the code, not by the guard. Unambiguous identifier names are now checked too (`email`, `displayName`, `fileName`, …); `to` is deliberately absent, because a version diff legitimately logs a `from`/`to` pair of numbers. A name-shaped check cannot be complete, so review still matters.

### Failures are logged where the context still exists

An exception nobody handled used to leave the dispatcher, unwind the filter chain — which clears the MDC in its `finally` — and only then reach the servlet container, which wrote the stack trace. The single most important line in the system therefore arrived correlated to nothing. `UnhandledExceptionHandler` catches it inside the chain, logs it at `ERROR` with the trace, and returns the standard envelope with a generic message; internals are for the log, not the caller.

It is ordered `LOWEST_PRECEDENCE` because `Exception` matches everything: at default precedence it would shadow the specific handlers and turn every 403 and 404 into a 500. What Spring Security and Spring MVC own — `AccessDeniedException`, `AuthenticationException`, anything implementing Spring's `ErrorResponse` — is rethrown rather than swallowed.

The same reasoning applies out-of-process: the office converter discarded its own output, so a failed DOCX conversion reported an exit status and nothing about what LibreOffice objected to. Its output is now captured (bounded, drained on its own thread so a chatty converter cannot deadlock on a full pipe) and logged when the conversion fails.

Object storage keeps throwing `StorageException` with the key rather than logging and rethrowing: log-and-throw duplicates every failure, and the handler above now records it with the request context attached.

### What is logged, and what is not

**Logged:** authentication outcomes and token-rotation rejections; permission denials; job start, completion with duration, and failure; external I/O (object storage, SMTP, the office converter); state transitions and destructive acts; and every `catch` that discards a genuine failure.

**Not logged:** ordinary reads, mappers, getters, validation that merely returns a message to the caller, and per-tick chatter from pollers. A poller that says "nothing to do" every five seconds costs more attention than it repays.

Levels mean something: `ERROR` needs a human tonight; `WARN` is a failure the system handled but somebody should see; `INFO` is a business event worth reconstructing later; `DEBUG` is for chasing one specific problem and is off in production.

### DEBUG is verbose, and one aspect rather than a thousand lines

`MethodTraceAspect` traces every public method of an `@Service` bean: the call with its arguments, the return with its shape and duration, and the frame an exception left through. Written by hand this would be a log statement in several hundred methods, drifting from the first refactor onwards; a pointcut cannot drift.

Scoped to the `@Service` stereotype rather than to the package, and not by preference — `@ConfigurationProperties` records live in `io.qnop.service` too, a record is final, and matching one makes Spring try to CGLIB-subclass it. That fails at startup, which is how it was found.

Its loggers are named `io.qnop.trace.<SimpleName>`, so tracing switches independently of `io.qnop`: everything at once, or one class when a single flow is in question. The layers underneath — Hibernate SQL, Spring Security, Spring web, transactions — each have their own property and are off by default, because each alone can turn one request into dozens of lines.

**Raising the level must not start writing personal data.** Arguments are therefore described, not printed: ids, numbers, booleans, enums and timestamps as themselves; a `String` as its length; anything else as its type and size. The cost is real — a job name is hidden along with a review title — and it is accepted because no shape rule separates them: `review-purge` and `max-mustermann` are the same string to a regex. `MethodTraceAspectIT` traces `getProfileBySlug` for exactly that reason: a slug is derived from a display name, so an aspect that printed arguments would leak one the moment DEBUG went on.

The MDC's `path` follows the same rule: the matched route template (`/api/v1/documents/{documentId}`), not the URI a caller typed. It says as much about where a request went, groups the lines that belong together, and cannot carry the slug. Only an unmatched request falls back to the raw path — a 404 that cannot be identified is not worth logging, and a path matching nothing is a probe rather than one of our users.

## Consequences

- A support report can be answered from a request id, across the async work it triggered.
- Structured output is a property change, not a code change.
- The privacy rule is enforced continuously rather than reviewed occasionally, and it fails with the file and the offending expression named.
- Log volume stays proportional because the policy says what stays silent, not only what does not.
- **Thread pools are the sharp edge.** A context left behind attributes the next request — a different person — to whoever came before, which is a false record in the very file someone later reads as evidence. Hence `LogContext.Scope` restoring rather than clearing, the filter wiping the whole MDC in `finally`, and tests that assert both.

## Alternatives considered

- **JSON everywhere, including locally.** Rejected: one format is tidier, but a developer reading JSON on a terminal reads less, and the point of this work is that failures get read.
- **Logging every service method's entry and exit at INFO.** Rejected: it is the literal reading of "comprehensive coverage" and produces a log in which the one relevant line is unfindable. The same tracing at DEBUG, switchable per class, is useful precisely because it is off by default.
- **Hand-written DEBUG lines instead of the aspect.** Rejected: several hundred edits, each one a thing to forget in the next refactor, and every one of them an opportunity to pass an argument that should not be logged.
- **Printing argument values at DEBUG**, on the grounds that DEBUG is a developer's level. Rejected: the file it writes to has the same absence of access control as any other, and "we only turn it on when something is wrong" describes exactly the moment when the most data flows through.
- **Logging no ids at all**, the strictest privacy reading. Rejected: it satisfies any audit and makes the logs useless for the purpose they are being built for. Pseudonymous ids with the resolution behind access control is the proportionate answer.
- **An ArchUnit rule for the privacy check.** Rejected as unable to express it — see above. The source scan is cruder and actually detects the thing.
- **Micrometer tracing with propagated trace ids.** Deferred, not rejected: it is the right answer once there is a second service to trace into. Today the request id does the same job without the dependency, and `X-Request-Id` is honoured on the way in so an edge proxy can already stitch calls together.

## Relationships

Completes the observability picture of [ADR-0037](0037-observability-actuator-health-and-prometheus.md) (health, metrics — logging was decided nowhere). The privacy rule shares its reasoning with [ADR-0038](0038-per-review-privacy.md) (identities are resolved server-side, never assumed safe to expose) and with [ADR-0042](0042-audit-trail-exposure.md), which is the place where who-did-what *is* recorded with names, behind access control — a log file is not that place. Related issue: #659.
