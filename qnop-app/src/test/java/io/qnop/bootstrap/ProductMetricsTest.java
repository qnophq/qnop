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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.service.stats.ProductStats;
import io.qnop.service.stats.ProductStatsService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The product gauges: a fixed set of series over one shared, slowly refreshed reading (#962). */
class ProductMetricsTest {

  private final ProductStatsService stats = mock(ProductStatsService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SteppingClock clock = new SteppingClock();

  @BeforeEach
  void bind() {
    when(stats.current())
        .thenReturn(
            new ProductStats(
                Map.of("in_review", 4L, "finalized", 9L, ProductStats.OTHER, 2L),
                Map.of("open", 11L),
                Map.of(ProductStats.USER_ENABLED, 30L, ProductStats.USER_PENDING, 3L),
                Map.of("committed", 50L),
                Map.of("committed", 1_000_000L)));
    new ProductMetrics(stats, clock).bindTo(registry);
  }

  @Test
  @DisplayName("publishes the counts, with zero for a state that has no rows")
  void publishesTheCounts() {
    assertThat(gauge("qnop.reviews", "state", "in_review")).isEqualTo(4.0);
    assertThat(gauge("qnop.reviews", "state", "finalized")).isEqualTo(9.0);
    // Absent from the grouped read means none, not unknown.
    assertThat(gauge("qnop.reviews", "state", "draft")).isZero();
    assertThat(gauge("qnop.annotations", "status", "open")).isEqualTo(11.0);
    assertThat(gauge("qnop.annotations", "status", "dismissed")).isZero();
    assertThat(gauge("qnop.users", "status", "enabled")).isEqualTo(30.0);
    assertThat(gauge("qnop.users", "status", "pending")).isEqualTo(3.0);
    assertThat(gauge("qnop.storage.objects", "status", "committed")).isEqualTo(50.0);
    assertThat(gauge("qnop.storage.objects.size", "status", "committed")).isEqualTo(1_000_000.0);
    assertThat(gauge("qnop.storage.objects", "status", "pending")).isZero();
  }

  @Test
  @DisplayName("a workflow state an add-on introduced is counted as 'other', never by its name")
  void unknownStatesAreOneSeries() {
    assertThat(gauge("qnop.reviews", "state", ProductStats.OTHER)).isEqualTo(2.0);
    assertThat(registry.find("qnop.reviews").gauges()).hasSize(ProductStats.REVIEW_STATES.size());
  }

  @Test
  @DisplayName("every gauge of a scrape shares one reading, refreshed after its maximum age")
  void sharesOneReading() {
    registry.getMeters().forEach(meter -> meter.measure().forEach(m -> m.getValue()));
    verify(stats, times(1)).current();

    clock.advance(ProductMetrics.SNAPSHOT_MAX_AGE.plus(Duration.ofSeconds(1)));
    registry.getMeters().forEach(meter -> meter.measure().forEach(m -> m.getValue()));
    verify(stats, times(2)).current();
  }

  @Test
  @DisplayName("a failed reading shows as NaN instead of a stale or made-up number")
  void failedReadingIsNaN() {
    when(stats.current()).thenThrow(new IllegalStateException("database down"));
    clock.advance(ProductMetrics.SNAPSHOT_MAX_AGE.plus(Duration.ofSeconds(1)));

    assertThat(gauge("qnop.users", "status", "enabled")).isNaN();
  }

  private double gauge(String name, String tagKey, String tagValue) {
    return registry.get(name).tag(tagKey, tagValue).gauge().value();
  }
}
