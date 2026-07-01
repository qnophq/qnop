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
import org.hibernate.annotations.UuidGenerator;

/**
 * A reviewer on a {@link Document} (issue #244, ADR-0011). The principal is <em>either</em> a user
 * <em>or</em> a team — never both, never neither — held as two nullable FKs with a Postgres {@code
 * CHECK} (user_id XOR team_id) in Liquibase (ADR-0020). A team principal means every member of that
 * team reviews.
 *
 * <p>There is no role field: a participant <em>is</em> a reviewer. The canonical document owner is
 * modelled structurally as {@code Document.ownerId}, not as a participant row, so no owner/reviewer
 * distinction is needed here (reviewer sub-roles, if ever required, are an additive change). Use
 * {@link #forUser} / {@link #forTeam} to construct.
 */
@Entity
@Table(name = "review_participant")
public class ReviewParticipant {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "user_id", updatable = false)
  private UUID userId;

  @Column(name = "team_id", updatable = false)
  private UUID teamId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ReviewParticipant() {
    // for JPA
  }

  private ReviewParticipant(UUID documentId, UUID userId, UUID teamId) {
    this.documentId = documentId;
    this.userId = userId;
    this.teamId = teamId;
  }

  /** A single user reviews the document. */
  public static ReviewParticipant forUser(UUID documentId, UUID userId) {
    return new ReviewParticipant(documentId, userId, null);
  }

  /** A whole team reviews the document. */
  public static ReviewParticipant forTeam(UUID documentId, UUID teamId) {
    return new ReviewParticipant(documentId, null, teamId);
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ReviewParticipant other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
