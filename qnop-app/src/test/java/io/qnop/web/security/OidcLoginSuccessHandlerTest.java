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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.observability.metrics.AuthMeters;
import io.qnop.security.QnopProperties;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.IssuedRefreshToken;
import io.qnop.service.oidc.OidcAccountDisabledException;
import io.qnop.service.oidc.OidcLoginService;
import io.qnop.service.oidc.OidcLoginService.LoginResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

/** How a completed provider login is counted (issue #962): once, and never by provider name. */
class OidcLoginSuccessHandlerTest {

  private final OidcLoginService logins = mock(OidcLoginService.class);
  private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
  private final RefreshTokenCookieFactory cookies = mock(RefreshTokenCookieFactory.class);
  private final QnopProperties properties = mock(QnopProperties.class, RETURNS_DEEP_STUBS);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OidcLoginSuccessHandler handler =
      new OidcLoginSuccessHandler(
          logins,
          refreshTokens,
          cookies,
          mock(OAuth2AuthorizedClientService.class),
          properties,
          new AuthMeters(registry));
  private final OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach
  void sameOriginFrontend() {
    when(properties.auth().oidc().frontendBaseUrl()).thenReturn("");
    when(token.getAuthorizedClientRegistrationId()).thenReturn("some-provider");
    when(token.getName()).thenReturn("subject");
  }

  @Test
  @DisplayName("a completed login is one success, tagged with the method only")
  void successIsCountedOnce() throws Exception {
    when(logins.completeLogin(any(), any())).thenReturn(new LoginResult(UUID.randomUUID(), null));
    when(refreshTokens.issue(any()))
        .thenReturn(new IssuedRefreshToken("plain", Instant.now(), Duration.ofDays(1)));
    when(cookies.build(any(), any())).thenReturn(ResponseCookie.from("qnop_refresh", "x").build());

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(logins("success")).isEqualTo(1.0);
    assertThat(registry.find(AuthMeters.LOGIN).counters()).hasSize(1);
    // The registration id is an administrator's free text; it must not become a series.
    assertThat(registry.find(AuthMeters.LOGIN).counter().getId().getTags())
        .extracting(tag -> tag.getKey())
        .containsExactlyInAnyOrder("method", "outcome");
  }

  @Test
  @DisplayName("a disabled account is rejected: the account's doing, not a fault")
  void disabledAccountIsRejected() throws Exception {
    when(logins.completeLogin(any(), any())).thenThrow(new OidcAccountDisabledException("off"));

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=account_disabled");
    assertThat(logins("rejected")).isEqualTo(1.0);
    assertThat(logins("error")).isZero();
  }

  @Test
  @DisplayName("anything else that stops the login is an error")
  void otherFailuresAreErrors() throws Exception {
    when(logins.completeLogin(any(), any())).thenThrow(new IllegalStateException("database down"));

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=oidc");
    assertThat(logins("error")).isEqualTo(1.0);
    assertThat(logins("success")).isZero();
  }

  private double logins(String outcome) {
    var counter =
        registry.find(AuthMeters.LOGIN).tag("method", "oidc").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
