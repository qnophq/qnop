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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One in-app notification for one recipient (issue #538, ADR-0051) — fan-out on write, so an event
 * touching five people leaves five rows.
 *
 * <p>Everything that carries identity is stored as an <strong>id</strong> and resolved when the
 * notification is read: under per-review anonymity (ADR-0038) a name belongs to a (review, viewer)
 * pair, not to a user, and a review's privacy setting can change after this row was written.
 * Everything that carries none — the excerpt, the decision, the version, the transition — is
 * snapshotted <strong>here</strong>, because it describes what happened then and the comment it
 * quotes may since have been edited or deleted.
 *
 * <p>Immutable except for {@link #readAt}: a notification is a record of a past event, and the only
 * thing that can happen to it afterwards is being read.
 */
@Entity
@Table(name = "notification")
public class Notification {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "recipient_id", nullable = false, updatable = false)
  private UUID recipientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 32, updatable = false)
  private NotificationType type;

  /** Who caused it; null for machine-driven events. Never stored as a name. */
  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  /** The review this is about; null leaves room for non-review notifications (none written yet). */
  @Column(name = "document_id", updatable = false)
  private UUID documentId;

  @Column(name = "annotation_id", updatable = false)
  private UUID annotationId;

  @Column(name = "comment_id", updatable = false)
  private UUID commentId;

  @Column(name = "excerpt", length = 200, updatable = false)
  private String excerpt;

  @Column(name = "decision", length = 32, updatable = false)
  private String decision;

  @Column(name = "version_number", updatable = false)
  private Integer versionNumber;

  @Column(name = "from_state", length = 32, updatable = false)
  private String fromState;

  @Column(name = "to_state", length = 32, updatable = false)
  private String toState;

  /** Null while unread — the badge counts exactly these. */
  @Column(name = "read_at")
  private Instant readAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Notification() {
    // JPA
  }

  private Notification(UUID recipientId, NotificationType type) {
    this.recipientId = recipientId;
    this.type = type;
  }

  /**
   * Starts a notification for one recipient; the context is filled in by the {@code with*} calls.
   */
  public static Notification of(UUID recipientId, NotificationType type) {
    return new Notification(Objects.requireNonNull(recipientId), Objects.requireNonNull(type));
  }

  public Notification withActor(UUID actorId) {
    this.actorId = actorId;
    return this;
  }

  public Notification withDocument(UUID documentId) {
    this.documentId = documentId;
    return this;
  }

  public Notification withAnnotation(UUID annotationId) {
    this.annotationId = annotationId;
    return this;
  }

  public Notification withComment(UUID commentId) {
    this.commentId = commentId;
    return this;
  }

  /** The quoted line, already flattened and capped by the resolver. */
  public Notification withExcerpt(String excerpt) {
    this.excerpt = excerpt == null || excerpt.isBlank() ? null : excerpt;
    return this;
  }

  public Notification withDecision(String decision) {
    this.decision = decision;
    return this;
  }

  public Notification withVersionNumber(Integer versionNumber) {
    this.versionNumber = versionNumber;
    return this;
  }

  public Notification withTransition(String fromState, String toState) {
    this.fromState = fromState;
    this.toState = toState;
    return this;
  }

  /** Marks it read at {@code when}; already-read notifications keep their original instant. */
  public void markRead(Instant when) {
    if (this.readAt == null) {
      this.readAt = when;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRecipientId() {
    return recipientId;
  }

  public NotificationType getType() {
    return type;
  }

  public UUID getActorId() {
    return actorId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getAnnotationId() {
    return annotationId;
  }

  public UUID getCommentId() {
    return commentId;
  }

  public String getExcerpt() {
    return excerpt;
  }

  public String getDecision() {
    return decision;
  }

  public Integer getVersionNumber() {
    return versionNumber;
  }

  public String getFromState() {
    return fromState;
  }

  public String getToState() {
    return toState;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
