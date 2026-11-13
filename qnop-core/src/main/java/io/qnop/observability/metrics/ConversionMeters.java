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
 * Meters of the out-of-process office converter (ADR-0010): how long a conversion waited for a
 * slot, how long it ran and how it ended, and how often one was refused for want of a slot.
 *
 * <p>A refusal is counted, not timed: it never ran, and a zero-length sample would pull every
 * duration figure towards zero exactly when the converter is saturated.
 */
@Component
public class ConversionMeters {

  public static final String CONVERSION = "qnop.office.conversion";
  public static final String SLOT_WAIT = "qnop.office.conversion.wait";
  public static final String REFUSED = "qnop.office.conversion.refused";

  /** How a conversion that ran ended. */
  public enum Outcome {
    SUCCESS,
    /** The document cannot be converted, however often it is tried: the upload is at fault. */
    REJECTED,
    /** The converter failed or ran out of time: the environment is at fault, a retry may work. */
    FAILURE
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
  private final Counter refused;

  public ConversionMeters(MeterRegistry registry) {
    this.registry = registry;
    this.slotWait =
        Timer.builder(SLOT_WAIT)
            .description("Time a conversion waited for a free converter slot")
            .serviceLevelObjectives(BUCKETS)
            .register(registry);
    this.refused =
        Counter.builder(REFUSED)
            .description("Conversions refused because no converter slot came free in time")
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

  /** A conversion was refused after waiting {@code waited} for a slot; the converter never ran. */
  public void refused(Duration waited) {
    slotWait.record(waited);
    refused.increment();
  }

  public void waitedForSlot(Duration waited) {
    slotWait.record(waited);
  }
}
