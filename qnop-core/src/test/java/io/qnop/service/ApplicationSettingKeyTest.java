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
package io.qnop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.qnop.entity.SettingValueType;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@link ApplicationSettingKey} registry, including that its key set matches the
 * Liquibase seed from issue #13 (the registry is authoritative; the seed is its projection —
 * ADR-0025).
 */
class ApplicationSettingKeyTest {

  /** The keys seeded by db/changelog/migrations/0002-settings-schema.yaml (issue #13). */
  private static final Set<String> SEEDED_KEYS =
      Set.of(
          "general.application_name",
          "general.base_url",
          "general.default_language",
          "upload.document_max_file_size_mb",
          "upload.attachment_max_file_size_mb",
          "tracking.enabled",
          "tracking.provider",
          "smtp.enabled",
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.encryption",
          "smtp.from",
          "smtp.from_name",
          "auth.self_registration_enabled",
          "auth.self_registration_default_role",
          "auth.password_reset_enabled",
          "auth.password_reset_token_ttl_minutes");

  @Test
  void registryMatchesSeededKeys() {
    Set<String> registryKeys =
        Arrays.stream(ApplicationSettingKey.values())
            .map(ApplicationSettingKey::getKey)
            .collect(Collectors.toSet());

    assertEquals(SEEDED_KEYS, registryKeys);
  }

  @Test
  void resolvesKnownKeyAndRejectsUnknown() {
    assertEquals(
        Optional.of(ApplicationSettingKey.SMTP_HOST), ApplicationSettingKey.fromKey("smtp.host"));
    assertTrue(ApplicationSettingKey.fromKey("does.not.exist").isEmpty());
  }

  @Test
  void onlyPasswordKeysAreSensitive() {
    assertTrue(ApplicationSettingKey.SMTP_PASSWORD.isSensitive());
    assertFalse(ApplicationSettingKey.SMTP_USERNAME.isSensitive());
    Arrays.stream(ApplicationSettingKey.values())
        .filter(ApplicationSettingKey::isSensitive)
        .forEach(key -> assertEquals(SettingValueType.PASSWORD, key.getType()));
  }
}
