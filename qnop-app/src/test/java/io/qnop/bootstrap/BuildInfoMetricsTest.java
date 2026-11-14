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
package io.qnop.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.web.RunningVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@code qnop.info} build gauge (issue #962). */
class BuildInfoMetricsTest {

  @Test
  @DisplayName("publishes qnop.info as 1, tagged with the running version and edition")
  void publishesTheRunningBuild() {
    RunningVersion version = mock(RunningVersion.class);
    when(version.value()).thenReturn("1.2.3");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new BuildInfoMetrics(version).bindTo(registry);

    Gauge info =
        registry.find("qnop.info").tag("version", "1.2.3").tag("edition", "community").gauge();
    assertThat(info).isNotNull();
    assertThat(info.value()).isEqualTo(1.0);
  }
}
