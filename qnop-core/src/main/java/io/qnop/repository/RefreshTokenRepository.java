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

import io.qnop.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link RefreshToken}. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * Constant-time lookup by HMAC of the plaintext refresh token (the unique index makes hit and
   * miss statistically equivalent). Returns the row regardless of revocation; callers check {@code
   * revokedAt} explicitly so reuse-detection can tell a replayed revoked token from an unknown one.
   */
  Optional<RefreshToken> findByTokenLookupHash(String tokenLookupHash);

  /**
   * Force-revokes every still-active row in a family. Idempotent: already-revoked rows keep their
   * original {@code revokedAt}/reason (the {@code WHERE} filters them out) for forensics.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      """
      UPDATE RefreshToken t
      SET t.revokedAt = :revokedAt, t.revocationReason = :reason
      WHERE t.familyId = :familyId AND t.revokedAt IS NULL
      """)
  int revokeFamily(
      @Param("familyId") UUID familyId,
      @Param("reason") String reason,
      @Param("revokedAt") Instant revokedAt);

  /** Revokes every active refresh token for a user (password-change / admin-disable paths). */
  @Modifying(clearAutomatically = true)
  @Query(
      """
      UPDATE RefreshToken t
      SET t.revokedAt = :revokedAt, t.revocationReason = :reason
      WHERE t.userId = :userId AND t.revokedAt IS NULL
      """)
  int revokeAllForUser(
      @Param("userId") UUID userId,
      @Param("reason") String reason,
      @Param("revokedAt") Instant revokedAt);

  /** Purges rows whose absolute expiry has passed. Called by the scheduled cleanup job (#17). */
  @Modifying
  @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
