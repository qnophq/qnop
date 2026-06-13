// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-api — the PUBLISHED REST API contract (ADR-0015): request/response DTOs
// and the OpenAPI definition. PURE types — no Spring/server dependencies — so
// external consumers and a generated TS/SDK client can depend on it without
// pulling the server. Implemented by qnop-web; versioned like qnop-spi.
// (Contents land in Phase 1.)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
