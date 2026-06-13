// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-web — REST controllers implementing the published qnop-api contract.
// Adapter: drives the application use cases. (Phase 1)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-application"))
    implementation(project(":qnop-api")) // implements the published REST contract

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
