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

import io.qnop.entity.EmailVerificationToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link EmailVerificationToken}. */
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  /** Lookup by the hex-encoded SHA-256 of the raw token presented on the verify-email link. */
  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  /**
   * Atomically consumes a token (issue #61): sets {@code consumed_at} only if it is still null.
   * Returns 1 if this call won the race, 0 if the token was already consumed — so a verification
   * token cannot be consumed twice under concurrency. Like every update that spends a link it also
   * drops the registration's candidate credentials (issue #900): a spent link has no use for them.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t SET t.consumedAt = :at, t.candidateUsername = NULL, t.candidateDisplayName = NULL,"
          + " t.candidatePasswordHash = NULL"
          + " WHERE t.id = :id AND t.consumedAt IS NULL")
  int markConsumed(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Reserves one password attempt on a still-usable token BEFORE the password is compared (issue
   * #900): atomic and conditional, so concurrent requests can never compare more than {@code max}
   * passwords against one link. Returns 0 when the token is spent, consumed or out of attempts.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t SET t.failedAttempts = t.failedAttempts + 1"
          + " WHERE t.id = :id AND t.consumedAt IS NULL AND t.failedAttempts < :max")
  int reserveAttempt(@Param("id") UUID id, @Param("max") int max);

  /**
   * Spends a token whose reserved attempts reached {@code max} (issue #900). Returns 1 when this
   * call spent it.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t SET t.consumedAt = :at, t.candidateUsername = NULL, t.candidateDisplayName = NULL,"
          + " t.candidatePasswordHash = NULL"
          + " WHERE t.id = :id AND t.consumedAt IS NULL AND t.failedAttempts >= :max")
  int consumeIfAttemptsExhausted(
      @Param("id") UUID id, @Param("max") int max, @Param("at") Instant at);

  /** Ids of a user's unconsumed tokens, oldest first — to cap outstanding links (#900). */
  @Query(
      "SELECT t.id FROM EmailVerificationToken t WHERE t.user.id = :userId"
          + " AND t.consumedAt IS NULL ORDER BY t.createdAt ASC, t.id ASC")
  List<UUID> findUnconsumedIdsForUserOldestFirst(@Param("userId") UUID userId);

  /** Spends the given still-unconsumed tokens (eviction beyond the cap, #900). */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t SET t.consumedAt = :at, t.candidateUsername = NULL, t.candidateDisplayName = NULL,"
          + " t.candidatePasswordHash = NULL"
          + " WHERE t.id IN :ids AND t.consumedAt IS NULL")
  int consumeAllById(@Param("ids") List<UUID> ids, @Param("at") Instant at);

  /** When the user's newest verification token was issued — the issuance throttle (#900). */
  @Query("SELECT MAX(t.createdAt) FROM EmailVerificationToken t WHERE t.user.id = :userId")
  Optional<Instant> findLatestIssuedAt(@Param("userId") UUID userId);

  /** Spends every unconsumed token of a user once one of them was verified (#900). */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t SET t.consumedAt = :at, t.candidateUsername = NULL, t.candidateDisplayName = NULL,"
          + " t.candidatePasswordHash = NULL"
          + " WHERE t.user.id = :userId AND t.consumedAt IS NULL")
  int consumeAllForUser(@Param("userId") UUID userId, @Param("at") Instant at);

  /** Scheduled-sweep target: rows whose {@code expires_at} has passed. */
  long deleteByExpiresAtBefore(Instant cutoff);
}
