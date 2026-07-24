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
 * One team's profile picture (issue #509), one row per team keyed by {@code team_id} — the team
 * counterpart of {@link UserAvatar}. Re-uploading replaces the row (current-only, no history);
 * removing the picture deletes it.
 *
 * <p>The bytes live in Postgres ({@code bytea}) rather than object storage so a database backup is
 * a complete restore, and the {@code team_id → team} foreign key cascades on delete — removing a
 * team removes its avatar, so there is no orphan-reaper concern (ADR-0031). The {@code
 * content_type} domain, the cascading FK, and the primary key live in Liquibase (migration {@code
 * 0022}), not JPA annotations (ADR-0020); JPA runs {@code ddl-auto=none}. {@code updatedBy} is a
 * best-effort audit breadcrumb (no FK): the admin/lead who set the avatar may still be deleted.
 */
@Entity
@Table(name = "team_avatar")
public class TeamAvatar {

  @Id
  @Column(name = "team_id", nullable = false, updatable = false)
  private UUID teamId;

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

  /** The user (admin or team lead) who last set the picture, if known. Nullable; no FK. */
  @Column(name = "updated_by")
  private UUID updatedBy;

  protected TeamAvatar() {
    // for JPA
  }

  /** Creates an avatar row for the given team. {@code updatedBy} may be {@code null}. */
  public static TeamAvatar create(
      UUID teamId,
      String contentType,
      byte[] content,
      String sha256,
      long sizeBytes,
      Integer width,
      Integer height,
      UUID updatedBy) {
    TeamAvatar avatar = new TeamAvatar();
    avatar.teamId = teamId;
    avatar.contentType = contentType;
    avatar.content = content == null ? null : content.clone();
    avatar.sha256 = sha256;
    avatar.sizeBytes = sizeBytes;
    avatar.width = width;
    avatar.height = height;
    avatar.updatedBy = updatedBy;
    return avatar;
  }

  public UUID getTeamId() {
    return teamId;
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
    if (!(o instanceof TeamAvatar other)) {
      return false;
    }
    return teamId != null && teamId.equals(other.teamId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(teamId);
  }
}
