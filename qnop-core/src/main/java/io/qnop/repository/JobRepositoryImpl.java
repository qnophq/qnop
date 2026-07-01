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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Native-SQL claim/reaper for the job queue (ADR-0033). Spring Data wires this in automatically as
 * the {@code Impl} fragment of {@link JobRepository}.
 *
 * <p>Claiming is two steps in one transaction: a {@code SELECT … FOR UPDATE SKIP LOCKED} locks the
 * due rows (a plain select returns their ids reliably, avoiding Hibernate's awkward handling of
 * {@code UPDATE … RETURNING}), then an {@code UPDATE} flips the already-locked rows to {@code
 * RUNNING}. Parallel pollers skip locked rows, so no job is claimed twice.
 */
public class JobRepositoryImpl implements JobRepositoryCustom {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<UUID> claimDuePending(Instant now, int limit) {
    List<?> raw =
        entityManager
            .createNativeQuery(
                """
                SELECT id FROM job
                WHERE status = 'PENDING' AND run_after <= :now
                ORDER BY run_after, created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .setParameter("now", now)
            .setParameter("limit", limit)
            .getResultList();

    List<UUID> ids = raw.stream().map(UUID.class::cast).toList();
    if (ids.isEmpty()) {
      return List.of();
    }

    entityManager
        .createNativeQuery(
            """
            UPDATE job SET status = 'RUNNING', attempts = attempts + 1, updated_at = :now
            WHERE id IN (:ids)
            """)
        .setParameter("now", now)
        .setParameter("ids", ids)
        .executeUpdate();

    return ids;
  }

  @Override
  public int reapStaleRunning(Instant staleBefore, Instant now) {
    return entityManager
        .createNativeQuery(
            """
            UPDATE job SET status = 'PENDING', run_after = :now, updated_at = :now
            WHERE status = 'RUNNING' AND updated_at < :staleBefore
            """)
        .setParameter("now", now)
        .setParameter("staleBefore", staleBefore)
        .executeUpdate();
  }
}
