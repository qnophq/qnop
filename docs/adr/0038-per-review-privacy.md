# ADR-0038: Per-review privacy — anonymity and thread participation policy

- **Status:** Accepted
- **Date:** 2026-07-05
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Until now, foreign annotations and comments rendered under the anonymous label **"Participant"** — but only because the frontend never resolved author names, not because anyone decided authorship should be hidden (issue #403). Meanwhile every participant could see, and comment on, every thread. Both were accidents of the implementation.

Issue #413 turns these into deliberate, **per-review** choices, fixed when the review is created and enforced by the server:

1. **Anonymity** — whether reviewers' identities are shown on annotations/comments.
2. **Thread participation policy** — who, besides an annotation's author and the review owner, may see and write in a thread.

The guiding constraint: *anonymity that only lives in the frontend is none.* If the API ships a resolvable identity, hiding it in the client is theatre. So the decision is fundamentally about what the **API** returns, not about client rendering.

The review model (ADR-0011) frames the actors: the **owner** lives structurally on the document (`Document.ownerId`, not a participant row) and coordinates the review; **reviewers** are participants (each a user or a team); an **author** is always a user (even when they participate via a team). The `FINALIZED` gate counts *all* `OPEN` annotations — a domain invariant over the true set, independent of any viewer.

## Decision

Two additive, nullable-with-default, **immutable-after-creation** columns on `document` (amended into the existing `0011` changeset, pre-prod convention): `anonymous BOOLEAN` (default `false`) and `thread_participation VARCHAR` (`PRIVATE | READ_ONLY | OPEN`, default `OPEN`, closed set pinned by a `CHECK`). Changing either mid-review would rewrite history semantics; if ever needed, that is its own topic.

### Setting 1 — anonymity (server-authoritative identity)

Author identity is resolved **server-side** into a new `authorDisplayName` on `AnnotationView`/`CommentView`. A `ReviewIdentityResolver` builds, per request, the display names (a direct user lookup — works for team-membership authors, who never appear in participant rows) and, for anonymous reviews, a stable pseudonym per non-owner author.

- **Non-anonymous:** `authorDisplayName` is the real name; `authorId` is the real id.
- **Anonymous:** the caller's *own* items and the *owner's* items keep their real id and name — both identities are already known to the caller (the owner is the publicly named coordinator, shown in the review header). Every **other** author is a stable **"Participant N"** pseudonym, and its `authorId` is replaced by a **synthetic per-document token** derived from the pseudonym ordinal — never the real user id — so the client cannot correlate it back to the participant roster. Own-ness (which drives the resolve/reopen affordances) still works because the caller's own `authorId` is unchanged.

Chosen sub-decisions (the open points the issue left to planning):

- **Stable pseudonyms, not one uniform label** — "Participant 2/3" keeps a thread followable (you can tell whether two comments are the same reviewer) without revealing who. The ordinal is assigned by ascending author id over the document's non-owner authors: deterministic, stable across requests and surfaces, and revealing nothing but a count.
- **The owner is exempt from anonymisation** — the owner's *authored* items show the owner's real name, because the owner's identity is already structural and public. This is authorship-exemption only: the owner, *as a viewer*, is still blind to foreign authorship (they see other reviewers as "Participant N" too).
- **The participant roster stays visible** — anonymity hides *who wrote which note*, the standard blind-review model; it does not hide the invited-reviewers list (that would change the "add reviewers" UX). Hiding the roster too is a possible future tightening, not part of this decision.

The author filter facet disappears entirely in an anonymous review.

### Setting 2 — thread participation policy

Enforced at the API, after the existing coarse document-level visibility check:

| Level | Foreign threads (caller is neither author nor owner) |
|-------|------------------------------------------------------|
| `PRIVATE` | filtered out of list/get server-side; `get`/`listComments` on one answer 404 (anti-enumeration) |
| `READ_ONLY` | visible, but foreign `addComment` answers 403 |
| `OPEN` | unchanged — today's behaviour, the default |

Owner and admin are exempt (they see and coordinate everything — the owner must, to finalize). Raising a *new* annotation is unaffected by the policy: an annotation you raise is your own thread.

**Consequences of `PRIVATE` on counts:** the `FINALIZED` gate keeps counting the *true* open set (unchanged) — and since finalizing is owner-only and the owner is exempt, the finalizer never sees a discrepancy. Presentation counts (progress bar, tasks board) derive from the filtered list, so they follow the caller's visibility automatically; a reviewer is shown a "not visible to you" hint for the difference against the true total.

## Rationale

Resolving identity on the server is the only way anonymity can be real, and it also *simplifies* the client: the duplicated author-name resolvers (panel + tasks, via the participant directory) collapse into "render `authorDisplayName`", and the team-membership-author gap disappears. Two orthogonal axes (names vs. visibility) map cleanly onto two independent columns and two independent enforcement seams. Keeping both immutable avoids the hardest semantics (retroactively hiding or revealing history) while covering the actual requirement.

## Consequences

- New wire fields: `AnnotationView.authorDisplayName`, `CommentView.authorDisplayName`, and `anonymous` / `threadParticipation` on the document schemas; the multipart create endpoint (ADR-0028, outside the contract) gains the two form fields.
- The `authorId` on the wire is **mode-dependent** for foreign authors under anonymity (a token, not the real id) — documented on the schema so no consumer treats it as a user id.
- Every future author-bearing surface (likes #410, permalinks #412, the filter bar) must inherit the resolver's rule rather than re-deriving identity.
- `PRIVATE` makes some annotations invisible to some callers; permalinks into an invisible thread degrade to the usual 404.

## Alternatives considered

- **Client-only anonymisation** (omit names in the UI, keep `authorId` on the wire). Rejected: trivially defeated — the real requirement is server-side.
- **A uniform "Participant" label.** Rejected: threads stop being followable in any multi-reviewer discussion.
- **Deriving the pseudonym token from the real author id** (e.g. a hash of `documentId:authorId`). Rejected: the participant roster exposes reviewer user ids, so such a token is confirmable by brute force over the roster — it must derive from the ordinal, whose mapping to real ids lives only server-side.
- **A single "private review" flag** conflating anonymity and visibility. Rejected: they are independent choices (an anonymous but fully-open review, or a named but private one, are both sensible).

## Relationships

Extends [ADR-0011](0011-review-workflow-state-model.md) (review model & the `FINALIZED` invariant). Related issues: #403 (the original "Participant" placeholder), #410 (likes), #412 (permalinks).
