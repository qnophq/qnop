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
package io.qnop.service.oidc;

import io.qnop.entity.OidcProvider;
import io.qnop.entity.User;
import io.qnop.repository.OidcProviderRepository;
import java.util.UUID;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges a completed Spring Security OAuth2 login to a local qnop user (issue #21), keeping the
 * {@code OidcProvider}/{@code User} entities inside the service layer (ADR-0004). The web success
 * handler calls {@link #completeLogin} and mints the qnop session from the returned {@link
 * LoginResult#userId()}.
 */
@Service
public class OidcLoginService {

  private final OidcProviderRepository providers;
  private final OidcPrincipalResolver principalResolver;
  private final OidcIdentityService identityService;

  public OidcLoginService(
      OidcProviderRepository providers,
      OidcPrincipalResolver principalResolver,
      OidcIdentityService identityService) {
    this.providers = providers;
    this.principalResolver = principalResolver;
    this.identityService = identityService;
  }

  /** Resolves + provisions the user behind a successful OAuth2 login. */
  @Transactional
  public LoginResult completeLogin(OAuth2AuthenticationToken authentication, String accessToken) {
    String registrationId = authentication.getAuthorizedClientRegistrationId();
    UUID providerId =
        OidcRegistrationIds.toProviderId(registrationId)
            .orElseThrow(
                () -> new IllegalStateException("Unknown OIDC registrationId: " + registrationId));
    OidcProvider provider =
        providers
            .findById(providerId)
            .filter(OidcProvider::isEnabled)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "OIDC provider not found or disabled: " + providerId));

    ResolvedPrincipal principal = principalResolver.resolve(authentication, provider, accessToken);
    if (principal.subject() == null || principal.subject().isBlank()) {
      throw new IllegalStateException("OIDC login returned no subject for provider " + providerId);
    }
    User user = identityService.upsertOnLogin(provider, principal);
    return new LoginResult(user.getId(), principal.upstreamIdToken());
  }

  /** The local user behind a completed login, plus the upstream id_token (for future RP logout). */
  public record LoginResult(UUID userId, String upstreamIdToken) {}
}
