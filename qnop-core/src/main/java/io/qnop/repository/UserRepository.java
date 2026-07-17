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
import java.util.Collection;
import java.util.List;
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

  /**
   * Display names for a set of user ids (issue #413) — resolves annotation/comment authors to real
   * names server-side, including authors who participate via a team and so never appear among the
   * participant rows. A projection so no password hashes or other columns are loaded.
   */
  @Query(
      "SELECT new io.qnop.repository.UserDisplayName(u.id, u.displayName) FROM User u"
          + " WHERE u.id IN :ids")
  List<UserDisplayName> findDisplayNamesByIdIn(@Param("ids") Collection<UUID> ids);

  /** Finds an internal user by exact username. */
  Optional<User> findByUsernameAndSource(String username, UserSource source);

  /** Resolves a user by the profile slug (issue #486) — uniqueness is per LOWER(slug). */
  Optional<User> findBySlugIgnoreCase(String slug);

  /** Batch id→slug resolution for pretty profile links (issue #486). */
  @Query("SELECT new io.qnop.repository.UserSlug(u.id, u.slug) FROM User u WHERE u.id IN :ids")
  List<UserSlug> findSlugsByIdIn(@Param("ids") Collection<UUID> ids);

  /** True when the profile slug is already claimed, ignoring case (issue #486). */
  boolean existsBySlugIgnoreCase(String slug);

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
   * Atomically replaces the password hash, clears {@code password_change_required}, and increments
   * {@code version} (issue #61). A self-service password change always satisfies any forced-change
   * requirement, and the version bump means the change cannot be lost to, or clobber, a concurrent
   * edit.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE User u SET u.passwordHash = :hash, u.passwordChangeRequired = false,"
          + " u.version = u.version + 1 WHERE u.id = :id")
  int updatePasswordHash(@Param("id") UUID id, @Param("hash") String hash);

  /**
   * Principal-directory search (issue #292): enabled users by display name or username.
   * Deliberately does NOT match on email — the directory must not confirm email addresses. {@code
   * q} pre-lowercased and {@code LIKE}-wrapped; {@code null} disables the filter. Limit via {@code
   * Pageable}.
   */
  @Query(
      "SELECT u FROM User u WHERE u.enabled = TRUE AND (:q IS NULL"
          + " OR LOWER(u.displayName) LIKE :q"
          + " OR (u.username IS NOT NULL AND LOWER(u.username) LIKE :q))"
          + " ORDER BY LOWER(u.displayName)")
  List<User> searchEnabledPrincipals(@Param("q") String q, Pageable pageable);
}
