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
package io.qnop.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import io.qnop.service.oidc.OidcProviderService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * Unit tests for {@link PromptAwareOAuth2AuthorizationRequestResolver}. Exercises the prompt
 * allow-list, the GitHub carve-out, and — most importantly — that adding {@code prompt} preserves
 * the PKCE {@code code_verifier}, {@code state}, redirect URI, and scopes (issue #106).
 */
@ExtendWith(MockitoExtension.class)
class PromptAwareOAuth2AuthorizationRequestResolverTest {

  private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock private ClientRegistrationRepository clientRegistrationRepository;
  @Mock private OidcProviderService providers;

  private PromptAwareOAuth2AuthorizationRequestResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new PromptAwareOAuth2AuthorizationRequestResolver(clientRegistrationRepository, providers);
  }

  @Test
  @DisplayName("forwards an allow-listed prompt and preserves PKCE, state, redirect, and scopes")
  void addsPrompt_andPreservesCriticalFields() {
    lenient().when(providers.honoursPrompt(PROVIDER_ID)).thenReturn(true);
    OAuth2AuthorizationRequest base = baseRequest(PROVIDER_ID.toString());

    OAuth2AuthorizationRequest result =
        resolver.maybeAddPrompt(requestWithPrompt("select_account"), base);

    assertThat(result.getAdditionalParameters()).containsEntry("prompt", "select_account");
    assertThat(result.getAttributes()).containsEntry(PkceParameterNames.CODE_VERIFIER, "verifier");
    assertThat(result.getState()).isEqualTo("state-xyz");
    assertThat(result.getRedirectUri()).isEqualTo(base.getRedirectUri());
    assertThat(result.getScopes()).containsExactlyInAnyOrder("openid", "email");
  }

  @Test
  @DisplayName("drops a non-allow-listed prompt value")
  void dropsUnknownPrompt() {
    OAuth2AuthorizationRequest base = baseRequest(PROVIDER_ID.toString());

    OAuth2AuthorizationRequest result = resolver.maybeAddPrompt(requestWithPrompt("consent"), base);

    assertThat(result).isSameAs(base);
    assertThat(result.getAdditionalParameters()).doesNotContainKey("prompt");
  }

  @Test
  @DisplayName("drops a repeated prompt parameter (pollution defence)")
  void dropsRepeatedPrompt() {
    OAuth2AuthorizationRequest base = baseRequest(PROVIDER_ID.toString());

    OAuth2AuthorizationRequest result =
        resolver.maybeAddPrompt(requestWithPrompt("select_account", "login"), base);

    assertThat(result).isSameAs(base);
  }

  @Test
  @DisplayName("passes a GitHub provider through unchanged (it ignores prompt)")
  void githubPassesThrough() {
    lenient().when(providers.honoursPrompt(PROVIDER_ID)).thenReturn(false);
    OAuth2AuthorizationRequest base = baseRequest(PROVIDER_ID.toString());

    OAuth2AuthorizationRequest result =
        resolver.maybeAddPrompt(requestWithPrompt("select_account"), base);

    assertThat(result).isSameAs(base);
    assertThat(result.getAdditionalParameters()).doesNotContainKey("prompt");
  }

  @Test
  @DisplayName("leaves the request untouched when the registration id is not a UUID")
  void malformedRegistrationId() {
    OAuth2AuthorizationRequest base = baseRequest("not-a-uuid");

    OAuth2AuthorizationRequest result =
        resolver.maybeAddPrompt(requestWithPrompt("select_account"), base);

    assertThat(result).isSameAs(base);
  }

  private static OAuth2AuthorizationRequest baseRequest(String registrationId) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://idp.example.com/authorize")
        .clientId("client-123")
        .redirectUri("https://app.example.com/login/oauth2/code/" + registrationId)
        .scopes(Set.of("openid", "email"))
        .state("state-xyz")
        .attributes(
            attrs -> {
              attrs.put("registration_id", registrationId);
              attrs.put(PkceParameterNames.CODE_VERIFIER, "verifier");
            })
        .build();
  }

  private static MockHttpServletRequest requestWithPrompt(String... prompts) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("prompt", prompts);
    return request;
  }
}
