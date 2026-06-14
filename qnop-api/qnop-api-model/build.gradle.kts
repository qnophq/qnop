// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-api-model — the PUBLISHED, Spring-free REST DTOs (ADR-0015, ADR-0021).
// The openapi-generator `java` generator is run in MODELS-ONLY mode
// (globalProperties = ["models"]). The `java` generator (not `spring`) is used
// deliberately: the `spring` generator decorates its Java models with Spring
// annotations (org.springframework.lang.Nullable, @DateTimeFormat), which would
// break purity; the `java` generator emits plain POJOs carrying only Jackson +
// Jakarta annotations. This is the external stability surface a generated TS/SDK
// client depends on without pulling the server. ArchUnit asserts the purity of
// `io.qnop.api.v1.model`. (plugwerk uses kotlin-spring for both submodules
// because Kotlin's built-in nullability needs no @Nullable annotation; the Java
// equivalent of that split is java-generator models + spring-generator apis.)

plugins {
    id("qnop.java-conventions")
    // Version comes from the root `plugins {}` block (apply false there) so the
    // generator loads once in the shared root classloader scope.
    id("org.openapi.generator")
}

description = "qnop-api-model — generated, Spring-free REST DTOs (ADR-0021)"

dependencies {
    // Annotation libraries the generated POJOs reference. Deliberately NO Spring.
    api(libs.jackson.annotations)
    api(libs.jakarta.validation.api)
    api(libs.jakarta.annotation.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val specFile = "${rootProject.projectDir}/qnop-api/src/main/resources/openapi/openapi.yaml"

openApiGenerate {
    generatorName.set("java")
    inputSpec.set(specFile)
    outputDir.set("${layout.buildDirectory.get()}/generated")
    modelPackage.set("io.qnop.api.v1.model")
    configOptions.set(
        mapOf(
            // `resttemplate` (not `native`/`webclient`) leaves supportUrlQuery
            // off, so the generated POJOs carry no `toUrlQueryString()` and no
            // reference to a client `ApiClient` class. We generate models only,
            // so no Spring RestTemplate code is ever emitted — the artifact stays
            // Spring-free.
            "library" to "resttemplate",
            "serializationLibrary" to "jackson",
            "useJakartaEe" to "true",
            "useBeanValidation" to "true",
            "openApiNullable" to "false",
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "MACRO_CASE",
            "hideGenerationTimestamp" to "true",
        ),
    )
    // MODELS ONLY — no apis, no supporting files. The `java` generator emits
    // plain POJOs (Jackson + Jakarta Bean Validation, java.time dates), so this
    // artifact stays Spring-free.
    globalProperties.set(mapOf("models" to ""))
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
