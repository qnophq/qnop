// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Shared Java conventions for every qnop module: toolchain, formatting,
// license-header enforcement, and test framework. Applied via
// `plugins { id("qnop.java-conventions") }` in each module.

plugins {
    `java-library`
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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
