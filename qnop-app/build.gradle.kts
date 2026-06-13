// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-app — composition root for the Community build and host of the
// architecture-conformance tests. The Spring Boot plugin and the bootable
// server are deliberately introduced in Phase 1 (deferred), so this module is
// currently a plain aggregator that wires the modules together.

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-web"))
    implementation(project(":qnop-persistence"))
    implementation(project(":qnop-storage"))
    implementation(project(":qnop-document"))
    implementation(project(":qnop-security"))
    implementation(project(":qnop-application"))
    implementation(project(":qnop-domain"))
    implementation(project(":qnop-spi"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
