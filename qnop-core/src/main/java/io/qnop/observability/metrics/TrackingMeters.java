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
 * Meters of the usage-tracking proxy (issue #666): how long forwarding one measurement to the
 * analytics backend took and how it ended.
 *
 * <p>Measurement fails silently for the user by design; this is where the operator sees it fail.
 * The backend is tagged by its kind, never by its host (ADR-0065).
 */
@Component
public class TrackingMeters {

  public static final String FORWARD = "qnop.tracking.forward";

  /** How a forward ended. */
  public enum Outcome {
    SUCCESS,
    /** The backend answered, but not with a 2xx. */
    REJECTED,
    /** The backend could not be reached, or did not answer in time. */
    ERROR
  }

  private static final Duration[] BUCKETS = {
    Duration.ofMillis(25),
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(3)
  };

  private final MeterRegistry registry;

  public TrackingMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * One measurement was forwarded in {@code duration}.
   *
   * @param provider the configured backend kind
   */
  public void forwarded(Enum<?> provider, Outcome outcome, Duration duration) {
    Timer.builder(FORWARD)
        .description("Duration of forwarding one measurement to the analytics backend")
        .tag("provider", MeterTags.of(provider))
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(BUCKETS)
        .register(registry)
        .record(duration);
  }
}
