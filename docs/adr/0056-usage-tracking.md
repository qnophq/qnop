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
Browser ──GET/POST /t/c/…─►  qnop ──►  https://matomo.internal/matomo.php   (forwarded)
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

### Four gates, any of which says no

Operator configuration, Do-Not-Track/GPC, the account-level opt-out, and consent. All four must agree before a single measurement is sent. The consent answer lives in the browser (the sign-in screen is measured, and nobody has an account yet at that point); the opt-out lives on the account, so it follows a person across devices and outlives a cleared browser store. Administrators and auditors are excluded unless an operator explicitly includes them — a handful of privileged people is barely a statistic and very much personal data.

### Events are a closed union

Four names, no properties: `review_created`, `annotation_created`, `review_finalized`, `export_generated`. An `event(name, props)` API would eventually carry a document title or a search term — not through carelessness, but because that is what an open API invites. A fifth event is a code change somebody reviews.

### Which backends, and one that did not fit

Matomo, Plausible, Umami, PostHog (self-hostable) and Pirsch (cloud, proxy-native). **Fathom was dropped during implementation**: its custom-domain feature was withdrawn in 2023 with no replacement, and its documented model expects beacons to reach it directly from the browser so its bot detection sees the real client. Proxying it would either not work or quietly poison its data — and adding a CSP exception for one vendor would give up the property this whole design exists to keep.

### Amendment (2026-11, issue #910): no vendor script — qnop builds the measurements itself

The original design proxied the vendor's **script** as well as its measurements: `/t/s.js` was fetched from the configured analytics host and served from qnop's origin. That kept the CSP at `'self'`, but it also meant the script ran with the application's full privileges — it could read the signed-in user's in-memory access token and call the API as them. Its content was not pinned and the fetch was unbounded, so a compromised analytics host, or a wrong host in the settings, was code execution in every consenting reviewer's session, administrators included.

**Decision.** The script is gone. The SPA builds every measurement itself, in the backend's own documented collect format, and posts it to the existing `/t/c/…` proxy; the server neither fetches nor serves any analytics JavaScript (`/t/s.js` no longer exists). Per backend:

| Backend | Page view | Event |
|---|---|---|
| Matomo | `GET /matomo.php` (HTTP Tracking API, `send_image=0`) | same, with `e_c=qnop&e_a=<name>` |
| Plausible | `POST /api/event` `{name:"pageview", url, domain}` | same, with the event name |
| Umami | `POST /api/send` `{type:"event", payload:{website, hostname, language, screen, url}}` | same, with `name` |
| PostHog | `POST /i/v0/e/` `{api_key, event:"$pageview", distinct_id, properties}` (anonymous: `$process_person_profile:false`) | same, with the event name |
| Pirsch | `GET /hit?code=…&url=…` (the format `pa.js` uses) | `POST /event` |

