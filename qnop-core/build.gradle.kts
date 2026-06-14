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
    implementation(project(":qnop-spi")) // implements the extension-point defaults
    implementation(project(":qnop-api")) // maps to/from the published REST DTOs

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
