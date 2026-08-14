# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32
# SPDX-License-Identifier: AGPL-3.0-only
#
# Multi-stage image for the qnop Community server (qnop-app, ADR-0020).
#
# Stage 1 builds the Spring Boot executable jar with the Gradle wrapper; stage 2
# runs it on a slim JRE as a non-root user. The datasource host comes from
# QNOP_DB_HOST (see application.yml) and the server fails fast unless the
# QNOP_AUTH_* secrets are supplied (ADR-0022). Tests / ArchUnit / Spotless are
# intentionally not run here — the dedicated CI jobs own those gates; this image
# only packages the runnable artifact (used by the smoke-test stack, issue #207).

FROM eclipse-temurin:21.0.11_10-jdk@sha256:57865c22b954cf920cb05a610af81d577e89783282514ba071e99c7357f6c769 AS build
WORKDIR /workspace

# The whole multi-module project is needed to configure the build (settings.gradle
# includes every module). The frontend, build outputs and VCS metadata are kept
# out of the context via .dockerignore.
COPY . .

# Build only the boot jar (not the plain library jar) and stage it at a stable
# path for the runtime stage.
RUN ./gradlew --no-daemon -x test :qnop-app:bootJar \
    && cp "$(ls qnop-app/build/libs/*.jar | grep -v -- '-plain')" /workspace/app.jar

FROM eclipse-temurin:21.0.11_10-jre@sha256:371da296b8cb74c7e53fbe7083d5374befc0011b493231d97d45fa789915e434 AS runtime
WORKDIR /app

# LibreOffice, headless, for the PDF export (issue #639) — and for DOCX ingest
# when that lands (ADR-0010). It is invoked as a subprocess and never linked, which
# is the only way ADR-0007 permits a copyleft tool.
#
# `--no-install-recommends` plus the Writer-only package keeps this to what the
# conversion actually needs; the full suite would be several times the size. A
# deployment that drops it still runs — the server then reports PDF as
# unavailable and the export wizard stops offering it.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends libreoffice-writer-nogui fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

# Run unprivileged.
RUN groupadd --system qnop && useradd --system --gid qnop --home /app qnop

COPY --from=build /workspace/app.jar /app/app.jar
USER qnop

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
