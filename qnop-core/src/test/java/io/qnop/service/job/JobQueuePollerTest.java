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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the durable-queue poller (issue #349). {@link JobQueuePoller}'s only collaborator
 * is {@link JobService}, so a mock fully isolates the batch-dispatch contract: run each claimed job,
 * record a handler failure without aborting the batch, and reap stale jobs. The transactional/
 * ShedLock semantics are inert without a Spring context and are covered by {@code JobQueueIT}.
 */
@ExtendWith(MockitoExtension.class)
class JobQueuePollerTest {

  @Mock private JobService jobService;

  private JobQueuePoller poller;

  @BeforeEach
  void setUp() {
    poller = new JobQueuePoller(jobService);
  }

  @Test
  @DisplayName("poll runs every claimed job, in claim order")
  void runsEveryClaimedJobInOrder() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    when(jobService.claimBatch()).thenReturn(List.of(a, b, c));

    poller.poll();

    InOrder inOrder = Mockito.inOrder(jobService);
    inOrder.verify(jobService).runOne(a);
    inOrder.verify(jobService).runOne(b);
    inOrder.verify(jobService).runOne(c);
    verify(jobService, never()).recordFailure(any(), any());
  }

  @Test
  @DisplayName("a failing job is recorded and the batch continues")
  void aFailingJobIsRecordedAndTheBatchContinues() {
    UUID ok = UUID.randomUUID();
    UUID boom = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(jobService.claimBatch()).thenReturn(List.of(ok, boom, next));
    RuntimeException failure = new IllegalStateException("handler blew up");
    doThrow(failure).when(jobService).runOne(boom);

    poller.poll();

    verify(jobService).runOne(ok);
    verify(jobService).recordFailure(eq(boom), any());
    // The batch is not aborted: the job after the failing one still runs.
    verify(jobService).runOne(next);
  }

  @Test
  @DisplayName("a failure to record the failure is swallowed, not propagated")
  void aFailureToRecordIsSwallowed() {
    UUID boom = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(jobService.claimBatch()).thenReturn(List.of(boom, next));
    doThrow(new IllegalStateException("handler blew up")).when(jobService).runOne(boom);
    doThrow(new IllegalStateException("could not record")).when(jobService).recordFailure(eq(boom), any());

    assertThatCode(() -> poller.poll()).doesNotThrowAnyException();
    // Even when recordFailure itself throws, the next job is still processed.
    verify(jobService).runOne(next);
  }

  @Test
  @DisplayName("an empty batch runs no jobs")
  void emptyBatchRunsNothing() {
    when(jobService.claimBatch()).thenReturn(List.of());

    poller.poll();

    verify(jobService, never()).runOne(any());
    verify(jobService, never()).recordFailure(any(), any());
  }

  @Test
  @DisplayName("reap delegates to reapStale and tolerates a positive stale count")
  void reapDelegatesToReapStale() {
    when(jobService.reapStale()).thenReturn(3);

    assertThatCode(() -> poller.reap()).doesNotThrowAnyException();

    verify(jobService).reapStale();
  }
}
