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
  deleted account or a disabled one is now rejected outright instead of being
  waved through until expiry. Deletion locks the user row, revokes the refresh
  tokens explicitly and then deletes (the FK cascade removes the rows).
- **Rotation is atomic** (#902). The presented refresh-token row is read with
  `PESSIMISTIC_WRITE` (Hibernate renders this as `FOR NO KEY UPDATE` on
  PostgreSQL). A concurrent rotation of the same token waits, then sees it
  revoked and is handled as reuse (family revoked) — one token can never fork
  into two live successors.
- **Rotation cannot outrun a revocation.** Before the token row, rotation
  share-locks the owning user row (`PESSIMISTIC_READ`, `FOR SHARE`). Every
  revocation path writes the user row first and the token rows second, so the
  lock order is always user → token (no deadlock), and a revocation either
  commits before the rotation checks the user or waits until the rotation's
  successor exists and is revoked with the rest. Rotation also refuses (and
  revokes the family, reason `INVALIDATED`) a token issued before
  `password_invalidated_before` or belonging to a missing/disabled user.
- **The last-admin guard is race-free** (#920). Demote/disable/delete of an
  enabled admin locks the enabled-admin rows (ordered by id, `PESSIMISTIC_WRITE`
  → `FOR NO KEY UPDATE`) before counting them, which serialises concurrent
  admin-removing changes; PostgreSQL re-checks a row a concurrent transaction
  changed and leaves it out of the count. No schema change.
- **Multiple tabs.** Strict reuse detection means two tabs presenting the same
  single-use refresh cookie at once would revoke the family and sign the user
  out everywhere. The server deliberately keeps no grace window; instead the
  SPA serialises refreshes across tabs with the Web Locks API
  (`navigator.locks.request('qnop-refresh', …)` in `qnop-ui/src/api/refresh.ts`).
  The cookie jar is shared, so a waiting tab sends the cookie the first tab's
  response already rotated and succeeds. Without `navigator.locks` the
  previous, unserialised behaviour remains.
- **Login timing is uniform** (#903). `AuthService.authenticate` runs exactly
  one password comparison on every path; unknown, disabled and password-less
  accounts are compared against a dummy hash computed at startup. Equalising
  the registration path (synchronous account creation and mail only for new
  addresses) is a follow-up on #903.

## Amendment (2026-11, #900 #903 #905): registration, verification and bootstrap credentials

Self-registration and the first-boot admin are the two places where a credential
comes into being without an authenticated session. Both are hardened:

- **Pending verification is its own state.** `qnop_user.email_verification_pending`
  (migration `0039`, check `NOT (email_verification_pending AND enabled)`) marks a
  self-registration that has not confirmed its address. It is distinct from an
  administrator-disabled account: verification activates **only** a pending
  account, through a conditional, version-bumping update
  (`… WHERE email_verification_pending`), so a verification link can never re-enable
  an account an administrator decided about — not even in a race. An explicit
  enabled-state decision by an administrator (`enabled` present in the update)
  settles the pending flag; a display-name or role edit does not.
  *Backfill:* only provably untouched, never-verified self-registrations become
  pending — internal, disabled, never signed in, `version = 0` (no edit after the
  insert) and holding an unconsumed verification token. Every other disabled
  account stays administrator-disabled: when in doubt the account is **not**
  reopened for self-service verification.
- **A password becomes a credential only once the mailbox owner confirms it.**
  `POST /auth/verify-email` takes the token **and** the password chosen at
  registration (the former `GET ?token=` is gone). The token is checked without
  being consumed, then the password, and only then is the single-use token spent,
  so a typo does not burn the link (`VERIFICATION_PASSWORD_MISMATCH` vs.
  `INVALID_VERIFICATION_TOKEN`). Guessing is bounded twice: each token counts wrong
  passwords (`email_verification_token.failed_attempts`) and is spent at the fifth,
  and the endpoint is rate-limited per client IP (default 10 per 15 minutes,
  `qnop.auth.rate-limit.verify-email.*`; the auth rate-limit catalogue may absorb it
  later). Whoever registers an address they do not control
  gains nothing: the owner who receives the link cannot confirm a password they do
  not know, and a new registration for an address that is still pending
  **replaces** the unverified username, display name, password and profile slug and
  supersedes the earlier link — but only once the last (re)registration is older
  than a 15-minute cool-down; within it the request is a uniform no-op that sends
  no mail, so a third party cannot keep killing the owner's fresh link or flood
  the mailbox. Nobody has proven control of the mailbox at that point, so no
  earlier registrant has a claim to protect.
- **Duplicate registration sends a neutral notice** (`auth.registration_attempt`)
  to the entered address when the address or username belongs to any other
  account; nothing is created or changed and the response stays uniform.
- **Timing does not tell the branches apart.** Registration encodes the password
  on every branch (BCrypt dominates the request), and the mails of the
  anti-enumeration flows — registration, notice and the self-service
  forgot-password request — are queued with
  `MailService.sendMailFromTemplateAfterCommit`: an `AFTER_COMMIT` event delivered
  by `DeferredMailListener` on the bounded `mailDispatchExecutor`, so neither
  rendering nor SMTP runs in the request thread and a rolled-back request mails
  nobody. A full queue drops the mail with a WARN rather than running it on the
  caller (which would bring the timing signal back); queued mails are delivered on
  shutdown (bounded wait), and the request's MDC travels with each task. Admin-initiated setup links stay synchronous because the admin UI needs
  the send result. (Login timing: issue #903, first part.)
- **First-boot admin credential.** A generated password is written to
  `admin-password.txt` inside a new `qnop-bootstrap-<random>` directory created
  with `rwx------` in one step, the file with `CREATE_NEW` + `rw-------` in one step:
  nothing pre-existing (file or symbolic link) is ever followed, there is no window
  with wider permissions, and where this cannot be guaranteed no file is written
  (stderr remains). `QNOP_ADMIN_PASSWORD` — a lasting ADMIN credential without a
  forced change — must be ≥ 12 characters, ≤ 72 bytes, not contain the username,
  use ≥ 8 different characters and mix three character classes (or be a 20+
  character passphrase); otherwise the startup fails. The password file is deleted
  once the generated password has been changed.

Accepted consequences: the verify step now asks for a password, so a user who
forgot the password chosen minutes earlier registers again after the cool-down
(which replaces the pending registration). An admin edit that decides the enabled
state of a pending account turns it into an ordinary (possibly disabled) account
the link can no longer activate. The admin user list does not yet show the pending
state separately (follow-up).
