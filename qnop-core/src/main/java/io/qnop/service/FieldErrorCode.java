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
 * Stable, machine-readable reasons for a field-level validation failure (issue #836). They travel
 * as {@code FieldError.code} next to the English {@code message}, so a client can show the reason
 * in the user's language; the accompanying {@code params} carry the values the message names (a
 * limit, the allowed options). Additive: a client that does not know a code falls back to the
 * message.
 */
public enum FieldErrorCode {
  /** The value is missing or blank. */
  REQUIRED,
  /** Not a whole number. */
  NOT_AN_INTEGER,
  /** Neither {@code true} nor {@code false}. */
  NOT_A_BOOLEAN,
  /** Not one of the allowed values; params: {@code options}. */
  NOT_AN_OPTION,
  /** Longer than allowed; params: {@code max}. */
  TOO_LONG,
  /** Length outside a range; params: {@code min}, {@code max}. */
  LENGTH_OUT_OF_RANGE,
  /** Below the minimum; params: {@code min}. */
  TOO_SMALL,
  /** Above the maximum; params: {@code max}. */
  TOO_LARGE,
  /** Not an email address. */
  INVALID_EMAIL,
  /** Not an http(s) URL. */
  INVALID_URL,
  /** Not an IANA time-zone id. */
  INVALID_TIMEZONE,
  /** Not a two-letter ISO 639-1 language code. */
  INVALID_LANGUAGE,
  /** Does not match the expected format. */
  INVALID_FORMAT,
  /** The setting key does not exist. */
  UNKNOWN_SETTING
}
