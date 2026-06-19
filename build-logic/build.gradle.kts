// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Plugin marker so `qnop.java-conventions` can apply Spotless via `id(...)`.
    // Keep version in sync with gradle/libs.versions.toml -> [versions].spotless
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.7.0")
}
