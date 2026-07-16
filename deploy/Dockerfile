# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89
# SPDX-License-Identifier: AGPL-3.0-only
#
# Runtime image for the RELEASED qnop server (ADR-0040). Copy-only by design:
# CI builds the boot jar (with the embedded qnop-ui SPA) once and stages it as
# the sole file of the build context — no toolchain enters this image, and the
# multi-arch build stays a plain file copy per platform. Building from source
# instead: use the root Dockerfile.
#
# Extend JVM flags via JDK_JAVA_OPTS (picked up by the java launcher), e.g.
#   JDK_JAVA_OPTS="-Xlog:gc* -XX:MaxRAMPercentage=60.0"

FROM eclipse-temurin:21.0.11_10-jre@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec
WORKDIR /app

# Run unprivileged.
RUN groupadd --system qnop && useradd --system --gid qnop --home /app qnop

COPY app.jar /app/app.jar
USER qnop

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
