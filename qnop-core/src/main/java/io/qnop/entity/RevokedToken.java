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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Denylist entry for a revoked access-token {@code jti}.
 *
 * <p>On logout or password change the SHA-256 hash of the token's {@code jti} claim is stored here
 * so the JWT decoder can reject the token before its natural expiry (the validation path is added
 * with the JWT/session core, #17). Expired rows are purged by a scheduled cleanup job.
 *
 * <p><strong>Hashed at rest:</strong> the {@code jti} column holds hex-encoded SHA-256, never the
 * raw claim — the only question asked is "have I seen this jti?", which a digest answers, so a DB
 * leak exposes opaque hashes rather than valid token IDs. The FK to {@code qnop_user} cascades on
 * delete (Liquibase, ADR-0020); the user is a raw UUID (lookups never need the user object).
 */
@Entity
@Table(name = "revoked_token")
public class RevokedToken {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "jti", nullable = false, unique = true, length = 64, updatable = false)
  private String jti;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "revoked_at", nullable = false, updatable = false)
  private Instant revokedAt;

  protected RevokedToken() {
    // for JPA
  }

  public RevokedToken(String jti, UUID userId, Instant expiresAt) {
    this.jti = jti;
    this.userId = userId;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public String getJti() {
    return jti;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RevokedToken other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
