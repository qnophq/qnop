// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-core — the layered backend core: JPA entities, Spring Data repositories
// and the service layer (business logic, the review workflow state machine,
// annotation anchoring, API/DTO mapping, and the Community default beans that
// implement the qnop-spi extension points). Spring Boot is introduced here in
// Phase 1. (Currently package-info placeholders only.)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies)) // BOM: manages versions

    implementation(project(":qnop-spi")) // implements the extension-point defaults
    // Maps entities to/from the published REST DTOs. Depends on the Spring-free
    // model only (not qnop-api-endpoint) — the service layer must not see the
    // Spring MVC interfaces (ADR-0021).
    implementation(project(":qnop-api:qnop-api-model"))

    // Persistence: JPA entities + Spring Data repositories live in this module.
    implementation(libs.spring.boot.starter.data.jpa)

    // Security & crypto foundation (issue #10, ADR-0022): the framework-light
    // io.qnop.security layer — validated properties, BCrypt, TextEncryptor, HKDF.
    // The servlet filter chain lives in qnop-app; core stays free of
    // spring-security-web by depending only on spring-security-crypto. The
    // identity schema (issue #11) uses TextEncryptor to encrypt the OIDC client
    // secret at rest.
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.security.crypto)

    // JWT & session core (issue #17): Nimbus encode/decode for self-issued HS256
    // access tokens, and Caffeine for the in-memory revocation (jti) denylist.
    // Framework-light (no spring-security-web); the servlet resource-server
    // filter is wired in qnop-app.
    implementation(libs.spring.security.oauth2.jose)
    implementation(libs.caffeine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
