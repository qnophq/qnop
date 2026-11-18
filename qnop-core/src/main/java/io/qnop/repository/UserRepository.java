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
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /** Reads a user with a row lock held until the transaction ends (issue #900). */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") UUID id);

  /** The pending flag straight from the database, bypassing a loaded entity (issue #900). */
  @Query("SELECT u.emailVerificationPending FROM User u WHERE u.id = :id")
  boolean isEmailVerificationPending(@Param("id") UUID id);

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
   * Serializes slug allocation across the flat user⟷team namespace (issue #595, ADR-0048): a
   * transaction-scoped Postgres advisory lock on the lowercased candidate. The per-table unique
   * indexes cannot guard a collision ACROSS tables, so concurrent allocations of the same candidate
   * queue here; the lock releases with the transaction. A {@code hashtext} collision between two
   * different slugs merely serializes two unrelated allocations, never corrupts one. The subselect
   * exists so the statement yields an integer — {@code pg_advisory_xact_lock} itself returns the
   * SQL {@code void} type, which JDBC cannot map.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:slugLower))) AS lock_taken",
      nativeQuery = true)
  Long acquireSlugAllocationLock(@Param("slugLower") String slugLower);

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

  /**
   * The enabled users with {@code role}, row-locked for the rest of the transaction ({@code
   * PESSIMISTIC_WRITE}, rendered as {@code FOR NO KEY UPDATE} on PostgreSQL; ordered by id so
   * concurrent callers lock in the same order). Backs the race-free last-admin guard (issue #920):
   * a concurrent transaction that demoted, disabled or deleted one of these rows makes this call
   * wait for its commit, after which PostgreSQL re-checks the row and leaves it out.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.role = :role AND u.enabled = TRUE ORDER BY u.id")
  List<User> lockEnabledByRole(@Param("role") UserRole role);

  /**
   * A user row share-locked ({@code PESSIMISTIC_READ}, {@code FOR SHARE} on PostgreSQL) for the
   * rest of the transaction. Refresh-token rotation takes it first (issue #902) so a concurrent
   * session revocation — which writes the user row ({@code password_invalidated_before}) before it
   * revokes the token rows — is either fully committed before the rotation checks the user, or
   * waits until the rotation's successor token is committed and can be revoked with the rest.
   */
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("SELECT u FROM User u WHERE u.id = :id")
  Optional<User> lockForSessionCheck(@Param("id") UUID id);

  /**
   * A user row locked for update ({@code PESSIMISTIC_WRITE}). Deletion takes it before revoking the
   * user's refresh tokens, so it locks user-then-tokens like rotation does (issue #901).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.id = :id")
  Optional<User> lockById(@Param("id") UUID id);

  /**
   * Key-share locks a user row (issue #952) and reports whether it still exists (1) or not (0). A
   * writer that is about to name the user in an audit detail takes it, so the user's deletion —
   * which erases them from the audit trail — waits for that writer to commit and then sees its row;
   * and a writer that comes second finds the user gone instead of writing their data afterwards.
   */
  @Query(
      value = "SELECT count(*) FROM (SELECT 1 FROM qnop_user WHERE id = :id FOR KEY SHARE) locked",
      nativeQuery = true)
  long keyShareLock(@Param("id") UUID id);

  /**
   * Announces, for the rest of this transaction, who is deleting users (issue #952). The database
   * attributes the {@code user.erased} audit event of the next user deletion to this user; it is
   * attribution only and authorises nothing. Returns the value set.
   */
  @Query(
      value = "SELECT set_config('qnop.erasing_actor', CAST(:actorId AS text), true)",
      nativeQuery = true)
  String announceErasingActor(@Param("actorId") String actorId);

  /**
   * Enabled accounts on this instance — the figure the user quota is measured against (issue #673).
   * Disabled accounts are excluded: they cannot sign in, so charging a seat for one would make
   * "deactivate the leaver" a worse move than deleting their history.
   */
  long countByEnabledTrue();

  boolean existsByEmailIgnoreCaseAndSource(String email, UserSource source);

  /**
   * Atomically activates an account whose e-mail verification is still pending, with the
   * credentials of the verified registration, and bumps {@code version} (issue #900). Conditional
   * on the pending flag, so it is a no-op (returns 0) for an account an administrator has decided
   * about in the meantime, or one another verification link activated first — not even a race can
   * re-enable an administrator-disabled account or apply two registrations.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE User u SET u.enabled = true, u.emailVerificationPending = false,"
          + " u.username = :username, u.displayName = :displayName, u.passwordHash = :hash,"
          + " u.version = u.version + 1 WHERE u.id = :id AND u.emailVerificationPending = true")
  int activateVerifiedRegistration(
      @Param("id") UUID id,
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("hash") String passwordHash);

  /**
   * Re-points the slug of a freshly verified registration to its display name (issue #900). Native
   * because the mapped column is not updatable; flushes first so pending entity changes are written
   * before, and are not lost to, this statement.
   */
  @Modifying(flushAutomatically = true)
  @Query(value = "UPDATE qnop_user SET slug = :slug WHERE id = :id", nativeQuery = true)
  int updateSlug(@Param("id") UUID id, @Param("slug") String slug);

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

  /**
   * The same enabled-principals rule as a {@link Page} (issue #540): the global search needs the
   * full match count for its "see all N" affordance, which the {@code List} variant cannot give.
   */
  @Query(
      value =
          "SELECT u FROM User u WHERE u.enabled = TRUE AND (:q IS NULL"
              + " OR LOWER(u.displayName) LIKE :q"
              + " OR (u.username IS NOT NULL AND LOWER(u.username) LIKE :q))"
              + " ORDER BY LOWER(u.displayName)",
      countQuery =
          "SELECT COUNT(u) FROM User u WHERE u.enabled = TRUE AND (:q IS NULL"
              + " OR LOWER(u.displayName) LIKE :q"
              + " OR (u.username IS NOT NULL AND LOWER(u.username) LIKE :q))")
  Page<User> pageEnabledPrincipals(@Param("q") String q, Pageable pageable);

  /**
   * How many accounts there are per sign-in state (issue #962), as {@code [enabled,
   * emailVerificationPending, count]} rows — one grouped read for the product gauges.
   */
  @Query(
      "SELECT u.enabled, u.emailVerificationPending, COUNT(u) FROM User u"
          + " GROUP BY u.enabled, u.emailVerificationPending")
  List<Object[]> countGroupedBySignInState();
}
