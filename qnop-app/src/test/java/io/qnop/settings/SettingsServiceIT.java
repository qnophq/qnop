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
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.ConfigurationKeyRedactor;
import io.qnop.service.SettingFieldError;
import io.qnop.service.SettingsValidationException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies {@link ApplicationSettingsService} against a real PostgreSQL (ADR-0020, ADR-0025): the
 * snapshot loads the seeded defaults, an update refreshes it after commit, secrets are encrypted at
 * rest yet decrypted in the snapshot and masked in the admin view, the mask sentinel preserves a
 * secret, and validation rejects bad input. Not transactional — the service commits its own writes
 * so {@code afterCommit} fires. Requires Docker.
 */
class SettingsServiceIT extends AbstractIntegrationTest {

  @Autowired ApplicationSettingsService settings;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void resetMutatedKeys() {
    settings.update(
        Map.of(
            ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB.getKey(), "25",
            ApplicationSettingKey.SMTP_PASSWORD.getKey(), ""),
        null);
  }

  @Test
  void loadsSeededDefaultsIntoSnapshot() {
    assertThat(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
        .isEqualTo("qnop");
    assertThat(settings.getInteger(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB)).isEqualTo(25);
    assertThat(settings.getString(ApplicationSettingKey.SMTP_ENCRYPTION)).isEqualTo("starttls");
  }

  @Test
  void updateRefreshesSnapshotAfterCommit() {
    settings.update(Map.of(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB.getKey(), "50"), null);

    assertThat(settings.getInteger(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB)).isEqualTo(50);
  }

  @Test
  void encryptsSecretAtRestDecryptsInSnapshotAndMasksInView() {
    settings.update(Map.of(ApplicationSettingKey.SMTP_PASSWORD.getKey(), "s3cr3t"), null);

    String stored =
        jdbc.queryForObject(
            "SELECT setting_value FROM application_setting WHERE setting_key = ?",
            String.class,
            "smtp.password");
    assertThat(stored).isNotNull().isNotEqualTo("s3cr3t");
    assertThat(settings.getString(ApplicationSettingKey.SMTP_PASSWORD)).isEqualTo("s3cr3t");
    assertThat(redactedValue("smtp.password")).isEqualTo(ConfigurationKeyRedactor.MASK);
  }

  @Test
  void maskSentinelLeavesSecretUnchanged() {
    settings.update(Map.of(ApplicationSettingKey.SMTP_PASSWORD.getKey(), "first"), null);
    settings.update(
        Map.of(ApplicationSettingKey.SMTP_PASSWORD.getKey(), ConfigurationKeyRedactor.MASK), null);

    assertThat(settings.getString(ApplicationSettingKey.SMTP_PASSWORD)).isEqualTo("first");
  }

  @Test
  void rejectsUnknownKey() {
    assertThatThrownBy(() -> settings.update(Map.of("nope.key", "x"), null))
        .isInstanceOf(SettingsValidationException.class);
  }

  @Test
  void rejectsTypeInvalidValue() {
    assertThatThrownBy(() -> settings.update(Map.of("upload.max_file_size_mb", "abc"), null))
        .isInstanceOf(SettingsValidationException.class);
  }

  @Test
  void aggregatesEveryInvalidField() {
    assertThatThrownBy(
            () -> settings.update(Map.of("smtp.port", "70000", "smtp.from", "not-an-email"), null))
        .isInstanceOfSatisfying(
            SettingsValidationException.class,
            ex ->
                assertThat(ex.getFieldErrors())
                    .extracting(SettingFieldError::field)
                    .containsExactlyInAnyOrder("smtp.port", "smtp.from"));
  }

  private String redactedValue(String key) {
    return settings.describeAll().stream()
        .filter(descriptor -> descriptor.key().equals(key))
        .findFirst()
        .orElseThrow()
        .value();
  }
}
