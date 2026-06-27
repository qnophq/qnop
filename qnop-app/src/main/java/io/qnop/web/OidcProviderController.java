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
package io.qnop.web;

import io.qnop.api.v1.endpoint.AdminOidcProvidersApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.OidcDiscoveryRequest;
import io.qnop.api.v1.model.OidcDiscoveryResponse;
import io.qnop.api.v1.model.OidcProviderCreateRequest;
import io.qnop.api.v1.model.OidcProviderDto;
import io.qnop.api.v1.model.OidcProviderListResponse;
import io.qnop.api.v1.model.OidcProviderTypeDto;
import io.qnop.api.v1.model.OidcProviderUpdateRequest;
import io.qnop.service.oidc.OidcProviderConflictException;
import io.qnop.service.oidc.OidcProviderNotFoundException;
import io.qnop.service.oidc.OidcProviderService;
import io.qnop.service.oidc.OidcProviderService.OidcDiscoveryOutcome;
import io.qnop.service.oidc.OidcProviderService.OidcProviderPatch;
import io.qnop.service.oidc.OidcProviderView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Superadmin management of DB-configured OIDC/OAuth2 providers ({@code
 * /api/v1/admin/oidc-providers/**}), implementing the generated {@link AdminOidcProvidersApi}
 * (issue #21, PR A). Authorization is enforced by the security chain ({@code /api/v1/admin/**}
 * requires {@code ADMIN}). The {@code OidcProvider} entity never reaches this layer — the service
 * returns {@link OidcProviderView}s (with the client secret elided), which are mapped here to API
 * DTOs (ADR-0004). SSRF/validation failures surface as 400, unknown providers as 404.
 */
@RestController
public class OidcProviderController implements AdminOidcProvidersApi {

  private final OidcProviderService oidcProviders;

  public OidcProviderController(OidcProviderService oidcProviders) {
    this.oidcProviders = oidcProviders;
  }

  @Override
  public ResponseEntity<OidcProviderListResponse> listOidcProviders() {
    return ResponseEntity.ok(
        new OidcProviderListResponse()
            .providers(oidcProviders.findAll().stream().map(this::toDto).toList()));
  }

  @Override
  public ResponseEntity<OidcProviderDto> createOidcProvider(OidcProviderCreateRequest request) {
    final OidcProviderView created;
    try {
      created =
          oidcProviders.create(
              request.getName(),
              request.getProviderType().name(),
              request.getClientId(),
              request.getClientSecret(),
              request.getIssuerUri(),
              request.getScope(),
              request.getAuthorizationUri(),
              request.getTokenUri(),
              request.getUserInfoUri(),
              request.getJwkSetUri(),
              request.getUserNameAttribute(),
              request.getEmailAttribute(),
              request.getDisplayNameAttribute());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
  }

  /** Duplicate provider name → 409, consistent with the teams/users conflict responses (#184). */
  @ExceptionHandler(OidcProviderConflictException.class)
  public ResponseEntity<ErrorResponse> onConflict(OidcProviderConflictException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ErrorResponse()
                .code(ex.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<OidcDiscoveryResponse> discoverOidcEndpoints(OidcDiscoveryRequest request) {
    OidcDiscoveryOutcome outcome = oidcProviders.discoverEndpoints(request.getIssuerUri());
    return ResponseEntity.ok(
        new OidcDiscoveryResponse()
            .success(outcome.success())
            .authorizationUri(outcome.authorizationUri())
            .tokenUri(outcome.tokenUri())
            .userInfoUri(outcome.userInfoUri())
            .jwkSetUri(outcome.jwkSetUri())
            .error(outcome.error()));
  }

  @Override
  public ResponseEntity<OidcProviderDto> getOidcProvider(UUID providerId) {
    try {
      return ResponseEntity.ok(toDto(oidcProviders.findById(providerId)));
    } catch (OidcProviderNotFoundException e) {
      throw notFound(providerId);
    }
  }

  @Override
  public ResponseEntity<OidcProviderDto> updateOidcProvider(
      UUID providerId, OidcProviderUpdateRequest request) {
    OidcProviderPatch patch =
        new OidcProviderPatch(
            request.getEnabled(),
            request.getName(),
            request.getClientId(),
            request.getClientSecret(),
            request.getIssuerUri(),
            request.getScope(),
            request.getAuthorizationUri(),
            request.getTokenUri(),
            request.getUserInfoUri(),
            request.getJwkSetUri(),
            request.getUserNameAttribute(),
            request.getEmailAttribute(),
            request.getDisplayNameAttribute());
    try {
      return ResponseEntity.ok(toDto(oidcProviders.update(providerId, patch)));
    } catch (OidcProviderNotFoundException e) {
      throw notFound(providerId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @Override
  public ResponseEntity<Void> deleteOidcProvider(UUID providerId) {
    try {
      oidcProviders.delete(providerId);
    } catch (OidcProviderNotFoundException e) {
      throw notFound(providerId);
    }
    return ResponseEntity.noContent().build();
  }

  private ResponseStatusException notFound(UUID providerId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "Unknown OIDC provider: " + providerId);
  }

  private OidcProviderDto toDto(OidcProviderView v) {
    return new OidcProviderDto()
        .id(v.id())
        .name(v.name())
        .providerType(OidcProviderTypeDto.valueOf(v.providerType()))
        .enabled(v.enabled())
        .clientId(v.clientId())
        .hasClientSecret(v.hasClientSecret())
        .issuerUri(v.issuerUri())
        .scope(v.scope())
        .authorizationUri(v.authorizationUri())
        .tokenUri(v.tokenUri())
        .userInfoUri(v.userInfoUri())
        .jwkSetUri(v.jwkSetUri())
        .userNameAttribute(v.userNameAttribute())
        .emailAttribute(v.emailAttribute())
        .displayNameAttribute(v.displayNameAttribute())
        .createdAt(toOffset(v.createdAt()))
        .updatedAt(toOffset(v.updatedAt()));
  }

  private OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
