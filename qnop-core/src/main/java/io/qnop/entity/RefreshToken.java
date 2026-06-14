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
 * Server-side record backing the opaque refresh cookie.
 *
 * <p>Only the HMAC-SHA256 of the plaintext refresh token is stored ({@code tokenLookupHash}); the
 * plaintext lives exactly once in the {@code Set-Cookie} header and never touches disk. Lookup is a
 * unique-index equality probe (constant-time). Every successful refresh revokes this row (sets
 * {@code revokedAt} + {@code revocationReason}) and issues a successor linked via {@code
 * rotatedToId}; replaying a revoked token force-revokes the whole {@code familyId} group
 * (reuse-detection). The token-minting/rotation logic itself lands with the JWT/session core (#17).
 *
 * <p>FKs are raw UUID columns (no {@code @ManyToOne}); the FK to {@code qnop_user} cascades on
 * delete and the self-FK {@code rotatedToId} is {@code SET NULL} — both defined in Liquibase
 * (ADR-0020). Identifiers are UUIDv7, generated application-side by Hibernate.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(
      name = "token_lookup_hash",
      nullable = false,
      unique = true,
      length = 64,
      updatable = false)
  private String tokenLookupHash;

  @CreationTimestamp
  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revocation_reason", length = 32)
  private String revocationReason;

  @Column(name = "rotated_to_id")
  private UUID rotatedToId;

  @Column(name = "upstream_id_token", columnDefinition = "TEXT")
  private String upstreamIdToken;

  protected RefreshToken() {
    // for JPA
  }

  public RefreshToken(UUID familyId, UUID userId, String tokenLookupHash, Instant expiresAt) {
    this.familyId = familyId;
    this.userId = userId;
    this.tokenLookupHash = tokenLookupHash;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenLookupHash() {
    return tokenLookupHash;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public String getRevocationReason() {
    return revocationReason;
  }

  public void setRevocationReason(String revocationReason) {
    this.revocationReason = revocationReason;
  }

  public UUID getRotatedToId() {
    return rotatedToId;
  }

  public void setRotatedToId(UUID rotatedToId) {
    this.rotatedToId = rotatedToId;
  }

  public String getUpstreamIdToken() {
    return upstreamIdToken;
  }

  public void setUpstreamIdToken(String upstreamIdToken) {
    this.upstreamIdToken = upstreamIdToken;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RefreshToken other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
