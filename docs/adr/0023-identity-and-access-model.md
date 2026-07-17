# ADR-0023: Identity & access model (schema level)

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Epic #7 (identity & application administration) needs persisted users and identity
providers before any authentication work (#10, #12, #17, #20, #21) can land. The
first migration must fix the shape of `qnop_user`, `oidc_provider` and
`oidc_identity`, including the Postgres-only invariants that JPA cannot express
(ADR-0020), and decide how a single user table accommodates both locally
authenticated and externally provisioned (OIDC) users.

## Decision

- **One user table, two sources.** `qnop_user` carries a `UserSource` enum
  (`INTERNAL` | `EXTERNAL`). Internal users authenticate locally and therefore
  have a `username` + `password_hash`; external users are provisioned from an
  identity provider and have neither. The mutual exclusivity is a Postgres
  `CHECK`, not application logic.
- **Uniqueness scoped to internal users.** Case-insensitive email uniqueness and
  username uniqueness are enforced by *partial* unique indexes
  (`WHERE source = 'INTERNAL'`). External users may legitimately share an email
  (or arrive without a unique local handle), so global unique constraints would
  be wrong.
- **OIDC providers are DB-configured.** `oidc_provider` stores provider type
  (`OIDC|GITHUB|GOOGLE|FACEBOOK|OAUTH2`, `CHECK`-guarded), client id/secret,
  issuer/endpoint URIs and attribute mappings, so providers are managed at
  runtime (#21) rather than via static config.
- **`oidc_identity` binds a provider subject to a user.** `unique(provider, subject)`
  prevents two users claiming the same external identity; `user_id` is itself
  unique, so a user has at most one external identity for now. Both foreign keys
  are `ON DELETE CASCADE`.
- **Postgres-only constraints live in Liquibase** (ADR-0020); JPA runs
  `ddl-auto=none` and entities map columns 1:1. Foreign keys are kept as raw
  `UUID` columns rather than `@ManyToOne` associations.
- **UUIDv7 primary keys, generated application-side** via Hibernate
  `@UuidGenerator(style = VERSION_7)` (Hibernate ORM 7). Time-ordered UUIDs keep
  B-tree index locality without a DB round-trip, and the id is known before
  insert. Retained even though the infra image is now Postgres 18 (issue #199),
  whose native `uuidv7()` would tie generation to the DB version and only yield
  the id after the write.
- **Client secrets are encrypted at rest** via a JPA `AttributeConverter`
  (`EncryptedStringConverter`) backed by the `TextEncryptor` from the
  security/crypto foundation (`io.qnop.security.CryptoConfiguration`, ADR-0022).
  The converter is a Spring bean, so Hibernate resolves it through Spring's bean
  container and injects the application encryptor. `oidc_provider.client_secret`
  is therefore never written in clear, from the first identity migration onward.

## Consequences

- A user can be linked to at most one external provider initially; multi-provider
  linking is a deliberate future change (drop the `user_id` unique constraint).
- Encrypted columns are non-deterministic and therefore cannot be queried or
  used as unique keys — acceptable, secrets are never lookup keys.
- Encryption depends on the security/crypto foundation (ADR-0022): the
  `qnop.auth.encryption-key` / `encryption-salt` secrets must be configured, and
  the context fails fast otherwise.
- The test suite gains real-Postgres coverage of the CHECK/partial-unique/cascade
  behaviour (Testcontainers), which is the reason ADR-0020 chose Postgres over H2.

## Alternatives considered

- **Separate `internal_user` / `external_user` tables** — rejected: identity is
  one concept; a single table with a discriminator keeps reviews/annotations
  referencing one `user_id`.
- **A pure-domain model with an entity mapper** — rejected per ADR-0004 (JPA
  entities are the model).
- **UUIDv4 keys** — rejected: random keys hurt index locality at scale.
- **Native `uuidv7()` DB default** — available on the infra image (Postgres 18)
  but not chosen: it would couple key generation to the DB version and only
  expose the id after the write, whereas app-side generation keeps the id known
  before insert and independent of the Postgres version.
- **Encrypting secrets in a service layer instead of a converter** — deferred
  until an `oidc_provider` service exists; the converter makes encryption
  transparent and testable from the schema layer today.
