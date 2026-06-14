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
// Two modules are published, Spring-free contracts; commercial add-ons (in a
// separate private repository) link only against qnop-spi (ADR-0002/0003).
include(
    "qnop-spi",   // published plugin contract: extension-point interfaces + DTOs (Spring-free)
    "qnop-api",   // published REST contract: request/response DTOs + OpenAPI (Spring-free)
    "qnop-core",  // entity/ repository/ service/ + SPI default beans (the Spring backend core)
    "qnop-web",   // @RestControllers implementing qnop-api + Spring Boot bootstrap/config
)
