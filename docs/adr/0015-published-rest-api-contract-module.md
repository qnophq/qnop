# ADR-0015: Published REST API contract as a separate module (qnop-api)

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

qnop will publish a REST API consumed not only by its own frontend but by **third parties / SaaS integrators**. A public API is a long-lived **stability surface**: consumers depend on its request/response shapes and need a versioned, documented contract they can build against — without taking a dependency on the server runtime. The existing `qnop-web` module is the inbound REST *adapter* (Spring controllers); it is not itself a publishable, consumer-facing artifact.

## Decision

Introduce a dedicated module **`qnop-api`** holding the **REST contract**: request/response DTOs and the OpenAPI definition. It is:

- **Pure types** — no Spring/server dependencies — so external consumers and a generated client (a TypeScript client for the frontend, an SDK for integrators) can depend on it without pulling the server.
- A **published, semantically versioned artifact**, parallel to `qnop-spi`: the SPI is the *plugin* contract, `qnop-api` is the *REST* contract.
- **Implemented by `qnop-web`**: controllers depend on `qnop-api` and map between its DTOs and application types.

ArchUnit enforces that `qnop-api` stays a pure contract (no dependency on Spring or the internal modules) and is consumed only by the service and web layers within the build ([ADR-0004](0004-layered-architecture-enforced-by-archunit.md)).

The public API carries URL versioning (`/api/v1`) and a deprecation policy. **Contract-first** (OpenAPI as the source of truth, generate DTOs/stubs) vs. **code-first** is deferred to Phase 1; the module exists from Phase 0 as an empty shell so the boundary is set before any endpoint is written.

## Consequences

- One versioned source of truth for the FE/BE and external contract; the frontend TS client and an external SDK can be generated from a single OpenAPI spec.
- Clear separation of contract (`qnop-api`, published) vs. implementation (`qnop-web`, internal); the wire format never leaks into the domain.
- One more module + the discipline of treating the API as a stable surface (versioning, deprecation policy).

## Alternatives considered

- **Keep DTOs + OpenAPI inside `qnop-web`** — rejected: ties the consumer-facing contract to the server runtime and makes a clean published/generated client awkward. Acceptable only for an internal-frontend-only API (not our case).
- **A second generic "REST" module beside `qnop-web`** — rejected: would split the same implementation concern and reintroduce naming confusion; `qnop-web` already is the REST adapter.
