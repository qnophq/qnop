// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-spi — the extension-point boundary between the AGPL core and commercial
// add-ons (ADR-0003). PURE interfaces and DTOs only; no dependencies, no logic.
// Published as a stable, versioned artifact consumed by the private enterprise
// repository. (Interfaces land in Phase 1.)

plugins {
    id("qnop.java-conventions")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
