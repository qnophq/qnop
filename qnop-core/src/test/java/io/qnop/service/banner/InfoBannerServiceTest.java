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
package io.qnop.service.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning banner settings into a banner, or into nothing (issue #664).
 *
 * <p>The interesting cases are all the half-configured ones: a banner switched on before anyone
 * wrote the text, a link label with no target. Each has an obvious wrong answer — an empty bar
 * across every page, an anchor that goes nowhere — and this is where they are ruled out.
 */
class InfoBannerServiceTest {

  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
  private final InfoBannerService banners = new InfoBannerService(settings);

  private void appBanner(boolean enabled, String severity, String text, String label, String url) {
    when(settings.getBoolean(ApplicationSettingKey.BANNER_APP_ENABLED)).thenReturn(enabled);
    when(settings.getString(ApplicationSettingKey.BANNER_APP_SEVERITY)).thenReturn(severity);
    when(settings.getString(ApplicationSettingKey.BANNER_APP_TEXT)).thenReturn(text);
    when(settings.getString(ApplicationSettingKey.BANNER_APP_LINK_LABEL)).thenReturn(label);
    when(settings.getString(ApplicationSettingKey.BANNER_APP_LINK_URL)).thenReturn(url);
  }

  @Test
  @DisplayName("an enabled banner with text and a link resolves fully")
  void resolvesAConfiguredBanner() {
    appBanner(
        true, "warning", "  Maintenance on Saturday.  ", "Status page", "https://status.example");

    Optional<InfoBannerView> banner = banners.inApp();

    assertThat(banner)
        .contains(
            new InfoBannerView(
                "warning", "Maintenance on Saturday.", "Status page", "https://status.example"));
  }

  @Test
  @DisplayName("switched off is nothing, whatever the text says")
  void disabledYieldsNothing() {
    appBanner(false, "info", "Maintenance on Saturday.", "", "");

    assertThat(banners.inApp()).isEmpty();
  }

  @Test
  @DisplayName("enabled with no text is nothing — an empty bar says less than none")
  void blankTextYieldsNothing() {
    appBanner(true, "info", "   ", "Status page", "https://status.example");

    assertThat(banners.inApp()).isEmpty();
  }

  @Test
  @DisplayName("half a link is no link, in either direction")
  void halfALinkIsDropped() {
    appBanner(true, "info", "Something happened.", "Status page", "");
    assertThat(banners.inApp())
        .contains(new InfoBannerView("info", "Something happened.", null, null));

    appBanner(true, "info", "Something happened.", "", "https://status.example");
    assertThat(banners.inApp())
        .contains(new InfoBannerView("info", "Something happened.", null, null));
  }

  @Test
  @DisplayName("the two placements read their own keys and do not borrow each other's")
  void placementsAreIndependent() {
    when(settings.getBoolean(ApplicationSettingKey.BANNER_APP_ENABLED)).thenReturn(false);
    when(settings.getBoolean(ApplicationSettingKey.BANNER_LOGIN_ENABLED)).thenReturn(true);
    when(settings.getString(ApplicationSettingKey.BANNER_LOGIN_SEVERITY)).thenReturn("info");
    when(settings.getString(ApplicationSettingKey.BANNER_LOGIN_TEXT))
        .thenReturn("Demo installation — sign in with demo@qnop.io / demo");
    when(settings.getString(ApplicationSettingKey.BANNER_LOGIN_LINK_LABEL)).thenReturn("");
    when(settings.getString(ApplicationSettingKey.BANNER_LOGIN_LINK_URL)).thenReturn("");

    // The whole point of two key sets: a public demo notice must not imply that
    // signed-in users are told anything, and vice versa.
    assertThat(banners.signIn()).isPresent();
    assertThat(banners.inApp()).isEmpty();
  }
}
