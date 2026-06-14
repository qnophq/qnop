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

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConfigurationKeyRedactor}. */
class ConfigurationKeyRedactorTest {

  private final ConfigurationKeyRedactor redactor = new ConfigurationKeyRedactor();

  @Test
  void masksNonBlankSecrets() {
    assertEquals(
        ConfigurationKeyRedactor.MASK,
        redactor.redact(ApplicationSettingKey.SMTP_PASSWORD, "s3cr3t"));
  }

  @Test
  void leavesBlankSecretsVisible() {
    assertEquals("", redactor.redact(ApplicationSettingKey.SMTP_PASSWORD, ""));
  }

  @Test
  void leavesNonSecretsUntouched() {
    assertEquals(
        "mail.example.com", redactor.redact(ApplicationSettingKey.SMTP_HOST, "mail.example.com"));
  }

  @Test
  void recognizesMaskSentinel() {
    assertTrue(redactor.isMask(ConfigurationKeyRedactor.MASK));
    assertFalse(redactor.isMask("real-value"));
  }
}
