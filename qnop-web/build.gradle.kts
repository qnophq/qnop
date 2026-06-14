// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-web — the runnable Community module: @RestControllers implementing the
// published qnop-api contract, plus the Spring Boot bootstrap/config
// (io.qnop.bootstrap). Depends on qnop-core (services) and qnop-api (DTOs).
// Hosts the architecture-conformance tests. The Spring Boot plugin and the
// bootable server are introduced in Phase 1. (Currently placeholders only.)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-core"))
    implementation(project(":qnop-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
