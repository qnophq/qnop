// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-storage — default StorageProvider adapter over the S3 API (MinIO / S3),
// per ADR-0005. Binary documents never live in PostgreSQL. (Phase 1)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    implementation(project(":qnop-application"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
