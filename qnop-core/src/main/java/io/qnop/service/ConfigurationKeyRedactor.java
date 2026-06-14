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

import org.springframework.stereotype.Component;

/**
 * Masks sensitive setting values for API responses (issue #16): a non-blank {@code PASSWORD} value
 * is replaced by {@link #MASK}. A client that sends {@code MASK} back on update is treated as
 * "leave unchanged" by the settings service, so the real secret is never round-tripped through the
 * browser.
 */
@Component
public class ConfigurationKeyRedactor {

  /** Placeholder shown instead of a stored secret. */
  public static final String MASK = "***";

  public String redact(ApplicationSettingKey key, String value) {
    if (key.isSensitive() && value != null && !value.isBlank()) {
      return MASK;
    }
    return value;
  }

  public boolean isMask(String value) {
    return MASK.equals(value);
  }
}
