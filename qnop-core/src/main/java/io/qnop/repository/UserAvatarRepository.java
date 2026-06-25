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

import io.qnop.entity.UserAvatar;
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
 * Data access for {@link UserAvatar} (issue #117). {@code findById} loads the full bytes for the
 * serving path; the {@code updatedAt} projections deliberately avoid selecting the {@code bytea}
 * {@code content}, so building avatar URLs for the admin user list never streams image bytes.
 */
public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {

  /** When the user's avatar was last set, without loading the image bytes. */
  @Query("select a.updatedAt from UserAvatar a where a.userId = :userId")
  Optional<Instant> findUpdatedAtByUserId(@Param("userId") UUID userId);

  /** Batch variant for the admin user list: one row per user that has an avatar. */
  @Query(
      "select a.userId as userId, a.updatedAt as updatedAt from UserAvatar a where a.userId in :userIds")
  List<AvatarUpdatedAtView> findUpdatedAtByUserIdIn(@Param("userIds") Collection<UUID> userIds);

  /**
   * Idempotent bulk delete (replaces the entity-load delete so a missing row is a no-op). Flushes
   * pending changes first and clears the persistence context afterwards, so a subsequent {@code
   * findById} in the same session reads the database rather than a stale cached row (matters when a
   * remove/replace and a read share one transaction).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from UserAvatar a where a.userId = :userId")
  int deleteByUserId(@Param("userId") UUID userId);

  /** Projection exposing only the user id and avatar timestamp (no bytes). */
  interface AvatarUpdatedAtView {
    UUID getUserId();

    Instant getUpdatedAt();
  }
}
