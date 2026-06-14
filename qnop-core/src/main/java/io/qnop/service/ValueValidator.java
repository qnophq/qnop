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

/**
 * Validates a raw setting value string against its key's declared type (issue #16). Shared, plain,
 * DB-free logic (ADR-0004). Throws {@link SettingValidationException} on violation.
 */
public final class ValueValidator {

  private ValueValidator() {}

  public static void validate(ApplicationSettingKey key, String value) {
    if (value == null) {
      throw new SettingValidationException(key.getKey(), "value must not be null");
    }
    switch (key.getType()) {
      case INTEGER -> requireInteger(key, value);
      case BOOLEAN -> requireBoolean(key, value);
      case ENUM -> requireEnumOption(key, value);
      case STRING, PASSWORD -> {
        // any string is acceptable
      }
    }
  }

  private static void requireInteger(ApplicationSettingKey key, String value) {
    try {
      Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new SettingValidationException(key.getKey(), "must be an integer");
    }
  }

  private static void requireBoolean(ApplicationSettingKey key, String value) {
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new SettingValidationException(key.getKey(), "must be 'true' or 'false'");
    }
  }

  private static void requireEnumOption(ApplicationSettingKey key, String value) {
    if (!key.getEnumOptions().contains(value)) {
      throw new SettingValidationException(key.getKey(), "must be one of " + key.getEnumOptions());
    }
  }
}
