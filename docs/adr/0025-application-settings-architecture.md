# ADR-0025: Application settings — registry-authoritative, snapshot-backed runtime

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** qnop core team (with Claude)

## Context

The application needs operator-configurable global settings (general, upload, tracking, SMTP, auth) read on hot paths and edited by superadmins. The schema (issue #13) gives `application_setting` (typed key/value) and `user_setting`. Issue #16 adds the runtime service and admin API. Three questions had to be settled: where the source of truth for keys/types/defaults lives, how reads stay cheap and consistent, and how secrets are handled.

## Decision

- **A code registry is authoritative for keys.** `ApplicationSettingKey` (an enum) defines every global key with its `SettingValueType`, default, description, and — for `ENUM` — its allowed options. The `application_setting` table is the **persisted projection**: the Liquibase seed (issue #13) initializes it and must mirror the registry. `value_type` is stored on the row as a denormalized, self-describing copy (admin UI rendering, redaction, DBA/ops) — see the rationale below.
- **Reads are served from an immutable snapshot** held in an `AtomicReference<Map<ApplicationSettingKey,String>>`. The snapshot holds *effective, decrypted* values; it is rebuilt **after a write commits** (`TransactionSynchronization.afterCommit`), then registered `SettingsChangeListener` beans are notified of the changed keys (e.g. SMTP reconfiguration, issue #19). Reads are lock-free; writes are rare.
- **Secrets are encrypted at the value, not the column.** `PASSWORD` values are encrypted with the application `TextEncryptor` (ADR-0022) on write and decrypted into the snapshot on load. The admin API masks them as `***` (`ConfigurationKeyRedactor`); sending the mask back on `PATCH` means "unchanged", so a secret never round-trips through the browser.
- **`user_setting` carries no type.** Per-user keys are typed by their own registry (issues #22/#24), not by a per-row `value_type` — there are N rows per key, so a per-row type would be redundant and drift-prone. The 1:1 `application_setting` row may carry `value_type`; the N:1 `user_setting` rows may not.
- **Admin authorization is a URL rule.** `/api/v1/admin/**` requires `SUPERADMIN` in the security filter chain (issue #10), covering all current and future admin endpoints. The authenticated principal (and thus `updated_by` attribution) is wired with the auth subsystem (issue #17).

## Consequences

- Hot-path reads never touch the DB and see a consistent, post-commit view.
- The registry and the `#13` seed must be kept in sync (asserted by a test); the registry wins on conflict.
- Secrets are safe at rest and in transit; the masked-sentinel update keeps the API simple.
- Endpoints are authorization-ready now but only reachable end-to-end once issue #17 provides authentication.

## Alternatives considered

- **DB-only definition (drop the registry, derive types from rows).** Rejected: defaults/validation/enum-options are behavior that belongs in code; a registry gives compile-time exhaustiveness.
- **Per-request DB reads / a cache abstraction (Caffeine).** Rejected as premature: the settings set is tiny and changes rarely; an `AtomicReference` snapshot is simpler and allocation-free on reads.
- **A native Postgres enum for `value_type`.** Rejected: a `VARCHAR` + `CHECK` (issue #13) matches the project's enum convention and avoids Postgres enum migration friction.
