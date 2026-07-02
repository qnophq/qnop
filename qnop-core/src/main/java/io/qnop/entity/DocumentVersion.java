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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One immutable version of a {@link Document} (issue #244, ADR-0011/0032). Uploading a new revision
 * appends a version with the next {@code versionNumber}; existing versions never change. The binary
 * itself lives in object storage (StorageProvider, #243) under {@code storageKey}; {@code
 * contentHash} lets ingest deduplicate and detect re-uploads.
 *
 * <p>{@code renderedDocument} is the normalized, coordinate-bearing representation the anchoring
 * and diff pipelines consume (ADR-0032). It is populated <em>after</em> insert by the async
 * extraction job (#245) via {@link #attachRenderedDocument(String)} — hence nullable and the only
 * mutable field; everything else is write-once. {@code @Version} guards the concurrent attach.
 */
@Entity
@Table(name = "document_version")
public class DocumentVersion {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  /** 1-based, sequential per document; unique with {@code documentId} (enforced in Liquibase). */
  @Column(name = "version_number", nullable = false, updatable = false)
  private int versionNumber;

  @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
  private String storageKey;

  @Column(name = "content_hash", nullable = false, length = 128, updatable = false)
  private String contentHash;

  @Column(name = "content_type", nullable = false, length = 128, updatable = false)
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  /** Normalized rendering (ADR-0032); null until the extraction job (#245) attaches it. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rendered_document")
  private String renderedDocument;

  /** Extraction lifecycle (issue #245): PENDING until the async job flips it to READY/FAILED. */
  @Enumerated(EnumType.STRING)
  @Column(name = "extraction_status", nullable = false, length = 16)
  private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected DocumentVersion() {
    // for JPA
  }

  public DocumentVersion(
      UUID documentId,
      int versionNumber,
      String storageKey,
      String contentHash,
      String contentType,
      long sizeBytes,
      UUID createdBy) {
    this.documentId = documentId;
    this.versionNumber = versionNumber;
    this.storageKey = storageKey;
    this.contentHash = contentHash;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.createdBy = createdBy;
  }

  /**
   * Attaches the normalized rendering produced by the extraction job (#245); jsonb payload. Marks
   * the extraction {@link ExtractionStatus#READY}.
   */
  public void attachRenderedDocument(String renderedDocumentJson) {
    this.renderedDocument = renderedDocumentJson;
    this.extractionStatus = ExtractionStatus.READY;
  }

  /** Marks the extraction permanently failed (unprocessable content, issue #245). */
  public void markExtractionFailed() {
    this.renderedDocument = null;
    this.extractionStatus = ExtractionStatus.FAILED;
  }

  public ExtractionStatus getExtractionStatus() {
    return extractionStatus;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public int getVersionNumber() {
    return versionNumber;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getRenderedDocument() {
    return renderedDocument;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocumentVersion other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
