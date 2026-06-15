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
import io.qnop.entity.OidcProviderType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Maps a Spring Security OAuth2/OIDC principal onto the provider-agnostic {@link ResolvedPrincipal}
 * (issue #21), applying the provider's attribute mappings and per-type conventions:
 *
 * <ul>
 *   <li><b>OIDC / GOOGLE</b> — the {@link OidcUser} carries a standard {@code sub}/{@code
 *       email}/{@code name} and the upstream id_token.
 *   <li><b>GITHUB</b> — userinfo uses {@code id}; the email is often absent and is then fetched via
 *       {@link GitHubEmailFetcher} using the user's access token.
 *   <li><b>FACEBOOK / OAUTH2 (generic)</b> — resolved from the configured attribute names.
 * </ul>
 */
@Component
public class OidcPrincipalResolver {

  private final GitHubEmailFetcher gitHubEmailFetcher;

  public OidcPrincipalResolver(GitHubEmailFetcher gitHubEmailFetcher) {
    this.gitHubEmailFetcher = gitHubEmailFetcher;
  }

  /** Resolves the principal; {@code accessToken} is used only for the GitHub email fallback. */
  public ResolvedPrincipal resolve(
      OAuth2AuthenticationToken authentication, OidcProvider provider, String accessToken) {
    OAuth2User user = authentication.getPrincipal();
    if (user instanceof OidcUser oidc) {
      return resolveOidc(oidc, provider);
    }
    return resolveOAuth2(user, provider, accessToken);
  }

  private ResolvedPrincipal resolveOidc(OidcUser oidc, OidcProvider provider) {
    String subject = firstNonBlank(attr(oidc, provider.getUserNameAttribute()), oidc.getSubject());
    String email = firstNonBlank(oidc.getEmail(), attr(oidc, provider.getEmailAttribute()));
    String displayName =
        firstNonBlank(
            oidc.getFullName(),
            attr(oidc, provider.getDisplayNameAttribute()),
            oidc.getPreferredUsername());
    String idToken = oidc.getIdToken() == null ? null : oidc.getIdToken().getTokenValue();
    return new ResolvedPrincipal(subject, email, displayName, idToken);
  }

  private ResolvedPrincipal resolveOAuth2(
      OAuth2User user, OidcProvider provider, String accessToken) {
    String subjectAttr = orDefault(provider.getUserNameAttribute(), defaultSubjectAttr(provider));
    Object rawSubject = user.getAttributes().get(subjectAttr);
    String subject = rawSubject == null ? null : String.valueOf(rawSubject);

    String email = attr(user, orDefault(provider.getEmailAttribute(), "email"));
    if (email == null && provider.getProviderType() == OidcProviderType.GITHUB) {
      email = gitHubEmailFetcher.fetchPrimaryEmail(accessToken);
    }
    String displayName =
        firstNonBlank(
            attr(user, orDefault(provider.getDisplayNameAttribute(), "name")), attr(user, "login"));
    return new ResolvedPrincipal(subject, email, displayName, null);
  }

  private static String defaultSubjectAttr(OidcProvider provider) {
    return switch (provider.getProviderType()) {
      case GITHUB, FACEBOOK -> "id";
      default -> "sub";
    };
  }

  private static String attr(OAuth2User user, String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    Object value = user.getAttributes().get(name);
    return value == null ? null : String.valueOf(value);
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
