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

import io.qnop.entity.Job;
import io.qnop.entity.JobStatus;
import io.qnop.repository.JobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable async job queue (issue #242, ADR-0033). {@link #enqueue} writes a job in the caller's
 * transaction (outbox); {@link JobQueuePoller} then drives execution by calling {@link
 * #claimBatch}, {@link #runOne} and {@link #recordFailure} — kept as separate transactional units
 * (not self-invoked) so a handler failure rolls back its own effects without losing the failure
 * bookkeep, and so {@link #reapStale} can recover jobs stranded {@code RUNNING} by a crash.
 * Handlers are dispatched by {@code Job.type} and must be idempotent.
 *
 * <p><strong>Handler transactions (issue #314).</strong> {@link #runOne} deliberately runs the
 * handler <em>without</em> an ambient transaction, so a handler doing slow I/O (an S3 fetch, a PDF
 * parse) never pins a pooled DB connection for its whole duration. Each handler owns the
 * transaction around its own writes, and the terminal {@code DONE} write is a separate short
 * transaction. Handler-effects and {@code DONE} are therefore no longer one atomic unit — the
 * mandatory idempotency (a re-run after a crash between the two is a no-op) is what keeps this
 * safe.
 */
@Service
public class JobService {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  static final int POLL_BATCH = 20;
  static final Duration BACKOFF_BASE = Duration.ofSeconds(10);
  static final Duration BACKOFF_CAP = Duration.ofMinutes(10);
  static final Duration STALE_AFTER = Duration.ofMinutes(5);
  private static final int MAX_ERROR_LENGTH = 2000;

  private final JobRepository repository;
  private final JobEnqueuer enqueuer;
  private final Map<String, JobHandler> handlers;

  public JobService(JobRepository repository, JobEnqueuer enqueuer, List<JobHandler> handlers) {
    this.repository = repository;
    this.enqueuer = enqueuer;
    this.handlers =
        handlers.stream()
            .collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
  }

  /**
   * Enqueues a job to run as soon as the poller picks it up. Delegates to {@link JobEnqueuer} — the
   * narrow write-side bean handlers depend on to avoid the dispatch↔handler cycle (issue #318) —
   * while remaining the queue's public facade for callers that already hold a {@code JobService}.
   * Runs in the caller's transaction (outbox): the job and the triggering write commit together.
   */
  public UUID enqueue(String type, String payload) {
    return enqueuer.enqueue(type, payload);
  }

  /** Claims a batch of due jobs (flips them to {@code RUNNING}) and returns their ids. */
  @Transactional
  public List<UUID> claimBatch() {
    return repository.claimDuePending(Instant.now(), POLL_BATCH);
  }

  /**
   * Runs one claimed job: dispatches its handler, then marks it {@code DONE}. Intentionally
   * <em>not</em> {@code @Transactional} (issue #314) — the handler must be free to do slow I/O
   * without holding a DB connection, so it owns the transaction around its own writes and this
   * method marks {@code DONE} in a separate short transaction (via the repository). A throwing
   * handler propagates to the caller, which records the failure; the {@code RUNNING} guard plus
   * handler idempotency make a re-run a no-op.
   */
  public void runOne(UUID id) {
    Job job = repository.findById(id).orElse(null);
    if (job == null || job.getStatus() != JobStatus.RUNNING) {
      return;
    }
    JobHandler handler = handlers.get(job.getType());
    if (handler == null) {
      throw new IllegalStateException("No JobHandler registered for type: " + job.getType());
    }
    handler.handle(job.getPayload());
    job.markDone();
    repository.save(job);
  }

  /**
   * Records a failed attempt in its own transaction: retry with backoff while attempts remain, else
   * {@code FAILED}.
   */
  @Transactional
  public void recordFailure(UUID id, Throwable cause) {
    Job job = repository.findById(id).orElse(null);
    if (job == null || job.getStatus() != JobStatus.RUNNING) {
      return;
    }
    String error = errorText(cause);
    if (job.getAttempts() >= job.getMaxAttempts()) {
      log.warn(
          "Job {} ({}) failed permanently after {} attempts", id, job.getType(), job.getAttempts());
      job.markFailed(error);
    } else {
      Instant next = Instant.now().plus(backoff(job.getAttempts()));
      log.info(
          "Job {} ({}) attempt {} failed; retrying at {}",
          id,
          job.getType(),
          job.getAttempts(),
          next);
      job.scheduleRetry(next, error);
    }
    repository.save(job);
  }

  /**
   * Reclaims jobs stuck {@code RUNNING} past the stale threshold (a crashed worker) back to {@code
   * PENDING}.
   */
  @Transactional
  public int reapStale() {
    Instant now = Instant.now();
    return repository.reapStaleRunning(now.minus(STALE_AFTER), now);
  }

  /**
   * A queue-depth snapshot for observability (issue #348): the backlog ({@code pending}), the
   * in-flight count ({@code running}), how many of those are stranded past the stale threshold
   * ({@code staleRunning} — a dead worker or a wedged poller/reaper), and the terminal failures
   * ({@code failed}). Returned as primitives so the web/actuator layer never touches the entity
   * enum.
   */
  public record QueueStats(long pending, long running, long staleRunning, long failed) {}

  /** The current queue-depth snapshot (issue #348), reusing the reaper's stale threshold. */
  @Transactional(readOnly = true)
  public QueueStats queueStats() {
    Instant staleBefore = Instant.now().minus(STALE_AFTER);
    return new QueueStats(
        repository.countByStatus(JobStatus.PENDING),
        repository.countByStatus(JobStatus.RUNNING),
        repository.countByStatusAndUpdatedAtBefore(JobStatus.RUNNING, staleBefore),
        repository.countByStatus(JobStatus.FAILED));
  }

  /** Capped exponential backoff: {@code base * 2^(attempts-1)}, clamped to the cap. */
  static Duration backoff(int attempts) {
    int shift = Math.min(Math.max(attempts - 1, 0), 20);
    long seconds = BACKOFF_BASE.getSeconds() << shift;
    return Duration.ofSeconds(Math.min(seconds, BACKOFF_CAP.getSeconds()));
  }

  private static String errorText(Throwable cause) {
    String text = cause.getClass().getName() + ": " + cause.getMessage();
    return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
  }
}
