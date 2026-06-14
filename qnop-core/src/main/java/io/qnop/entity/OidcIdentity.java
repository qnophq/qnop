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
 * Binds an external provider's {@code subject} to a local {@link User}. A user has at most one
 * identity per provider, and at most one identity overall ({@code user_id} is unique), so linking a
 * second provider to an already-linked user is rejected at the schema level. Foreign keys to {@link
 * OidcProvider} and {@link User} cascade on delete (ADR-0020). The provider and user are kept as
 * raw UUID columns rather than {@code @ManyToOne} associations.
 */
@Entity
@Table(name = "oidc_identity")
public class OidcIdentity {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "oidc_provider_id", nullable = false)
  private UUID oidcProviderId;

  @Column(name = "subject", nullable = false)
  private String subject;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected OidcIdentity() {
    // for JPA
  }

  public OidcIdentity(UUID oidcProviderId, String subject, UUID userId) {
    this.oidcProviderId = oidcProviderId;
    this.subject = subject;
    this.userId = userId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOidcProviderId() {
    return oidcProviderId;
  }

  public void setOidcProviderId(UUID oidcProviderId) {
    this.oidcProviderId = oidcProviderId;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof OidcIdentity other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
