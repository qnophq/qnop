# ADR-0060: Machine-credential authentication — a decoder fallback, not a filter seam

- **Status:** Accepted
- **Date:** 2026-08-31
- **Deciders:** devtank42 (with Claude)

## Context

Service accounts — machine callers of the existing 106-operation API — are an enterprise extension (qnop-ee#19). The published contract declares exactly one security scheme, `bearerAuth`, and that JWT belongs to a user: minting requires a `User` row, the subject is a user UUID, the one authority comes from the human `role` claim. Issue #686 asks for the core-side seam, under hard constraints: nothing may weaken human authentication, a contributed mechanism must not be able to precede rate limiting or change failure rendering, and audit must stay attributable.

**The filter-chain question, answered first (as the issue demanded):** the core already runs two `SecurityFilterChain` beans, so an extension can add a third chain for its *own* paths (`/api/ext/<id>/**`) with standard Spring (`@AutoConfiguration` + `@Order` + `securityMatcher`) and **no core change**. Extension-owned endpoints therefore need nothing from this ADR. The seam below exists solely for the harder case: a machine credential that authenticates against the **existing** API surface.

## Decision

### The seam is a fallback inside bearer decoding

`qnop-spi` grows a fifth contract, `io.qnop.spi.auth`:

- **`BearerCredentialAuthenticator`** — `Optional<MachinePrincipal> authenticate(String bearerCredential)`; empty means "not mine", never throw for that.
- **`MachinePrincipal`** — `subject` (opaque string, namespaced by convention, e.g. `svc:reporting`) and `scopes` (the extension's own vocabulary).

`DelegatingJwtDecoder` consults the registered authenticators **only after the local decode has rejected the credential**, renders a claim as a synthetic `Jwt` (marker claim `qnop_actor_kind=machine`, scopes in `qnop_ext_scopes`), and an unclaimed credential re-throws the original rejection — the 401 shape never changes.

**The placement is the enforcement.** Because the seam lives inside bearer decoding, an extension structurally cannot: run before the rate limiters or CSRF handling (those sit in front of bearer processing), reorder anything, observe or intercept valid user tokens (the fallback fires only when the local decode failed), or render failures its own way. None of this is convention — there is no API through which the extension could try. One caveat is enforced separately: a **signature-valid but expired user token** also fails the local decode, and it must not be handed to third-party code — the decoder rethrows validation failures (`JwtValidationException`) without consulting authenticators, so only credentials that are structurally not qnop tokens ever reach the seam; the SPI javadoc additionally obliges implementations never to log, store or forward an unclaimed credential.

### A contributed principal can never be, or become, a user

Two guards, both enforced in code:

1. **No UUID subjects.** User subjects are UUIDs; a contributed subject that parses as one is rejected (`BadJwtException`). `CurrentUser.requireUserId()` — the choke point of all 80 user-actor call sites — therefore answers 403 for every machine principal, exactly as its javadoc always promised.
2. **No human roles.** For a machine principal the converter ignores the `role` claim entirely and surfaces scopes as `EXT_`-prefixed authorities only. `hasRole("ADMIN"/"MEMBER"/"AUDITOR")` gates are structurally unsatisfiable.

Consequently a machine principal is *authenticated but nearly powerless* in the community core: it passes `.authenticated()`, and every user-actor and role-gated path refuses it. The residue — endpoints gated by neither a role nor `requireUserId()` (today: the banner read) — is pinned by an integration test, so an endpoint joining the machine-reachable surface is a deliberate, visible decision. That is deliberate. **Authorisation for machine access is the extension's business** (qnop-ee#19's scopes decide which of its own endpoints — or later, which opened read paths — a key may call). Opening individual community read paths to machine actors is the expected re-cut (ADR-0049), and it rides on the shared principal work with #684, not on this ADR.

### What stays with the extension

Credential issuance, storage, expiry, rotation, revocation, and machine-keyed rate limits on its own endpoints. The core's revocation store is user-keyed (`revoked_token.user_id` NOT NULL, FK) and does not apply — recorded here so nobody mistakes machine credentials as covered by user logout. The core hardening that rides along: `AuthController` no longer throws a raw `IllegalArgumentException` (500) for a non-UUID subject on logout/me — a machine principal gets the defined 401/no-op instead.

## Consequences

- `qnop-spi` publishes five contracts; semver discipline unchanged.
- qnop-ee#19 = one `BearerCredentialAuthenticator` bean + its own key management + its own `/api/ext/**` chain where it wants machine-only endpoints.
- The audit question stays answerable: machine principals cannot reach audited write paths in the community core, so `AuditEvent.actorId` keeps its user semantics until the #684 principal work widens it deliberately.
- `#672` (process-local rate-limit buckets) is not worsened: the seam adds no new unauthenticated surface.

## Alternatives considered

- **A filter-contribution SPI** (register filters, declare positions) — rejected: ordering and failure rendering would become negotiable, which is precisely what the constraints forbid; auditing such contributions is far harder than auditing a token-shaped function.
- **Mapping machine credentials onto bot `User` rows** (the `oidc_identity` precedent) — rejected: qnop-ee#19 enumerates the problems (quota, mail, roster pollution, offboarding); it would also make every machine actor a mentionable, listable person.
- **Teaching the core scopes** — rejected for now: a scope model without a second consumer would be cut wrong (ADR-0049); `EXT_` authorities carry the extension's vocabulary without the core learning it.
