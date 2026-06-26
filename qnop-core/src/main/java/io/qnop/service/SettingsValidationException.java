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

import java.util.List;

/**
 * Raised when a settings update carries one or more invalid values. Unlike {@link
 * SettingValidationException} (a single offending key), this aggregates <em>every</em> field-level
 * violation so the admin UI can mark all bad fields at once. Mapped to HTTP 400 with a {@code
 * fieldErrors} array at the web layer; thrown before any write, so the update is all-or-nothing.
 */
public class SettingsValidationException extends RuntimeException {

  private final transient List<SettingFieldError> fieldErrors;

  public SettingsValidationException(List<SettingFieldError> fieldErrors) {
    super("Settings validation failed for " + fieldErrors.size() + " field(s)");
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public List<SettingFieldError> getFieldErrors() {
    return fieldErrors;
  }
}
