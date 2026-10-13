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

import java.util.Map;

/**
 * Raised when a settings update references an unknown key or carries a value that violates the
 * key's {@link io.qnop.entity.SettingValueType}. Mapped to HTTP 400 at the web layer.
 */
public class SettingValidationException extends RuntimeException {

  private final String settingKey;
  private final String reason;
  private final FieldErrorCode code;
  private final Map<String, String> params;

  public SettingValidationException(String settingKey, String reason, FieldErrorCode code) {
    this(settingKey, reason, code, Map.of());
  }

  public SettingValidationException(
      String settingKey, String reason, FieldErrorCode code, Map<String, String> params) {
    super("Invalid setting '" + settingKey + "': " + reason);
    this.settingKey = settingKey;
    this.reason = reason;
    this.code = code;
    this.params = Map.copyOf(params);
  }

  public String getSettingKey() {
    return settingKey;
  }

  /** The bare, field-level reason (without the key prefix), for per-field error reporting. */
  public String getReason() {
    return reason;
  }

  /** The stable reason code, for a client that shows the reason in its own language. */
  public FieldErrorCode getCode() {
    return code;
  }

  /** The values {@link #getReason()} names (e.g. {@code max}), keyed as the code documents. */
  public Map<String, String> getParams() {
    return params;
  }
}
