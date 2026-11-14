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
 * Meters of the object store (ADR-0005): how long each call to the {@code StorageProvider} took and
 * how it ended, how many bytes went each way, and how many abandoned uploads the reaper removed.
 *
 * <p>Recorded around the provider, not inside one, so an add-on's provider is measured exactly like
 * the built-in S3 adapter.
 */
@Component
public class StorageMeters {

  public static final String OPERATION = "qnop.storage.operation";
  public static final String TRANSFERRED = "qnop.storage.transferred";
  public static final String ORPHANS_REAPED = "qnop.storage.orphans.reaped";

  /** The calls of the {@code StorageProvider} contract. */
  public enum Operation {
    PUT,
    GET,
    EXISTS,
    DELETE,
    LIST
  }

  /** Whether the provider answered. An absent object is an answer, so it is a success. */
  public enum Outcome {
    SUCCESS,
    ERROR
  }

  /** Which way object bytes travelled, seen from qnop. */
  public enum Direction {
    UPLOAD,
    DOWNLOAD
  }

  private static final Duration[] BUCKETS = {
    Duration.ofMillis(10),
    Duration.ofMillis(25),
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofMillis(2500),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(30)
  };

  private final MeterRegistry registry;
  private final Counter orphansReaped;

  public StorageMeters(MeterRegistry registry) {
    this.registry = registry;
    this.orphansReaped =
        Counter.builder(ORPHANS_REAPED)
            .description("Uploads that were never committed and were removed by the orphan reaper")
            .register(registry);
  }

  /** One provider call took {@code duration}. */
  public void called(Operation operation, Outcome outcome, Duration duration) {
    Timer.builder(OPERATION)
        .description("Duration of one call to the object store")
        .tag("operation", MeterTags.of(operation))
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(BUCKETS)
        .register(registry)
        .record(duration);
  }

  /**
   * {@code bytes} were handed to the store, or offered by it. A download counts the object's size
   * when it is opened: the caller streams it, and a reader that stops early is not seen here.
   */
  public void transferred(Direction direction, long bytes) {
    Counter.builder(TRANSFERRED)
        .description("Object bytes sent to and opened from the object store")
        .baseUnit("bytes")
        .tag("direction", MeterTags.of(direction))
        .register(registry)
        .increment(bytes);
  }

  /** The reaper removed {@code count} abandoned uploads. */
  public void orphansReaped(int count) {
    orphansReaped.increment(count);
  }
}
