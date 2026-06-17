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
import io.qnop.entity.User;
import io.qnop.entity.UserSetting;
import io.qnop.repository.ApplicationSettingRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSettingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the settings schema (issue #13) against a real PostgreSQL (ADR-0020): typed
 * application/user settings, the seeded defaults, the {@code value_type} CHECK that JPA cannot
 * express, UUIDv7 generation, the {@code (user_id, setting_key)} uniqueness, the {@code ON DELETE
 * CASCADE} on user settings, and the {@code ON DELETE SET NULL} audit reference on application
 * settings. Each test runs in a rolled-back transaction. Extends {@link AbstractIntegrationTest}.
 * Requires Docker.
 */
@Transactional
class SettingsSchemaIT extends AbstractIntegrationTest {

  @Autowired ApplicationSettingRepository applicationSettings;
  @Autowired UserSettingRepository userSettings;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager entityManager;

  @Test
  void persistsAndReadsApplicationSetting() {
    ApplicationSetting saved =
        applicationSettings.saveAndFlush(
            new ApplicationSetting("custom.greeting", "hello", SettingValueType.STRING));

    ApplicationSetting found = applicationSettings.findById(saved.getSettingKey()).orElseThrow();
    assertThat(found.getSettingValue()).isEqualTo("hello");
    assertThat(found.getValueType()).isEqualTo(SettingValueType.STRING);
    assertThat(found.getUpdatedAt()).isNotNull();
  }

  @Test
  void seedsDefaultSettingsWithDeclaredTypes() {
    assertThat(applicationSettings.findById("upload.max_file_size_mb"))
        .get()
        .satisfies(
            s -> {
              assertThat(s.getSettingValue()).isEqualTo("25");
              assertThat(s.getValueType()).isEqualTo(SettingValueType.INTEGER);
            });
    assertThat(applicationSettings.findById("smtp.password"))
        .get()
        .satisfies(s -> assertThat(s.getValueType()).isEqualTo(SettingValueType.PASSWORD));
  }

  @Test
  void rejectsUnknownValueType() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO application_setting (setting_key, value_type) VALUES (?, ?)",
                    "bad.key",
                    "BOGUS"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void persistsUserSettingWithGeneratedUuidV7() {
    User user = users.saveAndFlush(User.internal("Ann", "ann@example.com", "ann", "hash"));

    UserSetting saved =
        userSettings.saveAndFlush(new UserSetting(user.getId(), "ui.theme", "dark"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).isEqualTo(7);
    assertThat(userSettings.findByUserIdAndSettingKey(user.getId(), "ui.theme")).isPresent();
  }

  @Test
  void enforcesUserSettingUniqueness() {
    User user = users.saveAndFlush(User.internal("Bea", "bea@example.com", "bea", "hash"));
    userSettings.saveAndFlush(new UserSetting(user.getId(), "ui.theme", "dark"));

    assertThatThrownBy(
            () -> userSettings.saveAndFlush(new UserSetting(user.getId(), "ui.theme", "light")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void cascadesUserSettingsWhenUserRemoved() {
    User user = users.saveAndFlush(User.internal("Cas", "cas@example.com", "cas", "hash"));
    UUID settingId =
        userSettings.saveAndFlush(new UserSetting(user.getId(), "ui.density", "compact")).getId();

    users.deleteById(user.getId());
    entityManager.flush(); // let the DB-level ON DELETE CASCADE fire
    entityManager.clear();

    assertThat(userSettings.findById(settingId)).isEmpty();
  }

  @Test
  void nullsUpdatedByWhenEditingUserRemoved() {
    User editor = users.saveAndFlush(User.internal("Ed", "ed@example.com", "ed", "hash"));
    ApplicationSetting setting =
        new ApplicationSetting("custom.audited", "v", SettingValueType.STRING);
    setting.setUpdatedBy(editor.getId());
    applicationSettings.saveAndFlush(setting);

    users.deleteById(editor.getId());
    entityManager.flush(); // ON DELETE SET NULL on application_setting.updated_by
    entityManager.clear();

    ApplicationSetting reloaded = applicationSettings.findById("custom.audited").orElseThrow();
    assertThat(reloaded.getUpdatedBy()).isNull();
  }

  @Test
  void indexesTheUpdatedByForeignKey() {
    // The FK column must be indexed so deletes of a qnop_user (ON DELETE SET NULL) and joins on
    // updated_by do not sequentially scan application_setting (issue #46).
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'application_setting'"
                + " AND indexname = 'ix_application_setting_updated_by'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }
}
