// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Root build. Cross-module configuration lives in convention plugins under
// build-logic/ (applied per module), NOT in allprojects/subprojects blocks.
//
// The Spring Boot plugin is declared here with `apply false` so it loads once in
// the shared root classloader scope. Without this, applying it in qnop-web alone
// splits the plugin classloader and Spotless's shared build service clashes with
// its sibling modules. The runnable module (qnop-web) applies it without a version.

plugins {
    alias(libs.plugins.spring.boot) apply false
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every module (alias for the aggregate build)."
    dependsOn(subprojects.map { "${it.path}:build" })
}
