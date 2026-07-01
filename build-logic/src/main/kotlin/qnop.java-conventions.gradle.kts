// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Shared Java conventions for every qnop module: toolchain, formatting,
// license-header enforcement, and test framework. Applied via
// `plugins { id("qnop.java-conventions") }` in each module.

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
}

group = "io.qnop"
// Single source of truth: the project version lives in the root VERSION file.
// Read via the provider API so the configuration cache invalidates when it changes.
version = providers.fileContents(layout.settingsDirectory.file("VERSION")).asText.get().trim()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

spotless {
    java {
        // OpenAPI-generated sources (ADR-0021) live under build/generated; they
        // carry no SPDX header and are not hand-maintained, so exclude them from
        // formatting and license-header enforcement.
        targetExclude("**/build/generated/**")
        googleJavaFormat()
        // ADR-0007 + ADR-0019: every source file carries a copyright notice and
        // an SPDX identifier. The block lives in the root `license-header.txt`.
        licenseHeaderFile(rootProject.file("license-header.txt"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        // ADR-0007 + ADR-0019: Kotlin-DSL build scripts also carry the SPDX header,
        // now auto-inserted/checked by `spotlessApply`/`build` instead of only the CI
        // grep (issue #196). Kotlin uses a short `//` header (not the Java block), so a
        // dedicated header file. The delimiter ends the header region at the first line
        // that is neither the copyright nor the SPDX line, so descriptive `//` comments
        // (and a blank line) below the header are preserved rather than stripped.
        licenseHeaderFile(
            rootProject.file("license-header-kts.txt"),
            "(?!// (?:Copyright|SPDX-License-Identifier)).*",
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Expose the repo-level shared fixtures directory so integration tests can load
    // files (e.g. branding assets) without brittle relative paths; see testdata/README.md
    // and io.qnop.testsupport.TestData.
    systemProperty("qnop.testdata.dir", rootDir.resolve("testdata").absolutePath)
    // Surface full exception traces for failures. The integration tests are
    // CI-only (Testcontainers needs Docker), so a truncated stack in the CI log
    // would otherwise leave failures undebuggable.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

// Coverage is measured (report-only, no verification gate so it never breaks CI):
// run `./gradlew test jacocoTestReport` and open build/reports/jacoco/test/html.
// Drives the integration-test suite toward maximum coverage (issue #163).
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
