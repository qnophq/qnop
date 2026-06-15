// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-app — the runnable Community module: @RestControllers implementing the
// published qnop-api contract, plus the Spring Boot bootstrap/config
// (io.qnop.bootstrap). Depends on qnop-core (services) and qnop-api (DTOs).
// Hosts the architecture-conformance tests. This module carries the Spring Boot
// plugin and is the bootable server (Phase 1, ADR-0020).

plugins {
    id("qnop.java-conventions")
    // Version comes from the root `plugins {}` block (declared there `apply false`
    // to keep a single shared plugin classloader — see the root build script).
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies)) // BOM: manages versions

    implementation(project(":qnop-core"))
    implementation(project(":qnop-api:qnop-api-endpoint")) // generated Spring interfaces (+ DTOs transitively)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // Servlet security filter chain (io.qnop.web.security, issue #10 / ADR-0022).
    implementation(libs.spring.boot.starter.security)
    // Resource-server filter that validates the bearer JWT on each request, plus
    // the JWT (Nimbus) types used by the web-layer decoder/cookie glue (issue #17).
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // OIDC/OAuth2 browser login chain (io.qnop.web.security, issue #21): the
    // oauth2Login DSL + authorized-client types. (qnop-core also uses the client
    // library, but that is an implementation dep and does not reach this compile
    // classpath.)
    implementation(libs.spring.boot.starter.oauth2.client)
    // Auth-endpoint rate limiting (io.qnop.web.security.ratelimit, issue #18 / ADR-0027):
    // Bucket4j token buckets in a Caffeine cache. Caffeine is BOM-managed (also used by the
    // #17 revocation denylist in qnop-core); declared here too because that is an
    // implementation dependency and does not reach this module's compile classpath.
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    // The bootstrap registers the sibling data packages explicitly via
    // @EntityScan / @EnableJpaRepositories (issue #11), so this module takes a
    // direct JPA dependency for those compile-time symbols (Hibernate itself
    // still reaches the runtime classpath transitively via qnop-core).
    implementation(libs.spring.boot.starter.data.jpa)

    // Runtime-only: the JDBC driver and the schema migrator (Liquibase owns the
    // schema; JPA ddl-auto=none). The changelog ships in qnop-core resources.
    // spring-boot-liquibase carries LiquibaseAutoConfiguration (Boot 4 modularised
    // it out of spring-boot-autoconfigure) and pulls liquibase-core transitively.
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.spring.boot.liquibase)

    testImplementation(platform(libs.spring.boot.dependencies)) // BOM also on the test classpath
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test) // @WebMvcTest slice (Boot 4 module)
    testImplementation(libs.spring.security.test)
    // The fail-fast binding test (QnopPropertiesBindingTest) drives Bean Validation +
    // ValidationAutoConfiguration directly; qnop-core keeps validation as an
    // implementation dependency, so the app test classpath needs it explicitly.
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
