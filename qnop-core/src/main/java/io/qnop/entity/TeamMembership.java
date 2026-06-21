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
 * A user's membership in a {@link Team} with a per-team {@link TeamRole} (issue #105). The team and
 * user are held as plain UUID foreign keys (not JPA associations) to keep the service logic simple
 * and DB-free testable; the FKs and the {@code (team_id, user_id)} uniqueness are enforced in
 * Liquibase (ADR-0020). Deleting a team cascades its memberships. UUIDv7 ids, generated
 * application-side by Hibernate.
 */
@Entity
@Table(name = "team_membership")
public class TeamMembership {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "team_id", nullable = false, updatable = false)
  private UUID teamId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "team_role", nullable = false, length = 16)
  private TeamRole teamRole = TeamRole.MEMBER;

  @CreationTimestamp
  @Column(name = "joined_at", nullable = false, updatable = false)
  private Instant joinedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected TeamMembership() {
    // for JPA
  }

  /** Creates a membership of the given user in the given team with the given role. */
  public static TeamMembership of(UUID teamId, UUID userId, TeamRole teamRole) {
    TeamMembership membership = new TeamMembership();
    membership.teamId = teamId;
    membership.userId = userId;
    membership.teamRole = teamRole;
    return membership;
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

  public TeamRole getTeamRole() {
    return teamRole;
  }

  public void setTeamRole(TeamRole teamRole) {
    this.teamRole = teamRole;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public long getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TeamMembership other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
