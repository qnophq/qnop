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

import io.qnop.entity.RevokedToken;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link RevokedToken} (the access-token {@code jti} denylist). */
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {

  /** Denylist probe: has this (hashed) {@code jti} been revoked? */
  boolean existsByJti(String jti);

  /** Purges entries whose underlying token would have expired anyway (scheduled cleanup, #17). */
  @Modifying
  @Query("DELETE FROM RevokedToken r WHERE r.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

  /** Wipes a user's revocation rows (the FK also cascades; this is the explicit path). */
  long deleteByUserId(UUID userId);
}
