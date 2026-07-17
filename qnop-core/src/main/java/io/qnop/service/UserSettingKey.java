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
 * The authoritative registry of per-user settings (issue #22): each key carries its {@link
 * SettingValueType}, default, description, and (for {@code ENUM}) the allowed options.
 *
 * <p>Per-user settings live in {@code user_setting} (issue #13) keyed by {@code (user_id,
 * setting_key)} and are typed by this registry, not by a per-row column (ADR-0025). The set is
 * deliberately small and UI-facing; none are secrets.
 */
public enum UserSettingKey {
  THEME(
      "theme",
      SettingValueType.ENUM,
      "system",
      "Preferred UI theme.",
      List.of("system", "light", "dark")),
  PREFERRED_LANGUAGE(
      "preferred_language", SettingValueType.STRING, "en", "Preferred UI language (ISO 639-1)."),
  TIMEZONE("timezone", SettingValueType.STRING, "UTC", "Preferred display timezone (IANA id).");

  private static final Map<String, UserSettingKey> BY_KEY =
      Arrays.stream(values())
          .collect(Collectors.toUnmodifiableMap(UserSettingKey::getKey, Function.identity()));

  /**
   * Beyond-type value constraints per key (mirrors {@link ApplicationSettingKey}); {@link
   * ValueValidator} enforces them at the setting boundary. The display timezone must be a real IANA
   * zone id so a future backend consumer of the per-user zone (server-rendered export, scheduled
   * mail) can trust it (issue #465, ADR-0039).
   */
  private static final Map<UserSettingKey, SettingConstraints> CONSTRAINTS =
      Map.of(TIMEZONE, SettingConstraints.format(SettingConstraints.ValueFormat.TIMEZONE));

  private final String key;
  private final SettingValueType type;
  private final String defaultValue;
  private final String description;
  private final List<String> enumOptions;

  UserSettingKey(String key, SettingValueType type, String defaultValue, String description) {
    this(key, type, defaultValue, description, List.of());
  }

  UserSettingKey(
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

  public static Optional<UserSettingKey> fromKey(String key) {
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
   * Value constraints beyond the declared type (e.g. a valid IANA timezone); {@link
   * SettingConstraints#NONE} if none.
   */
  public SettingConstraints getConstraints() {
    return CONSTRAINTS.getOrDefault(this, SettingConstraints.NONE);
  }
}
