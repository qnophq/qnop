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

### Amendment (2026-08-05, issue #712): a third option, opt-in

Running the public demo produced a need the pair above could not express. The operator wanted to see exact addresses **in their own Umami instance** — to tell a returning evaluator from a bot, and to line abuse up with what the logs already show. Under the original two options the only way there was to stop proxying, which would have given up every other protection at once.

So `tracking.forward_client_ip` takes a third value, `full`. Three things make that defensible rather than a hole in the floor:

- **It is opt-in and never a default.** Existing deployments are untouched, and an unreadable setting value falls to `anonymized` — the fallback direction is deliberate, because a typo must not start forwarding personal data.
- **It loosens exactly one thing.** Consent and Do-Not-Track keep applying as configured; the path anonymisation, the collect allowlist and the SSRF checks are unaffected. A test pins this rather than leaving it as an intention.
- **It is not a disclosure to a third party.** The self-hosted case sends the address to the operator's own analytics instance — the same operator whose web server already logged it. What changes is which of their systems sees it, and that is a decision they are entitled to make under their own legal basis.

The wording follows `tracking.consent_required`: the setting says plainly that the legal basis, the consent record and the retention are the operator's to hold, and the admin screen repeats it where the choice is made rather than only in a document nobody has open.

One thing this amendment does **not** provide, and an operator relying on it should know: changing an application setting currently leaves no audit trail, so "when did this deployment start forwarding full addresses, and who decided" is not answerable from qnop today. That gap is tracked in issue #718.

**A note on the mechanism.** `full` parses the address and re-emits it canonically rather than passing the resolved string through. That is not tidiness: behind a trusted proxy, `X-Forwarded-For` carries whatever that proxy passed along, and the truncating path only validated it as a side effect of having to understand it. Forwarding raw would have put unvalidated foreign text into an outgoing header.

### Amendment (2026-11, issue #904): the outbound guard resolves, and pins what it resolved

`OutboundUriGuard` (shared by usage tracking and the OIDC providers, issue #21) was purely lexical: host names were never resolved, only canonical dotted-quad IPv4 literals were recognised, some ranges were missing, and Spring Security's OIDC clients followed redirects and resolved hosts on their own. The guard now has two halves.

**Lexical, at save time and before use (`OutboundUriGuard`, `OutboundAddressPolicy`).** Still DNS-free — a name resolved at save time proves nothing about the moment of the request. It refuses reserved names, any non-canonical numeric host (a single integer, hex or octal parts, a short dotted form — refused even with the private opt-in, since no legitimate configuration spells an address that way), and IP literals in a blocked range: loopback, `0.0.0.0/8`, RFC 1918, CGNAT `100.64.0.0/10`, link-local, `192.0.0.0/24`, `198.18.0.0/15`, multicast, reserved/broadcast; IPv6 unspecified/loopback, ULA `fc00::/7`, link-local, site-local, multicast; IPv4-mapped, IPv4-compatible and 6to4 addresses judged by their embedded IPv4 address; the NAT64 prefix `64:ff9b::/96` (and `64:ff9b:1::/48`) outright; and the cloud metadata addresses by name, including Azure's, which sits in public space.

**At connection time (`GuardedDnsResolver`, `OutboundHttpClients`).** The decision is enforced by the client that makes the request, on the address it actually connects to. We chose **Apache HttpClient 5** (Apache-2.0, already managed by the Spring Boot BOM) because it is the one mainstream JVM client with a DNS hook: the connection operator asks the `DnsResolver` for the host's addresses and opens the socket to exactly those, so there is no second lookup between check and connect — a name that answers with a public address first and a private one later cannot get through. Every resolved address must pass the policy; one blocked address refuses the host. SNI, certificate verification and the `Host` header keep using the name. The alternatives were worse: the JDK `HttpClient` and `HttpURLConnection` have no resolution hook; rewriting the URL to the IP literal breaks TLS host verification; a JVM-wide `InetAddressResolverProvider` would affect every connection the process makes (database, object storage) and is bypassed by the JDK address cache. The guarded clients follow **no redirects**, ignore proxy system properties (a proxy would resolve the name outside the guard), and carry the `qnop.http-client.outbound-*` timeouts.

Where it is wired:

- **Tracking proxy** — `TrackingProxyService` forwards and fetches the script over the guarded client.
- **OIDC discovery** — `OidcDiscoveryClient` fetches `<issuer>/.well-known/openid-configuration` over the guarded client and builds the registration with `ClientRegistrations.fromOidcConfiguration`, keeping the issuer-match check. `ClientRegistrations.fromIssuerLocation` was dropped: its private `RestTemplate` cannot be replaced, follows redirects, and also probes RFC 8414 locations, which OIDC providers do not need.
- **OIDC login flow** — `OidcOutboundHttpConfiguration` declares the token-response client, both user-info services and the ID-token `JwtDecoderFactory` as beans; Spring Security 7's `oauth2Login` configurer picks those up in place of its defaults, so the filter chain is unchanged. Each mirrors Spring Security's default configuration apart from the transport (the JWK set uses RS256 as `OidcIdTokenDecoderFactory` does by default).

The deployment opt-ins are unchanged in meaning: `qnop.auth.oidc.allow-private-discovery-uris` and `qnop.tracking.allow-private-host` relax the range checks at both halves (local Keycloak on `localhost` keeps working); the numeric-form check stays on.

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
- **Issue #904** — the guard resolves host names and pins the connection to the validated addresses (amendment above)
