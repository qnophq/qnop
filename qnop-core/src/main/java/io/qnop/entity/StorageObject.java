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
import org.hibernate.annotations.UuidGenerator;

/**
 * Staging registry row for an object in storage (issue #243, ADR-0005/0036). Every upload records
 * one row: {@code PENDING} until the domain row that references its {@code objectKey} is committed,
 * then {@code COMMITTED}. The orphan reaper deletes {@code PENDING} rows (and their objects) older
 * than the grace period, closing the upload-then-commit window.
 *
 * <p>Keys are content-addressed (sha-256), so {@code objectKey} is unique and identical content
 * deduplicates to one row/object. JPA runs {@code ddl-auto=none}; this mapping matches migration
 * 0012 exactly.
 */
@Entity
@Table(name = "storage_object")
public class StorageObject {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "object_key", nullable = false, updatable = false, length = 512)
  private String objectKey;

  @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
  private String contentHash;

  @Column(name = "content_type", nullable = false, updatable = false, length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private StorageObjectStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "committed_at")
  private Instant committedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected StorageObject() {
    // for JPA
  }

  private StorageObject(String objectKey, String contentHash, String contentType, long sizeBytes) {
    this.objectKey = objectKey;
    this.contentHash = contentHash;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.status = StorageObjectStatus.PENDING;
  }

  /** A freshly uploaded, not-yet-committed object. */
  public static StorageObject pending(
      String objectKey, String contentHash, String contentType, long sizeBytes) {
    return new StorageObject(objectKey, contentHash, contentType, sizeBytes);
  }

  /** Marks this object committed (referenced by a durable domain row); idempotent. */
  public void markCommitted(Instant at) {
    this.status = StorageObjectStatus.COMMITTED;
    this.committedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public String getObjectKey() {
    return objectKey;
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

  public StorageObjectStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCommittedAt() {
    return committedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof StorageObject other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
