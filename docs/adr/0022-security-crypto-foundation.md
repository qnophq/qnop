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
