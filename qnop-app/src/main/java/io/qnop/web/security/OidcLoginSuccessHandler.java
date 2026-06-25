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

import io.qnop.security.QnopProperties;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.IssuedRefreshToken;
import io.qnop.service.oidc.OidcAccountDisabledException;
import io.qnop.service.oidc.OidcEmailMissingException;
import io.qnop.service.oidc.OidcLoginService;
import io.qnop.service.oidc.OidcLoginService.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes an OIDC/OAuth2 browser login (issue #21): provisions/links the local user, mints a qnop
 * refresh session (reusing the issue-#17 refresh-token + cookie machinery), and redirects to the
 * SPA. The access token is delivered by the SPA's subsequent {@code /api/v1/auth/refresh} call, so
 * it never travels in a URL. The provider's access token (used only for the GitHub email fallback)
 * is read from the {@link OAuth2AuthorizedClientService}.
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

  private final OidcLoginService oidcLoginService;
  private final RefreshTokenService refreshTokenService;
  private final RefreshTokenCookieFactory cookieFactory;
  private final OAuth2AuthorizedClientService authorizedClientService;
  private final QnopProperties properties;

  public OidcLoginSuccessHandler(
      OidcLoginService oidcLoginService,
      RefreshTokenService refreshTokenService,
      RefreshTokenCookieFactory cookieFactory,
      OAuth2AuthorizedClientService authorizedClientService,
      QnopProperties properties) {
    this.oidcLoginService = oidcLoginService;
    this.refreshTokenService = refreshTokenService;
    this.cookieFactory = cookieFactory;
    this.authorizedClientService = authorizedClientService;
    this.properties = properties;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
    try {
      LoginResult result = oidcLoginService.completeLogin(token, providerAccessToken(token));
      IssuedRefreshToken refresh = refreshTokenService.issue(result.userId());
      response.addHeader(
          HttpHeaders.SET_COOKIE,
          cookieFactory.build(refresh.plaintext(), refresh.maxAge()).toString());
      response.sendRedirect(frontendBase());
    } catch (RuntimeException e) {
      log.warn("OIDC login could not be completed: {}", e.getMessage());
      response.sendRedirect(frontendBase() + "/login?error=" + errorCode(e));
    }
  }

  /**
   * Maps a login failure to a stable, non-sensitive code the SPA turns into a localized message.
   * Only the category crosses the redirect URL — never the raw exception text.
   */
  private static String errorCode(RuntimeException e) {
    if (e instanceof OidcAccountDisabledException) {
      return "account_disabled";
    }
    if (e instanceof OidcEmailMissingException) {
      return "email_missing";
    }
    return "oidc";
  }

  private String providerAccessToken(OAuth2AuthenticationToken token) {
    OAuth2AuthorizedClient client =
        authorizedClientService.loadAuthorizedClient(
            token.getAuthorizedClientRegistrationId(), token.getName());
    return client == null || client.getAccessToken() == null
        ? null
        : client.getAccessToken().getTokenValue();
  }

  private String frontendBase() {
    List<String> origins = properties.cors().allowedOrigins();
    if (origins == null || origins.isEmpty()) {
      return "";
    }
    String base = origins.get(0);
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
