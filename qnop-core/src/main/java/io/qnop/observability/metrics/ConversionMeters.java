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
 * Meters of the out-of-process office converter (ADR-0010): how long a conversion waited for a
 * slot, how long it ran and how it ended.
 */
@Component
public class ConversionMeters {

  public static final String CONVERSION = "qnop.office.conversion";
  public static final String SLOT_WAIT = "qnop.office.conversion.wait";

  /** How a conversion request ended. */
  public enum Outcome {
    SUCCESS,
    /** The converter ran and failed, or ran out of time. */
    FAILURE,
    /** No slot came free within the wait limit; the converter never ran. */
    BUSY
  }

  private static final Duration[] BUCKETS = {
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(2),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2)
  };

  private final MeterRegistry registry;
  private final Timer slotWait;

  public ConversionMeters(MeterRegistry registry) {
    this.registry = registry;
    this.slotWait =
        Timer.builder(SLOT_WAIT)
            .description("Time a conversion waited for a free converter slot")
            .serviceLevelObjectives(BUCKETS)
            .register(registry);
  }

  /** A conversion ran for {@code duration}, not counting the wait for a slot. */
  public void converted(Outcome outcome, Duration duration) {
    Timer.builder(CONVERSION)
        .description("Duration of one out-of-process document conversion")
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(BUCKETS)
        .register(registry)
        .record(duration);
  }

  /** A conversion was refused after waiting {@code waited} for a slot. */
  public void refused(Duration waited) {
    slotWait.record(waited);
    converted(Outcome.BUSY, Duration.ZERO);
  }

  public void waitedForSlot(Duration waited) {
    slotWait.record(waited);
  }
}
