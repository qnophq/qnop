// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-api-endpoint — generated Spring MVC interfaces for the qnop REST contract
// (ADR-0021). The openapi-generator `spring` generator is run in APIS-ONLY mode
// (globalProperties = ["apis", "supportingFiles"]); the generated interfaces
// import the DTOs from qnop-api-model rather than regenerating them. qnop-app
// implements these interfaces with @RestControllers. This module intentionally
// depends on Spring — purity is asserted only on qnop-api-model.

plugins {
    id("qnop.java-conventions")
    id("org.openapi.generator")
}

description = "qnop-api-endpoint — generated Spring MVC interfaces for the qnop REST contract (ADR-0021)"

dependencies {
    // Re-export the DTOs so implementers (qnop-app) get them transitively.
    api(project(":qnop-api:qnop-api-model"))

    implementation(platform(libs.spring.boot.dependencies)) // BOM: manages versions
    implementation(libs.spring.boot.starter.web)
    implementation(libs.swagger.annotations)
    implementation(libs.jakarta.validation.api)
    implementation(libs.jakarta.annotation.api)
    implementation(libs.jackson.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val specFile = "${rootProject.projectDir}/qnop-api/src/main/resources/openapi/openapi.yaml"

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(specFile)
    outputDir.set("${layout.buildDirectory.get()}/generated")
    apiPackage.set("io.qnop.api.v1.endpoint")
    modelPackage.set("io.qnop.api.v1.model") // imported from qnop-api-model, not regenerated
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useTags" to "true",
            "useSpringBoot3" to "true",
            "documentationProvider" to "none",
            "serializationLibrary" to "jackson",
            "useBeanValidation" to "true",
            "openApiNullable" to "false",
            "enumPropertyNaming" to "MACRO_CASE",
            "hideGenerationTimestamp" to "true",
        ),
    )
    // APIS ONLY — models come from qnop-api-model. Supporting files (e.g. ApiUtil)
    // are allowed here because this module already depends on Spring.
    globalProperties.set(mapOf("apis" to "", "supportingFiles" to ""))
}

sourceSets {
    main {
        java {
            srcDir("${layout.buildDirectory.get()}/generated/src/main/java")
        }
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.openApiGenerate)
}

tasks.named("sourcesJar") {
    dependsOn(tasks.openApiGenerate)
}
