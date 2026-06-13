# ADR-0011: Review workflow state model

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

A review moves through a coordinated workflow: a document is reviewed, reviewers comment, comments are accepted/rejected, changes produce new versions, and the review finalizes when no open annotations remain. Reviewers may be individual users or teams.

## Decision (direction — to be finalized in Phase 1)

Use an **explicit, enum-based state machine** in the service layer (`qnop-core`, `io.qnop.service`), kept as plain DB-free testable logic ([ADR-0004](0004-layered-architecture-enforced-by-archunit.md)), not Spring Statemachine.

Proposed states:

```
DRAFT → IN_REVIEW → CHANGES_REQUESTED → IN_REVIEW (after new version) → FINALIZED
                                                                       ↘ CANCELLED
```

- **Core invariant:** transition to `FINALIZED` is allowed only when there are **zero** annotations in status `OPEN`. This lives as a domain invariant, not in a controller.
- Annotation lifecycle is a small sub-machine: `OPEN → ACCEPTED | REJECTED`.
- Every transition emits an append-only `AuditEvent`.

Model transitions as a transition table + guard functions, applied through a single `transition()` choke-point in the service layer.

## Rationale

The workflow is small and nearly linear; ~150 lines of tested Java is clearer and dependency-free. Spring Statemachine is in maintenance mode and validates against Spring Boot 3.5.x, not 4.x — wrong risk for long-lived core infra. Keep the shape framework-like (guard interface + single choke-point) so a configurable engine could be introduced later (likely as an enterprise feature) if custom workflows are needed.

## Status note

Recorded now because it shapes the domain model. Implementation is deferred to Phase 1.
