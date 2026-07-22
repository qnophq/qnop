// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-app — the runnable Community module: @RestControllers implementing the
// published qnop-api contract, plus the Spring Boot bootstrap/config
// (io.qnop.bootstrap). Depends on qnop-core (services) and qnop-api (DTOs).
// Hosts the architecture-conformance tests. This module carries the Spring Boot
// plugin and is the bootable server (Phase 1, ADR-0020).

import org.gradle.language.jvm.tasks.ProcessResources
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("qnop.java-conventions")
    // Version comes from the root `plugins {}` block (declared there `apply false`
    // to keep a single shared plugin classloader — see the root build script).
    id("org.springframework.boot")
    // CycloneDX SBOM of this module's runtime classpath (issue #496) — the input
    // to the CI Trivy SBOM scan (the backend CVE gate).
    id("org.cyclonedx.bom")
}

// Stamp build-info.properties into the jar so /api/v1/config reports the real
// version (BuildProperties in ConfigController, issue #495).
springBoot { buildInfo() }

// CycloneDX SBOM (issue #496): emit build/reports/bom.json for exactly the
// runtime classpath — the deps that actually ship. Test-only configurations
// (JUnit, Testcontainers, ArchUnit, …) are excluded so a CVE in a test tool
// never gates a shipped artifact. This SBOM is the input to the CI Trivy scan.
tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setOutputFormat("json")
    setOutputName("bom")
    setProjectType("application")
    setIncludeLicenseText(false)
}

// SPA embedding (ADR-0040): with -PembedUi the boot jar carries the built
// qnop-ui bundle under static/. Opt-in so developer builds and the test suite
// stay Node-free; the release workflow and the deploy image always set it.
val embedUi = providers.gradleProperty("embedUi").isPresent
if (embedUi) {
    val uiDir = rootProject.layout.projectDirectory.dir("qnop-ui")

    val installUi = tasks.register<Exec>("installUi") {
        workingDir = uiDir.asFile
        commandLine("pnpm", "install", "--frozen-lockfile")
    }

    val buildUi = tasks.register<Exec>("buildUi") {
        dependsOn(installUi)
        workingDir = uiDir.asFile
        commandLine("pnpm", "build")
        inputs.dir(uiDir.dir("src"))
        inputs.file(uiDir.file("package.json"))
        inputs.file(uiDir.file("pnpm-lock.yaml"))
        outputs.dir(uiDir.dir("dist"))
    }

    tasks.named<ProcessResources>("processResources") {
        dependsOn(buildUi)
        from(uiDir.dir("dist")) { into("static") }
    }
}

// Local-dev convenience (issue #110): load the repo-root `.env` into bootRun's
// forked JVM so `./gradlew :qnop-app:bootRun` starts without `set -a; source .env`
// first — matching how docker-compose already consumes the same file. Spring does
// not read `.env` itself. Shell-exported variables win (we only fill keys absent
// from the process environment), a missing `.env` is a no-op, and this never
// affects tests or CI (they supply real environment / Testcontainers secrets).
tasks.named<BootRun>("bootRun") {
    val dotenv = rootProject.layout.projectDirectory.file(".env").asFile
    if (dotenv.exists()) {
        dotenv.readLines().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"', '\'')
            if (System.getenv(key) == null) {
                environment(key, value)
            }
        }
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies)) // BOM: manages versions

    // Compile-time only: harvests Javadoc on this module's @ConfigurationProperties
    // (RateLimitProperties) into spring-configuration-metadata.json, joined with qnop-core's
    // at runtime to caption the effective-configuration page (issue #522).
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(project(":qnop-core"))
    implementation(project(":qnop-api:qnop-api-endpoint")) // generated Spring interfaces (+ DTOs transitively)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus scrape registry for the /actuator/prometheus endpoint (issue #348). Runtime-only:
    // Boot auto-configures it; the metrics/health code uses micrometer-core (via actuator) directly.
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Servlet security filter chain (io.qnop.web.security, issue #10 / ADR-0022).
    implementation(libs.spring.boot.starter.security)
    // Resource-server filter that validates the bearer JWT on each request, plus
    // the JWT (Nimbus) types used by the web-layer decoder/cookie glue (issue #17).
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // OIDC/OAuth2 browser login chain (io.qnop.web.security, issue #21): the
    // oauth2Login DSL + authorized-client types. (qnop-core also uses the client
    // library, but that is an implementation dep and does not reach this compile
    // classpath.)
    implementation(libs.spring.boot.starter.oauth2.client)
    // Auth-endpoint rate limiting (io.qnop.web.security.ratelimit, issue #18 / ADR-0027):
    // Bucket4j token buckets in a Caffeine cache. Caffeine is BOM-managed (also used by the
    // #17 revocation denylist in qnop-core); declared here too because that is an
    // implementation dependency and does not reach this module's compile classpath.
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    // Distributed scheduler locks (io.qnop.bootstrap.SchedulingConfiguration, issue #52 /
    // ADR-0029): @EnableSchedulerLock + the Postgres JdbcTemplateLockProvider that backs the
    // @SchedulerLock'd cleanup sweeps. shedlock-spring is declared here too (qnop-core's is an
    // implementation dep and does not reach this compile classpath).
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    // The bootstrap registers the sibling data packages explicitly via
    // @EntityScan / @EnableJpaRepositories (issue #11), so this module takes a
    // direct JPA dependency for those compile-time symbols (Hibernate itself
    // still reaches the runtime classpath transitively via qnop-core).
    implementation(libs.spring.boot.starter.data.jpa)

    // Runtime-only: the JDBC driver and the schema migrator (Liquibase owns the
    // schema; JPA ddl-auto=none). The changelog ships in qnop-core resources.
    // spring-boot-liquibase carries LiquibaseAutoConfiguration (Boot 4 modularised
    // it out of spring-boot-autoconfigure) and pulls liquibase-core transitively.
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.spring.boot.liquibase)

    testImplementation(platform(libs.spring.boot.dependencies)) // BOM also on the test classpath
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test) // @WebMvcTest slice (Boot 4 module)
    testImplementation(libs.spring.security.test)
    // The fail-fast binding test (QnopPropertiesBindingTest) drives Bean Validation +
    // ValidationAutoConfiguration directly; qnop-core keeps validation as an
    // implementation dependency, so the app test classpath needs it explicitly.
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    // Object-storage integration tests run against a real MinIO (issue #243) and use the
    // StorageProvider SPI types directly (qnop-core hides qnop-spi behind `implementation`).
    testImplementation(libs.testcontainers.minio)
    testImplementation(project(":qnop-spi"))
    // The document-ingest ITs (issue #245) generate their PDF fixtures with PDFBox
    // instead of committing binary blobs.
    testImplementation(libs.pdfbox)
    testRuntimeOnly(libs.junit.platform.launcher)
}
