# ADR-0020: Bootable server — PostgreSQL + Liquibase + JPA, tests on Testcontainers

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Phase 0 left a compiling skeleton with no runnable server. Phase 1 (identity & application administration, epic #7) needs a booting Spring Boot application with a real database before any entity, repository or service work can land. We also need a test database that faithfully exercises **Postgres-only** schema features — `CHECK` constraints and partial/functional unique indexes — because the identity schema (#11) and others depend on them. H2 cannot represent those.

## Decision

- **`qnop-web` is the bootable module.** It carries the Spring Boot Gradle plugin; `io.qnop.bootstrap.QnopApplication` is the entry point. Because the layered modules use sibling packages, scanning is pointed at the `io.qnop` root with explicit `@EntityScan("io.qnop.entity")` and `@EnableJpaRepositories("io.qnop.repository")`.
- **PostgreSQL is the database; Liquibase owns the schema.** The master changelog lives in `qnop-core` (`classpath:/db/changelog/db.changelog-master.yaml`); JPA runs with `hibernate.ddl-auto=none` so Hibernate never alters the schema. Real changesets land under `db/changelog/migrations/` from #11 onward.
- **Versions via the Spring Boot BOM.** `spring-boot-dependencies` (imported with `platform(...)`) manages the Spring stack, JPA/Hibernate, the PostgreSQL driver, Liquibase and Testcontainers. The previous standalone `postgresql`/`liquibase` version pins are dropped in favour of the BOM.
- **Configuration via environment.** `application.yml` reads `QNOP_DB_URL`/`QNOP_DB_USERNAME`/`QNOP_DB_PASSWORD` with local-dev defaults that match `docker-compose.yml`; production overrides via the environment.
- **Tests run against a real PostgreSQL via Testcontainers** (`@ServiceConnection`, image `postgres:17` to match local infra). A context test boots the app, lets Liquibase apply the baseline changelog, and asserts the actuator health endpoint reports `UP`.

## Consequences

- Test ↔ production parity on the database: Postgres-only constraints/indexes are validated in CI.
- **Docker is required to run the test suite** (`./gradlew build`/`test`). CI's `ubuntu-latest` provides it; local developers need Docker running. Documented in the README.
- Slightly slower test startup (container boot), accepted for fidelity.
- Local `bootRun` targets the `docker-compose` Postgres out of the box.

## Alternatives considered

- **H2 (in-memory), Liquibase off, Hibernate DDL for tests** (plugwerk's `test` profile). Rejected here: it cannot represent the partial/functional unique indexes and `CHECK` constraints the identity model relies on, so the migrations would go untested.
- **Zonky embedded-postgres.** Real Postgres without Docker, but a less standard, less maintained path than Testcontainers and harder to pin to the exact prod image.
- **A shared dev database for tests.** Rejected — not isolated, not reproducible in CI.
