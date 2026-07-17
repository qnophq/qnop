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
 * One user's emoji reaction on an {@link Annotation} XOR a {@link Comment} (issue #410) — Slack's
 * model: at most one row per user × emoji × target (partial unique indexes), several DIFFERENT
 * emojis per user allowed. Rows are immutable; toggling off deletes them. Deleting the target
 * cascades its reactions (enforced in Liquibase, ADR-0020).
 */
@Entity
@Table(name = "reaction")
public class Reaction {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "annotation_id", updatable = false)
  private UUID annotationId;

  @Column(name = "comment_id", updatable = false)
  private UUID commentId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "emoji", nullable = false, updatable = false, length = 32)
  private String emoji;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Reaction() {
    // for JPA
  }

  private Reaction(UUID annotationId, UUID commentId, UUID userId, String emoji) {
    this.annotationId = annotationId;
    this.commentId = commentId;
    this.userId = userId;
    this.emoji = emoji;
  }

  public static Reaction onAnnotation(UUID annotationId, UUID userId, String emoji) {
    return new Reaction(annotationId, null, userId, emoji);
  }

  public static Reaction onComment(UUID commentId, UUID userId, String emoji) {
    return new Reaction(null, commentId, userId, emoji);
  }

  public UUID getId() {
    return id;
  }

  public UUID getAnnotationId() {
    return annotationId;
  }

  public UUID getCommentId() {
    return commentId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getEmoji() {
    return emoji;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
