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
    implementation(project(":qnop-api"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // The bootstrap registers the sibling data packages explicitly via
    // @EntityScan / @EnableJpaRepositories (issue #11), so this module takes a
    // direct JPA dependency for those compile-time symbols (Hibernate itself
    // still reaches the runtime classpath transitively via qnop-core).
    implementation(libs.spring.boot.starter.data.jpa)

    // Runtime-only: the JDBC driver and the schema migrator (Liquibase owns the
    // schema; JPA ddl-auto=none). The changelog ships in qnop-core resources.
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.liquibase.core)

    testImplementation(platform(libs.spring.boot.dependencies)) // BOM also on the test classpath
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
