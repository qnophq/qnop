# ADR-0026: JWT access tokens, rotating refresh tokens, and revocation

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Epic #7 needs an authenticated session model: short-lived credentials for API
calls, a longer-lived way to stay signed in, and a way to revoke both on logout
and password change. The security/crypto foundation (ADR-0022) already provides
HKDF key derivation (`JwtKeyService`), BCrypt, and validated `qnop.auth.*`
config; the token tables (`refresh_token`, `revoked_token`) landed with issue #12.

## Decision

- **Access tokens** are self-issued HS256 JWTs (Nimbus encoder), signed with a
  key HKDF-derived from `qnop.auth.jwt-secret` under the `access-token` purpose.
  Claims: `jti` (random UUID, for revocation), `iss`, `iat`, `exp`, `sub` (the
  `qnop_user.id`). Default TTL 15m. Sent as `Authorization: Bearer`.
- **Refresh tokens** are opaque 256-bit random strings; only their
  `HMAC-SHA256` lookup hash is stored (`refresh_token.token_lookup_hash`). They
  rotate single-use within a *family*: presenting an active token revokes it
  (`ROTATED`) and issues a successor; presenting an already-revoked token (a
  replay) revokes the **whole family** (`REUSE_DETECTED`). Default TTL 7d.
- **The refresh token rides in an HttpOnly, `SameSite=Strict`, `Secure` cookie**
  (`qnop_refresh`) scoped to `/api/v1/auth`, so it is invisible to JavaScript and
  not sent cross-site. `Secure` is configurable (`qnop.auth.cookie-secure`) for
  local HTTP dev.
- **Revocation** has two layers: an explicit `jti` denylist (`revoked_token`,
  SHA-256 hashed, fronted by a Caffeine cache sized to the access-token TTL) and
  bulk invalidation via `qnop_user.password_invalidated_before` — any token
  issued before that instant is rejected. `DelegatingJwtDecoder` runs the local
  HMAC verification then both checks; the resource-server filter uses it. (OIDC
  provider decoders will be layered in as a fallback by issue #21.)
- **CSRF:** the API is otherwise stateless and bearer-based (CSRF-exempt), but
  the two cookie-bearing endpoints (`/auth/refresh`, `/auth/logout`) are
  protected with a double-submit cookie token (`CookieCsrfTokenRepository` +
  `X-XSRF-TOKEN`), defended in depth alongside `SameSite=Strict`.
- **`AuthController`** (implementing the generated `AuthApi`) exposes `login`,
  `refresh`, `logout`, and `change-password`. Password change re-verifies the
  current password and invalidates every existing session.

## Consequences

- Logout and password change take effect immediately for access tokens (not just
  at expiry), at the cost of a per-request revocation check — kept cheap by the
  Caffeine cache.
- Expired `revoked_token` / `refresh_token` rows accumulate until a cleanup job
  (a later ticket) purges them; the repositories already expose `deleteExpiredBefore`.
- The frontend must read `XSRF-TOKEN` and send `X-XSRF-TOKEN` on refresh/logout.

## Alternatives considered

- **Stateless JWT-only (no refresh, no revocation)** — rejected: cannot revoke on
  logout/password change, and long-lived access tokens are unsafe.
- **Server-side sessions** — rejected: the API is designed stateless; a JWT +
  rotating refresh cookie gives the same UX without session affinity.
- **Storing refresh tokens in `localStorage`** — rejected: XSS could exfiltrate
  them; the HttpOnly cookie cannot be read by scripts.
- **`jjwt` instead of Nimbus** — rejected: Nimbus integrates natively with the
  Spring resource-server filter.

## Amendment (2026-11, #897 #901 #902 #903 #920)

Hardening of the session lifecycle (security review, epic #896):

- **Every credential or privilege change ends every session.** Besides
  password change, admin reset, generate-password and disable, the e-mail
  token password reset now also revokes the user's refresh families (#897),
  and so does **any role change** (#901): the role is a claim of the access
  token, so `password_invalidated_before` is bumped and refresh families are
  revoked; the user signs in again and gets a token carrying the current role.
  The password-reset writes are atomic, version-bumping updates so the
  context-clearing revocation in the same transaction cannot discard them.
- **A token whose user is missing or disabled is revoked** (#901).
  `TokenRevocationService.isRevoked` already loads the user per request; a
  deleted account (its refresh tokens cascade away) or a disabled one is now
  rejected outright instead of being waved through until expiry.
- **Rotation is atomic** (#902). The presented refresh-token row is read with
  `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`). A concurrent rotation of the
  same token waits, then sees it revoked and is handled as reuse (family
  revoked) — one token can never fork into two live successors.
- **The last-admin guard is race-free** (#920). Demote/disable/delete of an
  enabled admin locks the enabled-admin rows (ordered by id) before counting
  them, which serialises concurrent admin-removing changes; PostgreSQL
  re-checks a row a concurrent transaction changed and leaves it out of the
  count. No schema change.
- **Login timing is uniform** (#903). `AuthService.authenticate` runs exactly
  one password comparison on every path; unknown, disabled and password-less
  accounts are compared against a dummy hash computed at startup. Equalising
  the registration path (synchronous account creation and mail only for new
  addresses) is a follow-up on #903.
