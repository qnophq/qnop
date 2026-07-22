// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-spi — the extension-point boundary between the AGPL core and commercial
// add-ons (ADR-0003). PURE interfaces and DTOs only; no dependencies, no logic.
// Published as a stable, versioned artifact consumed by the private enterprise
// repository. (Interfaces land in Phase 1.)

plugins {
    id("qnop.java-conventions")
    // Published to GitHub Packages for the qnop-enterprise repo (issue #497, ADR-0046).
    id("qnop.publish-conventions")
}

description = "qnop-spi — the Spring-free extension-point contract for qnop add-ons (ADR-0003)"

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
