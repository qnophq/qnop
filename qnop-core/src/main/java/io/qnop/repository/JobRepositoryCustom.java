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
package io.qnop.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Native-SQL job-queue operations that Spring Data derivation can't express (ADR-0033). */
public interface JobRepositoryCustom {

  /**
   * Atomically claims up to {@code limit} due {@code PENDING} jobs: locks them with {@code FOR
   * UPDATE SKIP LOCKED} (so parallel pollers never grab the same row), flips them to {@code
   * RUNNING} and bumps {@code attempts}, and returns their ids. Must run inside a transaction.
   */
  List<UUID> claimDuePending(Instant now, int limit);

  /**
   * Reclaims jobs left {@code RUNNING} by a crash — those not touched since {@code staleBefore} —
   * by resetting them to {@code PENDING}, due immediately. Returns how many were reclaimed.
   */
  int reapStaleRunning(Instant staleBefore, Instant now);
}
