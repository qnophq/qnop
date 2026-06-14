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
package io.qnop.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;
import java.util.Set;

/**
 * Constraint logic for {@link ValidSecret}. Kept as plain, framework-light code so it is unit
 * testable without a Spring context (ADR-0004).
 */
public class SecretValidator implements ConstraintValidator<ValidSecret, String> {

  /** Minimum secret length; below this brute-forcing derived keys becomes feasible. */
  static final int MIN_LENGTH = 32;

  /**
   * Well-known placeholder/default values. These ship in {@code .env.example} precisely so that a
   * deployment which forgot to set a real secret fails fast instead of running with a public value.
   */
  private static final Set<String> FORBIDDEN =
      Set.of("change_me", "change-me", "changeme", "changeit", "secret", "password", "qnop");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.length() < MIN_LENGTH) {
      return false;
    }
    return !FORBIDDEN.contains(trimmed.toLowerCase(Locale.ROOT));
  }
}
