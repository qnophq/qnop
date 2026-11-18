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
 * Publishes the state of each scheduled maintenance job (issue #962): {@code
 * qnop.scheduler.last.run.timestamp{job}} in epoch seconds, {@code
 * qnop.scheduler.last.run.failed{job}} as 1 or 0, and {@code qnop.scheduler.job.enabled{job}} as 1
 * or 0.
 *
 * <p>A nightly job that silently stops is invisible to a duration timer, which simply records
 * nothing. Alert on {@code failed == 1}, and on a stale timestamp <em>only where the job is
 * enabled</em>: {@code (time() - timestamp > interval) and enabled == 1}. A job an operator
 * switched off stops running on purpose, and without the third gauge it would look dead.
 *
 * <p>A job that has never run reports {@code NaN} for the first two, and a comparison with {@code
 * NaN} is never true: "enabled but never ran" needs its own rule ({@code enabled == 1 unless
 * timestamp == timestamp}).
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
          view ->
              view.lastRunAt() == null
                  ? Double.NaN
                  : view.lastRunAt().toEpochMilli() / MILLIS_PER_SECOND);
      gauge(
          registry,
          "qnop.scheduler.last.run.failed",
          null,
          "1 if the job's last run failed, else 0",
          jobId,
          view ->
              view.lastRunAt() == null
                  ? Double.NaN
                  : RunOutcome.FAILURE.name().equals(view.lastOutcome()) ? 1.0 : 0.0);
      gauge(
          registry,
          "qnop.scheduler.job.enabled",
          null,
          "1 if the job runs on its schedule, 0 if an operator switched it off",
          jobId,
          view -> view.enabled() ? 1.0 : 0.0);
    }
  }

  private void gauge(
      MeterRegistry registry,
      String name,
      String baseUnit,
      String description,
      String jobId,
      ToDoubleFunction<SchedulerJobView> value) {
    Gauge.builder(
            name,
            snapshot,
            s -> {
              SchedulerJobView view = s.get().get(jobId);
              return view == null ? Double.NaN : value.applyAsDouble(view);
            })
        .tag("job", jobId)
        .baseUnit(baseUnit)
        .description(description)
        .register(registry);
  }
}
