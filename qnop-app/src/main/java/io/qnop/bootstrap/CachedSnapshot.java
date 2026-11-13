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
 * <p>A failed load is remembered for the same time and rethrown to every reader, who reports {@code
 * NaN}. Without that, each gauge would retry on its own: with the database down, every one of them
 * would wait out the connection timeout in turn, and a single scrape would hold a request thread
 * for minutes.
 *
 * @param <T> the snapshot type
 */
final class CachedSnapshot<T> {

  private final Supplier<T> loader;
  private final Duration maxAge;
  private final Clock clock;

  private T value;
  private RuntimeException failure;
  private Instant loadedAt;

  CachedSnapshot(Supplier<T> loader, Duration maxAge, Clock clock) {
    this.loader = loader;
    this.maxAge = maxAge;
    this.clock = clock;
  }

  synchronized T get() {
    Instant now = clock.instant();
    if (loadedAt == null || !now.isBefore(loadedAt.plus(maxAge))) {
      load();
      // Stamped after the load: a slow failure must not have expired by the time it returns.
      loadedAt = clock.instant();
    }
    if (failure != null) {
      throw failure;
    }
    return value;
  }

  private void load() {
    try {
      value = loader.get();
      failure = null;
    } catch (RuntimeException e) {
      value = null;
      failure = e;
    }
  }
}
