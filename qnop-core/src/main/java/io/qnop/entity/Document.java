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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The aggregate root of a review (issue #244, ADR-0011). A document owns an ordered chain of
 * immutable {@link DocumentVersion}s, its reviewer set ({@link ReviewParticipant}), the
 * version-independent {@link Annotation}s raised against it, and its {@link AuditEvent} trail.
 * There is no separate "review" entity — the document <em>is</em> the review.
 *
 * <p>The {@code ownerId} is a non-null user FK: modelling the owner structurally (rather than as a
 * participant row) guarantees exactly one owner per document. The {@code workflowState} is
 * persisted as a plain string with no closed DB {@code CHECK} so an enterprise edition can extend
 * the state machine (ADR-0011/0035); Community code uses {@link WorkflowState} for the states it
 * knows.
 *
 * <p>Related rows are held as plain UUID FKs (not JPA associations), keeping the workflow and
 * re-anchoring logic DB-free testable in the service layer; the FKs are enforced in Liquibase
 * (ADR-0020). Identity is UUIDv7; {@code @Version} guards concurrent workflow transitions
 * (ADR-0030).
 */
@Entity
@Table(name = "document")
public class Document {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "title", nullable = false, length = 500)
  private String title;

  /**
   * The workflow state as an extensible string (ADR-0011). Community writes {@link WorkflowState}
   * values via {@link #setWorkflowState(WorkflowState)}; the column tolerates enterprise states.
   */
  @Column(name = "workflow_state", nullable = false, length = 32)
  private String workflowState;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Optional, informational completion deadline (issue #295). Nullable and free of any
   * workflow-guard semantics: an overdue review is highlighted in the UI, never auto-cancelled or
   * blocked. Community scope.
   */
  @Column(name = "due_at")
  private Instant dueAt;

  /**
   * Optional friendly URL slug (issue #411): kebab-case, globally unique case-insensitively, never
   * UUID-shaped — the format and uniqueness are pinned in Liquibase; normalisation and the
   * UUID-shape guard live in the service.
   */
  @Column(name = "slug", length = 64, updatable = false)
  private String slug;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected Document() {
    // for JPA
  }

  /** Creates a new document owned by {@code ownerId}, starting in {@link WorkflowState#DRAFT}. */
  public Document(UUID ownerId, String title) {
    this.ownerId = ownerId;
    this.title = title;
    this.workflowState = WorkflowState.DRAFT.name();
  }

  /**
   * Sets the workflow state to a known Community state. The transition <em>guards</em> (which
   * transitions are legal, and the "no open annotations" finalization rule) live in the workflow
   * state machine (#246), not here — this entity only holds the state.
   */
  public void setWorkflowState(WorkflowState newState) {
    this.workflowState = newState.name();
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getTitle() {
    return title;
  }

  /** The raw persisted workflow state; may be an enterprise state unknown to this edition. */
  public String getWorkflowState() {
    return workflowState;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** The optional completion deadline, or {@code null} when none is set (issue #295). */
  public Instant getDueAt() {
    return dueAt;
  }

  public String getSlug() {
    return slug;
  }

  /** Set once at creation (the column is not updatable); {@code null} means no friendly URL. */
  public void setSlug(String slug) {
    this.slug = slug;
  }

  /** Sets or clears ({@code null}) the optional completion deadline (issue #295). */
  public void setDueAt(Instant dueAt) {
    this.dueAt = dueAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Document other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
