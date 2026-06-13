# ADR-0006: Gradle Kotlin DSL, convention plugins, JDK 21

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

A growing multi-module backend needs a maintainable, type-safe build with shared configuration, and a JDK baseline compatible with Spring Boot 4.x (the target application framework, introduced in Phase 1).

## Decision

- **Gradle with the Kotlin DSL** (`*.gradle.kts`). Application code remains **Java**.
- Shared build configuration lives in an included build, `build-logic/`, as a precompiled convention plugin `qnop.java-conventions` (Java toolchain, Spotless, test framework) — **no** `allprojects {}`/`subprojects {}` cross-configuration.
- Dependency versions are centralized in a **version catalog** (`gradle/libs.versions.toml`).
- **JDK 21 (LTS)** toolchain, pinned via Gradle toolchains.
- Code formatting and the SPDX license header are enforced by **Spotless** (`google-java-format`); see [ADR-0007](0007-spdx-dco-license-scanning.md).

## Consequences

- Type-safe, IDE-assisted build scripts; consistent module configuration.
- One caveat: precompiled convention plugins can't read the version catalog at plugin-resolution time, so the Spotless plugin version is duplicated in `build-logic/build.gradle.kts` with a sync comment.

## Alternatives considered

- **Groovy DSL** — rejected: weaker type-safety/IDE support for a growing build.
- **Maven** — rejected: the team prefers Gradle's flexibility for multi-module + convention plugins.
- **`subprojects {}` cross-config** — rejected: discouraged; convention plugins are the modern approach.
