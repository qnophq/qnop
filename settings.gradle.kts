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
// Boundary between open-source core and commercial add-ons is `qnop-spi`
// (see docs/adr/0003-agpl-boundary-is-the-spi.md). Enterprise modules live
// in a separate private repository and are NOT included here.
include(
    "qnop-spi",          // pure extension-point interfaces + DTOs (published artifact)
    "qnop-domain",       // framework-free entities, value objects, workflow state machine
    "qnop-application",  // use cases, ports, orchestration
    "qnop-persistence",  // JPA adapters, Flyway migrations
    "qnop-storage",      // StorageProvider default adapter (S3/MinIO)
    "qnop-document",      // text extraction / conversion / anchoring adapters
    "qnop-security",     // authn/authz, user & team model
    "qnop-web",          // REST controllers, DTO mapping, OpenAPI
    "qnop-bootstrap",    // composition root / Spring Boot bootstrap (Community build)
)
