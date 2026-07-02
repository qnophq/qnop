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

import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.PlacementStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for per-version annotation placements (issue #244, ADR-0009). */
public interface AnnotationPlacementRepository extends JpaRepository<AnnotationPlacement, UUID> {

  /**
   * Placements in a given status across <em>all</em> versions of a document — the document-wide
   * FINALIZED gate (issue #246, ADR-0011): finalization requires zero {@code PENDING} placements
   * anywhere, i.e. re-anchoring has completed for every version.
   */
  @Query(
      "SELECT count(p) FROM AnnotationPlacement p, DocumentVersion v"
          + " WHERE p.documentVersionId = v.id AND v.documentId = :documentId"
          + " AND p.status = :status")
  long countByDocumentIdAndStatus(
      @Param("documentId") UUID documentId, @Param("status") PlacementStatus status);

  /** Every version placement of one annotation. */
  List<AnnotationPlacement> findByAnnotationId(UUID annotationId);

  /** All annotation placements on one document version. */
  List<AnnotationPlacement> findByDocumentVersionId(UUID documentVersionId);

  /** The single placement of an annotation on a specific version (unique pair). */
  Optional<AnnotationPlacement> findByAnnotationIdAndDocumentVersionId(
      UUID annotationId, UUID documentVersionId);

  /** Placements on a version in a given re-anchoring state — e.g. PENDING for the re-anchor job. */
  List<AnnotationPlacement> findByDocumentVersionIdAndStatus(
      UUID documentVersionId, PlacementStatus status);
}
