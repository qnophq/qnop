// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-document — text extraction (PDFBox / POI), document conversion and
// annotation anchoring adapters (ADR-0009/0010). (Phase 1)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-application"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
