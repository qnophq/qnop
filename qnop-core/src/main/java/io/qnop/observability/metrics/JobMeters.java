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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Meters of the durable job queue (ADR-0033): how long a job waited, how long it ran and how it
 * ended. The {@code type} tag is the job type of a registered handler, a fixed set.
 */
@Component
public class JobMeters {

  public static final String EXECUTION = "qnop.job.execution";
  public static final String QUEUE_WAIT = "qnop.job.queue.wait";
  public static final String REAPED = "qnop.jobs.reaped";

  /** How one attempt at a job ended. */
  public enum Outcome {
    SUCCESS,
    /** The attempt failed and the queue will try again. */
    RETRY,
    /** The attempt failed and no attempts remain. */
    FAILED
  }

  // Extraction of a large PDF and a DOCX conversion run for seconds to minutes.
  private static final Duration[] EXECUTION_BUCKETS = {
    Duration.ofMillis(100),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(5),
    Duration.ofSeconds(15),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2),
    Duration.ofMinutes(5)
  };

  // The poller ticks every few seconds; anything beyond a minute means a backlog.
  private static final Duration[] WAIT_BUCKETS = {
    Duration.ofSeconds(1),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(5),
    Duration.ofMinutes(15),
    Duration.ofHours(1)
  };

  private final MeterRegistry registry;
  private final Counter reaped;

  public JobMeters(MeterRegistry registry) {
    this.registry = registry;
    this.reaped =
        Counter.builder(REAPED)
            .description("Jobs stranded RUNNING by a dead worker and returned to the queue")
            .register(registry);
  }

  /**
   * One attempt at a job finished, successfully or not. {@code type} must be the type of a
   * registered handler; the queue only gets here for one, which keeps the tag a closed set.
   *
   * <p>A conversion refused for want of a slot surfaces here as {@code retry}: the job did not
   * fail, it comes back under the queue's backoff.
   */
  public void executed(String type, Outcome outcome, Duration duration) {
    Timer.builder(EXECUTION)
        .description("Duration of one attempt at a queued job")
        .tag("type", type)
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(EXECUTION_BUCKETS)
        .register(registry)
        .record(duration);
  }

  /** A job was picked up {@code wait} after it became due. */
  public void waited(String type, Duration wait) {
    Timer.builder(QUEUE_WAIT)
        .description("Time between a job becoming due and a worker starting it")
        .tag("type", type)
        .serviceLevelObjectives(WAIT_BUCKETS)
        .register(registry)
        .record(wait.isNegative() ? Duration.ZERO : wait);
  }

  public void reaped(int jobs) {
    reaped.increment(jobs);
  }
}
