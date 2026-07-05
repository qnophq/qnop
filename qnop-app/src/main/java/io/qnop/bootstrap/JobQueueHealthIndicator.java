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

import io.qnop.service.job.JobService;
import io.qnop.service.job.JobService.QueueStats;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes the durable job queue to {@code /actuator/health} (issue #348). Reports {@code DOWN}
 * when a {@code RUNNING} job is stranded past the stale threshold — a dead worker the reaper has
 * not reclaimed, or a wedged poller/reaper — otherwise {@code UP}. The queue-depth counts ride
 * along as details (revealed only to an authenticated admin via {@code show-details:
 * when_authorized}). This indicator is not part of the liveness/readiness probe groups, so a
 * stale-queue {@code DOWN} surfaces the condition without triggering a pod restart.
 */
@Component
class JobQueueHealthIndicator implements HealthIndicator {

  private final JobService jobs;

  JobQueueHealthIndicator(JobService jobs) {
    this.jobs = jobs;
  }

  @Override
  public Health health() {
    QueueStats stats = jobs.queueStats();
    Health.Builder builder = stats.staleRunning() > 0 ? Health.down() : Health.up();
    return builder
        .withDetail("pending", stats.pending())
        .withDetail("running", stats.running())
        .withDetail("staleRunning", stats.staleRunning())
        .withDetail("failed", stats.failed())
        .build();
  }
}
