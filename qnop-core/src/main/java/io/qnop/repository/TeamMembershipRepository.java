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

  /** The members of a team joined with their user identity, ordered by display name. */
  @Query(
      "SELECT new io.qnop.repository.TeamMemberProjection("
          + "m.id, u.id, u.displayName, u.email, m.teamRole, m.joinedAt) "
          + "FROM TeamMembership m JOIN User u ON u.id = m.userId "
          + "WHERE m.teamId = :teamId ORDER BY LOWER(u.displayName)")
  List<TeamMemberProjection> findMembersByTeamId(@Param("teamId") UUID teamId);

  /** Member counts for a set of teams, to populate the list without an N+1. */
  @Query(
      "SELECT new io.qnop.repository.TeamMemberCount(m.teamId, COUNT(m)) "
          + "FROM TeamMembership m WHERE m.teamId IN :teamIds GROUP BY m.teamId")
  List<TeamMemberCount> countMembersByTeamIds(@Param("teamIds") Collection<UUID> teamIds);
}
