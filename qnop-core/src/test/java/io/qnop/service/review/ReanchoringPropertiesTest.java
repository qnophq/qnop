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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReanchoringProperties} defaults and null-coalescing (issue #320). */
class ReanchoringPropertiesTest {

  @Test
  @DisplayName("defaults() carries the documented ADR-0009 thresholds")
  void defaultsCarryDocumentedThresholds() {
    ReanchoringProperties props = ReanchoringProperties.defaults();

    assertThat(props.similarityThreshold()).isEqualTo(0.75);
    assertThat(props.ambiguityMargin()).isEqualTo(0.05);
    assertThat(props.contextLength()).isEqualTo(32);
    assertThat(props.maxCandidates()).isEqualTo(64);
    assertThat(props.quoteWeight()).isEqualTo(0.7);
    assertThat(props.contextWeight()).isEqualTo(0.3);
  }

  @Test
  @DisplayName("a null component falls back to its default; supplied values are kept")
  void nullComponentsFallBackToDefaults() {
    ReanchoringProperties props = new ReanchoringProperties(0.9, null, null, 128, null, null);

    assertThat(props.similarityThreshold()).isEqualTo(0.9); // supplied
    assertThat(props.maxCandidates()).isEqualTo(128); // supplied
    assertThat(props.ambiguityMargin()).isEqualTo(0.05); // default
    assertThat(props.contextLength()).isEqualTo(32); // default
    assertThat(props.quoteWeight()).isEqualTo(0.7); // default
    assertThat(props.contextWeight()).isEqualTo(0.3); // default
  }
}
