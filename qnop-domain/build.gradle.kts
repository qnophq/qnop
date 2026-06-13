// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-domain — framework-free entities, value objects and the review workflow
// state machine. No Spring / JPA / web dependencies (enforced by ArchUnit).

plugins {
    id("qnop.java-conventions")
}

dependencies {
    api(project(":qnop-spi"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
