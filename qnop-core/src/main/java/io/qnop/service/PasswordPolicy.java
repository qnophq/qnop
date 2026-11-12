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

import java.nio.charset.StandardCharsets;

/**
 * The one password policy for every path where a user chooses a password (issue #900):
 * self-registration, setting new credentials at verification, password reset and change. 8 to 200
 * characters, and at most {@value #MAX_BYTES} UTF-8 bytes — the BCrypt input limit, beyond which
 * the encoder refuses (it would otherwise surface as a server error). A violation is an {@link
 * InvalidTextException}, i.e. a {@code 400 VALIDATION_ERROR} naming the field.
 */
public final class PasswordPolicy {

  public static final int MIN_LENGTH = 8;
  public static final int MAX_LENGTH = 200;
  public static final int MAX_BYTES = 72;

  private PasswordPolicy() {}

  /** Rejects {@code password} (submitted as {@code field}) unless it satisfies the policy. */
  public static void require(String field, String password) {
    if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
      throw new InvalidTextException(
          field, "must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new InvalidTextException(
          field,
          "must not be longer than "
              + MAX_BYTES
              + " bytes (characters outside the Latin alphabet take several)");
    }
  }
}
