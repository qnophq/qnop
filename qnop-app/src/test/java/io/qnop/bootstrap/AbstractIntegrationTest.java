/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.qnop.bootstrap;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for full-context integration tests: boots the application against a real PostgreSQL
 * (Testcontainers, ADR-0020) and a real MinIO object store (ADR-0005, issue #243), and supplies
 * strong {@code QNOP_AUTH_*} secrets so the security foundation's fail-fast validation (ADR-0022)
 * is satisfied. Requires Docker.
 *
 * <p>Both containers are JVM-lifetime singletons (started once in a static initializer, never
 * explicitly stopped — Ryuk reaps them). A per-class {@code @Testcontainers} lifecycle would stop
 * and restart them between test classes, handing the second class a new port while Spring reuses
 * the cached context built for the first — causing connection-refused failures.
 */
@SpringBootTest(
    classes = QnopApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

  // postgres:18 to match docker-compose(.smoke).yml — the ADR-0020 test/infra parity (issue #199).
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

  // MinIO for the object-storage adapter (ADR-0005). No @ServiceConnection: the qnop.s3.* props are
  // wired manually below. Digest-pinned to match docker-compose.yml.
  static final MinIOContainer minio =
      new MinIOContainer(
          DockerImageName.parse(
                  "minio/minio:latest@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e")
              .asCompatibleSubstituteFor("minio/minio"));

  static {
    postgres.start();
    minio.start();
  }

  @DynamicPropertySource
  static void securitySecrets(DynamicPropertyRegistry registry) {
    registry.add("qnop.auth.jwt-secret", () -> "integration-test-jwt-secret-0123456789");
    registry.add("qnop.auth.encryption-key", () -> "integration-test-encryption-key-0123456789");
    registry.add("qnop.auth.encryption-salt", () -> "0123456789abcdef0123456789abcdef");
    registry.add("qnop.cors.allowed-origins", () -> "http://localhost:5173");
    // Effectively disable the background job poller/reaper in tests (interval == initial delay ==
    // 1h): tests drive the queue via direct poll()/reap() calls, so a stray scheduled tick must not
    // race them (ADR-0033).
    registry.add("qnop.jobs.poll-interval-ms", () -> "3600000");
    registry.add("qnop.jobs.reaper-interval-ms", () -> "3600000");
    // Object storage against the MinIO container (ADR-0005): auto-create the bucket, and disable
    // the
    // scheduled orphan reaper ("-") so StorageServiceIT can drive reapOrphans() deterministically.
    registry.add("qnop.s3.endpoint", minio::getS3URL);
    registry.add("qnop.s3.region", () -> "us-east-1");
    registry.add("qnop.s3.bucket", () -> "qnop-test");
    registry.add("qnop.s3.access-key", minio::getUserName);
    registry.add("qnop.s3.secret-key", minio::getPassword);
    registry.add("qnop.s3.path-style-access", () -> "true");
    registry.add("qnop.s3.auto-create-bucket", () -> "true");
    registry.add("qnop.s3.reaper-cron", () -> "-");
  }
}
