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
package io.qnop.service.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobServiceBackoffTest {

  @Test
  @DisplayName("backoff doubles per attempt and is clamped to the cap")
  void backoffIsCappedExponential() {
    assertThat(JobService.backoff(1)).isEqualTo(Duration.ofSeconds(10)); // base
    assertThat(JobService.backoff(2)).isEqualTo(Duration.ofSeconds(20));
    assertThat(JobService.backoff(3)).isEqualTo(Duration.ofSeconds(40));
    assertThat(JobService.backoff(4)).isEqualTo(Duration.ofSeconds(80));
    assertThat(JobService.backoff(7)).isEqualTo(Duration.ofMinutes(10)); // 640s clamped to 600s cap
    assertThat(JobService.backoff(30))
        .isEqualTo(Duration.ofMinutes(10)); // clamp holds, no overflow
  }

  @Test
  @DisplayName("backoff guards non-positive attempts")
  void backoffGuardsLowAttempts() {
    assertThat(JobService.backoff(0)).isEqualTo(Duration.ofSeconds(10));
    assertThat(JobService.backoff(-5)).isEqualTo(Duration.ofSeconds(10));
  }
}
