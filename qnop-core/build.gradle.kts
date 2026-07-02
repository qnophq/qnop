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

    // OIDC/OAuth2 provider admin (issue #21): ClientRegistrations.fromIssuerLocation
    // for endpoint discovery. The browser login flow is wired in qnop-app (PR B).
    implementation(libs.spring.boot.starter.oauth2.client)

    // Mail subsystem (issue #19): runtime SMTP (JavaMailSender) + logic-less
    // Mustache rendering of DB-stored, admin-editable templates.
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.jmustache)

    // Distributed scheduler locks (issue #52, ADR-0029): the @SchedulerLock
    // annotation on the @Scheduled cleanup sweeps. The Postgres-backed LockProvider
    // that makes the lock real is wired in qnop-app.
    implementation(libs.shedlock.spring)

    // Object storage (issue #243, ADR-0005): the AWS SDK v2 S3 client backs the
    // StorageProvider default (io.qnop.service.storage) via endpointOverride +
    // path-style, so MinIO / S3 / GCS is a deploy-time config switch.
    implementation(libs.aws.sdk.s3)

    // PDF extraction (issue #245, ADR-0032): PDFBox turns uploads into RenderedDocuments;
    // Jackson 3 (tools.jackson, BOM-managed — the same stack Boot 4's MVC uses) serializes
    // them into the jsonb column, so stored payloads and HTTP responses share one mapper line.
    implementation(libs.pdfbox)
    implementation(libs.jackson3.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Brings AssertJ + Mockito for the mail-layer unit tests (stubbed JavaMailSender).
    testImplementation(libs.spring.boot.starter.test)
}
