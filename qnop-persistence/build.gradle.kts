// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-persistence — JPA adapters, repository implementations and Flyway
// migrations (PostgreSQL). Adapter: implements application ports. (Phase 1)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-application"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
