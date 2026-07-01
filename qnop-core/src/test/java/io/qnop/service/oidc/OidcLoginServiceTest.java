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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.OidcProvider;
import io.qnop.entity.User;
import io.qnop.repository.OidcProviderRepository;
import io.qnop.service.oidc.OidcLoginService.LoginResult;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

/**
 * Unit tests for the OIDC login bridge (issue #21/#106). Pins the security gate: a disabled local
 * account must be rejected on the OIDC path, mirroring the local login.
 */
@ExtendWith(MockitoExtension.class)
class OidcLoginServiceTest {

  @Mock private OidcProviderRepository providers;
  @Mock private OidcPrincipalResolver principalResolver;
  @Mock private OidcIdentityService identityService;
  @Mock private OAuth2AuthenticationToken authentication;

  private OidcLoginService service;

  @BeforeEach
  void setUp() {
    service = new OidcLoginService(providers, principalResolver, identityService);
  }

  @Test
  @DisplayName("rejects the login when the linked account is disabled")
  void rejectsDisabledAccount() {
    UUID providerId = UUID.randomUUID();
    OidcProvider provider = mock(OidcProvider.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.getName()).thenReturn("keycloak");
    User user = mock(User.class);
    when(user.isEnabled()).thenReturn(false);
    ResolvedPrincipal principal = new ResolvedPrincipal("sub-1", "bob@example.com", "Bob", null);

    when(authentication.getAuthorizedClientRegistrationId()).thenReturn(providerId.toString());
    when(providers.findById(providerId)).thenReturn(Optional.of(provider));
    when(principalResolver.resolve(authentication, provider, "tok")).thenReturn(principal);
    when(identityService.upsertOnLogin(provider, principal)).thenReturn(user);

    assertThatThrownBy(() -> service.completeLogin(authentication, "tok"))
        .isInstanceOf(OidcAccountDisabledException.class);
  }

  @Test
  @DisplayName("completes the login for an enabled account")
  void completesForEnabledAccount() {
    UUID providerId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OidcProvider provider = mock(OidcProvider.class);
    when(provider.isEnabled()).thenReturn(true);
    User user = mock(User.class);
    when(user.isEnabled()).thenReturn(true);
    when(user.getId()).thenReturn(userId);
    ResolvedPrincipal principal = new ResolvedPrincipal("sub-1", "bob@example.com", "Bob", "idtok");

    when(authentication.getAuthorizedClientRegistrationId()).thenReturn(providerId.toString());
    when(providers.findById(providerId)).thenReturn(Optional.of(provider));
    when(principalResolver.resolve(authentication, provider, "tok")).thenReturn(principal);
    when(identityService.upsertOnLogin(provider, principal)).thenReturn(user);
    lenient().when(provider.getName()).thenReturn("keycloak");

    LoginResult result = service.completeLogin(authentication, "tok");

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.upstreamIdToken()).isEqualTo("idtok");
  }

  @Test
  @DisplayName("rejects a registrationId that does not resolve to a provider id")
  void rejectsUnparseableRegistrationId() {
    when(authentication.getAuthorizedClientRegistrationId()).thenReturn("not-a-uuid");

    assertThatThrownBy(() -> service.completeLogin(authentication, "tok"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("rejects a login for an unknown provider id")
  void rejectsUnknownProvider() {
    UUID providerId = UUID.randomUUID();
    when(authentication.getAuthorizedClientRegistrationId()).thenReturn(providerId.toString());
    when(providers.findById(providerId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.completeLogin(authentication, "tok"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("rejects a replayed callback for a disabled provider before provisioning")
  void rejectsDisabledProvider() {
    UUID providerId = UUID.randomUUID();
    OidcProvider provider = mock(OidcProvider.class);
    when(provider.isEnabled()).thenReturn(false);
    when(authentication.getAuthorizedClientRegistrationId()).thenReturn(providerId.toString());
    when(providers.findById(providerId)).thenReturn(Optional.of(provider));

    assertThatThrownBy(() -> service.completeLogin(authentication, "tok"))
        .isInstanceOf(IllegalStateException.class);
  }
}
