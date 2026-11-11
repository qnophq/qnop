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
 * A single-line text field (a display name, issue #909) carries characters it may not hold. The web
 * layer maps it to a {@code 400 VALIDATION_ERROR} naming the field.
 */
public class InvalidTextException extends RuntimeException {

  private final String field;

  public InvalidTextException(String field, String message) {
    super(message);
    this.field = field;
  }

  /** The public name of the offending field, e.g. {@code displayName}. */
  public String field() {
    return field;
  }

  /** Rejects {@code value} for {@code field} when it holds control characters. */
  public static void requireNoControlCharacters(String field, String value) {
    if (ControlCharacters.contains(value)) {
      throw new InvalidTextException(field, "must not contain control characters or line breaks");
    }
  }
}
