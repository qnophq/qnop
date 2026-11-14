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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Measurements of committed work wait for the commit (ADR-0065). */
class AfterCommitTest {

  private final AtomicInteger measured = new AtomicInteger();

  @AfterEach
  void clear() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("outside a transaction the measurement is taken at once")
  void runsAtOnceWithoutATransaction() {
    AfterCommit.run(measured::incrementAndGet);

    assertThat(measured).hasValue(1);
  }

  @Test
  @DisplayName("inside a transaction nothing is measured until it commits")
  void waitsForTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    AfterCommit.run(measured::incrementAndGet);
    assertThat(measured).hasValue(0);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(measured).hasValue(1);
  }

  @Test
  @DisplayName("a transaction that rolls back measures nothing")
  void rollbackMeasuresNothing() {
    TransactionSynchronizationManager.initSynchronization();
    AfterCommit.run(measured::incrementAndGet);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    assertThat(measured).hasValue(0);
  }
}
