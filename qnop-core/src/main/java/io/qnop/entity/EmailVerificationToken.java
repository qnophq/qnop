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
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One row per issued self-registration email-verification token (lifecycle added with the
 * local-user flow, #20).
 *
 * <p>The raw token is never stored — {@code tokenHash} holds hex-encoded SHA-256 (so a DB leak
 * alone cannot grant account access); the raw token lives only in the verification email and the
 * inbound {@code ?token=…} query parameter. {@code consumedAt} flips when the link is used; the row
 * is kept for audit until a scheduled sweep removes expired rows.
 *
 * <p>Unlike the other token entities, {@code user} is an <strong>EAGER {@code @ManyToOne}</strong>:
 * the verify-email consumers read the linked user outside the service transaction (to flip {@code
 * enabled} / send to {@code email}), and LAZY would raise {@code LazyInitializationException}. The
 * FK to {@code qnop_user} cascades on delete (Liquibase, ADR-0020).
 */
@Entity
@Table(name = "email_verification_token")
public class EmailVerificationToken {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "user_id", nullable = false, updatable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
  private String tokenHash;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected EmailVerificationToken() {
    // for JPA
  }

  public EmailVerificationToken(User user, String tokenHash, Instant expiresAt) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public void setConsumedAt(Instant consumedAt) {
    this.consumedAt = consumedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EmailVerificationToken other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
