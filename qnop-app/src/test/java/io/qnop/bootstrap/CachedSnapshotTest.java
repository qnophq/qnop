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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The short-lived snapshot several gauges share (issue #962). */
class CachedSnapshotTest {

  private static final Duration MAX_AGE = Duration.ofSeconds(15);

  private final SteppingClock clock = new SteppingClock();
  private final AtomicInteger loads = new AtomicInteger();

  @Test
  @DisplayName("loads once however often it is read within its lifetime")
  void sharesOneLoadWithinTheLifetime() {
    CachedSnapshot<Integer> snapshot = new CachedSnapshot<>(loads::incrementAndGet, MAX_AGE, clock);

    assertThat(snapshot.get()).isEqualTo(1);
    clock.advance(MAX_AGE.minusMillis(1));
    assertThat(snapshot.get()).isEqualTo(1);

    assertThat(loads).hasValue(1);
  }

  @Test
  @DisplayName("loads again once the snapshot has reached its maximum age")
  void reloadsWhenExpired() {
    CachedSnapshot<Integer> snapshot = new CachedSnapshot<>(loads::incrementAndGet, MAX_AGE, clock);
    snapshot.get();

    clock.advance(MAX_AGE);

    assertThat(snapshot.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("does not cache a failed load: the next read tries again")
  void retriesAfterAFailedLoad() {
    CachedSnapshot<Integer> snapshot =
        new CachedSnapshot<>(
            () -> {
              if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
              }
              return loads.get();
            },
            MAX_AGE,
            clock);

    assertThatThrownBy(snapshot::get).isInstanceOf(IllegalStateException.class);

    assertThat(snapshot.get()).isEqualTo(2);
  }
}
