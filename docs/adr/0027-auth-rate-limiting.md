# ADR-0027: Auth rate limiting & trusted-proxy IP resolution

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

The public/auth endpoints (issue #17's `AuthController`, and #20's registration /
password-reset to come) are exposed to online abuse: credential brute-force on
login, refresh-token spam, and brute-force of the current password on
change-password. We need per-endpoint throttling that returns a standard HTTP
`429 Too Many Requests` with `Retry-After`, keyed appropriately per endpoint, and
tunable per deployment. Ported from plugwerk's security package.

Two cross-cutting concerns shape the design:

- **Where the limit is enforced.** IP-keyed limits can be applied pre-auth in a
  servlet filter (no request body needed). Subject-keyed limits need the
  authenticated principal, so they must run after the resource-server bearer
  filter. Email-/token-keyed limits (register, password-reset) need the parsed
  request body and therefore belong inside the controller — those land with #20.
- **Trusting `X-Forwarded-For`.** Per-IP limiting is only as sound as the client
  IP. A naive read of `X-Forwarded-For` lets an attacker spoof the header to
  rotate buckets and bypass the limit entirely.

## Decision

- **Bucket4j + Caffeine.** A single `BucketRateLimitService` holds token buckets
  in a Caffeine cache keyed `scope:key`, with `expireAfterAccess` + a size cap to
  bound memory. Callers pass scope, key, capacity and window at consume time, so
  one service backs every rate-limit context. Bucket4j carries an explicit version
  (not in the Spring Boot BOM); Caffeine is BOM-managed and already present from
  the #17 revocation denylist.
- **`OncePerRequestFilter` per endpoint, returning `429` + `Retry-After`.** The
  IP-keyed login and refresh filters run before CSRF/auth processing; the
  subject-keyed change-password filter runs after bearer authentication (before
  `AuthorizationFilter`) so it can key on the JWT `sub`. A `null` key (e.g.
  unauthenticated change-password) passes through to the normal `401` rather than
  being masked by a rate-limit decision.
- **Trusted-proxy `X-Forwarded-For`, secure by default.**
  `HttpClientIpResolver` only honours `X-Forwarded-For` when the immediate hop
  (`getRemoteAddr()`) matches a configured trusted-proxy CIDR. **The default trust
  list is empty**, so a server with no reverse proxy ignores the header entirely
  and cannot be spoofed. Operators behind a proxy MUST set
  `qnop.auth.rate-limit.trusted-proxy-cidrs` to the proxy's egress IPs.
- **Tunable via `qnop.auth.rate-limit.*`** (`RateLimitProperties`). Defaults match
  issue #18: login 10/60s (IP), refresh 30/60s (IP), change-password 5/300s
  (subject). Register (10/60s IP + 5/3600s email) and password-reset (5/900s IP +
  10/3600s token) limits are added when their endpoints land in #20.

## Consequences

- Brute-force and spam against the live auth endpoints are bounded; clients get a
  standard `429` + `Retry-After` they can honour.
- Per-IP limiting is correct behind a single reverse proxy **only if**
  `trusted-proxy-cidrs` is configured; otherwise every client shares the proxy's
  bucket. This is documented on the property and resolver. Multi-hop proxy chains
  would need a right-to-left walk — deferred until a deployment needs it.
- The rate-limit subsystem lives in the web layer (`io.qnop.web.security.ratelimit`)
  with its own `RateLimitProperties`, keeping Bucket4j/servlet concerns out of
  `qnop-core` and out of the crypto foundation's `QnopProperties`.
- Register / forgot-password / reset-password rate limiting (the email- and
  token-keyed buckets, enforced in-controller) is deferred to issue #20 alongside
  those endpoints; the reusable service and config surface are in place for it.

## Alternatives considered

- **Gateway/proxy-level rate limiting (nginx, ALB).** Rejected as the sole
  mechanism: not all deployments have a configurable gateway, and subject-keyed
  limits (change-password) need application context the proxy lacks. App-level
  limiting is portable and edition-agnostic; a gateway can still add a coarse
  outer layer.
- **Trusting `X-Forwarded-For` unconditionally.** Rejected: trivially spoofable to
  defeat per-IP limits. The trusted-proxy gate is the whole point.
- **Distributed bucket store (Redis).** Rejected for now: Community runs
  single-node and Redis is explicitly deferred (ADR-0013). The in-process Caffeine
  store is sufficient; a distributed backend can replace it behind the same
  service if horizontal scaling arrives.
