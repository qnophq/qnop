# ADR-0039: Date/time localization — store UTC, transport ISO-with-offset, render in the user's zone

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** qnop core team

## Context

Timestamps cross three boundaries — the database, the REST contract, and the browser — and a
mistake at any one silently corrupts the others (a "10:15" with no zone is meaningless; an epoch
integer is unreadable; rendering server-local time misleads a reviewer in another zone). qnop is an
enterprise review tool whose users and teams span time zones, and audit trails, due dates, and
"who did what when" must be unambiguous. The pieces were mostly already right (every entity
timestamp is `java.time.Instant`; columns are `TIMESTAMP WITH TIME ZONE`), but the policy was
implicit and unguarded, and the frontend rendered every timestamp in the *browser's* zone,
ignoring the user's stored preference. This ADR records the end-to-end policy and the guards that
keep it true. Language i18n is a separate concern (issue #464); language ≠ time zone. See
issue #465.

## Decision

- **Store UTC.** Every persisted timestamp is a `java.time.Instant` — a zone-agnostic UTC epoch,
  never a `LocalDateTime`/`OffsetDateTime`/`java.sql.Timestamp`/`java.util.Date`. An ArchUnit rule
  (`entityTimestampsAreStoredAsInstant`) fails the build if a zone-less/zoned temporal field is ever
  added to `io.qnop.entity`. ShedLock's own lock table uses plain `TIMESTAMP` by its own convention
  (it operates in UTC) and is not a JPA entity, so it is exempt.
- **Transport ISO-8601 with an explicit offset.** The API serializes every timestamp as an
  ISO-8601 string ending in `Z` (e.g. `2026-07-12T10:15:30Z`), never epoch millis and never a
  zone-less local string. This is the Jackson 3 default; it is pinned explicitly in
  `application.yml` via `spring.jackson.datatype.datetime.WRITE_DATES_AS_TIMESTAMPS: false` (in
  Jackson 3 this moved from `SerializationFeature` to `DateTimeFeature`, bound by Spring Boot 4
  under `spring.jackson.datatype.datetime`), and a serialization test asserts it so a dependency
  bump cannot silently flip it.
- **Render in the user's zone, with a two-step fallback.** The frontend resolves the active display
  zone as **user profile `timezone` (`UserSettingKey.TIMEZONE`, IANA id) → application default
  (`ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE`, exposed on `/config` as
  `general.defaultTimezone`) → `UTC`**, and threads it into every `Intl.DateTimeFormat` through a
  single `useFormatters()` hook. No formatter renders in the raw browser zone.
- **Validate the configured zone.** Both the per-user and application-default zone values are
  validated as real IANA ids (`ZoneId.of`) at the setting boundary (`SettingConstraints.ValueFormat.TIMEZONE`).

## Consequences

- Timestamps are unambiguous end-to-end: one instant in the DB, one canonical wire format, one
  display zone chosen by the viewer — the same audit row reads correctly for a reviewer in Berlin
  and one in Tokyo.
- No data migration is needed: stored values are already UTC `Instant`s.
- The Jackson pin is Jackson-3-specific (`datatype.datetime`, exact enum key — relaxed kebab-case
  binding does not apply to `tools.jackson` enum map keys); a future Jackson upgrade must revisit
  the property path, and the serialization test is the tripwire.
- **Out of scope:** UI language translation (#464) and per-document/per-review display zones — the
  profile zone is the single display zone.
