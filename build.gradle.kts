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
    // OWASP Dependency-Check (issue #195): applied at the root so
    // `dependencyCheckAggregate` scans every module against the NVD. It is NOT wired
    // into `check`/`build`, so the local gate is unaffected; the CI `backend-audit`
    // job invokes it only when an NVD API key secret is present (otherwise the NVD
    // feed is heavily rate-limited).
    alias(libs.plugins.dependency.check)
    // Dependency-license scanner (issue #498, ADR-0007): applied at the root so
    // `checkLicense` aggregates every module's shipped dependencies and fails on any
    // license outside the permissive allowlist. Like the OWASP gate above it is NOT
    // wired into `check`/`build`; the CI `license-scan` job invokes it explicitly.
    alias(libs.plugins.license.report)
}

dependencyCheck {
    // Fail on a high/critical advisory (CVSS >= 7.0) in any module's runtime deps.
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "SARIF")
    // NVD API key from the NVD_API_KEY env var (CI secret) or a -PnvdApiKey property;
    // kept off the command line so it does not leak into build logs.
    (findProperty("nvdApiKey") as String? ?: System.getenv("NVD_API_KEY"))
        ?.takeIf { it.isNotBlank() }
        ?.let { nvd.apiKey = it }
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
