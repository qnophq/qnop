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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link EmailVerificationToken}. */
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  /** Lookup by the hex-encoded SHA-256 of the raw token presented on the verify-email link. */
  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  /**
   * All in-flight (unconsumed) tokens for a user — used by the resend path to invalidate previous
   * tokens before issuing a fresh one, so a user never has two valid verification links at once.
   */
  @Query(
      "SELECT t FROM EmailVerificationToken t WHERE t.user.id = :userId AND t.consumedAt IS NULL")
  List<EmailVerificationToken> findUnconsumedTokensForUser(@Param("userId") UUID userId);

  /** Scheduled-sweep target: rows whose {@code expires_at} has passed. */
  long deleteByExpiresAtBefore(Instant cutoff);
}
