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
import io.qnop.repository.JobRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of the durable job queue: appends a job in the caller's transaction (outbox,
 * ADR-0033). Split out of {@link JobService} to break the dispatch↔handler cycle (issue #318) —
 * {@code JobService} injects every {@link JobHandler}, yet a handler's write phase needs to enqueue
 * a follow-up job. Depending on this narrow bean (its only collaborator is {@link JobRepository})
 * instead of the full {@code JobService} means a handler no longer has a path back to the
 * dispatcher that injected it, so no lazy {@code ObjectProvider} indirection is needed.
 */
@Service
public class JobEnqueuer {

  /** Attempts a job is retried before it is parked {@code FAILED} (matches the queue's backoff). */
  static final int DEFAULT_MAX_ATTEMPTS = 5;

  private final JobRepository repository;

  public JobEnqueuer(JobRepository repository) {
    this.repository = repository;
  }

  /**
   * Enqueues a job to run as soon as the poller picks it up. Runs in the caller's transaction
   * (outbox): the job and the triggering write commit together, or neither does.
   */
  @Transactional
  public UUID enqueue(String type, String payload) {
    Job job = new Job(type, payload, DEFAULT_MAX_ATTEMPTS, Instant.now());
    return repository.save(job).getId();
  }
}
