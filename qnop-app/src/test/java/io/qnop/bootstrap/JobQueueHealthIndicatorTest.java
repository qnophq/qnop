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

import io.qnop.service.job.JobService;
import io.qnop.service.job.JobService.QueueStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** The job-queue health contribution (issue #348): UP unless a RUNNING job is stranded. */
class JobQueueHealthIndicatorTest {

  private final JobService jobs = mock(JobService.class);
  private final JobQueueHealthIndicator indicator = new JobQueueHealthIndicator(jobs);

  @Test
  @DisplayName("UP with the depth counts as details when nothing is stale")
  void upWhenNoStaleJobs() {
    when(jobs.queueStats()).thenReturn(new QueueStats(3, 1, 0, 2));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("pending", 3L)
        .containsEntry("running", 1L)
        .containsEntry("staleRunning", 0L)
        .containsEntry("failed", 2L);
  }

  @Test
  @DisplayName("DOWN when a RUNNING job is stranded past the stale threshold")
  void downWhenStaleJobsPresent() {
    when(jobs.queueStats()).thenReturn(new QueueStats(0, 2, 1, 0));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("staleRunning", 1L);
  }
}
