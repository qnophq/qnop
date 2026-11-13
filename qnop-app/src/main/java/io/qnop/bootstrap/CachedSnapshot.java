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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Holds a computed value for a short while, so several gauges can read one snapshot instead of each
 * querying for it (issue #962).
 *
 * <p>Gauges are evaluated at scrape time, one by one. A gauge that queries the database therefore
 * costs a query per gauge per scrape, and a snapshot of four counts read by four gauges costs
 * sixteen. Reading through this class costs one load per {@code maxAge}, however many gauges share
 * it and however often they are scraped.
 *
 * <p>A load that throws is not cached: the exception reaches the gauge, which reports {@code NaN},
 * and the next read tries again.
 *
 * @param <T> the snapshot type
 */
final class CachedSnapshot<T> {

  private final Supplier<T> loader;
  private final Duration maxAge;
  private final Clock clock;

  private T value;
  private Instant loadedAt;

  CachedSnapshot(Supplier<T> loader, Duration maxAge, Clock clock) {
    this.loader = loader;
    this.maxAge = maxAge;
    this.clock = clock;
  }

  synchronized T get() {
    Instant now = clock.instant();
    if (loadedAt == null || !now.isBefore(loadedAt.plus(maxAge))) {
      value = loader.get();
      loadedAt = now;
    }
    return value;
  }
}
