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

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.openapi.generator) apply false
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every module (alias for the aggregate build)."
    dependsOn(subprojects.map { "${it.path}:build" })
}
