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

import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for the immutable version chain of a document (issue #244, ADR-0011). */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

  /** The version chain in order, oldest first. */
  List<DocumentVersion> findByDocumentIdOrderByVersionNumberAsc(UUID documentId);

  /** A specific numbered version of a document. */
  Optional<DocumentVersion> findByDocumentIdAndVersionNumber(UUID documentId, int versionNumber);

  /** Whether any version of the document is in the given extraction state (issue #323). */
  boolean existsByDocumentIdAndExtractionStatus(UUID documentId, ExtractionStatus extractionStatus);

  /** The latest (highest-numbered) version of a document, if any. */
  Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);

  /** Batched highest version numbers for the reviews overview (issue #292). */
  @Query(
      "SELECT new io.qnop.repository.DocumentMaxVersion(v.documentId, MAX(v.versionNumber))"
          + " FROM DocumentVersion v WHERE v.documentId IN :documentIds GROUP BY v.documentId")
  List<DocumentMaxVersion> findMaxVersionsByDocumentIds(
      @Param("documentIds") Collection<UUID> documentIds);
}
