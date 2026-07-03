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

import io.qnop.entity.Document;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for review aggregate roots (issue #244, ADR-0011). */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

  /** Documents owned by the given user. */
  List<Document> findByOwnerId(UUID ownerId);

  /**
   * The documents visible to a user for the reviews overview (issue #292): owned, joined as a
   * direct participant, or joined through membership in a participating team. {@code q} must be
   * passed pre-lowercased and {@code LIKE}-wrapped; {@code null} disables the title filter.
   */
  @Query(
      "SELECT d FROM Document d WHERE (:q IS NULL OR LOWER(d.title) LIKE :q)"
          + " AND (d.ownerId = :actor"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant p"
          + "   WHERE p.documentId = d.id AND p.userId = :actor)"
          + " OR EXISTS (SELECT 1 FROM ReviewParticipant pt, TeamMembership m"
          + "   WHERE pt.documentId = d.id AND pt.teamId = m.teamId AND m.userId = :actor))")
  Page<Document> findVisibleTo(@Param("actor") UUID actor, @Param("q") String q, Pageable pageable);
}
