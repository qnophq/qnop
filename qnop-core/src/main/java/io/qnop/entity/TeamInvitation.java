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
 * A pending invitation of a user into a {@link Team} (issue #936, ADR-0064). A team lead's add
 * creates one of these instead of a {@link TeamMembership}; it grants nothing — no team access, no
 * review visibility — until the invited user accepts it, which turns it into a {@code MEMBER}
 * membership. Declining or revoking deletes the row. Kept in its own table, not as a status on the
 * membership, so every query that derives access from {@code team_membership} keeps meaning "active
 * member". Plain UUID foreign keys as on {@link TeamMembership}; the FKs (cascading with the team
 * and the user) and the {@code (team_id, user_id)} uniqueness live in Liquibase.
 */
@Entity
@Table(name = "team_invitation")
public class TeamInvitation {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "team_id", nullable = false, updatable = false)
  private UUID teamId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The lead who sent the invitation; null once that account is deleted. */
  @Column(name = "invited_by", updatable = false)
  private UUID invitedBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TeamInvitation() {
    // for JPA
  }

  /** An invitation of {@code userId} into {@code teamId}, sent by {@code invitedBy}. */
  public static TeamInvitation of(UUID teamId, UUID userId, UUID invitedBy) {
    TeamInvitation invitation = new TeamInvitation();
    invitation.teamId = teamId;
    invitation.userId = userId;
    invitation.invitedBy = invitedBy;
    return invitation;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getInvitedBy() {
    return invitedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TeamInvitation other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
