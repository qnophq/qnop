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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.entity.Job;
import io.qnop.entity.JobStatus;
import io.qnop.observability.metrics.JobMeters;
import io.qnop.repository.JobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the job queue reports about its own work (issue #962). */
class JobServiceMetersTest {

  private static final String TYPE = "test.job";

  private final JobRepository repository = mock(JobRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final UUID id = UUID.randomUUID();

  private JobService serviceWith(JobHandler handler) {
    return new JobService(
        repository, mock(JobEnqueuer.class), List.of(handler), new JobMeters(registry));
  }

  private static JobHandler handler(Runnable work) {
    return new JobHandler() {
      @Override
      public String type() {
        return TYPE;
      }

      @Override
      public void handle(String payload) {
        work.run();
      }
    };
  }

  private void givenRunningJob(int attempts, int maxAttempts, Duration dueSince) {
    Job job = mock(Job.class);
    when(job.getStatus()).thenReturn(JobStatus.RUNNING);
    when(job.getType()).thenReturn(TYPE);
    when(job.getPayload()).thenReturn("{}");
    when(job.getAttempts()).thenReturn(attempts);
    when(job.getMaxAttempts()).thenReturn(maxAttempts);
    when(job.getRunAfter()).thenReturn(Instant.now().minus(dueSince));
    when(repository.findById(id)).thenReturn(Optional.of(job));
  }

  private Timer execution(String outcome) {
    return registry.find(JobMeters.EXECUTION).tag("type", TYPE).tag("outcome", outcome).timer();
  }

  @Test
  @DisplayName("a job that completes is timed as a success")
  void timesASuccess() {
    givenRunningJob(1, 5, Duration.ZERO);

    serviceWith(handler(() -> {})).runOne(id);

    assertThat(execution("success").count()).isEqualTo(1);
    assertThat(execution("retry")).isNull();
    assertThat(execution("failed")).isNull();
  }

  @Test
  @DisplayName("a failed attempt with attempts left is timed as a retry, and still propagates")
  void timesARetry() {
    givenRunningJob(1, 5, Duration.ZERO);
    JobService service = serviceWith(handler(JobServiceMetersTest::fail));

    assertThatThrownBy(() -> service.runOne(id)).isInstanceOf(IllegalStateException.class);

    assertThat(execution("retry").count()).isEqualTo(1);
    assertThat(execution("success")).isNull();
  }

  @Test
  @DisplayName("a failed last attempt is timed as failed")
  void timesAFinalFailure() {
    givenRunningJob(5, 5, Duration.ZERO);
    JobService service = serviceWith(handler(JobServiceMetersTest::fail));

    assertThatThrownBy(() -> service.runOne(id)).isInstanceOf(IllegalStateException.class);

    assertThat(execution("failed").count()).isEqualTo(1);
    assertThat(execution("retry")).isNull();
  }

  @Test
  @DisplayName("records how long the job was due before a worker started it")
  void recordsTheQueueWait() {
    givenRunningJob(1, 5, Duration.ofSeconds(40));

    serviceWith(handler(() -> {})).runOne(id);

    Timer wait = registry.find(JobMeters.QUEUE_WAIT).tag("type", TYPE).timer();
    assertThat(wait.count()).isEqualTo(1);
    assertThat(wait.totalTime(TimeUnit.SECONDS)).isBetween(40.0, 60.0);
  }

  @Test
  @DisplayName("a job that is no longer RUNNING is not run and not measured")
  void skipsWhatIsNotRunning() {
    Job job = mock(Job.class);
    when(job.getStatus()).thenReturn(JobStatus.DONE);
    when(repository.findById(id)).thenReturn(Optional.of(job));

    serviceWith(handler(JobServiceMetersTest::fail)).runOne(id);

    assertThat(registry.find(JobMeters.EXECUTION).timers()).isEmpty();
    assertThat(registry.find(JobMeters.QUEUE_WAIT).timers()).isEmpty();
  }

  @Test
  @DisplayName("counts the jobs the reaper returns to the queue")
  void countsReapedJobs() {
    when(repository.reapStaleRunning(any(), any())).thenReturn(3);

    serviceWith(handler(() -> {})).reapStale();

    assertThat(registry.find(JobMeters.REAPED).counter().count()).isEqualTo(3.0);
  }

  @Test
  @DisplayName("reports how long the oldest due job has waited, and zero when none is due")
  void reportsTheOldestPendingAge() {
    JobService service = serviceWith(handler(() -> {}));
    when(repository.findEarliestRunAfter(eq(JobStatus.PENDING), any())).thenReturn(null);
    assertThat(service.oldestPendingAge()).isEqualTo(Duration.ZERO);

    when(repository.findEarliestRunAfter(eq(JobStatus.PENDING), any()))
        .thenReturn(Instant.now().minus(Duration.ofMinutes(3)));

    assertThat(service.oldestPendingAge()).isBetween(Duration.ofMinutes(3), Duration.ofMinutes(4));
  }

  private static void fail() {
    throw new IllegalStateException("handler failed");
  }
}
