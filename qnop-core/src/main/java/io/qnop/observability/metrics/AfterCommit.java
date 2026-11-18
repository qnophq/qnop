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

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a measurement until the surrounding transaction has committed (ADR-0065).
 *
 * <p>A counter that describes committed work must not move when the work rolls back: the job queue
 * retries a rolled-back attempt, and a concurrent request that loses a uniqueness race is retried
 * by its caller, so counting straight away would count the same thing twice. Outside a transaction
 * there is nothing to wait for and the measurement is taken at once.
 */
public final class AfterCommit {

  private AfterCommit() {}

  public static void run(Runnable measurement) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      measurement.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            measurement.run();
          }
        });
  }
}
