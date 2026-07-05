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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.qnop.service.job.JobService;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Publishes job-queue depth as Prometheus gauges (issue #348): {@code qnop.jobs{state="pending"}}
 * (the backlog), {@code running}, {@code stale} (in-flight past the stale threshold) and {@code
 * failed}. Spring Boot binds every {@link MeterBinder} bean to the meter registry, so scraping
 * {@code /actuator/prometheus} evaluates these gauges live — each reads a fresh snapshot at scrape
 * time.
 */
@Component
class JobQueueMetrics implements MeterBinder {

  private final JobService jobs;

  JobQueueMetrics(JobService jobs) {
    this.jobs = jobs;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    gauge(registry, "pending", stats -> stats.pending());
    gauge(registry, "running", stats -> stats.running());
    gauge(registry, "stale", stats -> stats.staleRunning());
    gauge(registry, "failed", stats -> stats.failed());
  }

  private void gauge(
      MeterRegistry registry, String state, ToDoubleFunction<JobService.QueueStats> value) {
    Gauge.builder("qnop.jobs", jobs, j -> value.applyAsDouble(j.queueStats()))
        .tag("state", state)
        .description("Durable job-queue depth by lifecycle state")
        .register(registry);
  }
}
