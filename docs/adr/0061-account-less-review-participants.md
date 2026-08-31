# ADR-0061: Account-less review participants — one principal-id space, a core-implemented facade

- **Status:** Accepted
- **Date:** 2026-08-31
- **Deciders:** devtank42 (with Claude)

## Context

Guest links (qnop-ee#17) invite an external reviewer without an account. The core knows exactly one kind of actor — a `User` row — and issue #684 asks for the seam, warning against both extremes: a bare "non-user principal" hook (the extension would reimplement participation, identity and visits against internals) and a full guest model in the core (the AGPL core would carry the substance of a paid feature). Epic #689 demands one principal answer shared with the machine-credential seam (#686, ADR-0060).

## Decision

### One principal-id space: a UUID that is usually a user id

ADR-0060 fixed the *authentication* shape (subject string; UUID = user). This ADR fixes the *review-domain* shape: **every actor in the review domain is a UUID principal id.** A user's principal id is their user id. An account-less participant is a `review_participant` row with `user_id` and `team_id` null, a stored `external_display_name`, and **the row's own id as its principal id** — flowing through authorship columns, access checks, visit records and anonymity ordinals without any of those learning a second concept. The two seams meet cleanly: an extension authenticates a guest with its own credential (its own chain, or a `BearerCredentialAuthenticator` whose subject is non-UUID) and then acts for the guest's principal UUID through this facade.

### What the core learns — and refuses to learn

- **Schema:** `external_display_name` on `review_participant`; the XOR check becomes three-way. `review_visit.user_id` loses its FK to `qnop_user` — the column holds a principal id now.
- **Access:** `existsAccessibleParticipant` gains a third leg (`p.id = :principal AND external`), so `DocumentAccessService.canAccess` and every caller work unchanged.
- **Identity (ADR-0038):** external names resolve from the participant row; guests number into the same ordinal space, so an anonymous review pseudonymizes a guest exactly like a user — nothing an extension supplies as a display name can pierce anonymity.
- **API:** `ParticipantKind` gains `EXTERNAL` (no slug, no avatar, display name from the row).
- **Refused:** links, invitations, expiry, credentials, commercial gating (ADR-0003) — and external roster changes publish no `ReviewEvent`: whom to tell about a guest is the extension's decision. The audit trail records add/remove with a null actor.

### The seam: a facade the core implements

`io.qnop.spi.participant.ExternalParticipants` — `add(documentId, displayName) → UUID`, `remove(documentId, participantId)`, `hasAccess(documentId, participantId)` — is `qnop-spi`'s sixth contract and its first of the *inverse* kind: the **core implements it, the extension calls it** (the direction #602's facades will repeat). `remove` refuses non-external rows, so an extension can never manage the account-bearing roster through the seam.

## Consequences

- qnop-ee#17 = credential lifecycle + invitation UI + an authenticator, calling this facade; no community change.
- Deliberately absent until a real consumer forces them (ADR-0049): guest-scoped thread-participation levels (`thread_participation` already expresses them when needed), guest authorship endpoints, and the widening of `AuditEvent` actor semantics — machine/guest actors audit as detail text today.
- The `ParticipantKind` enum change is additive (client enums tolerate unknown values poorly in general, but the generated TS client types are string unions — additive is safe).

## Alternatives considered

- **Bot user rows for guests** — rejected: pollutes the user table, quota, mail, mentions, offboarding (same reasoning as ADR-0060's rejection).
- **A separate guest table + second principal concept** — rejected: every later feature would ask "which kind of actor is this?"; the epic exists to prevent exactly that.
- **Publishing repository-level access to the extension** — rejected: the facade keeps invariants (external-only removal, validation, audit) core-side.
