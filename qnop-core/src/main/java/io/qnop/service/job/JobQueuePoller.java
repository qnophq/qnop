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

import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the durable job queue (ADR-0033). A separate bean from {@link JobService} so its calls
 * into the service cross the transactional proxy (claim, run, and failure each get their own
 * transaction). {@code @SchedulerLock} (ShedLock, ADR-0029) makes each tick run at most once across
 * app instances. A failing job never aborts the batch.
 */
@Component
public class JobQueuePoller {

  private static final Logger log = LoggerFactory.getLogger(JobQueuePoller.class);

  private final JobService jobService;

  public JobQueuePoller(JobService jobService) {
    this.jobService = jobService;
  }

  /**
   * Claims due jobs and runs each; a handler failure is recorded (retry/backoff) without aborting
   * the batch.
   */
  @Scheduled(
      fixedDelayString = "${qnop.jobs.poll-interval-ms:5000}",
      initialDelayString = "${qnop.jobs.poll-interval-ms:5000}")
  @SchedulerLock(name = "jobQueuePoller", lockAtMostFor = "PT2M")
  public void poll() {
    List<UUID> claimed = jobService.claimBatch();
    for (UUID id : claimed) {
      try {
        jobService.runOne(id);
      } catch (RuntimeException e) {
        try {
          jobService.recordFailure(id, e);
        } catch (RuntimeException recordError) {
          log.error("Could not record failure for job {}", id, recordError);
        }
      }
    }
  }

  /** Reclaims jobs stranded RUNNING by a crash. */
  @Scheduled(
      fixedDelayString = "${qnop.jobs.reaper-interval-ms:60000}",
      initialDelayString = "${qnop.jobs.reaper-interval-ms:60000}")
  @SchedulerLock(name = "jobQueueReaper", lockAtMostFor = "PT2M")
  public void reap() {
    int reclaimed = jobService.reapStale();
    if (reclaimed > 0) {
      log.warn("Reaped {} stale RUNNING job(s) back to PENDING", reclaimed);
    }
  }
}
