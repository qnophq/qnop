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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ValueValidator} across the setting value types. */
class ValueValidatorTest {

  @Test
  void acceptsValidTypedValues() {
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_APPLICATION_NAME, "anything"));
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB, "42"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENABLED, "false"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENCRYPTION, "tls"));
    assertDoesNotThrow(
        () -> ValueValidator.validate(ApplicationSettingKey.TRACKING_PROVIDER, "matomo"));
    assertDoesNotThrow(() -> ValueValidator.validate(ApplicationSettingKey.SMTP_PASSWORD, "p@ss"));
  }

  @Test
  void rejectsNonInteger() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.UPLOAD_MAX_FILE_SIZE_MB, "abc"));
  }

  @Test
  void rejectsNonBoolean() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENABLED, "yes"));
  }

  @Test
  void rejectsUnknownEnumOption() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.TRACKING_PROVIDER, "google-analytics"));
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.SMTP_ENCRYPTION, "ssl"));
  }

  @Test
  void rejectsNull() {
    assertThrows(
        SettingValidationException.class,
        () -> ValueValidator.validate(ApplicationSettingKey.GENERAL_BASE_URL, null));
  }
}
