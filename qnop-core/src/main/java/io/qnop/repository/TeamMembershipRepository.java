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
package io.qnop.repository;

import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link TeamMembership}. */
public interface TeamMembershipRepository extends JpaRepository<TeamMembership, UUID> {

  Optional<TeamMembership> findByTeamIdAndUserId(UUID teamId, UUID userId);

  boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

  /** Whether the user holds the given role in the team — the LEAD self-management guard (#470). */
  boolean existsByTeamIdAndUserIdAndTeamRole(UUID teamId, UUID userId, TeamRole teamRole);

  /** How many members hold the given role in the team — the last-lead guardrail (#470). */
  long countByTeamIdAndTeamRole(UUID teamId, TeamRole teamRole);

  /**
   * Total members of a team, regardless of role — lets the last-lead guard allow emptying a team
   * (removing its sole member) while still blocking a lead-less team with members (issue #542).
   */
  long countByTeamId(UUID teamId);

  /** The members of a team joined with their user identity, ordered by display name. */
  @Query(
      "SELECT new io.qnop.repository.TeamMemberProjection("
          + "m.id, u.id, u.displayName, u.slug, u.email, m.teamRole, m.joinedAt) "
          + "FROM TeamMembership m JOIN User u ON u.id = m.userId "
          + "WHERE m.teamId = :teamId ORDER BY LOWER(u.displayName)")
  List<TeamMemberProjection> findMembersByTeamId(@Param("teamId") UUID teamId);

  /** Member counts for a set of teams, to populate the list without an N+1. */
  @Query(
      "SELECT new io.qnop.repository.TeamMemberCount(m.teamId, COUNT(m)) "
          + "FROM TeamMembership m WHERE m.teamId IN :teamIds GROUP BY m.teamId")
  List<TeamMemberCount> countMembersByTeamIds(@Param("teamIds") Collection<UUID> teamIds);

  /** The user's enabled teams with their role there, ordered by name (issue #473). */
  @Query(
      "SELECT new io.qnop.repository.UserTeamProjection(t.id, t.name, t.slug, m.teamRole)"
          + " FROM TeamMembership m, Team t"
          + " WHERE t.id = m.teamId AND m.userId = :userId AND t.enabled = true"
          + " ORDER BY t.name")
  java.util.List<UserTeamProjection> findTeamsOfUser(@Param("userId") UUID userId);
}
