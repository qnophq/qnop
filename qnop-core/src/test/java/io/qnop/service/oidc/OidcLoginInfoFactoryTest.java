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

import io.qnop.entity.OidcProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure login-button derivation (issue #106). Pins the icon mapping and the
 * account-switch rules — they must stay in lock-step with {@code
 * PromptAwareOAuth2AuthorizationRequestResolver}'s prompt allow-list.
 */
class OidcLoginInfoFactoryTest {

  private static final String LOGIN_URL = "/oauth2/authorization/p1";

  @Test
  @DisplayName("iconKind maps each provider type to its brand glyph key")
  void iconKind_mapsEachType() {
    assertThat(OidcLoginInfoFactory.iconKind(OidcProviderType.GITHUB)).isEqualTo("github");
    assertThat(OidcLoginInfoFactory.iconKind(OidcProviderType.GOOGLE)).isEqualTo("google");
    assertThat(OidcLoginInfoFactory.iconKind(OidcProviderType.FACEBOOK)).isEqualTo("facebook");
    assertThat(OidcLoginInfoFactory.iconKind(OidcProviderType.OIDC)).isEqualTo("oidc");
    assertThat(OidcLoginInfoFactory.iconKind(OidcProviderType.OAUTH2)).isEqualTo("oauth2");
  }

  @Test
  @DisplayName("OIDC-style providers get a select_account account picker")
  void accountPicker_selectAccountForIdpStyleProviders() {
    assertThat(OidcLoginInfoFactory.accountPickerLoginUrl(OidcProviderType.OIDC, LOGIN_URL))
        .isEqualTo(LOGIN_URL + "?prompt=select_account");
    assertThat(OidcLoginInfoFactory.accountPickerLoginUrl(OidcProviderType.GOOGLE, LOGIN_URL))
        .isEqualTo(LOGIN_URL + "?prompt=select_account");
    assertThat(OidcLoginInfoFactory.accountPickerLoginUrl(OidcProviderType.FACEBOOK, LOGIN_URL))
        .isEqualTo(LOGIN_URL + "?prompt=select_account");
  }

  @Test
  @DisplayName("generic OAuth2 gets a login (re-auth) prompt instead of a picker")
  void accountPicker_loginPromptForGenericOauth2() {
    assertThat(OidcLoginInfoFactory.accountPickerLoginUrl(OidcProviderType.OAUTH2, LOGIN_URL))
        .isEqualTo(LOGIN_URL + "?prompt=login");
  }

  @Test
  @DisplayName("GitHub has no account picker but gets a sign-out hint")
  void github_noPickerButHasSignOutHint() {
    assertThat(OidcLoginInfoFactory.accountPickerLoginUrl(OidcProviderType.GITHUB, LOGIN_URL))
        .isNull();
    assertThat(OidcLoginInfoFactory.accountSwitchHintUrl(OidcProviderType.GITHUB))
        .isEqualTo("https://github.com/logout");
  }

  @Test
  @DisplayName("non-GitHub providers carry no sign-out hint")
  void nonGithub_noSignOutHint() {
    assertThat(OidcLoginInfoFactory.accountSwitchHintUrl(OidcProviderType.OIDC)).isNull();
    assertThat(OidcLoginInfoFactory.accountSwitchHintUrl(OidcProviderType.GOOGLE)).isNull();
    assertThat(OidcLoginInfoFactory.accountSwitchHintUrl(OidcProviderType.FACEBOOK)).isNull();
    assertThat(OidcLoginInfoFactory.accountSwitchHintUrl(OidcProviderType.OAUTH2)).isNull();
  }
}
