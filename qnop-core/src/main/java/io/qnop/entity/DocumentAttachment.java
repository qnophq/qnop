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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * An image attached to a document's review discussion (issue #446). The bytes live in object
 * storage under a content-addressed key (ADR-0036); this row is the immutable domain reference —
 * scope (the document), uploader, sniffed content type and hash. Markdown comment bodies reference
 * the attachment by its serving URL; the row (and, via the registry, the object) dies with the
 * document. JPA runs {@code ddl-auto=none}; this mapping matches migration 0014 exactly.
 */
@Entity
@Table(name = "document_attachment")
public class DocumentAttachment {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "uploader_id", nullable = false, updatable = false)
  private UUID uploaderId;

  @Column(name = "file_name", nullable = false, updatable = false, length = 255)
  private String fileName;

  @Column(name = "content_type", nullable = false, updatable = false, length = 128)
  private String contentType;

  @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
  private String contentHash;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
  private String storageKey;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected DocumentAttachment() {
    // for JPA
  }

  public DocumentAttachment(
      UUID documentId,
      UUID uploaderId,
      String fileName,
      String contentType,
      String contentHash,
      long sizeBytes,
      String storageKey) {
    this.documentId = documentId;
    this.uploaderId = uploaderId;
    this.fileName = fileName;
    this.contentType = contentType;
    this.contentHash = contentHash;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getUploaderId() {
    return uploaderId;
  }

  public String getFileName() {
    return fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public String getContentHash() {
    return contentHash;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
