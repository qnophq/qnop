# ADR-0020: Bootable server — PostgreSQL + Liquibase + JPA, tests on Testcontainers

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

Phase 0 left a compiling skeleton with no runnable server. Phase 1 (identity & application administration, epic #7) needs a booting Spring Boot application with a real database before any entity, repository or service work can land. We also need a test database that faithfully exercises **Postgres-only** schema features — `CHECK` constraints and partial/functional unique indexes — because the identity schema (#11) and others depend on them. H2 cannot represent those.

## Decision

- **`qnop-app` is the bootable module.** It carries the Spring Boot Gradle plugin; `io.qnop.bootstrap.QnopApplication` is the entry point. Because the layered modules use sibling packages, component scanning is pointed at the `io.qnop` root via `@SpringBootApplication(scanBasePackages = "io.qnop")`. Explicit `@EntityScan`/`@EnableJpaRepositories` for `io.qnop.entity`/`io.qnop.repository` are added with the data model (#11), once entities and repositories exist.
- **PostgreSQL is the database; Liquibase owns the schema.** The master changelog lives in `qnop-core` (`classpath:/db/changelog/db.changelog-master.yaml`); JPA runs with `hibernate.ddl-auto=none` so Hibernate never alters the schema. Real changesets land under `db/changelog/migrations/` from #11 onward.
- **Versions via the Spring Boot BOM.** `spring-boot-dependencies` (imported with `platform(...)`) manages the Spring stack, JPA/Hibernate, the PostgreSQL driver, Liquibase and Testcontainers. The previous standalone `postgresql`/`liquibase` version pins are dropped in favour of the BOM.
- **Configuration via environment — all qnop variables are `QNOP_`-prefixed.** `application.yml` builds the datasource from `QNOP_DB_HOST`/`QNOP_DB_PORT`/`QNOP_DB_NAME`/`QNOP_DB_USERNAME`/`QNOP_DB_PASSWORD`. The official Postgres/MinIO images mandate `POSTGRES_*`/`MINIO_*`, so `docker-compose.yml` maps the same `QNOP_*` inputs onto those image keys — `.env` stays a single `QNOP_`-namespaced source for both the containers and the app. Local-dev defaults match `docker-compose.yml`; production may instead set the standard `SPRING_DATASOURCE_*` keys, which override. This `QNOP_` prefix is the project-wide convention for application/operator environment variables (later config — JWT/encryption secrets — follows it, e.g. `QNOP_AUTH_*`).
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
