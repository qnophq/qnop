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

  /**
   * Inserts an assignment unless it conflicts with an existing one — on the (document, principal)
   * pair OR the (document, ordinal) pair. A concurrent allocator that already claimed either wins
   * and this call is a no-op (no exception that would abort the caller's transaction); the caller
   * re-reads and retries for anything still unassigned.
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
