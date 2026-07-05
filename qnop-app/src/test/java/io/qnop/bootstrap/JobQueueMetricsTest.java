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
import io.qnop.service.job.JobService;
import io.qnop.service.job.JobService.QueueStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The job-queue depth gauges (issue #348): one {@code qnop.jobs} gauge per lifecycle state. */
class JobQueueMetricsTest {

  private final JobService jobs = mock(JobService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("binds a qnop.jobs gauge per state, each reading the live snapshot")
  void bindsQueueDepthGauges() {
    when(jobs.queueStats()).thenReturn(new QueueStats(5, 2, 1, 3));

    new JobQueueMetrics(jobs).bindTo(registry);

    assertThat(gauge("pending")).isEqualTo(5.0);
    assertThat(gauge("running")).isEqualTo(2.0);
    assertThat(gauge("stale")).isEqualTo(1.0);
    assertThat(gauge("failed")).isEqualTo(3.0);
  }

  @Test
  @DisplayName("re-reads the snapshot on each scrape")
  void gaugesReflectTheLatestSnapshot() {
    when(jobs.queueStats()).thenReturn(new QueueStats(1, 0, 0, 0));
    new JobQueueMetrics(jobs).bindTo(registry);
    assertThat(gauge("pending")).isEqualTo(1.0);

    when(jobs.queueStats()).thenReturn(new QueueStats(9, 0, 0, 0));
    assertThat(gauge("pending")).isEqualTo(9.0);
  }

  private double gauge(String state) {
    Gauge gauge = registry.find("qnop.jobs").tag("state", state).gauge();
    assertThat(gauge).as("gauge qnop.jobs{state=%s}", state).isNotNull();
    return gauge.value();
  }
}
