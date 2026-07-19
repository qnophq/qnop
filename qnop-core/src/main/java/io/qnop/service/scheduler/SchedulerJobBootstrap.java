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
package io.qnop.service.scheduler;

import io.qnop.entity.SchedulerJob;
import io.qnop.repository.SchedulerJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a {@code scheduler_job} row for every catalogued job at start-up (issue #524, ADR-0045).
 * Idempotent: an existing row (with its operator settings and last-run history) is left untouched,
 * so a restart never resets an admin's toggles. A fresh install gets every job enabled and not in
 * dry-run. Missing rows are harmless anyway — the gate fails open — but seeding means the dashboard
 * shows the full set immediately.
 */
@Component
public class SchedulerJobBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SchedulerJobBootstrap.class);

  private final SchedulerJobRepository jobs;

  public SchedulerJobBootstrap(SchedulerJobRepository jobs) {
    this.jobs = jobs;
  }

  @Override
  public void run(ApplicationArguments args) {
    int seeded = 0;
    for (String jobId : SchedulerJobCatalog.jobIds()) {
      if (!jobs.existsById(jobId)) {
        jobs.save(SchedulerJob.seed(jobId));
        seeded++;
      }
    }
    if (seeded > 0) {
      log.info("Seeded {} scheduler-job row(s).", seeded);
    }
  }
}
