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

  /** Batched latest-version MIME types for the overview's typed document icon (issue #509). */
  @Query(
      "SELECT new io.qnop.repository.DocumentLatestContentType(v.documentId, v.contentType)"
          + " FROM DocumentVersion v WHERE v.documentId IN :documentIds"
          + " AND v.versionNumber = (SELECT MAX(v2.versionNumber) FROM DocumentVersion v2"
          + "   WHERE v2.documentId = v.documentId)")
  List<DocumentLatestContentType> findLatestContentTypesByDocumentIds(
      @Param("documentIds") Collection<UUID> documentIds);

  /**
   * Every version's storage key, for the storage-consistency scan's referenced set (issue #523).
   *
   * <p>A converted version references two objects, and its rendition is just as referenced as its
   * upload (issue #343) — hence {@link #findAllRenditionStorageKeys()}. Left as two queries rather
   * than one union so each stays a plain projection.
   */
  @Query("SELECT v.storageKey FROM DocumentVersion v")
  List<String> findAllStorageKeys();

  /** Every converted version's rendition key; the other half of the referenced set (issue #343). */
  @Query(
      "SELECT v.renditionStorageKey FROM DocumentVersion v WHERE v.renditionStorageKey IS NOT NULL")
  List<String> findAllRenditionStorageKeys();

  /**
   * The storage keys one document's versions reference — collected before a purge deletes the
   * aggregate, so the purge knows which keys to re-check for other referrers (issue #577). Includes
   * the converted PDFs (issue #343), which nothing else would ever reclaim.
   */
  @Query(
      "SELECT v.storageKey FROM DocumentVersion v WHERE v.documentId = :documentId"
          + " UNION SELECT v.renditionStorageKey FROM DocumentVersion v"
          + " WHERE v.documentId = :documentId AND v.renditionStorageKey IS NOT NULL")
  List<String> findStorageKeysByDocumentId(@Param("documentId") UUID documentId);

  /** Maps missing storage keys back to their document + version, to explain a data-loss finding. */
  @Query(
      "SELECT new io.qnop.repository.VersionStorageRef(v.storageKey, v.documentId, v.versionNumber)"
          + " FROM DocumentVersion v WHERE v.storageKey IN :keys")
  List<VersionStorageRef> findVersionRefsByStorageKeyIn(@Param("keys") Collection<String> keys);

  /**
   * The same, for keys referenced as a rendition (issue #343).
   *
   * <p>Separate from {@link #findVersionRefsByStorageKeyIn} because the projection has to name the
   * key that matched: a purge asking "is this key still referenced?" about a rendition would
   * otherwise be told about the upload instead, and delete a file that is still in use.
   */
  @Query(
      "SELECT new io.qnop.repository.VersionStorageRef("
          + "v.renditionStorageKey, v.documentId, v.versionNumber)"
          + " FROM DocumentVersion v WHERE v.renditionStorageKey IN :keys")
  List<VersionStorageRef> findVersionRefsByRenditionKeyIn(@Param("keys") Collection<String> keys);
}
