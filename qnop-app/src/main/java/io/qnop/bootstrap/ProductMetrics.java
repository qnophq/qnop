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
import io.qnop.service.stats.ProductStats;
import io.qnop.service.stats.ProductStatsService;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Publishes how much this instance holds (issue #962): {@code qnop.reviews{state}}, {@code
 * qnop.annotations{status}}, {@code qnop.users{status}}, {@code qnop.storage.objects{status}} and
 * {@code qnop.storage.objects.size{status}}.
 *
 * <p>Every tag value comes from a list fixed in {@link ProductStats}, so the number of series is
 * fixed when the application is built (ADR-0065). A review in a workflow state an add-on introduced
 * is counted under {@code state="other"}, not under the add-on's name.
 *
 * <p>All gauges share one snapshot that is at most {@link #SNAPSHOT_MAX_AGE} old. These figures
 * move slowly and counting them reads whole tables, so the snapshot is kept longer than the queue's
 * and a scrape can never cost more than four grouped reads a minute. They come from the database
 * and are the same on every instance: aggregate them with {@code max}, not {@code sum}.
 */
@Component
class ProductMetrics implements MeterBinder {

  static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(60);

  private final CachedSnapshot<ProductStats> snapshot;

  @Autowired
  ProductMetrics(ProductStatsService stats) {
    this(stats, Clock.systemUTC());
  }

  ProductMetrics(ProductStatsService stats, Clock clock) {
    this.snapshot = new CachedSnapshot<>(stats::current, SNAPSHOT_MAX_AGE, clock);
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String state : ProductStats.REVIEW_STATES) {
      gauge("qnop.reviews", "state", state, ProductStats::reviews)
          .description("Reviews by workflow state")
          .register(registry);
    }
    for (String status : ProductStats.ANNOTATION_STATUSES) {
      gauge("qnop.annotations", "status", status, ProductStats::annotations)
          .description("Annotations by status")
          .register(registry);
    }
    for (String state : ProductStats.USER_STATES) {
      gauge("qnop.users", "status", state, ProductStats::users)
          .description("Accounts by sign-in state")
          .register(registry);
    }
    for (String status : ProductStats.STORAGE_STATUSES) {
      gauge("qnop.storage.objects", "status", status, ProductStats::storageObjects)
          .description("Objects in the storage registry by lifecycle state")
          .register(registry);
      gauge("qnop.storage.objects.size", "status", status, ProductStats::storageBytes)
          .baseUnit("bytes")
          .description("Bytes held by the objects in the storage registry, by lifecycle state")
          .register(registry);
    }
  }

  /** One series for {@code key}; a state with no rows is absent from the counts, which is zero. */
  private Gauge.Builder<CachedSnapshot<ProductStats>> gauge(
      String name, String tagKey, String key, Function<ProductStats, Map<String, Long>> counts) {
    return Gauge.builder(name, snapshot, s -> counts.apply(s.get()).getOrDefault(key, 0L))
        .tag(tagKey, key);
  }
}
