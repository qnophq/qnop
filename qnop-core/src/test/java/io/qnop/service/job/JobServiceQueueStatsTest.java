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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.JobStatus;
import io.qnop.repository.JobRepository;
import io.qnop.service.job.JobService.QueueStats;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The observability queue-depth snapshot (issue #348). */
class JobServiceQueueStatsTest {

  private final JobRepository repository = mock(JobRepository.class);
  private final JobService service = new JobService(repository, mock(JobEnqueuer.class), List.of());

  @Test
  @DisplayName("assembles the per-state counts, measuring stale RUNNING against the threshold")
  void queueStatsAssemblesCounts() {
    when(repository.countByStatus(JobStatus.PENDING)).thenReturn(4L);
    when(repository.countByStatus(JobStatus.RUNNING)).thenReturn(2L);
    when(repository.countByStatus(JobStatus.FAILED)).thenReturn(1L);
    when(repository.countByStatusAndUpdatedAtBefore(eq(JobStatus.RUNNING), any())).thenReturn(1L);

    QueueStats stats = service.queueStats();

    assertThat(stats).isEqualTo(new QueueStats(4, 2, 1, 1));
  }

  @Test
  @DisplayName("counts stale RUNNING with a threshold in the past, not the future")
  void staleThresholdIsInThePast() {
    ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
    when(repository.countByStatusAndUpdatedAtBefore(eq(JobStatus.RUNNING), staleBefore.capture()))
        .thenReturn(0L);

    service.queueStats();

    assertThat(staleBefore.getValue()).isBefore(Instant.now());
  }
}
