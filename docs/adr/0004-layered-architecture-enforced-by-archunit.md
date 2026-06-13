# ADR-0004: Layered architecture with published contracts, enforced by ArchUnit

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team
- **Supersedes:** the initial hexagonal/ports-and-adapters proposal (10 modules)

## Context

The first cut used a strict hexagonal layout (pure domain + application + per-adapter modules with domain↔entity mappers). For most of qnop — a Spring Boot application over PostgreSQL — that is unnecessary ceremony: the double entity mapping and the many modules add boilerplate without a matching payoff. A classic Spring layered architecture (entities / repositories / services) is well understood and faster to build.

Two constraints, however, are **not** matters of architectural taste and must survive any simplification:

1. **Open-core boundary** — commercial add-ons (a business requirement) may only link against a narrow, Spring-free contract, not the whole AGPL application (AGPL §13). See [ADR-0002](0002-open-core-via-polyrepo-and-published-spi.md)/[ADR-0003](0003-agpl-boundary-is-the-spi.md).
2. **Public REST API** — qnop publishes a third-party API; consumers and a generated client must depend on the contract without the server runtime. See [ADR-0015](0015-published-rest-api-contract-module.md).

## Decision

Adopt a **layered architecture in four Gradle modules**:

| Module | Spring? | Role |
|--------|---------|------|
| `qnop-spi` | no | Published plugin contract — extension-point interfaces + DTOs. |
| `qnop-api` | no | Published REST contract — request/response DTOs + OpenAPI. |
| `qnop-core` | yes | `io.qnop.entity` (JPA entities = the model), `io.qnop.repository` (Spring Data), `io.qnop.service` (business logic, workflow state machine, anchoring, API mapping, SPI default beans). |
| `qnop-web` | yes | `io.qnop.web` (`@RestController`s implementing `qnop-api`) + `io.qnop.bootstrap` (Spring Boot main/config). The runnable Community module. |

Dependencies: `qnop-spi`, `qnop-api` ← `qnop-core` ← `qnop-web` (web also → `qnop-api`).

**ArchUnit enforces** the layering (`ArchitectureRulesTest` in `qnop-web`):
- `web → service → repository → entity` only; controllers never reach repositories directly.
- entities never leak to the web layer (the service maps them to API DTOs).
- `qnop-spi` and `qnop-api` stay pure (no Spring, no internal dependencies).

JPA entities are the model — no separate pure-domain model, no domain↔entity mapper. The only mapping is entity ↔ API DTO, done in the service layer.

## Consequences

- Far less boilerplate than full hexagonal; familiar to any Spring developer.
- The open-core and public-API boundaries are preserved (the two published modules).
- **Guardrail (not a module):** the genuinely complex logic — annotation re-anchoring ([ADR-0009](0009-multi-layer-annotation-anchoring.md)) and the workflow state machine ([ADR-0011](0011-review-workflow-state-model.md)) — must be written as plain, DB-free testable units in `io.qnop.service`, not buried in `@Transactional` methods needing a live `EntityManager`. That is where the core differentiator's bugs will hide.
- Coupling the model to JPA is accepted; we are committed to PostgreSQL and do not need persistence portability.

## Alternatives considered

- **Full hexagonal (10 modules, pure domain + mappers)** — rejected: ceremony/boilerplate not justified by qnop's complexity or any portability need.
- **A single module with package layers** — rejected: would merge the published Spring-free contracts with the Spring app, breaking the open-core and public-API boundaries.
