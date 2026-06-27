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
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One operator-uploaded branding asset, one row per {@link BrandingSlot} (issue #15, ported from
 * plugwerk). The {@code slot} column is unique, so re-uploading a slot replaces the current asset
 * rather than versioning it — the contract is "current asset", not history.
 *
 * <p>The bytes live in Postgres ({@code bytea}) rather than object storage so a database backup is
 * a complete restore; see ADR-0024 (and plugwerk's ADR-0037 as prior art). The {@code slot} and
 * {@code content_type} domains, the {@code (slot)} uniqueness, and the {@code ON DELETE SET NULL}
 * {@code uploaded_by → qnop_user} foreign key are enforced in Liquibase (migration {@code 0005}),
 * not in JPA annotations (ADR-0020); JPA runs {@code ddl-auto=none}. Identifiers are UUIDv7,
 * generated application-side by Hibernate.
 */
@Entity
@Table(name = "application_asset")
public class ApplicationAsset {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Convert(converter = BrandingSlotConverter.class)
  @Column(name = "slot", nullable = false, length = 32)
  private BrandingSlot slot;

  @Column(name = "content_type", nullable = false, length = 64)
  private String contentType;

  @Column(name = "content", nullable = false)
  private byte[] content;

  /** Hex-encoded SHA-256 of {@link #content}; used as the HTTP ETag / cache-buster on read. */
  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @CreationTimestamp
  @Column(name = "uploaded_at", nullable = false, updatable = false)
  private Instant uploadedAt;

  /**
   * The user who uploaded the asset, if known. Nullable, audit-only; the FK is {@code ON DELETE SET
   * NULL}, so deleting the uploader nulls this rather than blocking the delete (issue #180).
   */
  @Column(name = "uploaded_by")
  private UUID uploadedBy;

  protected ApplicationAsset() {
    // for JPA
  }

  /** Creates an asset for the given slot. {@code uploadedBy} may be {@code null}. */
  public static ApplicationAsset create(
      BrandingSlot slot,
      String contentType,
      byte[] content,
      String sha256,
      long sizeBytes,
      UUID uploadedBy) {
    ApplicationAsset asset = new ApplicationAsset();
    asset.slot = slot;
    asset.contentType = contentType;
    asset.content = content == null ? null : content.clone();
    asset.sha256 = sha256;
    asset.sizeBytes = sizeBytes;
    asset.uploadedBy = uploadedBy;
    return asset;
  }

  public UUID getId() {
    return id;
  }

  public BrandingSlot getSlot() {
    return slot;
  }

  public void setSlot(BrandingSlot slot) {
    this.slot = slot;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  /**
   * Returns a defensive copy so the stored bytes (and their {@code sha256}/{@code size}) stay
   * intact.
   */
  public byte[] getContent() {
    return content == null ? null : content.clone();
  }

  public void setContent(byte[] content) {
    this.content = content == null ? null : content.clone();
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public void setUploadedBy(UUID uploadedBy) {
    this.uploadedBy = uploadedBy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ApplicationAsset other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
