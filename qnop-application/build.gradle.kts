// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-application — use cases / services and the ports (repository & provider
// interfaces) the adapters implement. Depends only on the domain (and, through
// it, the SPI).

plugins {
    id("qnop.java-conventions")
}

dependencies {
    api(project(":qnop-domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
