# ADR-0022: Security & crypto foundation — layer placement and primitives

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** qnop core team (with Claude)

## Context

Every auth ticket in epic #7 (#12–#23: tokens, local users, OIDC, settings, mail, branding) needs the same primitives: a password hasher, symmetric encryption for secrets at rest, signing-key material for JWTs, validated configuration, and a servlet security filter chain. Issue #10 builds that shared foundation before any of it.

Two forces pull against each other:

- The **service layer** (`qnop-core`, `io.qnop.service`) must inject crypto primitives (`PasswordEncoder`, `TextEncryptor`, key derivation) — so they cannot live above it in `qnop-app`, or the layering (ADR-0004) inverts.
- The **`SecurityFilterChain`** is a servlet/web concern (CORS, CSP, 401 entry point, request matrix) and belongs in the web layer (`qnop-app`). Putting it in `qnop-core` would drag `spring-security-web` and the servlet API into the framework-light core.

## Decision

Split the foundation across the two existing modules, introducing **one new ArchUnit layer** for the framework-light half:

- **`io.qnop.security` in `qnop-core`** — `QnopProperties` (validated `@ConfigurationProperties`), `Hkdf` (RFC 5869), `JwtKeyService`, and `CryptoConfiguration` (`BCryptPasswordEncoder`, `Encryptors.delux` `TextEncryptor`). Depends only on `spring-security-crypto` + Bean Validation — no servlet, no web. Declared as the ArchUnit layer **Security**, accessible **only by the Service and Web layers**.
- **`io.qnop.web.security` in `qnop-app`** — `SecurityConfiguration` (the `SecurityFilterChain`, CORS source, JSON 401 entry point). Part of the existing **Web** layer; takes `spring-boot-starter-security`.

Crypto choices: **BCrypt** for password hashing; **`Encryptors.delux`** (AES) keyed by `qnop.auth.encryption-key` + hex `qnop.auth.encryption-salt` for text-at-rest; **HKDF-SHA256** to derive domain-separated JWT keys from `qnop.auth.jwt-secret` (one independent key per purpose label).

**Fail fast on weak secrets.** `QnopProperties` is `@Validated`; `@ValidSecret` rejects blank, short (< 32 char), or known-placeholder secrets, and the salt must be hex. A context bound to a `.env.example` default refuses to start. Secrets come from the `QNOP_AUTH_*` environment namespace (ADR-0020); no secure default is compiled in.

## Consequences

- Services inject crypto beans by type from `qnop-core`; the web filter chain stays in `qnop-app`. Core remains servlet-free.
- `io.qnop.security` deviates from issue #10's literal "one `io.qnop.security` package" wording (the filter chain lands in `io.qnop.web.security`) — recorded here as the deliberate trade-off for clean layering.
- A misconfigured deployment fails at boot instead of running with a public secret — but local `bootRun` now requires real `QNOP_AUTH_*` values (documented in the README and `.env.example`).
- Token issuance/verification itself is **not** here; #10 ships only the key-derivation foundation (issue #17 builds on it).

## Alternatives considered

- **Everything in `io.qnop.security` in `qnop-core`** (issue's literal shape). Rejected: forces `spring-security-web` + `jakarta.servlet` into the framework-light core and blurs the web/core boundary.
- **Everything in `qnop-app`.** Rejected: the service layer could not inject `PasswordEncoder`/`TextEncryptor` without an upward dependency, inverting ADR-0004.
- **Spring Security defaults (form login, stateful session, default CSP).** Rejected: this is a stateless token API; a login redirect and session cookies are the wrong shape, and the default headers are too lax for a JSON API.

## Amendment (2026-11, #927 — versioned secrets at rest and key rotation)

The original text-at-rest choice, `Encryptors.delux`, derives its AES key with PBKDF2-HMAC-SHA1 at 1024 iterations from a static salt and writes no key identifier. Both halves were weak spots: the derivation adds little over the raw key, and with no key id a changed `qnop.auth.encryption-key` made every stored secret unreadable, so the key could never be rotated.

**Format.** Secrets at rest (the sensitive application settings such as `smtp.password`, and `oidc_provider.client_secret_encrypted`) are now written by `io.qnop.security.SecretCipher` as

```
v2:<keyId>:<base64url(nonce || ciphertext || tag)>
```

- **AES-256-GCM**, a fresh random **96-bit nonce** per value, 128-bit tag.
- The data key is **HKDF-SHA256** over `qnop.auth.encryption-key` (input keying material), the hex-decoded `qnop.auth.encryption-salt` (HKDF salt) and the purpose label `qnop:secrets-at-rest:aes-256-gcm:v2`. The KDF is deliberately cheap: the input is required to be a high-entropy secret, not a password, so stretching buys nothing.
- **Key id** = 8 hex characters of HKDF output under the separate label `qnop:secrets-at-rest:key-id:v2` — a fingerprint that names the key without revealing anything usable about it.
- The `v2:<keyId>` header is GCM **associated data**, so a value cannot be relabelled to another version or key without failing the tag check.
- `@ValidSecret` gains a light sanity check besides the length: at least 8 distinct characters, which rejects degenerate values (a repeated character or short pattern) that pass the length rule.

The purpose labels are unique among the HKDF users (`qnop:jwt:*`, the review pseudonym and device-cookie keys).

**Legacy.** Unprefixed values are the hex output of the old `Encryptors.delux`; they are still decrypted (under the current key, then the previous one) but never written. Every write uses the current format, and a **re-encryption pass** (`SecretReEncryptionService`) runs once at each start, after the application is ready: it rewrites every stored secret that is legacy or under a non-current key. It is idempotent (current values are skipped), runs under a ShedLock (ADR-0029, lock `secretReEncryption`), and each rewrite is a compare-and-set on the ciphertext that was read, so a concurrent admin edit or a second instance is never overwritten. A pass that rewrote anything records a `secrets.reencrypted` SYSTEM audit event (counts and the current key id only).

**Rotation.** `qnop.auth.previous-encryption-key` (`QNOP_AUTH_ENCRYPTION_KEY_PREVIOUS`) and the optional `qnop.auth.previous-encryption-salt` (`QNOP_AUTH_ENCRYPTION_SALT_PREVIOUS`; blank = same salt as the current key) are accepted for **decryption only**. The procedure — set the new key and move the old one to *previous*, restart, let the pass re-encrypt, then remove *previous* and restart — is documented in `docs/DEPLOYMENT.md`. A previous key must meet the same strength rule as the current one. A start-up pass, not a recurring job, is sufficient: the key only changes with a restart.

**Unreadable secrets — not fail-closed.** A stored secret that no configured key can decrypt (rotated away without keeping the old key as *previous*, or altered — the GCM tag rejects it) does **not** stop the instance. Before #927 it did, by accident: the settings snapshot threw at start-up. Refusing to boot over one SMTP password or one OIDC provider bricks the whole deployment, including the admin screens that would fix it. Instead the value reads as **unset**, with a loud error log naming the setting/provider (never the value), and the start-up pass records a `secrets.unreadable` SYSTEM audit event listing them, visible to administrators and auditors in the audit trail. The ciphertext is preserved — the settings snapshot never writes it back, and `OidcProvider` is `@DynamicUpdate` so an unrelated provider edit does not overwrite the column — so restoring the right key as *previous* recovers it, and an administrator can alternatively enter the secret again. Failing open here is safe because every affected feature (SMTP login, an OIDC client) then fails at its own boundary instead of with a wrong secret.
