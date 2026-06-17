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

import io.qnop.entity.PasswordResetToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link PasswordResetToken}. */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

  /** Lookup by the hex-encoded SHA-256 of the raw token presented on the reset-password request. */
  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /**
   * All in-flight (unconsumed) tokens for a user — used by the issue path to supersede previous
   * tokens, so clicking "forgot password" twice leaves only the most-recent link working.
   */
  @Query("SELECT t FROM PasswordResetToken t WHERE t.user.id = :userId AND t.consumedAt IS NULL")
  List<PasswordResetToken> findUnconsumedTokensForUser(@Param("userId") UUID userId);

  /**
   * Atomically consumes a token (issue #61): sets {@code consumed_at} only if it is still null.
   * Returns 1 if this call won the race, 0 if already consumed — so a reset token cannot be
   * replayed under concurrency.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE PasswordResetToken t SET t.consumedAt = :at WHERE t.id = :id AND t.consumedAt IS NULL")
  int markConsumed(@Param("id") UUID id, @Param("at") Instant at);

  /** Scheduled-sweep target: rows whose {@code expires_at} has passed. */
  long deleteByExpiresAtBefore(Instant cutoff);
}
