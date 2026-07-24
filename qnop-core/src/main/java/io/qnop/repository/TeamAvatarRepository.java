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

import io.qnop.entity.TeamAvatar;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link TeamAvatar} (issue #509), the team counterpart of {@code
 * UserAvatarRepository}. {@code findById} loads the full bytes for the serving path; the {@code
 * updatedAt} projections deliberately avoid selecting the {@code bytea} {@code content}, so
 * building avatar URLs for a team list never streams image bytes.
 */
public interface TeamAvatarRepository extends JpaRepository<TeamAvatar, UUID> {

  /** When the team's avatar was last set, without loading the image bytes. */
  @Query("select a.updatedAt from TeamAvatar a where a.teamId = :teamId")
  Optional<Instant> findUpdatedAtByTeamId(@Param("teamId") UUID teamId);

  /** Batch variant for a team list: one row per team that has an avatar. */
  @Query(
      "select a.teamId as teamId, a.updatedAt as updatedAt from TeamAvatar a where a.teamId in :teamIds")
  List<AvatarUpdatedAtView> findUpdatedAtByTeamIdIn(@Param("teamIds") Collection<UUID> teamIds);

  /**
   * Idempotent bulk delete (a missing row is a no-op). Flushes pending changes first and clears the
   * persistence context afterwards, so a subsequent {@code findById} in the same session reads the
   * database rather than a stale cached row (matters when a remove/replace and a read share one
   * transaction).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from TeamAvatar a where a.teamId = :teamId")
  int deleteByTeamId(@Param("teamId") UUID teamId);

  /** Projection exposing only the team id and avatar timestamp (no bytes). */
  interface AvatarUpdatedAtView {
    UUID getTeamId();

    Instant getUpdatedAt();
  }
}
