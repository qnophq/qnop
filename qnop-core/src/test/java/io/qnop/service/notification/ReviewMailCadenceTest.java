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
package io.qnop.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.UserSettingKey;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The stored-value mapping, which migration 0035 has to agree with exactly (issue #680). */
class ReviewMailCadenceTest {

  @Test
  @DisplayName("an explicit opt-out survives the move away from booleans")
  void legacyFalseStaysOff() {
    // The one outcome this change must not have is mailing somebody who had said
    // no, so "false" maps to OFF and never to the new default.
    assertThat(ReviewMailCadence.parse("false")).contains(ReviewMailCadence.OFF);
    assertThat(ReviewMailCadence.parse("FALSE")).contains(ReviewMailCadence.OFF);
  }

  @Test
  @DisplayName("the legacy opt-in becomes DAILY, as the migration writes it")
  void legacyTrueBecomesDaily() {
    assertThat(ReviewMailCadence.parse("true")).contains(ReviewMailCadence.DAILY);
  }

  @Test
  @DisplayName("the three current values read back, whitespace and case aside")
  void currentValues() {
    assertThat(ReviewMailCadence.parse("IMMEDIATE")).contains(ReviewMailCadence.IMMEDIATE);
    assertThat(ReviewMailCadence.parse(" daily ")).contains(ReviewMailCadence.DAILY);
    assertThat(ReviewMailCadence.parse("off")).contains(ReviewMailCadence.OFF);
  }

  @Test
  @DisplayName("anything else is absent, so the caller falls back deliberately")
  void unknownIsEmpty() {
    assertThat(ReviewMailCadence.parse(null)).isEmpty();
    assertThat(ReviewMailCadence.parse("")).isEmpty();
    assertThat(ReviewMailCadence.parse("weekly")).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("the default comes from the registry, not from a second copy of it")
  void defaultFollowsTheRegistry() {
    assertThat(ReviewMailCadence.registryDefault())
        .isEqualTo(
            ReviewMailCadence.parse(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getDefaultValue())
                .orElseThrow());
    assertThat(ReviewMailCadence.registryDefault()).isEqualTo(ReviewMailCadence.DAILY);
  }
}
