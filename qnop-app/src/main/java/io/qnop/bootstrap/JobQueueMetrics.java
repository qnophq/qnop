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
import java.time.Clock;
import java.time.Duration;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Publishes job-queue depth as Prometheus gauges (issue #348): {@code qnop.jobs{state="pending"}}
 * (the backlog), {@code running}, {@code stale} (in-flight past the stale threshold) and {@code
 * failed}. Spring Boot binds every {@link MeterBinder} bean to the meter registry, so scraping
 * {@code /actuator/prometheus} evaluates these gauges.
 *
 * <p>{@code qnop.jobs.oldest.pending.age} says whether the queue is moving: a short queue whose
 * oldest job has waited for minutes is stuck.
 *
 * <p>The gauges share one snapshot that is at most {@link #SNAPSHOT_MAX_AGE} old (issue #962), so a
 * scrape costs one read of the queue rather than one per gauge. The counts come from the database
 * and are therefore the same on every instance: aggregate them with {@code max}, not {@code sum}.
 */
@Component
class JobQueueMetrics implements MeterBinder {

  /** Well under any sensible scrape interval's alerting window, and long enough to be shared. */
  static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(15);

  private static final double MILLIS_PER_SECOND = 1000.0;

  /** Everything the gauges need, read from the queue in one go. */
  private record Snapshot(JobService.QueueStats stats, Duration oldestPendingAge) {}

  private final CachedSnapshot<Snapshot> snapshot;

  @Autowired
  JobQueueMetrics(JobService jobs) {
    this(jobs, Clock.systemUTC());
  }

  JobQueueMetrics(JobService jobs, Clock clock) {
    this.snapshot =
        new CachedSnapshot<>(
            () -> new Snapshot(jobs.queueStats(), jobs.oldestPendingAge()),
            SNAPSHOT_MAX_AGE,
            clock);
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    gauge(registry, "pending", stats -> stats.pending());
    gauge(registry, "running", stats -> stats.running());
    gauge(registry, "stale", stats -> stats.staleRunning());
    gauge(registry, "failed", stats -> stats.failed());
    Gauge.builder(
            "qnop.jobs.oldest.pending.age",
            snapshot,
            s -> s.get().oldestPendingAge().toMillis() / MILLIS_PER_SECOND)
        .baseUnit("seconds")
        .description("How long the longest-waiting due job has been waiting; 0 when none is due")
        .register(registry);
  }

  private void gauge(
      MeterRegistry registry, String state, ToDoubleFunction<JobService.QueueStats> value) {
    Gauge.builder("qnop.jobs", snapshot, s -> value.applyAsDouble(s.get().stats()))
        .tag("state", state)
        .description("Durable job-queue depth by lifecycle state")
        .register(registry);
  }
}
