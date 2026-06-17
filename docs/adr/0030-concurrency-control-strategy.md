# ADR-0030: Concurrency control for entity writes

- **Status:** Accepted
- **Date:** 2026-06-17
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

PR #56 (issue #47) added `@Version` to `ApplicationSetting` because the review
flagged that one entity. A systematic look (issue #61) at the other entities' write
patterns found two genuinely racy ones that #47 did not cover:

- **`User` read-modify-write.** Several flows do `findById → setX → save`:
  `last_login_at` on every login, `password_invalidated_before` on token revocation
  (`TokenRevocationService.revokeAllForUser`), the password hash on change/reset, and
  admin enable/disable. Because a JPA full-entity save writes **every** column, a
  high-frequency low-value write (a login stamping `last_login_at`) can silently
  revert a concurrent **security** write (a revocation bumping
  `password_invalidated_before`) — a lost revocation.
- **Single-use tokens.** `EmailVerificationToken` / `PasswordResetToken` `consume()`
  is check-then-act (`if (consumedAt != null) reject; setConsumedAt(now)`), so two
  concurrent requests with the same token can both succeed — a double-consume
  (password-reset replay).

## Decision

Match the control to the write shape rather than blanket-applying `@Version`:

1. **Atomic targeted `@Modifying` updates** for the security-critical and
   high-frequency single-column `User` writes, eliminating the read-modify-write:
   - `bumpPasswordInvalidatedBefore(id, at)` — sets the column **and** `version =
     version + 1`. The revocation is always applied, and the version bump makes any
     concurrently-loaded stale entity's later full save fail optimistically instead
     of reverting the revocation.
   - `updatePasswordHash(id, hash)` — same, version-bumping.
   - `touchLastLogin(id, at)` — best-effort, **no** version bump (a login marker may
     be overwritten by a concurrent edit; harmless, and it must not make logins
     fail).
   `AuthService.changePassword` now reads the user for verification only and applies
   the hash via the atomic update — it never dirty-saves the entity.

2. **Optimistic locking (`@Version`) on `User`** as the backstop for the remaining
   genuine multi-field full-entity edits (admin enable/disable, `applyPasswordReset`,
   registration inserts). A concurrent conflict there is rare and surfaces as a
   loud failure (correct) rather than a silent lost update. No retry is added for
   these admin-initiated flows; the security-critical writes don't need it because
   they are atomic.

3. **Conditional atomic consume** for single-use tokens (follow-up step under #61):
   `UPDATE … SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL`, checking
   the affected-row count, so a token is consumed at most once.

`ApplicationSetting` keeps the `@Version` + retry approach from #47 (its writes are
genuinely multi-field, admin-edited read-modify-writes).

## Consequences

- A token revocation can no longer be silently reverted by a concurrent login or
  edit — the core security fix.
- Logins stay cheap and never fail on contention (atomic, no version bump).
- Admin edits of the same user concurrently now fail loudly (optimistic conflict)
  instead of losing an update; callers may retry. A future global `@ControllerAdvice`
  (#45) should map the conflict to `409`.
- Mixing bulk `version = version + 1` updates with `@Version` requires care: the
  affected flows must not also dirty-save the same entity in the same transaction
  (verified for `changePassword`).
- Config entities (`OidcProvider`, `MailTemplate`, `UserSetting`) are **not** changed
  — their admin/single-user last-writer-wins edits are low-impact; revisit only if a
  concrete conflict surfaces.

## Alternatives considered

- **`@Version` + retry everywhere on `User`.** Rejected: would make every login do a
  version-checked full save and retry on contention — needless overhead on the hot
  path, and still clobber-prone unless every write is version-checked. Targeted
  atomic updates are simpler and strictly correct for the single-column writes.
- **Serializable isolation.** Rejected: heavy, broad performance impact for a
  localized problem.
- **`@Version` on the single-use tokens.** Works, but a conditional `UPDATE … WHERE
  consumed_at IS NULL` expresses "consume once" directly and avoids a load-modify
  round-trip.
