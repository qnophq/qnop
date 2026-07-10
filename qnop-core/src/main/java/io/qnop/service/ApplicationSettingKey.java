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
 * enum covers only the global, admin-managed ones.
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
  UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB(
      "upload.document_max_file_size_mb",
      SettingValueType.INTEGER,
      "25",
      "Maximum document upload size in megabytes."),
  UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB(
      "upload.attachment_max_file_size_mb",
      SettingValueType.INTEGER,
      "10",
      "Maximum comment attachment size in megabytes."),
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
  SMTP_ENABLED(
      "smtp.enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Master switch for outgoing mail; when off, message sends are skipped."),
  SMTP_HOST("smtp.host", SettingValueType.STRING, "", "SMTP server host."),
  SMTP_PORT("smtp.port", SettingValueType.INTEGER, "587", "SMTP server port."),
  SMTP_USERNAME("smtp.username", SettingValueType.STRING, "", "SMTP authentication username."),
  SMTP_PASSWORD(
      "smtp.password",
      SettingValueType.PASSWORD,
      "",
      "SMTP authentication password (stored encrypted, redacted in API)."),
  SMTP_ENCRYPTION(
      "smtp.encryption",
      SettingValueType.ENUM,
      "starttls",
      "SMTP transport encryption: none, starttls (port 587), or tls (implicit SSL, port 465).",
      List.of("none", "starttls", "tls")),
  SMTP_FROM("smtp.from", SettingValueType.STRING, "", "Default From address for outgoing mail."),
  SMTP_FROM_NAME(
      "smtp.from_name",
      SettingValueType.STRING,
      "qnop",
      "Display name used in the From header of outgoing mail."),
  AUTH_SELF_REGISTRATION_ENABLED(
      "auth.self_registration_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Whether visitors may register their own local accounts."),
  AUTH_SELF_REGISTRATION_DEFAULT_ROLE(
      "auth.self_registration_default_role",
      SettingValueType.ENUM,
      "MEMBER",
      "Global role assigned to self-registered users (ADMIN is intentionally not selectable).",
      List.of("MEMBER", "AUDITOR")),
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

  /**
   * Beyond-type value constraints per key (admin validation). Kept here rather than threaded
   * through the constructor so the enum constants stay readable; {@link ValueValidator} enforces
   * them.
   */
  private static final Map<ApplicationSettingKey, SettingConstraints> CONSTRAINTS =
      Map.of(
          UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB, SettingConstraints.range(1, 1024),
          // Capped below the container's multipart ceiling (QNOP_UPLOAD_MULTIPART_LIMIT_MB,
          // default 55) so the service's clean 413 always fires first.
          UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB, SettingConstraints.range(1, 50),
          AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES, SettingConstraints.range(1, 1440),
          SMTP_PORT, SettingConstraints.range(1, 65535),
          SMTP_FROM, SettingConstraints.format(SettingConstraints.ValueFormat.EMAIL),
          GENERAL_BASE_URL, SettingConstraints.format(SettingConstraints.ValueFormat.URL));

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

  /**
   * Value constraints beyond the declared type (range/format); {@link SettingConstraints#NONE} if
   * none.
   */
  public SettingConstraints getConstraints() {
    return CONSTRAINTS.getOrDefault(this, SettingConstraints.NONE);
  }

  /** A sensitive value is encrypted at rest and redacted in API responses. */
  public boolean isSensitive() {
    return type == SettingValueType.PASSWORD;
  }
}
