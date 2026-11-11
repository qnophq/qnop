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

import io.qnop.entity.ReviewPseudonym;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for persisted anonymous-review pseudonym ordinals (issue #914). */
public interface ReviewPseudonymRepository extends JpaRepository<ReviewPseudonym, UUID> {

  List<ReviewPseudonym> findByDocumentId(UUID documentId);

  /** Removes a deleted review's rows (there is no FK to cascade them — see migration 0038). */
  @Modifying
  @Query(value = "DELETE FROM review_pseudonym WHERE document_id = :documentId", nativeQuery = true)
  int deleteByDocumentId(@Param("documentId") UUID documentId);

  /**
   * Serializes pseudonym allocation per review for the rest of the current transaction (issue
   * #914). Taken only when something needs allocating, inside the short allocation transaction.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
      nativeQuery = true)
  int lockForAllocation(@Param("key") String key);

  /**
   * Inserts an assignment unless it conflicts with an existing one. Callers hold {@link
   * #lockForAllocation}, so a conflict is not expected; the clause only keeps a stray duplicate
   * from failing the allocation.
   *
   * @return 1 when inserted, 0 when skipped
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO review_pseudonym (id, document_id, principal_id, ordinal)"
              + " VALUES (:id, :documentId, :principalId, :ordinal)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("documentId") UUID documentId,
      @Param("principalId") UUID principalId,
      @Param("ordinal") int ordinal);
}
