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
        googleJavaFormat()
        // ADR-0007: every source file carries an SPDX identifier.
        licenseHeader("// SPDX-License-Identifier: AGPL-3.0-only\n\n")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
