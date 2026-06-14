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
import jakarta.persistence.Convert;
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
 * A DB-configured external identity provider (OIDC or OAuth2). The client secret is stored
 * encrypted at rest via {@link EncryptedStringConverter}; the OAuth2 endpoint URIs and attribute
 * mappings are only needed for the generic {@link OidcProviderType#OAUTH2} variant. The {@code
 * provider_type} domain is guarded by a Postgres {@code CHECK} (ADR-0020).
 */
@Entity
@Table(name = "oidc_provider")
public class OidcProvider {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", nullable = false, length = 16)
  private OidcProviderType providerType;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "client_secret_encrypted", length = 1024)
  private String clientSecret;

  @Column(name = "issuer_uri")
  private String issuerUri;

  @Column(name = "scope")
  private String scope;

  @Column(name = "authorization_uri")
  private String authorizationUri;

  @Column(name = "token_uri")
  private String tokenUri;

  @Column(name = "user_info_uri")
  private String userInfoUri;

  @Column(name = "jwk_set_uri")
  private String jwkSetUri;

  @Column(name = "user_name_attribute")
  private String userNameAttribute;

  @Column(name = "email_attribute")
  private String emailAttribute;

  @Column(name = "display_name_attribute")
  private String displayNameAttribute;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OidcProvider() {
    // for JPA
  }

  public OidcProvider(String name, OidcProviderType providerType, String clientId) {
    this.name = name;
    this.providerType = providerType;
    this.clientId = clientId;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public OidcProviderType getProviderType() {
    return providerType;
  }

  public void setProviderType(OidcProviderType providerType) {
    this.providerType = providerType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getIssuerUri() {
    return issuerUri;
  }

  public void setIssuerUri(String issuerUri) {
    this.issuerUri = issuerUri;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getAuthorizationUri() {
    return authorizationUri;
  }

  public void setAuthorizationUri(String authorizationUri) {
    this.authorizationUri = authorizationUri;
  }

  public String getTokenUri() {
    return tokenUri;
  }

  public void setTokenUri(String tokenUri) {
    this.tokenUri = tokenUri;
  }

  public String getUserInfoUri() {
    return userInfoUri;
  }

  public void setUserInfoUri(String userInfoUri) {
    this.userInfoUri = userInfoUri;
  }

  public String getJwkSetUri() {
    return jwkSetUri;
  }

  public void setJwkSetUri(String jwkSetUri) {
    this.jwkSetUri = jwkSetUri;
  }

  public String getUserNameAttribute() {
    return userNameAttribute;
  }

  public void setUserNameAttribute(String userNameAttribute) {
    this.userNameAttribute = userNameAttribute;
  }

  public String getEmailAttribute() {
    return emailAttribute;
  }

  public void setEmailAttribute(String emailAttribute) {
    this.emailAttribute = emailAttribute;
  }

  public String getDisplayNameAttribute() {
    return displayNameAttribute;
  }

  public void setDisplayNameAttribute(String displayNameAttribute) {
    this.displayNameAttribute = displayNameAttribute;
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
    if (!(o instanceof OidcProvider other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
