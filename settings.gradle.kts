// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only

rootProject.name = "qnop"

pluginManagement {
    // Convention plugins live in an included build (see build-logic/).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Subprojects declare no repositories themselves; central source of truth here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// --- AGPL core modules ---------------------------------------------------
// Layered architecture (see docs/adr/0004-layered-architecture-enforced-by-archunit.md).
// Commercial add-ons (in a separate private repository) link only against
// qnop-spi (ADR-0002/0003).
//
// The published REST contract (qnop-api) is split into two submodules (ADR-0021):
// qnop-api-model holds the pure DTOs (Spring-free — the external stability
// surface), qnop-api-endpoint the generated Spring MVC interfaces that qnop-app
// implements. The `:qnop-api` container project is created implicitly by the
// path-includes and carries only the shared OpenAPI spec.
include(
    "qnop-spi",                    // published plugin contract: extension-point interfaces + DTOs (Spring-free)
    "qnop-api:qnop-api-model",     // published REST DTOs (java generator, models-only) — Spring-free
    "qnop-api:qnop-api-endpoint",  // generated Spring MVC interfaces (spring generator, apis-only)
    "qnop-core",                   // entity/ repository/ service/ + SPI default beans (the Spring backend core)
    "qnop-app",                    // @RestControllers implementing qnop-api + Spring Boot bootstrap/config
)
