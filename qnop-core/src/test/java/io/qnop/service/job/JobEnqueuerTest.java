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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Job;
import io.qnop.entity.JobStatus;
import io.qnop.repository.JobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for the outbox write side of the job queue (issue #318, ADR-0033). */
class JobEnqueuerTest {

  private final JobRepository repository = mock(JobRepository.class);
  private final JobEnqueuer enqueuer = new JobEnqueuer(repository);

  @Test
  @DisplayName("enqueue saves a PENDING job carrying the type, payload and default attempts")
  void enqueuePersistsAPendingJob() {
    ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
    when(repository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

    enqueuer.enqueue("document.reanchor", "{\"versionId\":\"x\"}");

    verify(repository).save(saved.capture());
    Job job = saved.getValue();
    assertThat(job.getType()).isEqualTo("document.reanchor");
    assertThat(job.getPayload()).isEqualTo("{\"versionId\":\"x\"}");
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(job.getMaxAttempts()).isEqualTo(JobEnqueuer.DEFAULT_MAX_ATTEMPTS);
  }
}
