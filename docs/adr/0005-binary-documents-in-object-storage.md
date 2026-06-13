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
