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
package io.qnop.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Job;
import io.qnop.entity.JobStatus;
import io.qnop.repository.JobRepository;
import io.qnop.service.job.JobHandler;
import io.qnop.service.job.JobQueuePoller;
import io.qnop.service.job.JobService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** End-to-end tests for the durable async job queue (issue #242, ADR-0033). Requires Docker. */
@Import(JobQueueIT.TestHandlerConfig.class)
class JobQueueIT extends AbstractIntegrationTest {

  static final String TYPE = "test.job";

  @Autowired private JobService jobService;
  @Autowired private JobQueuePoller poller;
  @Autowired private JobRepository jobRepository;
  @Autowired private TestJobHandler handler;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM job");
    handler.reset();
  }

  @Test
  @DisplayName("an enqueued job is claimed, handled, and marked DONE")
  void enqueuedJobRunsToDone() {
    UUID id = jobService.enqueue(TYPE, "{\"k\":\"v\"}");

    poller.poll();

    assertThat(handler.invocations.get()).isEqualTo(1);
    assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.DONE);
  }

  @Test
  @DisplayName("a failing handler is retried with backoff, then succeeds")
  void handlerFailureRetriesThenSucceeds() {
    handler.failFirstN = 1; // fail the first attempt only
    UUID id = jobService.enqueue(TYPE, "{}");

    poller.poll(); // attempt 1 fails → scheduled retry

    Job afterFail = jobRepository.findById(id).orElseThrow();
    assertThat(afterFail.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(afterFail.getAttempts()).isEqualTo(1);
    assertThat(afterFail.getRunAfter()).isAfter(Instant.now()); // backoff pushed it forward
    assertThat(afterFail.getLastError()).contains("boom");

    makeDue(id);
    poller.poll(); // attempt 2 succeeds

    Job afterSuccess = jobRepository.findById(id).orElseThrow();
    assertThat(afterSuccess.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(afterSuccess.getAttempts()).isEqualTo(2);
    assertThat(handler.invocations.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("a job that never succeeds is marked FAILED once attempts are exhausted")
  void exhaustsRetriesAndFails() {
    handler.failFirstN = 99; // always fail
    UUID id = insertPendingJob(1); // max_attempts = 1 → one failure exhausts it

    poller.poll();

    Job job = jobRepository.findById(id).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts()).isEqualTo(1);
    assertThat(job.getLastError()).isNotNull();
  }

  @Test
  @DisplayName("the reaper returns crash-stranded RUNNING jobs to PENDING")
  void reaperReclaimsStaleRunning() {
    UUID id = insertRunningJob(Instant.now().minus(10, ChronoUnit.MINUTES));

    poller.reap();

    assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
  }

  @Test
  @DisplayName("enqueue is transactional with the caller (outbox): a rollback drops the job")
  void enqueueRollsBackWithCaller() {
    TransactionTemplate tx = new TransactionTemplate(txManager);

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jobService.enqueue(TYPE, "{}");
                      throw new IllegalStateException("caller failed after enqueue");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jobRepository.count()).isZero();
  }

  @Test
  @DisplayName("re-running an already-DONE job is a no-op (idempotency guard)")
  void runOneIsIdempotentOnDoneJob() {
    UUID id = jobService.enqueue(TYPE, "{}");
    poller.poll();
    assertThat(handler.invocations.get()).isEqualTo(1);

    jobService.runOne(id); // simulate a re-delivery of a completed job

    assertThat(handler.invocations.get()).isEqualTo(1); // not handled twice
    assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.DONE);
  }

  private void makeDue(UUID id) {
    jdbc.update(
        "UPDATE job SET run_after = ? WHERE id = ?::uuid",
        Timestamp.from(Instant.now().minusSeconds(1)),
        id.toString());
  }

  private UUID insertPendingJob(int maxAttempts) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job (id, type, payload, status, attempts, max_attempts, run_after,"
            + " created_at, updated_at, version)"
            + " VALUES (?::uuid, ?, ?::jsonb, 'PENDING', 0, ?, now(), now(), now(), 0)",
        id.toString(),
        TYPE,
        "{}",
        maxAttempts);
    return id;
  }

  private UUID insertRunningJob(Instant updatedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job (id, type, payload, status, attempts, max_attempts, run_after,"
            + " created_at, updated_at, version)"
            + " VALUES (?::uuid, ?, ?::jsonb, 'RUNNING', 1, 5, now(), now(), ?, 0)",
        id.toString(),
        TYPE,
        "{}",
        Timestamp.from(updatedAt));
    return id;
  }

  /** A handler whose failure behaviour and invocation count the tests control. */
  static class TestJobHandler implements JobHandler {
    final AtomicInteger invocations = new AtomicInteger();
    volatile int failFirstN = 0;

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public void handle(String payload) {
      int n = invocations.incrementAndGet();
      if (n <= failFirstN) {
        throw new IllegalStateException("boom on attempt " + n);
      }
    }

    void reset() {
      invocations.set(0);
      failFirstN = 0;
    }
  }

  @TestConfiguration
  static class TestHandlerConfig {
    @Bean
    TestJobHandler testJobHandler() {
      return new TestJobHandler();
    }
  }
}
