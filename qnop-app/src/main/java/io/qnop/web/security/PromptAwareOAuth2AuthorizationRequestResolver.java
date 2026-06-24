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

import io.qnop.service.oidc.OidcProviderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Adds the OIDC {@code prompt} query parameter to the upstream authorization request when the
 * operator picked "Use a different account" on the login page (issue #106).
 *
 * <p>Spring's default resolver sends no {@code prompt}, so a logged-out qnop user clicking "Sign in
 * with Google" is silently re-authenticated as the same upstream account (the IdP session cookie is
 * still valid). The SPA renders a "Use a different account" link pointing at {@code
 * /oauth2/authorization/{id}?prompt=…}; this resolver validates that {@code prompt} strictly and
 * forwards it as {@code additionalParameters["prompt"]}.
 *
 * <p><strong>Allow-list:</strong> only {@code select_account} (OIDC/Google/Facebook) and {@code
 * login} (generic OAuth2) reach the upstream — anything else (consent, none, arbitrary or repeated
 * values) is dropped silently. This is the only defence against a caller injecting upstream
 * parameters via the query string.
 *
 * <p><strong>GitHub carve-out:</strong> GitHub ignores {@code prompt}; for a GitHub provider the
 * request passes through unchanged (the SPA mirrors this by rendering a sign-out hint instead of a
 * link). The type lookup is delegated to {@link OidcProviderService#honoursPrompt(UUID)} so the web
 * layer never touches the repository or the entity enum (ADR-0004).
 *
 * <p><strong>PKCE / state preservation:</strong> {@link OAuth2AuthorizationRequest#from} copies
 * every other field — the PKCE {@code code_verifier} (in {@code attributes}), {@code state}, the
 * resolved redirect URI, and the scopes — so adding {@code prompt} cannot drop them.
 */
@Component
public class PromptAwareOAuth2AuthorizationRequestResolver
    implements OAuth2AuthorizationRequestResolver {

  static final String DEFAULT_AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";

  /** Spring's attribute key carrying the registration id on the resolved request. */
  static final String REGISTRATION_ID_ATTR = "registration_id";

  /** The closed set of {@code prompt} values forwarded upstream. */
  static final Set<String> ALLOWED_PROMPT_VALUES = Set.of("select_account", "login");

  private final DefaultOAuth2AuthorizationRequestResolver delegate;
  private final OidcProviderService providers;

  public PromptAwareOAuth2AuthorizationRequestResolver(
      ClientRegistrationRepository clientRegistrationRepository, OidcProviderService providers) {
    this.delegate =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    this.providers = providers;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    OAuth2AuthorizationRequest resolved = delegate.resolve(request);
    return resolved == null ? null : maybeAddPrompt(request, resolved);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      HttpServletRequest request, String clientRegistrationId) {
    OAuth2AuthorizationRequest resolved = delegate.resolve(request, clientRegistrationId);
    return resolved == null ? null : maybeAddPrompt(request, resolved);
  }

  OAuth2AuthorizationRequest maybeAddPrompt(
      HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
    String prompt = whitelistedPrompt(request);
    if (prompt == null) {
      return resolved;
    }
    if (!(resolved.getAttributes().get(REGISTRATION_ID_ATTR) instanceof String registrationId)) {
      return resolved;
    }
    UUID providerId;
    try {
      providerId = UUID.fromString(registrationId);
    } catch (IllegalArgumentException e) {
      return resolved;
    }
    if (!providers.honoursPrompt(providerId)) {
      return resolved;
    }
    return OAuth2AuthorizationRequest.from(resolved)
        .additionalParameters(params -> params.put("prompt", prompt))
        .build();
  }

  /**
   * Returns the inbound {@code ?prompt=…} only when it is exactly one allow-listed value. Null for
   * a missing, unknown, empty, or repeated parameter (pollution defence).
   */
  private static String whitelistedPrompt(HttpServletRequest request) {
    String[] values = request.getParameterValues("prompt");
    if (values == null || values.length != 1) {
      return null;
    }
    return ALLOWED_PROMPT_VALUES.contains(values[0]) ? values[0] : null;
  }
}
