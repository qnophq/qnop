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

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.entity.UserSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link User}. */
public interface UserRepository extends JpaRepository<User, UUID> {

  /** Finds an internal user by case-insensitive email (matches the partial-unique index). */
  Optional<User> findByEmailIgnoreCaseAndSource(String email, UserSource source);

  /** Finds an internal user by exact username. */
  Optional<User> findByUsernameAndSource(String username, UserSource source);

  /**
   * Paginated admin search (issues #104/#124): an optional case-insensitive match on display name,
   * email or username, plus optional role and enabled-status filters. {@code q} must be passed
   * pre-lowercased and {@code LIKE}-wrapped (e.g. {@code %alice%}); a {@code null} {@code q}/{@code
   * role}/{@code enabled} disables that filter. Sorting is supplied via {@code Pageable}.
   */
  @Query(
      "SELECT u FROM User u WHERE (:role IS NULL OR u.role = :role)"
          + " AND (:enabled IS NULL OR u.enabled = :enabled)"
          + " AND (:q IS NULL OR LOWER(u.displayName) LIKE :q OR LOWER(u.email) LIKE :q"
          + " OR (u.username IS NOT NULL AND LOWER(u.username) LIKE :q))")
  Page<User> search(
      @Param("q") String q,
      @Param("role") UserRole role,
      @Param("enabled") Boolean enabled,
      Pageable pageable);

  /** Number of enabled users with the given role — guards the last-admin invariant (issue #104). */
  long countByRoleAndEnabledTrue(UserRole role);

  boolean existsByEmailIgnoreCaseAndSource(String email, UserSource source);

  /**
   * Atomically stamps the last-login timestamp (issue #61) without a read-modify-write, so a login
   * never clobbers a concurrent security write. Best-effort: it does not bump {@code version}, so a
   * concurrent full-entity edit may overwrite the timestamp — harmless for a login marker.
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE User u SET u.lastLoginAt = :at WHERE u.id = :id")
  int touchLastLogin(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Atomically bumps {@code password_invalidated_before} (token revocation) and increments {@code
   * version} (issue #61). The version bump makes any concurrently-loaded stale entity's later
   * full-entity save fail optimistically rather than reverting the revocation.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE User u SET u.passwordInvalidatedBefore = :at, u.version = u.version + 1"
          + " WHERE u.id = :id")
  int bumpPasswordInvalidatedBefore(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Atomically replaces the password hash and increments {@code version} (issue #61), so a password
   * change cannot be lost to, or clobber, a concurrent edit.
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE User u SET u.passwordHash = :hash, u.version = u.version + 1 WHERE u.id = :id")
  int updatePasswordHash(@Param("id") UUID id, @Param("hash") String hash);
}
