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

## Amendment (2026-11, #900 #903 #905): registration, verification and bootstrap credentials

Self-registration and the first-boot admin are the two places where a credential
comes into being without an authenticated session. Both are hardened:

- **Pending verification is its own state.** `qnop_user.email_verification_pending`
  (migration `0039`, check `NOT (email_verification_pending AND enabled)`) marks a
  self-registration that has not confirmed its address. It is distinct from an
  administrator-disabled account: verification activates **only** a pending
  account, through a conditional, version-bumping update
  (`… WHERE email_verification_pending`), so a verification link can never re-enable
  an account an administrator decided about — not even in a race. Any explicit
  enabled-state decision (`User.setEnabled`, i.e. every admin edit) settles the
  pending flag.
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
  `INVALID_VERIFICATION_TOKEN`). Whoever registers an address they do not control
  gains nothing: the owner who receives the link cannot confirm a password they do
  not know, and a new registration for an address that is still pending
  **replaces** the unverified username, display name and password and supersedes
  the earlier link. Nobody has proven control of the mailbox at that point, so no
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
  nobody. Admin-initiated setup links stay synchronous because the admin UI needs
  the send result. (Login timing: issue #903, first part.)
- **First-boot admin credential.** A generated password is written to
  `admin-password.txt` inside a new `qnop-bootstrap-<random>` directory created
  with `rwx------` in one step, the file with `CREATE_NEW` + `rw-------` in one step:
  nothing pre-existing (file or symbolic link) is ever followed, there is no window
  with wider permissions, and where this cannot be guaranteed no file is written
  (stderr remains). `QNOP_ADMIN_PASSWORD` — a lasting ADMIN credential without a
  forced change — must be ≥ 12 characters, ≤ 72 bytes, not contain the username and
  mix three character classes (or be a 20+ character passphrase); otherwise the
  startup fails.

Accepted consequences: the verify step now asks for a password, so a user who
forgot the password chosen minutes earlier registers again (which replaces the
pending registration). An admin who edits a pending account in any way turns it
into an ordinary (possibly disabled) account the link can no longer activate. The
admin user list does not yet show the pending state separately (follow-up).
