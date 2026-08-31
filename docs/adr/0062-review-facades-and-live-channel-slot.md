# ADR-0062: Review facades and the live-channel UI slot — the last live-feed seams

- **Status:** Accepted
- **Date:** 2026-08-31
- **Deciders:** devtank42 (with Claude)

## Context

The live annotation/comment feed over SSE is an enterprise extension (qnop-ee#5). Issue #602 named four seams; the first — a published event projection — was settled by ADR-0059 (`PublishedEventListener`, the curated catalogue). What remained is the scoping an SSE endpoint must reuse rather than re-implement, and the client-side hook where an SSE connection lives.

## Decision

### `ReviewFacade` — qnop-spi's seventh contract, core-implemented (the ADR-0061 direction)

`io.qnop.spi.review.ReviewFacade`, read-only:

- **`mayView(documentId, principalId)`** — exactly the annotation-listing visibility rule (`DocumentAccessService.isVisible`, admin override deliberately absent: an extension serves regular principals). Covers account-less principals via the ADR-0061 third access leg.
- **`reviewCircle(documentId)`** — the audience the notification path addresses. The computation moved into `ReviewFacadeService.circleOf` and `ReviewNotificationService` now delegates to it: **one implementation**, so the mail audience and an extension's answer cannot drift. Account-less participants stay out (no notification identity — an extension admits them via `ExternalParticipants.hasAccess`); the actor is not pre-excluded (a consumer subtracts the published event's `actorId`).
- **`displayNameFor` / `exposedAuthorIdFor`** — the ADR-0038 per-viewer identity, delegated to `ReviewIdentityResolver`. What a consumer forwards outward must be these values, never raw ids.

### The live-channel UI slot + invalidation facade

`qnop-ui/src/extensions/liveChannel.ts`: a registered `LiveChannelContributor` gets `onReviewMounted(context)` while a review surface is mounted and returns its teardown. The context carries `documentId` and two invalidation functions (`invalidateAnnotations()`, `invalidateComments(annotationId)`) that close over the host's query client and internal query keys — a push event becomes a normal authorized refetch without the extension importing `annotationKeys`/`commentKeys`. Registered per-feature (the mentions/composer-modes pattern); migrates to the generic slot registry when it lands on main (#600) or at the `qnop-ui-spi` cut.

### Deliberately absent

The SSE endpoint, emitter registry, heartbeats, reconnect, and any broker (ADR-0013) — enterprise. The e-mail path keeps consuming internal events unchanged.

## Consequences

- `qnop-spi` publishes seven contracts; qnop-ee#5 = one `PublishedEventListener` + one authenticated `/api/ext/**` SSE controller consulting `ReviewFacade` + one `LiveChannelContributor`.
- The facade\'s answers are IT-pinned against the listing endpoint\'s behaviour for owner, participant, team member and outsider.

## Alternatives considered

- **Exposing repositories/services to extensions** — rejected: the facade keeps the scoping rules behind one reviewed surface (ADR-0061 reasoning).
- **A per-event pre-resolved audience pushed through the event stream** — rejected: audiences change between commit and delivery; consumers must resolve at send time.
