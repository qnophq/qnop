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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The authoritative registry of known application settings (issue #16): each key carries its {@link
 * SettingValueType}, default value, human description, and (for {@code ENUM}) the allowed options.
 *
 * <p>This registry is the single source of truth for the global settings; the {@code
 * application_setting} table (issue #13) is its persisted projection. The key set here must stay in
 * sync with the Liquibase seed. Per-user settings have their own registry (issues #22/#24); this
 * enum covers only the global, superadmin-managed ones.
 */
public enum ApplicationSettingKey {
  GENERAL_APPLICATION_NAME(
      "general.application_name",
      SettingValueType.STRING,
      "qnop",
      "Display name of this qnop instance."),
  GENERAL_BASE_URL(
      "general.base_url",
      SettingValueType.STRING,
      "",
      "Public base URL, used in generated links and emails."),
  GENERAL_DEFAULT_LANGUAGE(
      "general.default_language",
      SettingValueType.STRING,
      "en",
      "Default UI language (ISO 639-1)."),
  UPLOAD_MAX_FILE_SIZE_MB(
      "upload.max_file_size_mb",
      SettingValueType.INTEGER,
      "25",
      "Maximum document upload size in megabytes."),
  TRACKING_ENABLED(
      "tracking.enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Whether anonymous usage tracking is enabled."),
  TRACKING_PROVIDER(
      "tracking.provider",
      SettingValueType.ENUM,
      "none",
      "Usage-tracking provider.",
      List.of("none", "matomo", "plausible", "umami")),
  SMTP_HOST("smtp.host", SettingValueType.STRING, "", "SMTP server host."),
  SMTP_PORT("smtp.port", SettingValueType.INTEGER, "587", "SMTP server port."),
  SMTP_USERNAME("smtp.username", SettingValueType.STRING, "", "SMTP authentication username."),
  SMTP_PASSWORD(
      "smtp.password",
      SettingValueType.PASSWORD,
      "",
      "SMTP authentication password (stored encrypted, redacted in API)."),
  SMTP_FROM("smtp.from", SettingValueType.STRING, "", "Default From address for outgoing mail."),
  SMTP_TLS_ENABLED(
      "smtp.tls_enabled", SettingValueType.BOOLEAN, "true", "Whether to use STARTTLS for SMTP."),
  AUTH_SELF_REGISTRATION_ENABLED(
      "auth.self_registration_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Whether visitors may register their own local accounts."),
  AUTH_SELF_REGISTRATION_DEFAULT_ROLE(
      "auth.self_registration_default_role",
      SettingValueType.STRING,
      "USER",
      "Role assigned to self-registered users."),
  AUTH_PASSWORD_RESET_ENABLED(
      "auth.password_reset_enabled",
      SettingValueType.BOOLEAN,
      "true",
      "Whether local users may reset their password by email."),
  AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES(
      "auth.password_reset_token_ttl_minutes",
      SettingValueType.INTEGER,
      "30",
      "Validity window of a password-reset token, in minutes.");

  private static final Map<String, ApplicationSettingKey> BY_KEY =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(ApplicationSettingKey::getKey, Function.identity()));

  private final String key;
  private final SettingValueType type;
  private final String defaultValue;
  private final String description;
  private final List<String> enumOptions;

  ApplicationSettingKey(
      String key, SettingValueType type, String defaultValue, String description) {
    this(key, type, defaultValue, description, List.of());
  }

  ApplicationSettingKey(
      String key,
      SettingValueType type,
      String defaultValue,
      String description,
      List<String> enumOptions) {
    this.key = key;
    this.type = type;
    this.defaultValue = defaultValue;
    this.description = description;
    this.enumOptions = List.copyOf(enumOptions);
  }

  public static Optional<ApplicationSettingKey> fromKey(String key) {
    return Optional.ofNullable(BY_KEY.get(key));
  }

  public String getKey() {
    return key;
  }

  public SettingValueType getType() {
    return type;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  public String getDescription() {
    return description;
  }

  public List<String> getEnumOptions() {
    return enumOptions;
  }

  /** A sensitive value is encrypted at rest and redacted in API responses. */
  public boolean isSensitive() {
    return type == SettingValueType.PASSWORD;
  }
}
