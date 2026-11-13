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
package io.qnop.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Meters of the scheduled maintenance jobs (ADR-0045): how long a run took and how it ended. The
 * {@code job} tag is an id from the scheduler catalogue, a fixed set.
 *
 * <p>Recorded on the instance that ran the job, so across instances the series add up. When a job
 * last ran is a fact about the database, published separately as a gauge.
 */
@Component
public class SchedulerMeters {

  public static final String RUN = "qnop.scheduler.run";

  /** How a run ended. A run skipped because the job is disabled is not a run and is not timed. */
  public enum Outcome {
    SUCCESS,
    FAILURE
  }

  /** What started the run. */
  public enum Trigger {
    SCHEDULED,
    MANUAL
  }

  private static final Duration[] BUCKETS = {
    Duration.ofMillis(100),
    Duration.ofSeconds(1),
    Duration.ofSeconds(5),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(5),
    Duration.ofMinutes(15),
    Duration.ofMinutes(30)
  };

  private final MeterRegistry registry;

  public SchedulerMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  public void ran(String jobId, Outcome outcome, Trigger trigger, Duration duration) {
    Timer.builder(RUN)
        .description("Duration of one run of a scheduled maintenance job")
        .tag("job", jobId)
        .tag("outcome", MeterTags.of(outcome))
        .tag("trigger", MeterTags.of(trigger))
        .serviceLevelObjectives(BUCKETS)
        .register(registry)
        .record(duration);
  }
}
