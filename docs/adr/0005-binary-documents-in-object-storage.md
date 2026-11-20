# ADR-0005: Binary documents in S3-compatible object storage

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

qnop stores uploaded documents (PDF, DOCX) and their immutable versions. Structured data (metadata, reviews, annotations, workflow state) belongs in PostgreSQL, but binary blobs do not scale well there. We also need On-Prem (AGPL self-host) and SaaS deployments to differ only by configuration.

## Decision

- **PostgreSQL** holds relational data only: users, teams, documents (metadata), document versions (metadata + storage reference), reviews, annotations (anchor payload as `jsonb`), workflow state, audit log.
- **Binary documents live in S3-compatible object storage**, never as Postgres `bytea`/large objects.
- Access goes through a `StorageProvider` SPI; the default implementation (in `qnop-core`, `io.qnop.service`) uses the **AWS SDK for Java v2** with `endpointOverride` + path-style, so the backend (MinIO On-Prem, S3/GCS in SaaS) is a deploy-time config choice.
- File↔metadata consistency uses an upload-then-commit pattern with a reaper for orphaned objects.

## Consequences

- DB stays lean; backups and restores stay fast; documents stream natively (range requests for the viewer).
- One extra moving part (object store) in local dev — provided via `docker-compose.yml` (MinIO).
- No multi-cloud abstraction is built (YAGNI); the S3 API is the abstraction.

## Alternatives considered

- **Postgres BLOBs** — rejected: bloats the DB, slow backups, awkward streaming.
- **A bespoke storage abstraction over multiple clouds** — rejected: the S3 API is already the de-facto standard; MinIO/SeaweedFS/S3 all speak it.

## Amendment (2026-11-20, failures and retries of the storage SPI — issue #983)

The S3 adapter translated only the errors the service answered (`S3Exception`). A client-side failure, such as a refused or reset connection, escaped as the SDK's own exception, which broke the SPI's promise that every failure surfaces as `StorageException`. An upload also could not be retried: it was handed to the SDK as a one-shot stream, so the SDK's retry of a dropped attempt failed with an `IllegalStateException`, and that exception hid the real failure. Three refinements fix this, all of them additive (SemVer minor) for `qnop-spi`:

- **`ContentSource` and `StorageProvider.putReopenable`.** `ContentSource` is content that can be opened again, from its first byte. The new `default` method `putReopenable` opens it once and delegates to the stream variant of `put`, so an existing provider keeps working unchanged. A provider that retries overrides it. The core stores every upload this way, because it buffers each upload to a temporary file anyway. The S3 adapter hands the SDK a provider that opens the buffer again for each attempt, so a retry sends the whole content once more. The method has its own name rather than overloading `put`, so that callers and tests using `put(…, any(), …)` still compile without ambiguity.
- **`StorageUnavailableException`, a subtype of `StorageException`.** It means the store could not be reached, or failed on its own side; retrying later may succeed. The S3 adapter now translates every `SdkException`, including failures that occur while a listing is being consumed. A client-side failure, a 5xx answer and throttling become `StorageUnavailableException`. Any other answer, such as a refused or malformed request, stays a plain `StorageException`.
- **The web layer answers an unavailable store with 503.** The response is the standard `ErrorResponse` with code `STORAGE_UNAVAILABLE`, instead of the catch-all 500. The server itself works, and the same request may succeed once the store is back; clients and load balancers can act on that. The log line names the root cause without a stack trace. Any other `StorageException` remains a 500.

A provider that does not throw the subtype loses nothing: its failures stay 500s, as before. The stream variant of `put` still cannot be retried, and says so in its documentation.

Two consequences for code built against the SPI. Signatures stay compatible, but behaviour changes:

- The core now uploads through `putReopenable`. A test that mocks `StorageProvider` and verifies `put` no longer sees the core's uploads, because a mock does not run the default method. Such a test must verify `putReopenable` instead.
- A provider that wraps another one, for example to measure or log its calls, must forward `putReopenable`. Otherwise the default routes the content through the stream variant, and the wrapped provider silently loses its retry.
