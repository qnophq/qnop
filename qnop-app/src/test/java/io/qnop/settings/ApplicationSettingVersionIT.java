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
package io.qnop.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.ApplicationSetting;
import io.qnop.entity.SettingValueType;
import io.qnop.repository.ApplicationSettingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies optimistic locking on {@link ApplicationSetting} (issue #47): the {@code @Version}
 * column increments on update and a write against a stale version is rejected — the guard that
 * stops two concurrent admin edits from silently losing one another. Runs against a real PostgreSQL
 * (Testcontainers); each test rolls back. Requires Docker.
 */
@Transactional
class ApplicationSettingVersionIT extends AbstractIntegrationTest {

  private static final String KEY = "test.optlock.key";

  @Autowired ApplicationSettingRepository settings;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager entityManager;

  @Test
  void versionStartsAtZeroAndIncrementsOnUpdate() {
    ApplicationSetting saved =
        settings.saveAndFlush(new ApplicationSetting(KEY, "v0", SettingValueType.STRING));
    assertThat(saved.getVersion()).isZero();

    saved.setSettingValue("v1");
    ApplicationSetting updated = settings.saveAndFlush(saved);

    assertThat(updated.getVersion()).isEqualTo(1L);
  }

  @Test
  void rejectsAWriteAgainstAStaleVersion() {
    settings.saveAndFlush(new ApplicationSetting(KEY, "v0", SettingValueType.STRING));
    ApplicationSetting stale = settings.findById(KEY).orElseThrow();
    entityManager.detach(stale); // hold a snapshot at version 0

    // Simulate a concurrent commit that bumps the version out from under `stale`.
    jdbc.update(
        "UPDATE application_setting SET setting_value = 'concurrent', version = version + 1"
            + " WHERE setting_key = ?",
        KEY);

    stale.setSettingValue("mine");
    assertThatThrownBy(() -> settings.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }
}
