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

import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Retries an action that may lose an optimistic-locking race (issue #47, ADR not required — a small
 * local concern). The action MUST run in its own transaction per attempt, so the {@code @Version}
 * conflict surfaces as an {@link OptimisticLockingFailureException} on commit before the next
 * attempt re-reads the current state.
 */
final class OptimisticRetry {

  private OptimisticRetry() {}

  /**
   * Runs {@code action}, retrying up to {@code maxAttempts} times when it fails with an {@link
   * OptimisticLockingFailureException}. Re-throws the last such failure if every attempt loses the
   * race; any other exception propagates immediately, unwrapped.
   *
   * @param maxAttempts total attempts including the first (must be &ge; 1)
   * @param action the transactional unit of work to run
   */
  static void execute(int maxAttempts, Runnable action) {
    OptimisticLockingFailureException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        action.run();
        return;
      } catch (OptimisticLockingFailureException e) {
        last = e;
      }
    }
    throw last;
  }
}
