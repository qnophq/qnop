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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.qnop.entity.SettingValueType;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link UserSettingKey} registry and its validation (issue #22). */
class UserSettingKeyTest {

  @Test
  void resolvesKnownKeyAndRejectsUnknown() {
    assertEquals(Optional.of(UserSettingKey.THEME), UserSettingKey.fromKey("theme"));
    assertTrue(UserSettingKey.fromKey("nope").isEmpty());
  }

  @Test
  void themeIsAConstrainedEnum() {
    assertEquals(SettingValueType.ENUM, UserSettingKey.THEME.getType());
    assertEquals("system", UserSettingKey.THEME.getDefaultValue());
    assertDoesNotThrow(() -> ValueValidator.validate(UserSettingKey.THEME, "dark"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(UserSettingKey.THEME, "neon"));
  }

  @Test
  void freeTextKeysAcceptAnyString() {
    assertDoesNotThrow(() -> ValueValidator.validate(UserSettingKey.PREFERRED_LANGUAGE, "de"));
    assertDoesNotThrow(() -> ValueValidator.validate(UserSettingKey.TIMEZONE, "Europe/Berlin"));
  }
}
