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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A qnop user — either {@link UserSource#INTERNAL local} (username + password hash) or {@link
 * UserSource#EXTERNAL} (provisioned from an OIDC/OAuth2 provider, see {@link OidcIdentity}).
 *
 * <p>The Postgres-only invariants — {@code source} domain, the INTERNAL⇒credentials /
 * EXTERNAL⇒no-credentials rule, and the case-insensitive-email / username uniqueness scoped to
 * internal users — live in Liquibase, not in JPA annotations (ADR-0020). Identifiers are UUIDv7,
 * generated application-side by Hibernate.
 */
@Entity
@Table(name = "qnop_user")
public class User {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "email", nullable = false)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private UserSource source;

  @Column(name = "username")
  private String username;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "password_change_required", nullable = false)
  private boolean passwordChangeRequired = false;

  @Column(name = "is_superadmin", nullable = false)
  private boolean superadmin = false;

  @Column(name = "password_invalidated_before")
  private Instant passwordInvalidatedBefore;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {
    // for JPA
  }

  /** Creates an internal (local) user with credentials. */
  public static User internal(
      String displayName, String email, String username, String passwordHash) {
    User user = new User();
    user.source = UserSource.INTERNAL;
    user.displayName = displayName;
    user.email = email;
    user.username = username;
    user.passwordHash = passwordHash;
    return user;
  }

  /** Creates an external user provisioned from an identity provider (no local credentials). */
  public static User external(String displayName, String email) {
    User user = new User();
    user.source = UserSource.EXTERNAL;
    user.displayName = displayName;
    user.email = email;
    return user;
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public UserSource getSource() {
    return source;
  }

  public void setSource(UserSource source) {
    this.source = source;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isPasswordChangeRequired() {
    return passwordChangeRequired;
  }

  public void setPasswordChangeRequired(boolean passwordChangeRequired) {
    this.passwordChangeRequired = passwordChangeRequired;
  }

  public boolean isSuperadmin() {
    return superadmin;
  }

  public void setSuperadmin(boolean superadmin) {
    this.superadmin = superadmin;
  }

  public Instant getPasswordInvalidatedBefore() {
    return passwordInvalidatedBefore;
  }

  public void setPasswordInvalidatedBefore(Instant passwordInvalidatedBefore) {
    this.passwordInvalidatedBefore = passwordInvalidatedBefore;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof User other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
