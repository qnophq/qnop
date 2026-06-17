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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/** Unit tests for the optimistic-locking retry policy (issue #47). */
class OptimisticRetryTest {

  @Test
  void runsOnceWhenTheActionSucceeds() {
    AtomicInteger calls = new AtomicInteger();

    OptimisticRetry.execute(3, calls::incrementAndGet);

    assertThat(calls).hasValue(1);
  }

  @Test
  void retriesUntilTheActionStopsConflicting() {
    AtomicInteger calls = new AtomicInteger();

    OptimisticRetry.execute(
        3,
        () -> {
          if (calls.incrementAndGet() < 3) {
            throw new OptimisticLockingFailureException("conflict");
          }
        });

    assertThat(calls).hasValue(3);
  }

  @Test
  void rethrowsTheLastConflictWhenAllAttemptsLose() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                OptimisticRetry.execute(
                    3,
                    () -> {
                      calls.incrementAndGet();
                      throw new OptimisticLockingFailureException("always conflicts");
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(calls).hasValue(3);
  }

  @Test
  void doesNotRetryUnrelatedExceptions() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                OptimisticRetry.execute(
                    3,
                    () -> {
                      calls.incrementAndGet();
                      throw new IllegalStateException("not a lock conflict");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
  }
}
