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

import io.qnop.entity.ReviewParticipant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the reviewer set of a document (issue #244, ADR-0011). */
public interface ReviewParticipantRepository extends JpaRepository<ReviewParticipant, UUID> {

  /** The reviewers (user and team principals) on a document. */
  List<ReviewParticipant> findByDocumentId(UUID documentId);

  /** The reviews a given user is a direct participant in. */
  List<ReviewParticipant> findByUserId(UUID userId);

  /** The reviews a given team is a participant in. */
  List<ReviewParticipant> findByTeamId(UUID teamId);

  boolean existsByDocumentIdAndUserId(UUID documentId, UUID userId);

  boolean existsByDocumentIdAndTeamId(UUID documentId, UUID teamId);

  String VIEW_SELECT =
      "SELECT new io.qnop.repository.ParticipantProjection(p.id, p.documentId, p.userId,"
          + " p.teamId, COALESCE(u.displayName, t.name), p.createdAt)"
          + " FROM ReviewParticipant p"
          + " LEFT JOIN User u ON u.id = p.userId"
          + " LEFT JOIN Team t ON t.id = p.teamId";

  /** The participants of one document with display names, oldest first (issue #292). */
  @Query(VIEW_SELECT + " WHERE p.documentId = :documentId ORDER BY p.createdAt")
  List<ParticipantProjection> findViewsByDocumentId(@Param("documentId") UUID documentId);

  /** Batched participant views for the reviews overview — avoids N+1 (issue #292). */
  @Query(VIEW_SELECT + " WHERE p.documentId IN :documentIds ORDER BY p.createdAt")
  List<ParticipantProjection> findViewsByDocumentIds(
      @Param("documentIds") Collection<UUID> documentIds);
}
