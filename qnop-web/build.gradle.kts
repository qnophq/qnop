// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-web — REST controllers, DTO mapping and OpenAPI. Adapter: drives the
// application use cases. (Phase 1)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-application"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
