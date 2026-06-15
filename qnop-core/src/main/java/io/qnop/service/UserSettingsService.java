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

import io.qnop.entity.UserSetting;
import io.qnop.repository.UserSettingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user settings access (issue #22). Each user's values are stored in {@code user_setting} and
 * typed by the {@link UserSettingKey} registry; missing keys fall back to the registry default.
 *
 * <p>Unlike the global application settings (issue #16), there is no in-memory snapshot — per-user
 * reads are infrequent and indexed by {@code user_id} — and no secrets/encryption.
 */
@Service
public class UserSettingsService {

  private final UserSettingRepository repository;

  public UserSettingsService(UserSettingRepository repository) {
    this.repository = repository;
  }

  /** All settings for the user, with stored values overlaid on registry defaults. */
  @Transactional(readOnly = true)
  public List<SettingView> getSettings(UUID userId) {
    Map<String, UserSetting> stored = new HashMap<>();
    repository.findByUserId(userId).forEach(row -> stored.put(row.getSettingKey(), row));

    List<SettingView> views = new ArrayList<>();
    for (UserSettingKey key : UserSettingKey.values()) {
      UserSetting row = stored.get(key.getKey());
      String value = row != null ? row.getSettingValue() : key.getDefaultValue();
      views.add(new SettingView(key.getKey(), value, key.getType().name(), key.getDescription()));
    }
    return views;
  }

  /**
   * Applies a partial set of changes for the user. Unknown keys and type-invalid values are
   * rejected with {@link SettingValidationException}.
   */
  @Transactional
  public List<SettingView> updateSettings(UUID userId, Map<String, String> changes) {
    for (Map.Entry<String, String> entry : changes.entrySet()) {
      UserSettingKey key =
          UserSettingKey.fromKey(entry.getKey())
              .orElseThrow(
                  () -> new SettingValidationException(entry.getKey(), "unknown user setting key"));
      ValueValidator.validate(key, entry.getValue());

      UserSetting row =
          repository
              .findByUserIdAndSettingKey(userId, key.getKey())
              .orElseGet(() -> new UserSetting(userId, key.getKey(), null));
      row.setSettingValue(entry.getValue());
      repository.save(row);
    }
    return getSettings(userId);
  }

  /** A self-contained view of one user setting (no entity types reach the web layer). */
  public record SettingView(String key, String value, String type, String description) {}
}
