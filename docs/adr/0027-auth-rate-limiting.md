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

## Amendment (2026-11, #898 and #899): right-to-left client IP, per-identifier buckets

A security review (epic #896) found two places where the implementation fell short of this
decision.

**Client IP (#898).** Behind a trusted proxy the resolver used the *leftmost*
`X-Forwarded-For` entry. Proxies that append to the header put the address they actually saw
on the right, so the leftmost entry is whatever the client sent. `HttpClientIpResolver` now
walks the header **right to left**: entries inside `trusted-proxy-cidrs` are skipped, and the
first entry outside it is the client. If every entry is trusted, the leftmost is used. An
entry that is not an IP literal (empty, `unknown`, a hostname) ends the walk at the last
trusted hop, since nothing left of it can be verified; entries are never resolved through DNS.
Repeated header lines are joined in order; ports and IPv6 brackets are stripped. This also
removes the "multi-hop chains deferred" consequence above — a chain of trusted proxies is
supported as long as every hop is listed. Operator guidance is in `docs/DEPLOYMENT.md`
("Behind a reverse proxy"). The secure default (empty trust list → header ignored) is
unchanged.

Only entries that start with a hex digit or `:` and otherwise contain nothing but hex digits,
`:` and `.` are ever handed to the JDK's address parser, which treats exactly those as literals.

**Address grouping.** One IPv6 subscriber normally holds at least a /56, so keying anything on
a single IPv6 address lets one party rotate through more buckets than any limit can absorb.
The per-IP filters therefore key IPv4 per address and IPv6 per /56. The per-identifier buckets
below key "the network an attempt comes from" as the IPv4 /24 or the IPv6 /56.

**Per-identifier buckets (#899).** The email- and token-keyed limits deferred to #20 were never
built, and login had no per-account limit, so per-IP limits were the only bound and a caller
using many source addresses was not limited at all. They now exist next to the per-IP ones:

| Scope (`qnop.auth.rate-limit.*`) | Key | Default | Counts | When drained |
|---|---|---|---|---|
| `login-account-network` | account × client network, or account × recognised device | 10 / 900s | failed logins | `429` |
| `login-account` | account (unrecognised devices only) | 100 / 3600s | failed logins | silent `401` |
| `forgot-password-email-network` | e-mail × client network | 3 / 3600s | every request | `429` |
| `forgot-password-email` | e-mail | 10 / 3600s | every request | `204`, no mail |
| `register-email` | e-mail | 5 / 3600s | every request | `429` |
| `reset-password-token` | reset token | 10 / 3600s | every attempt | `429` |
| `reset-password` (per IP, filter) | client IP | 5 / 900s | every attempt | `429` |
| `verify-email` (per IP, filter, #900) | client IP (IPv6 /56) | 10 / 900s | every verify-email or `/registration` request | `429` |

`login-device-cookie-lifetime` (default 30 days) bounds the recognised-device cookie. The per-IP
`reset-password` filter is the IP half of the password-reset limit the decision above already
named; `forgot-password` keeps its existing per-IP filter.

- **Enforced in the controller**, as the context section anticipated: the filters never read the
  body, and buffering and re-parsing it in a filter is not worth it. `AuthIdentifierRateLimiter`
  is called by `AuthController`, `AuthRegistrationController` and `AuthPasswordResetController`
  after their feature-flag checks. A `429` is thrown as `RateLimitExceededException` and rendered
  by `RateLimitExceptionHandler` with the *same* status, `Retry-After`, code and message as that
  endpoint's per-IP filter.
- **Which account a login is charged to.** The key is the user id whenever the identifier
  resolves to a local account. The throttle does not look the account up itself: it runs as a
  gate inside `AuthService.authenticate`, between the credential check's own lookup (username
  exact, else e-mail case-insensitively) and the password comparison, and receives the resolved
  account id from it. Username and e-mail of one account, and every spelling the lookup accepts,
  therefore share one key, with a single lookup per login. An identifier that resolves to
  nothing is keyed on a hash folded the way the lookup folds (case-insensitive if it contains
  `@`, exact otherwise, never trimmed) and gets buckets exactly like a real account. The
  password comparison runs on every path that is not refused with a `429` (against a dummy hash
  for unknown accounts, #903).
- **Only the caller's own bucket answers `429`.** Every identifier throttle that has a ceiling
  pairs it with a strict bucket per identifier and *source* — the client network, or for login a
  recognised device. Only the source bucket refuses with `429`. A drained ceiling refuses
  silently: a login gets the ordinary `401` without its password being honoured (the credential
  check still runs, so the refusal takes as long as a real one), a reset request gets the
  ordinary `204` and no mail. State created from one network therefore never surfaces as a
  `429` in another, and a held-down ceiling reveals neither itself nor which identifiers share
  it.
- **Login: failures only; recognised devices are exempt from the ceiling (OWASP "device
  cookie").** Tokens are taken before the credential check (atomic, so concurrent attempts
  cannot overshoot) and given back on success, so only failures count. A successful login sets
  `qnop_device`: `HttpOnly`, `SameSite=Strict`, `Secure` (per `qnop.auth.cookie-secure`), path
  `/api/v1/auth/login`, holding `accountId ‖ nonce ‖ issuedAt` signed with HMAC-SHA256 under a
  key derived from the JWT secret with the HKDF purpose label `login-device-cookie`
  (ADR-0022). A later attempt for the same account presenting a valid, unexpired cookie is
  charged to a bucket of that device alone and skips the ceiling. Every other attempt is charged
  to its account × network bucket and to the ceiling; when the ceiling refuses, the network
  token stays spent.
- **The lock-out trade-off, stated accurately.** Buckets refill continuously, so the ceiling
  can be held drained by a *sustained* failure rate of about 100 per hour, which a handful of
  client networks can supply (each network allows about 40 per hour). While it is drained,
  every attempt for that account from an unrecognised device fails, the correct password
  included. That is the price of a global bound on guesses against one account. The owner is
  not locked out of devices they have signed in on within the cookie lifetime; a new device, or
  one whose cookie expired or was cleared, must wait until the failures stop. The ceiling being
  reached is logged at `WARN` (account key only). Rotating the JWT secret invalidates all device
  cookies. A device cookie is not revoked by a password change; it only exempts its holder from
  the ceiling, never from the password check or its own bucket.
- **Residual signals.** (1) A caller who drains *their own* network bucket for an account with
  one spelling and then sees a `429` for another spelling from the same network learns that both
  spellings belong to one account. That costs the full per-network allowance each time and is
  bounded by it; from any other network nothing is visible. (2) The reset-mail suppression is
  invisible to the requester by design. Rejected: a pure per-account hard block (trivial
  lock-out), a `429` from the ceiling (lock-out plus a cross-network signal), progressive
  server-side delay (ties up request threads under the load it is meant to absorb), lock-out
  with out-of-band unlock (needs mail and an unlock flow, and still lets a third party trigger
  it).
- **Register** keeps a single hard cap per e-mail: it protects a mailbox, and the owner does not
  need to register repeatedly. **Reset token**: each guess is a different token and gets a fresh
  bucket, so the per-token cap does nothing against guessing — the per-IP `reset-password`
  filter and the token's entropy do that. It bounds retries with one (possibly leaked) token.
  Neither of these, nor the reset-request buckets, ever counts against login.
- **Keys are SHA-256 hashes** (or the user id); the raw identifier never sits in the bucket
  store. The identifier buckets live in a store of their own (same `max-buckets` cap), so a
  flood of per-IP keys cannot evict them and vice versa.
- A breached-password check was considered and left out of scope here.