Each request carries the route pattern (as an absolute URL on qnop's origin where the backend wants one), the event name for events, and — only where the backend's format has a field for it — the screen size (Matomo, Umami, Pirsch) and the browser language (Umami). Never a title or a referrer. Requests are sent with `credentials: 'omit'` and `referrerPolicy: 'no-referrer'`. PostHog's `distinct_id` is a random id per page load, which is what the previous `persistence: 'memory'` configuration amounted to — so PostHog's "unique users" count page loads, not people, and should not be read as visitor numbers; that is the privacy design, not a defect.

**The allowlist shrank to match.** `TrackingProvider` now admits exactly the paths above: Umami's `/api/batch`, PostHog's `/e`, `/batch`, `/decide/`, `/flags` and Pirsch's `/session` are refused. Nothing but qnop's own code sends to the proxy any more, so nothing else needs to pass.

**The server accepts only that shape.** The collect endpoint is unauthenticated, so `TrackingMeasurementValidator` admits a request only if it is exactly what the client builds: the right method and path, only the known keys and query parameters, this deployment's site id (no relaying into another site on the same analytics instance), an event from the closed list of four, empty `tags`/`event_meta` for Pirsch, and for PostHog no person or identity properties, with `$process_person_profile` forced to `false`. What is forwarded is rebuilt from the validated values with a pinned content type — never the caller's bytes or header — and the URL sanitiser still runs after it. A request a browser marks `Sec-Fetch-Site: cross-site` is dropped as well. The golden requests in `qnop-ui/src/tracking/__fixtures__/beacons.json` are written by the client's tests from the real builders and read by the server's tests, which assert that every one is accepted and that the paths per backend equal the allowlist — client and server cannot drift without a test failing.

**Transport.** A non-private tracking host must use `https`; plain `http` remains possible only for a host the lexical SSRF policy classes as private (an IP literal in a private range, `localhost`, `*.local`, `*.internal`), which in turn requires the `qnop.tracking.allow-private-host` deployment opt-in. A public name that merely resolves to a private address must use https. The rule (`TrackingHostPolicy`) is purely lexical, so it is enforced when the setting is saved — the field reports `REQUIRES_HTTPS` — and again in `TrackingConfigService` for a value saved before the rule existed, which then switches measurement off with a warning. **Upgrade note:** a deployment whose public analytics host is configured as `http://` stops measuring after this change until the host is switched to `https://`.

**Visitor addresses through the proxy.** Every measurement reaches the backend from qnop's server, so the backend must take the visitor's address from `X-Forwarded-For`. Matomo does so only when its `config.ini.php` lists `HTTP_X_FORWARDED_FOR` in `proxy_client_headers[]`; the setting help and `docs/DEPLOYMENT.md` say so. Pirsch's identification-code endpoints are designed for browser calls, and its own proxy integrations use server-side API clients, so through qnop it may attribute every visitor to qnop's address — a known limitation, with a server-side access-token mode and a real-instance smoke test across all five backends tracked in issue #944. Neither could be verified against live instances in this change; the request formats were checked against the vendors' current scripts and API documentation.

**The admin screen says what is trusted.** The tracking settings group carries a standing note (in every UI locale): qnop runs no provider code in the browser, the configured backend still receives each forwarded measurement with the visitor's user agent and — as configured — IP address, so it must be a server the operator trusts, and a public host must use https.

**Alternatives considered.**

- *Vendor script in a sandboxed iframe* (`sandbox="allow-scripts"`, opaque origin, postMessage bridge). Rejected. Requests from an opaque origin arrive with `Origin: null`; the JSON POSTs of Plausible, Umami and PostHog are non-simple, so the unauthenticated proxy would have had to answer CORS preflights for `null` — accepting cross-origin writes from any sandboxed document anywhere. Pirsch's script can only report its own `location`, which inside the frame is the frame's URL, not the page's. Pirsch also reads `localStorage` unguarded, which throws in an opaque origin. And the iframe would still run third-party code whose behaviour qnop cannot review — only in a smaller box.
- *Pin the script by hash, with an admin-confirmed digest.* Rejected as the primary control: it keeps third-party code in the application origin and turns every vendor release into a silent outage until an administrator re-confirms a hash they cannot meaningfully review. It was the fallback had the first-party beacons not been feasible; they were.
- *Response-size cap on the script fetch.* Moot — there is no script fetch. Forwarded measurements already have a 64 KiB request cap, and the backend's response is discarded.

**Consequences.** No third-party JavaScript executes in qnop's origin under any configuration, and the unauthenticated collect endpoint passes on nothing qnop's own client would not have sent. The vendors' scripts are no longer a moving part, so a vendor release cannot change what qnop sends; the price is that qnop now owns five small request builders (`qnop-ui/src/tracking/providers.ts`), each pinned by a test against the server's allowlist — a change to a vendor's collect API is a code change here rather than an automatic pickup. Features that only the scripts provided — Plausible's engagement/scroll-depth events, Umami's performance and error reports, PostHog's remote config — are gone, all of them things qnop did not want to collect anyway.

## Consequences

**Positive.** The CSP is unchanged. No reviewer's browser contacts an analytics host. Ad blockers have nothing to act on, so the numbers describe everyone who consented rather than everyone who consented and did not block. Switching backends is a settings change, not a deployment change.

**Negative.** Five vendor integrations to keep working, each of which can change its script or endpoints without telling us; a broken one fails silently by design. The proxy adds a request path to keep an eye on. Payloads that are compressed or otherwise unreadable pass the server-side sanitiser untouched, so the clients are configured to send plain JSON — a coupling worth knowing about.

**Neutral.** ~~Pirsch reports its own page view because it offers no documented way to send one with a chosen URL; the server rewrites the id out of it, which is exactly why the second line exists.~~ Superseded by the #910 amendment: qnop now sends Pirsch's page view itself, with the route pattern; the server-side rewrite remains the second line for every backend.

## Related

- **ADR-0040** — the SPA's Content-Security-Policy, the constraint this design bends around
- **ADR-0025** — application settings; every operator-facing knob here is one
- **ADR-0027** — the rate-limiting the unauthenticated collect endpoint reuses
- **Issue #21** — the OIDC SSRF guard, extracted here into `OutboundUriGuard` and shared
- **Issue #910** — no vendor script; first-party collect requests and HTTPS for public hosts (amendment above)
