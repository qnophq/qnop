# ADR-0011: Review workflow & domain model

- **Status:** Accepted
- **Date:** 2026-06-13 (finalized 2026-06-28)
- **Deciders:** qnop core team; finalized by bigpuritz, devtank42 (with Claude)

## Context

A review moves through a coordinated workflow: a document is reviewed, reviewers comment, comments are accepted/rejected, changes produce new versions, and the review finalizes when no open annotations remain. Reviewers may be individual users or whole teams. Phase 0 fixed the *state machine* direction; this finalization also pins the **domain model** it operates on (document/version/participant/annotation), since they are inseparable.

## Decision

### Domain model

- **Review = not editing.** qnop reviews documents; it does not edit them. There is no in-app editor — the viewer is read-only plus an annotation layer. A new version is created **only by the owner re-uploading** a revised document (changes were applied externally), matching "Qualified Notes *on* Papers".
- **Document = Review (1:1 aggregate).** The `Document` is the aggregate root: it carries metadata, the workflow state, the participants, the annotations, and an ordered list of **immutable `DocumentVersion`s** (each an uploaded binary + its `RenderedDocument`, [ADR-0032](0032-document-representation-and-rendering-pipeline.md)). There is no separate long-lived "document shared across multiple reviews" — a fresh review of the same paper is a new `Document`. (A shared-document, multi-cycle history is a future additive enterprise concern, not a rebuild.)
- **`ReviewParticipant`** is its own relation: `{ documentId, principal = user | team, role = OWNER | REVIEWER }`. Reviewers can be individuals or teams; the owner uploads versions and decides annotation outcomes.
- **Annotation = a mark + a comment thread.** An `Annotation` is the version-independent anchored mark ([ADR-0009](0009-multi-layer-annotation-anchoring.md)) carrying status `OPEN | ACCEPTED | REJECTED`; it owns a `Comment[]` thread (the discussion). Status lives on the annotation (the owner's decision), not on individual comments.

### Workflow state machine

An **explicit, enum-based state machine** in the service layer (`qnop-core`, `io.qnop.service`), kept as plain DB-free testable logic ([ADR-0004](0004-layered-architecture-enforced-by-archunit.md)) — not Spring Statemachine. Modeled as a transition table + guard functions applied through a single `transition()` choke-point.

```
DRAFT → IN_REVIEW → CHANGES_REQUESTED → IN_REVIEW (after new version) → FINALIZED
                                                                       ↘ CANCELLED
```

- **Core invariant:** transition to `FINALIZED` is allowed only when there are **zero** annotations in status `OPEN` — *and* no `AnnotationPlacement` is still `PENDING` (re-anchoring must have completed, [ADR-0033](0033-durable-async-job-execution-on-postgres.md)). This is a domain invariant, not a controller check.
- Annotation lifecycle is a small sub-machine: `OPEN → ACCEPTED | REJECTED` (owner-decided), which drives `IN_REVIEW → CHANGES_REQUESTED`.
- Every transition emits an append-only `AuditEvent`.

## Rationale

The workflow is small and nearly linear; ~150 lines of tested Java is clearer and dependency-free. Spring Statemachine is in maintenance mode and validates against Spring Boot 3.5.x, not 4.x — wrong risk for long-lived core infra. Keeping the shape framework-like (guard interface + single choke-point) leaves room for a configurable engine later (likely enterprise) if custom workflows are needed.

## Consequences

- The model is schema-shaping: `Document`, `DocumentVersion`, `ReviewParticipant`, `Annotation`, `Comment`, `AnnotationPlacement`, `AuditEvent`.
- Versioning is dead simple (re-upload), and the diff ([ADR-0034](0034-inter-version-diff.md)) compares two uploaded versions, not an edit history.
- Team-as-reviewer needs a principal abstraction in participants and in annotation authorship/permissions.
- The `FINALIZED` precondition ties the workflow to async re-anchoring cleanly.

## Alternatives considered

- **In-app editing / co-editing (Google-Docs style).** Rejected: qnop is a review tool; versions come from owner re-upload. Lightweight in-app markup may be revisited but is out of the core.
- **Document : Review = 1 : N (separate Review entity).** Rejected for now (YAGNI): a 1:1 aggregate covers the prototype's review-centric model and stays lean; a second review is a new document.
- **Spring Statemachine.** Rejected: maintenance mode, not Boot-4 validated; overkill for a near-linear flow.
- **Annotation and comment as strictly separate top-level entities.** Rejected: an annotation *is* a mark plus its thread; coupling them models the domain directly.
