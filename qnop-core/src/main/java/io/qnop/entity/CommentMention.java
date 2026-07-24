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
 * One resolved {@code @mention} of a user in a {@link Comment} (issue #462). Mentions are parsed
 * and resolved server-side on create — only against users with access to the document — so
 * rendering and mail notifications never re-parse the body. At most one row per comment × mentioned
 * user (unique index). Deleting the comment cascades its mentions (enforced in Liquibase,
 * ADR-0020).
 */
@Entity
@Table(name = "comment_mention")
public class CommentMention {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "comment_id", nullable = false, updatable = false)
  private UUID commentId;

  @Column(name = "mentioned_user_id", nullable = false, updatable = false)
  private UUID mentionedUserId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CommentMention() {
    // for JPA
  }

  public CommentMention(UUID commentId, UUID mentionedUserId) {
    this.commentId = commentId;
    this.mentionedUserId = mentionedUserId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCommentId() {
    return commentId;
  }

  public UUID getMentionedUserId() {
    return mentionedUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
