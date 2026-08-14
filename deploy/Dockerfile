# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32
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

FROM eclipse-temurin:21.0.11_10-jre@sha256:371da296b8cb74c7e53fbe7083d5374befc0011b493231d97d45fa789915e434
WORKDIR /app

# Run unprivileged.
RUN groupadd --system qnop && useradd --system --gid qnop --home /app qnop

COPY app.jar /app/app.jar
USER qnop

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
