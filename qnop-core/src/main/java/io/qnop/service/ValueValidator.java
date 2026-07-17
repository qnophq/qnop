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

import io.qnop.entity.SettingValueType;
import java.net.URI;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a raw setting value string against its key's declared type (issue #16). Shared, plain,
 * DB-free logic (ADR-0004). Throws {@link SettingValidationException} on violation.
 */
public final class ValueValidator {

  private ValueValidator() {}

  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  public static void validate(ApplicationSettingKey key, String value) {
    validate(key.getType(), key.getEnumOptions(), value, key.getKey());
    validateConstraints(key.getConstraints(), value, key.getKey());
  }

  public static void validate(UserSettingKey key, String value) {
    validate(key.getType(), key.getEnumOptions(), value, key.getKey());
    validateConstraints(key.getConstraints(), value, key.getKey());
  }

  /** Core, key-agnostic validation shared by the application- and user-setting registries. */
  public static void validate(
      SettingValueType type, List<String> enumOptions, String value, String keyForError) {
    if (value == null) {
      throw new SettingValidationException(keyForError, "value must not be null");
    }
    switch (type) {
      case INTEGER -> requireInteger(keyForError, value);
      case BOOLEAN -> requireBoolean(keyForError, value);
      case ENUM -> requireEnumOption(keyForError, enumOptions, value);
      case STRING, PASSWORD -> {
        // any string is acceptable
      }
    }
  }

  private static void requireInteger(String keyForError, String value) {
    try {
      Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new SettingValidationException(keyForError, "must be an integer");
    }
  }

  private static void requireBoolean(String keyForError, String value) {
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new SettingValidationException(keyForError, "must be 'true' or 'false'");
    }
  }

  private static void requireEnumOption(
      String keyForError, List<String> enumOptions, String value) {
    if (!enumOptions.contains(value)) {
      throw new SettingValidationException(keyForError, "must be one of " + enumOptions);
    }
  }

  /**
   * Enforces beyond-type constraints after the type check has passed: an inclusive integer range
   * (the value already parses as an int) and/or a string format (skipped when blank, so empty
   * defaults stay valid).
   */
  private static void validateConstraints(
      SettingConstraints constraints, String value, String keyForError) {
    if (constraints.min() != null || constraints.max() != null) {
      int parsed = Integer.parseInt(value.trim());
      if (constraints.min() != null && parsed < constraints.min()) {
        throw new SettingValidationException(keyForError, "must be at least " + constraints.min());
      }
      if (constraints.max() != null && parsed > constraints.max()) {
        throw new SettingValidationException(keyForError, "must be at most " + constraints.max());
      }
    }
    if (constraints.format() != null && !value.isBlank()) {
      switch (constraints.format()) {
        case EMAIL -> requireEmail(keyForError, value);
        case URL -> requireHttpUrl(keyForError, value);
        case TIMEZONE -> requireTimezone(keyForError, value);
      }
    }
  }

  private static void requireEmail(String keyForError, String value) {
    if (!EMAIL_PATTERN.matcher(value.trim()).matches()) {
      throw new SettingValidationException(keyForError, "must be a valid email address");
    }
  }

  /**
   * Requires a valid IANA time-zone id ({@code ZoneId.of}). Fixed offsets like {@code +02:00} are
   * accepted by {@code ZoneId} too, but a region id (e.g. {@code Europe/Berlin}) is the intended
   * value; both round-trip correctly to {@code Intl.DateTimeFormat} on the frontend.
   */
  private static void requireTimezone(String keyForError, String value) {
    try {
      ZoneId.of(value.trim());
    } catch (DateTimeException e) {
      throw new SettingValidationException(keyForError, "must be a valid IANA timezone id");
    }
  }

  private static void requireHttpUrl(String keyForError, String value) {
    try {
      URI uri = URI.create(value.trim());
      String scheme = uri.getScheme();
      boolean http = scheme != null && (scheme.equals("http") || scheme.equals("https"));
      if (!http || uri.getHost() == null) {
        throw new SettingValidationException(keyForError, "must be a valid http(s) URL");
      }
    } catch (IllegalArgumentException e) {
      throw new SettingValidationException(keyForError, "must be a valid http(s) URL");
    }
  }
}
