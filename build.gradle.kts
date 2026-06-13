// SPDX-License-Identifier: AGPL-3.0-only
//
// Root build. Cross-module configuration lives in convention plugins under
// build-logic/ (applied per module), NOT in allprojects/subprojects blocks.
// This file is intentionally minimal.

tasks.register("buildAll") {
    group = "build"
    description = "Builds every module (alias for the aggregate build)."
    dependsOn(subprojects.map { "${it.path}:build" })
}
