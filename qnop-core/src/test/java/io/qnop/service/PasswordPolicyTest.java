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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  @DisplayName("8 to 200 characters within 72 UTF-8 bytes pass")
  void acceptsWithinTheLimits() {
    assertThatCode(() -> PasswordPolicy.require("password", "12345678")).doesNotThrowAnyException();
    assertThatCode(() -> PasswordPolicy.require("password", "a".repeat(72)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("too short, null, or beyond 72 UTF-8 bytes is a validation error naming the field")
  void rejectsOutsideTheLimits() {
    assertThatThrownBy(() -> PasswordPolicy.require("password", "short"))
        .isInstanceOf(InvalidTextException.class);
    assertThatThrownBy(() -> PasswordPolicy.require("password", null))
        .isInstanceOf(InvalidTextException.class);
    assertThatThrownBy(() -> PasswordPolicy.require("newPassword", "a".repeat(73)))
        .isInstanceOf(InvalidTextException.class)
        .extracting("field")
        .isEqualTo("newPassword");
    // 37 characters, 73 bytes.
    assertThatThrownBy(() -> PasswordPolicy.require("password", "ä".repeat(36) + "x"))
        .isInstanceOf(InvalidTextException.class);
  }
}
