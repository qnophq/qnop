// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Root build. Cross-module configuration lives in convention plugins under
// build-logic/ (applied per module), NOT in allprojects/subprojects blocks.
//
// Plugins are declared here with `apply false` so they load once in the shared
// root classloader scope:
//   - Spring Boot: without this, applying it in qnop-app alone splits the plugin
//     classloader and Spotless's shared build service clashes with sibling
//     modules. The runnable module (qnop-app) applies it without a version.
//   - openapi-generator (ADR-0021): applied without a version by qnop-api-model
//     and qnop-api-endpoint.

import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.openapi.generator) apply false
    // Dependency-license scanner (issue #498, ADR-0007): applied at the root so
    // `checkLicense` aggregates every module's shipped dependencies and fails on any
    // license outside the permissive allowlist. NOT wired into `check`/`build`; the
    // CI `license-scan` job invokes it explicitly.
    alias(libs.plugins.license.report)
    // CycloneDX SBOM generation (issue #496): declared here so the plugin loads
    // once in the shared root classloader scope; qnop-app applies it (without a
    // version) to emit a CycloneDX SBOM of its runtime classpath. The CI Trivy
    // SBOM scan gates on that SBOM (CRITICAL fails, HIGH warns), replacing the
    // OWASP Dependency-Check job that was a no-op without an NVD API key (issue
    // #195, ADR-0007 amendment).
    alias(libs.plugins.cyclonedx.bom) apply false
}

// Dependency-license policy (issue #498, ADR-0007). Scans only what we ship —
// each module's `runtimeClasspath` — so test/compile-only licenses (e.g. JUnit's
// EPL) never gate the product. The bundle normalizer collapses the many spellings
// of each license to a canonical name before the allowlist in config/
// allowed-licenses.json is applied by `checkLicense`.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf(LicenseBundleNormalizer())
    renderers = arrayOf(
        InventoryHtmlReportRenderer("index.html", "qnop dependency licenses"),
        JsonReportRenderer("licenses.json", false),
    )
    allowedLicensesFile = file("config/allowed-licenses.json")
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every module (alias for the aggregate build)."
    dependsOn(subprojects.map { "${it.path}:build" })
}
