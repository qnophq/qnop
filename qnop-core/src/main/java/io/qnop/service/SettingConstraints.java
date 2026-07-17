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
 * Optional, beyond-type constraints for an {@link ApplicationSettingKey} value (issue: admin
 * validation): an inclusive integer range and/or a string format. {@link ValueValidator} enforces
 * these after the type check. A {@code format} check is skipped for blank values (the empty default
 * stays valid until an operator fills it in).
 */
public record SettingConstraints(Integer min, Integer max, ValueFormat format) {

  /** A string format an {@code ENUM}-free value must match when non-blank. */
  public enum ValueFormat {
    EMAIL,
    URL,
    TIMEZONE
  }

  /** No extra constraints beyond the declared type. */
  public static final SettingConstraints NONE = new SettingConstraints(null, null, null);

  /**
   * An inclusive integer range, e.g. a TCP port (1–65535). Only valid for {@code INTEGER}-typed
   * keys: {@link ValueValidator} parses the value as an int after the type check has passed.
   */
  public static SettingConstraints range(int min, int max) {
    return new SettingConstraints(min, max, null);
  }

  /** A string-format constraint, e.g. an email address or http(s) URL. */
  public static SettingConstraints format(ValueFormat format) {
    return new SettingConstraints(null, null, format);
  }
}
