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
import io.qnop.entity.User;
import io.qnop.repository.UserRepository;
import io.qnop.service.SettingValidationException;
import io.qnop.service.UserSettingKey;
import io.qnop.service.UserSettingsService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link UserSettingsService} against a real PostgreSQL (ADR-0020): registry defaults for
 * a fresh user, per-user upsert and isolation, and validation. Transactional (rolled back) — the
 * service does not rely on post-commit behavior. Requires Docker.
 */
@Transactional
class UserSettingsServiceIT extends AbstractIntegrationTest {

  @Autowired UserSettingsService settings;
  @Autowired UserRepository users;

  private UUID newUser(String tag) {
    return users.saveAndFlush(User.internal(tag, tag + "@example.com", tag, "hash")).getId();
  }

  @Test
  void returnsRegistryDefaultsForFreshUser() {
    UUID userId = newUser("fresh");

    assertThat(settings.getSettings(userId))
        .anySatisfy(
            view -> {
              assertThat(view.key()).isEqualTo("theme");
              assertThat(view.value()).isEqualTo("system");
            });
  }

  @Test
  void persistsAndReadsBackUserValue() {
    UUID userId = newUser("setter");

    settings.updateSettings(userId, Map.of("theme", "dark"));

    assertThat(settings.getSettings(userId))
        .filteredOn(view -> view.key().equals("theme"))
        .singleElement()
        .satisfies(view -> assertThat(view.value()).isEqualTo("dark"));
  }

  @Test
  void keepsUsersIsolated() {
    UUID alice = newUser("alice");
    UUID bob = newUser("bob");

    settings.updateSettings(alice, Map.of("theme", "dark"));

    assertThat(themeOf(bob)).isEqualTo("system"); // bob still on the default
    assertThat(themeOf(alice)).isEqualTo("dark");
  }

  @Test
  void rejectsUnknownKey() {
    UUID userId = newUser("unknown");
    assertThatThrownBy(() -> settings.updateSettings(userId, Map.of("nope", "x")))
        .isInstanceOf(SettingValidationException.class);
  }

  @Test
  void rejectsInvalidEnumValue() {
    UUID userId = newUser("invalid");
    assertThatThrownBy(() -> settings.updateSettings(userId, Map.of("theme", "neon")))
        .isInstanceOf(SettingValidationException.class);
  }

  private String themeOf(UUID userId) {
    return settings.getSettings(userId).stream()
        .filter(view -> view.key().equals(UserSettingKey.THEME.getKey()))
        .findFirst()
        .orElseThrow()
        .value();
  }
}
