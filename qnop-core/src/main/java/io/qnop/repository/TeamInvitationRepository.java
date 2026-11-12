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

import io.qnop.entity.TeamInvitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link TeamInvitation} (issue #936). */
public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, UUID> {

  Optional<TeamInvitation> findByTeamIdAndUserId(UUID teamId, UUID userId);

  boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

  /** Pending invitations of one team — the per-team pending cap (issue #936). */
  long countByTeamId(UUID teamId);

  /**
   * Deletes the invitation of the user into the team and reports how many rows went (0 or 1). A
   * counted bulk delete, so an invitation answered or revoked concurrently is a clean "not found"
   * rather than a stale-entity failure on flush (issue #936).
   */
  @Modifying
  @Query("DELETE FROM TeamInvitation i WHERE i.teamId = :teamId AND i.userId = :userId")
  int deleteInvitation(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

  /** Drops every pending invitation of a team — when an admin disables it (issue #936). */
  @Modifying
  @Query("DELETE FROM TeamInvitation i WHERE i.teamId = :teamId")
  int deleteAllOfTeam(@Param("teamId") UUID teamId);

  /** A team's pending invitations joined with the invited user's identity, by display name. */
  @Query(
      "SELECT new io.qnop.repository.TeamInvitationProjection("
          + "u.id, u.displayName, u.slug, u.email, i.createdAt) "
          + "FROM TeamInvitation i JOIN User u ON u.id = i.userId "
          + "WHERE i.teamId = :teamId ORDER BY LOWER(u.displayName)")
  List<TeamInvitationProjection> findPendingByTeamId(@Param("teamId") UUID teamId);

  /**
   * The user's own pending invitations into enabled teams, newest first, with the inviting lead's
   * display name (null once that account is gone).
   */
  @Query(
      "SELECT new io.qnop.repository.UserInvitationProjection("
          + "t.id, t.name, t.slug, inviter.displayName, i.createdAt) "
          + "FROM TeamInvitation i JOIN Team t ON t.id = i.teamId "
          + "LEFT JOIN User inviter ON inviter.id = i.invitedBy "
          + "WHERE i.userId = :userId AND t.enabled = true ORDER BY i.createdAt DESC")
  List<UserInvitationProjection> findInvitationsOfUser(@Param("userId") UUID userId);
}
