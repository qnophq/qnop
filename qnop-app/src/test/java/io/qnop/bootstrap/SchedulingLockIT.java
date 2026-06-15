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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the ShedLock wiring (issue #52, ADR-0029): the Postgres-backed {@link LockProvider} bean
 * is present, the {@code shedlock} table exists (Liquibase migration 0006), and the lock provides
 * mutual exclusion — a second acquisition of a held lock is refused until the first is released.
 * This is what makes the {@code @Scheduled} sweeps run at most once across instances. Not
 * {@code @Transactional}: lock state must commit to be observable across acquisitions. Requires
 * Docker.
 */
class SchedulingLockIT extends AbstractIntegrationTest {

  @Autowired LockProvider lockProvider;
  @Autowired JdbcTemplate jdbc;

  @Test
  void shedlockTableIsCreatedByLiquibase() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM shedlock", Integer.class);

    assertThat(count).isNotNull();
  }

  @Test
  void lockIsExclusiveWhileHeldAndReusableAfterRelease() {
    LockConfiguration config =
        new LockConfiguration(
            Instant.now(), "shedlock-it-probe", Duration.ofMinutes(5), Duration.ZERO);

    Optional<SimpleLock> first = lockProvider.lock(config);
    assertThat(first).as("first acquisition succeeds").isPresent();

    try {
      assertThat(lockProvider.lock(config)).as("a held lock cannot be re-acquired").isEmpty();
    } finally {
      first.orElseThrow().unlock();
    }

    Optional<SimpleLock> afterRelease = lockProvider.lock(config);
    assertThat(afterRelease).as("the lock is acquirable again once released").isPresent();
    afterRelease.ifPresent(SimpleLock::unlock);
  }
}
