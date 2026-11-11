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

**Per-identifier buckets (#899).** The email- and token-keyed limits deferred to #20 were never
built, and login had no per-account limit, so per-IP limits were the only bound and a caller
using many source addresses was not limited at all. They now exist next to the per-IP ones:

| Scope (`qnop.auth.rate-limit.*`) | Key | Default | Counts |
|---|---|---|---|
| `login-account-network` | account × client network (IPv4 /24, IPv6 /64) | 10 / 900s | failed attempts only |
| `login-account` | account, all networks together | 100 / 3600s | failed attempts only |
| `register-email` | e-mail | 5 / 3600s | every attempt |
| `forgot-password-email` | e-mail | 5 / 3600s | every attempt |
| `reset-password-token` | reset token | 10 / 3600s | every attempt |
| `reset-password` (per IP, filter) | client IP | 5 / 900s | every attempt |

The per-IP `reset-password` filter is the IP half of the password-reset limit the decision
above already named; `forgot-password` keeps its existing per-IP filter.

- **Enforced in the controller**, as the context section anticipated: the filters never read the
  body, and buffering and re-parsing it in a filter is not worth it. `AuthIdentifierRateLimiter`
  is called by `AuthController`, `AuthRegistrationController` and `AuthPasswordResetController`
  after their feature-flag checks; a drained bucket throws `RateLimitExceededException`, rendered
  by `RateLimitExceptionHandler` with the *same* status, `Retry-After`, code and message as that
  endpoint's per-IP filter.
- **Which account a login is charged to.** The key is the user id whenever the identifier
  resolves to a local account, through `AuthService.findLocalAccountId` — the very lookup the
  credential check uses (username exact, else e-mail case-insensitively). Username and e-mail of
  one account, and every spelling the lookup accepts, therefore share one bucket. The lookup runs
  on every attempt, known or unknown, so it adds no timing difference of its own. An identifier
  that resolves to nothing is keyed on a hash folded the same way the lookup folds
  (case-insensitive if it contains `@`, exact otherwise, never trimmed), and gets buckets exactly
  like a real account, so a refusal does not distinguish the two. The one residual signal is that
  a username and an e-mail address share a bucket only if they belong to the same account; seeing
  it requires exhausting that bucket from one network, i.e. many failed attempts, which the
  limits themselves bound.
- **Login: failures only, per network first, then a generous overall ceiling.** Each attempt
  takes a token from both login buckets before the credential check (atomic, so concurrent
  attempts cannot overshoot); a success gives both back, so only failures count. A drained bucket
  refuses every attempt, the correct password included — otherwise the refusal would reveal the
  right guess. The trade-off between guessing resistance and lock-out by a third party:
  - A single hard per-account cap would let anyone keep an account's owner out simply by failing
    on purpose. Keying the strict bucket on account × client network confines that effect to the
    network that produces the failures; the owner signing in from anywhere else is unaffected.
  - The overall per-account ceiling is an order of magnitude higher. It bounds the total guesses
    against one account per hour however many networks are used, and it is the only bucket a
    third party can drain for everyone — which takes failures from many networks. Reaching it is
    an attack signal operators should see in the logs, not routine.
  - Rejected: a pure per-account hard block (trivial lock-out), progressive server-side delay
    (ties up request threads under exactly the load it is meant to absorb), and lock-out with
    out-of-band unlock (needs mail and an unlock flow, and still lets a third party trigger it).
- **Register / forgot-password / reset are hard caps per e-mail / token.** They protect a mailbox
  from being flooded, not an account from guessing, so a hard cap is right. They are scopes of
  their own and never count against login: draining them does not stop the owner from signing in.
  E-mail keys are trimmed and lower-cased; the reset token is case-sensitive and only hashed.
- **Keys are SHA-256 hashes** (or the user id); the raw identifier never sits in the bucket store.
  The uniform `200`/`204` answers of register and forgot-password are kept.
- A breached-password check was considered and left out of scope here.
