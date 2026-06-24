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

/**
 * Derives the public login-button projection ({@link OidcProviderLoginView}) for an enabled OIDC
 * provider (issue #21/#106). Pure, DB-free, and unit-testable per the architecture guardrail —
 * keeps the icon/account-switch rules out of the {@code @Transactional} service methods.
 *
 * <p>The {@code accountPickerLoginUrl} appends an allow-listed {@code prompt} that {@link
 * io.qnop.web.security.PromptAwareOAuth2AuthorizationRequestResolver} forwards upstream; the
 * derivation here must stay in lock-step with that resolver's allow-list.
 */
public final class OidcLoginInfoFactory {

  /** Spring's OAuth2 authorization-start base path; the registration id is the provider UUID. */
  private static final String AUTHORIZATION_BASE = "/oauth2/authorization/";

  /**
   * GitHub honours no {@code prompt} parameter, so there is no in-product account picker; the SPA
   * instead links to GitHub's sign-out as an honest fallback.
   */
  private static final String GITHUB_SIGN_OUT_URL = "https://github.com/logout";

  private OidcLoginInfoFactory() {}

  /** Builds the login-page projection for one (already-enabled) provider. */
  public static OidcProviderLoginView loginView(OidcProvider provider) {
    String loginUrl = AUTHORIZATION_BASE + provider.getId();
    OidcProviderType type = provider.getProviderType();
    return new OidcProviderLoginView(
        provider.getId().toString(),
        provider.getName(),
        loginUrl,
        iconKind(type),
        accountPickerLoginUrl(type, loginUrl),
        accountSwitchHintUrl(type));
  }

  /** Brand glyph key for the SPA; decoupled 1:1 from the provider type. */
  static String iconKind(OidcProviderType type) {
    return switch (type) {
      case GITHUB -> "github";
      case GOOGLE -> "google";
      case FACEBOOK -> "facebook";
      case OIDC -> "oidc";
      case OAUTH2 -> "oauth2";
    };
  }

  /**
   * The "use a different account" URL, or {@code null} when the provider cannot honour a {@code
   * prompt}. {@code select_account} pops the picker (OIDC/Google/Facebook); {@code login} forces a
   * fresh upstream auth (generic OAuth2); GitHub has neither.
   */
  static String accountPickerLoginUrl(OidcProviderType type, String loginUrl) {
    return switch (type) {
      case OIDC, GOOGLE, FACEBOOK -> loginUrl + "?prompt=select_account";
      case OAUTH2 -> loginUrl + "?prompt=login";
      case GITHUB -> null;
    };
  }

  /** Upstream sign-out URL fallback — today only GitHub, which ignores {@code prompt}. */
  static String accountSwitchHintUrl(OidcProviderType type) {
    return type == OidcProviderType.GITHUB ? GITHUB_SIGN_OUT_URL : null;
  }
}
