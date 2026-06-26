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
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One user's profile picture (issue #117), one row per user keyed by {@code user_id}. Re-uploading
 * replaces the row (current-only, no history); removing the picture deletes it.
 *
 * <p>The bytes live in Postgres ({@code bytea}) rather than object storage so a database backup is
 * a complete restore, and the {@code user_id → qnop_user} foreign key cascades on delete — removing
 * a user removes their avatar, so there is no orphan-reaper concern (ADR-0031). The {@code
 * content_type} domain, the cascading FK, and the primary key live in Liquibase (migration {@code
 * 0009}), not JPA annotations (ADR-0020); JPA runs {@code ddl-auto=none}. {@code updatedBy} is a
 * best-effort audit breadcrumb (no FK): the admin who set another user's avatar may still be
 * deleted.
 */
@Entity
@Table(name = "user_avatar")
public class UserAvatar {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "content_type", nullable = false, length = 64)
  private String contentType;

  @Column(name = "content", nullable = false)
  private byte[] content;

  /** Hex-encoded SHA-256 of {@link #content}; used as the HTTP ETag on the read path. */
  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  /** Intrinsic pixel width/height when decodable (raster), else {@code null}. */
  @Column(name = "width")
  private Integer width;

  @Column(name = "height")
  private Integer height;

  @CreationTimestamp
  @Column(name = "updated_at", nullable = false, updatable = false)
  private Instant updatedAt;

  /** The user (self or admin) who last set the picture, if known. Nullable; no FK. */
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected UserAvatar() {
    // for JPA
  }

  /** Creates an avatar row for the given user. {@code updatedBy} may be {@code null}. */
  public static UserAvatar create(
      UUID userId,
      String contentType,
      byte[] content,
      String sha256,
      long sizeBytes,
      Integer width,
      Integer height,
      UUID updatedBy) {
    UserAvatar avatar = new UserAvatar();
    avatar.userId = userId;
    avatar.contentType = contentType;
    avatar.content = content == null ? null : content.clone();
    avatar.sha256 = sha256;
    avatar.sizeBytes = sizeBytes;
    avatar.width = width;
    avatar.height = height;
    avatar.updatedBy = updatedBy;
    return avatar;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getContentType() {
    return contentType;
  }

  /**
   * Returns a defensive copy so the stored bytes (and their {@code sha256}/{@code size}) stay
   * intact.
   */
  public byte[] getContent() {
    return content == null ? null : content.clone();
  }

  public String getSha256() {
    return sha256;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public Integer getWidth() {
    return width;
  }

  public Integer getHeight() {
    return height;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof UserAvatar other)) {
      return false;
    }
    return userId != null && userId.equals(other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(userId);
  }
}
