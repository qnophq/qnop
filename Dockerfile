# syntax=docker/dockerfile:1
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

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# The whole multi-module project is needed to configure the build (settings.gradle
# includes every module). The frontend, build outputs and VCS metadata are kept
# out of the context via .dockerignore.
COPY . .

# Build only the boot jar (not the plain library jar) and stage it at a stable
# path for the runtime stage.
RUN ./gradlew --no-daemon -x test :qnop-app:bootJar \
    && cp "$(ls qnop-app/build/libs/*.jar | grep -v -- '-plain')" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run unprivileged.
RUN groupadd --system qnop && useradd --system --gid qnop --home /app qnop

COPY --from=build /workspace/app.jar /app/app.jar
USER qnop

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
