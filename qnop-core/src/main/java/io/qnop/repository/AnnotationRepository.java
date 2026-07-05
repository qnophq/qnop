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

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for annotations raised against a document (issue #244, ADR-0011). */
public interface AnnotationRepository extends JpaRepository<Annotation, UUID> {

  /** All annotations on a document. */
  List<Annotation> findByDocumentId(UUID documentId);

  /** The distinct users who authored an annotation on a document (issue #413 pseudonyms). */
  @Query("SELECT DISTINCT a.authorId FROM Annotation a WHERE a.documentId = :documentId")
  List<UUID> findDistinctAuthorIdsByDocumentId(@Param("documentId") UUID documentId);

  /** Annotations on a document in a given status. */
  List<Annotation> findByDocumentIdAndStatus(UUID documentId, AnnotationStatus status);

  /**
   * How many annotations on a document are in a given status — e.g. OPEN, for the finalize gate.
   */
  long countByDocumentIdAndStatus(UUID documentId, AnnotationStatus status);

  /** Batched total/open annotation counts for the reviews overview (issue #292). */
  @Query(
      "SELECT new io.qnop.repository.DocumentAnnotationCounts(a.documentId, COUNT(a),"
          + " SUM(CASE WHEN a.status = io.qnop.entity.AnnotationStatus.OPEN THEN 1 ELSE 0 END))"
          + " FROM Annotation a WHERE a.documentId IN :documentIds GROUP BY a.documentId")
  List<DocumentAnnotationCounts> countByDocumentIds(
      @Param("documentIds") Collection<UUID> documentIds);

  /**
   * Batched counts scoped to what {@code actor} may see (issue #413): under a PRIVATE thread policy
   * only the actor's own annotations (and, if they own the review, all of them) count — so the
   * reviews overview's totals follow the caller's visibility rather than over-counting hidden
   * threads. A document where the actor sees nothing is simply absent (the caller defaults to 0).
   */
  @Query(
      "SELECT new io.qnop.repository.DocumentAnnotationCounts(a.documentId, COUNT(a),"
          + " SUM(CASE WHEN a.status = io.qnop.entity.AnnotationStatus.OPEN THEN 1 ELSE 0 END))"
          + " FROM Annotation a, Document d"
          + " WHERE a.documentId = d.id AND a.documentId IN :documentIds"
          + " AND (d.threadParticipation <> io.qnop.entity.ThreadParticipation.PRIVATE"
          + " OR d.ownerId = :actor OR a.authorId = :actor)"
          + " GROUP BY a.documentId")
  List<DocumentAnnotationCounts> countVisibleByDocumentIds(
      @Param("documentIds") Collection<UUID> documentIds, @Param("actor") UUID actor);
}
