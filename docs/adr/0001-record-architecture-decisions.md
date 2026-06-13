# ADR-0001: Record architecture decisions as ADRs

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

qnop is a greenfield, multi-phase system with several consequential, hard-to-reverse decisions (open-core boundary, persistence topology, anchoring model). These decisions and their rationale must survive team and context changes, and must be auditable — especially the licensing-sensitive ones.

## Decision

Every important architecture decision is captured as a numbered ADR in `docs/adr/`, using the lightweight template in the [README](README.md). ADRs are immutable once Accepted; a decision is changed by adding a new ADR that supersedes the prior one. Decisions whose details are intentionally deferred are recorded as **Proposed** so the intent is tracked without pretending the work is done.

## Consequences

- Rationale is preserved next to the code, not lost in chat history.
- A new contributor can reconstruct *why* the system is shaped as it is.
- Small overhead per decision; we accept it for the traceability.

## Alternatives considered

- **Document only in the wiki / PRs** — rejected: not versioned with the code, hard to audit.
- **No formal record** — rejected: the open-core/AGPL decisions in particular need a defensible paper trail.
