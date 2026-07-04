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

import io.qnop.entity.ReviewVisit;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the per-user visit stamps (issue #307). */
public interface ReviewVisitRepository extends JpaRepository<ReviewVisit, UUID> {

  Optional<ReviewVisit> findByDocumentIdAndUserId(UUID documentId, UUID userId);

  /**
   * Race-free stamp: a concurrent first visit (second tab) simply updates instead of violating the
   * unique pair — no exception path, both sessions keep a usable baseline.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO review_visit (id, document_id, user_id, last_seen_at)"
              + " VALUES (:id, :documentId, :userId, :now)"
              + " ON CONFLICT (document_id, user_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at",
      nativeQuery = true)
  void upsert(
      @Param("id") UUID id,
      @Param("documentId") UUID documentId,
      @Param("userId") UUID userId,
      @Param("now") Instant now);
}
