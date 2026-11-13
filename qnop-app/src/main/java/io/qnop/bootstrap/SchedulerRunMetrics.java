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
import io.qnop.service.scheduler.RunOutcome;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.scheduler.SchedulerService.SchedulerJobView;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Publishes when each scheduled maintenance job last ran and whether that run failed (issue #962):
 * {@code qnop.scheduler.last.run.timestamp{job}} in epoch seconds and {@code
 * qnop.scheduler.last.run.failed{job}} as 1 or 0. Both are {@code NaN} for a job that has never
 * run.
 *
 * <p>A nightly job that silently stops is invisible to a duration timer, which simply records
 * nothing. Alert on {@code time() - timestamp} exceeding the job's interval, or on {@code failed ==
 * 1}.
 *
 * <p>The values come from the scheduler's own table, so they are the same on every instance
 * (aggregate with {@code max}) and survive a restart. The gauges share one snapshot of that table,
 * at most {@link #SNAPSHOT_MAX_AGE} old.
 */
@Component
class SchedulerRunMetrics implements MeterBinder {

  static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(15);

  private static final double MILLIS_PER_SECOND = 1000.0;

  private final CachedSnapshot<Map<String, SchedulerJobView>> snapshot;

  @Autowired
  SchedulerRunMetrics(SchedulerService scheduler) {
    this(scheduler, Clock.systemUTC());
  }

  SchedulerRunMetrics(SchedulerService scheduler, Clock clock) {
    this.snapshot =
        new CachedSnapshot<>(
            () ->
                scheduler.list().stream()
                    .collect(Collectors.toMap(SchedulerJobView::jobId, Function.identity())),
            SNAPSHOT_MAX_AGE,
            clock);
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String jobId : SchedulerJobCatalog.jobIds()) {
      gauge(
          registry,
          "qnop.scheduler.last.run.timestamp",
          "seconds",
          "When the job last ran, as a Unix timestamp",
          jobId,
          view -> view.lastRunAt().toEpochMilli() / MILLIS_PER_SECOND);
      gauge(
          registry,
          "qnop.scheduler.last.run.failed",
          null,
          "1 if the job's last run failed, else 0",
          jobId,
          view -> RunOutcome.FAILURE.name().equals(view.lastOutcome()) ? 1.0 : 0.0);
    }
  }

  private void gauge(
      MeterRegistry registry,
      String name,
      String baseUnit,
      String description,
      String jobId,
      ToDoubleFunction<SchedulerJobView> value) {
    Gauge.builder(name, snapshot, s -> valueOf(s.get().get(jobId), value))
        .tag("job", jobId)
        .baseUnit(baseUnit)
        .description(description)
        .register(registry);
  }

  private static double valueOf(SchedulerJobView view, ToDoubleFunction<SchedulerJobView> value) {
    return view == null || view.lastRunAt() == null ? Double.NaN : value.applyAsDouble(view);
  }
}
