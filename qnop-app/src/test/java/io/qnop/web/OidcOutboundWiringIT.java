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

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.service.oidc.GitHubEmailFetcher;
import io.qnop.service.oidc.OidcHttpTransport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The OIDC/OAuth2 login flow uses the SSRF-guarded HTTP clients (issue #904): the {@code
 * oauth2Login} configurer must have picked up the guarded token client, user-info services and
 * ID-token decoder factory rather than building Spring Security's unguarded defaults. Requires
 * Docker.
 */
class OidcOutboundWiringIT extends AbstractIntegrationTest {

  @Autowired List<SecurityFilterChain> chains;

  @Autowired
  OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> guardedTokenClient;

  @Autowired OAuth2UserService<OAuth2UserRequest, OAuth2User> guardedUserService;
  @Autowired OAuth2UserService<OidcUserRequest, OidcUser> guardedOidcUserService;
  @Autowired JwtDecoderFactory<ClientRegistration> guardedDecoderFactory;
  @Autowired GitHubEmailFetcher gitHubEmailFetcher;
  @Autowired OidcHttpTransport oidcTransport;

  @Test
  @DisplayName("the login filter's providers use the guarded token, user-info and JWK clients")
  void loginFlowUsesGuardedClients() {
    List<AuthenticationProvider> providers = loginProviders();

    OidcAuthorizationCodeAuthenticationProvider oidc =
        only(providers, OidcAuthorizationCodeAuthenticationProvider.class);
    assertThat(ReflectionTestUtils.getField(oidc, "accessTokenResponseClient"))
        .isSameAs(guardedTokenClient);
    assertThat(ReflectionTestUtils.getField(oidc, "userService")).isSameAs(guardedOidcUserService);
    assertThat(ReflectionTestUtils.getField(oidc, "jwtDecoderFactory"))
        .isSameAs(guardedDecoderFactory);

    OAuth2LoginAuthenticationProvider oauth2 =
        only(providers, OAuth2LoginAuthenticationProvider.class);
    assertThat(ReflectionTestUtils.getField(oauth2, "userService")).isSameAs(guardedUserService);
    Object codeProvider =
        ReflectionTestUtils.getField(oauth2, "authorizationCodeAuthenticationProvider");
    assertThat(ReflectionTestUtils.getField(codeProvider, "accessTokenResponseClient"))
        .isSameAs(guardedTokenClient);
  }

  @Test
  @DisplayName("the GitHub e-mail lookup uses the guarded OIDC transport (issue #946)")
  void gitHubEmailLookupUsesGuardedTransport() {
    Object restClient = ReflectionTestUtils.getField(gitHubEmailFetcher, "restClient");

    assertThat(ReflectionTestUtils.getField(restClient, "clientRequestFactory"))
        .isSameAs(oidcTransport.requestFactory());
    assertThat(ReflectionTestUtils.getField(gitHubEmailFetcher, "endpoint"))
        .isEqualTo("https://api.github.com/user/emails");
  }

  private List<AuthenticationProvider> loginProviders() {
    OAuth2LoginAuthenticationFilter filter =
        chains.stream()
            .flatMap(chain -> chain.getFilters().stream())
            .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
            .map(OAuth2LoginAuthenticationFilter.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no OAuth2 login filter in any chain"));
    return providers(ReflectionTestUtils.getField(filter, "authenticationManager"));
  }

  /** Unwraps the observation decorator (present with Actuator) down to the provider list. */
  private static List<AuthenticationProvider> providers(Object manager) {
    if (manager instanceof ProviderManager providerManager) {
      return providerManager.getProviders();
    }
    return providers(ReflectionTestUtils.getField(manager, "delegate"));
  }

  private static <T> T only(List<AuthenticationProvider> providers, Class<T> type) {
    return providers.stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " configured"));
  }
}
