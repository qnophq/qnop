// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Init script for regenerating gradle/verification-metadata.xml (issue #925).
// Gradle records checksums only for artifacts a run actually resolves, so this
// registers `resolveAllForVerification` in every project: it resolves every
// resolvable configuration (build script classpaths included). Detached
// configurations that tools resolve at execution time (Spotless's formatter,
// the license report) are covered by running those tasks alongside — see
// scripts/update-gradle-verification-metadata.sh, the only intended caller.

allprojects {
    tasks.register("resolveAllForVerification") {
        notCompatibleWithConfigurationCache("resolves every configuration of the project")
        doLast {
            val all = project.buildscript.configurations + project.configurations
            all.filter { it.isCanBeResolved }.forEach { configuration ->
                // Lenient: a configuration that cannot resolve in isolation (e.g. a
                // consumable-only variant) must not stop the sweep of the others.
                configuration.incoming.artifactView { lenient(true) }.files.files
            }
        }
    }
}
