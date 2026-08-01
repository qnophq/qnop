# ADR-0056: Usage tracking — server-proxied, path-anonymised, consent-gated

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** devtank42 (with Claude)

## Context

`tracking.enabled` and `tracking.provider` have existed since issue #16 and were read by nothing — a dropdown offering Matomo, Plausible and Umami, wired to no code at all. Making it real means answering questions a document-review tool cannot answer the way an ordinary web app would.

The binding constraint is already in the repo. `PathAwareCspHeaderWriter` (ADR-0040) pins the SPA to `script-src 'self'; connect-src 'self'`. Every analytics vendor's install instructions begin by violating exactly that.

And the second constraint is the product itself: a qnop URL contains the id of a customer's document, a search query is the subject of one, and the visible screen *is* one.

## Decision

### Measurement goes through this server, never past it

```
Browser ──GET /t/s.js──►  qnop ──►  https://matomo.internal/matomo.js   (cached 1h)
        ──POST /t/c/…─►  qnop ──►  https://matomo.internal/matomo.php   (forwarded)
```

The alternative — extending the CSP with the configured analytics host per deployment — was rejected. It weakens the one policy that is identical on every other page, it hands each reviewer's IP address to a third party, and it puts the measurement squarely in the path of ad blockers. Proxying costs two endpoints and turns all three around: the CSP stays untouched, the backend sees only what qnop chooses to send, and there is no third-party host to block.

The endpoints sit outside `/api/v1` (as `@Controller`, since `ApiPathConfig` prefixes every `@RestController`): they speak whatever shape the configured backend speaks, which is that backend's contract and not qnop's published one (ADR-0015).

### The collect allowlist is a boundary, not a convenience

`TrackingProvider` declares, per backend, the exact paths that may be forwarded — compared whole, never by prefix. This is what makes a promise enforceable rather than merely stated: PostHog's `/s/` (session recording) is absent, so a client that asked for session replay would be refused *by this server*, whatever its configuration says. It also stops the endpoint from being a general-purpose relay to the analytics host.

**Session recording and heatmaps are out of scope permanently** — Hotjar, Clarity, FullStory, Smartlook and everything like them. They record the screen, and in qnop the screen holds a customer's confidential document. That is not a setting.

### Addresses are patterns, and they are checked twice

The client reports `/reviews/:documentId/tasks`, built from the router's own params — the router knows which segments were parameters because it filled them in, which is the only way to catch a slug like `/users/mia-member` that no shape check could recognise as personal.

The server checks again on every forwarded measurement (`TrackedUrlSanitizer`): the query string always goes, and identifier-shaped segments become `:id`. A stale bundle in an open tab, or a page that forgets the rule, cannot put a document id into an analytics report that will outlive the review.

### The visitor's address is truncated, not withheld

Proxying raises a question direct measurement never asks. Forward nothing and the backend counts a whole company as one visitor; forward the real address and proxying has withheld nothing. So `X-Forwarded-For` carries the address truncated to its /24 (IPv4) or /64 (IPv6) — Matomo's own anonymisation, and the long-standing middle ground of German data-protection practice. `tracking.forward_client_ip=none` remains available, with distorted counts as the stated cost.

### Four gates, any of which says no

Operator configuration, Do-Not-Track/GPC, the account-level opt-out, and consent. All four must agree before a script tag is even created. The consent answer lives in the browser (the sign-in screen is measured, and nobody has an account yet at that point); the opt-out lives on the account, so it follows a person across devices and outlives a cleared browser store. Administrators and auditors are excluded unless an operator explicitly includes them — a handful of privileged people is barely a statistic and very much personal data.

### Events are a closed union

Four names, no properties: `review_created`, `annotation_created`, `review_finalized`, `export_generated`. An `event(name, props)` API would eventually carry a document title or a search term — not through carelessness, but because that is what an open API invites. A fifth event is a code change somebody reviews.

### Which backends, and one that did not fit

Matomo, Plausible, Umami, PostHog (self-hostable) and Pirsch (cloud, proxy-native). **Fathom was dropped during implementation**: its custom-domain feature was withdrawn in 2023 with no replacement, and its documented model expects beacons to reach it directly from the browser so its bot detection sees the real client. Proxying it would either not work or quietly poison its data — and adding a CSP exception for one vendor would give up the property this whole design exists to keep.

## Consequences

**Positive.** The CSP is unchanged. No reviewer's browser contacts an analytics host. Ad blockers have nothing to act on, so the numbers describe everyone who consented rather than everyone who consented and did not block. Switching backends is a settings change, not a deployment change.

**Negative.** Five vendor integrations to keep working, each of which can change its script or endpoints without telling us; a broken one fails silently by design. The proxy adds a request path to keep an eye on. Payloads that are compressed or otherwise unreadable pass the server-side sanitiser untouched, so the clients are configured to send plain JSON — a coupling worth knowing about.

**Neutral.** Pirsch reports its own page view because it offers no documented way to send one with a chosen URL; the server rewrites the id out of it, which is exactly why the second line exists.

## Related

- **ADR-0040** — the SPA's Content-Security-Policy, the constraint this design bends around
- **ADR-0025** — application settings; every operator-facing knob here is one
- **ADR-0027** — the rate-limiting the unauthenticated collect endpoint reuses
- **Issue #21** — the OIDC SSRF guard, extracted here into `OutboundUriGuard` and shared
