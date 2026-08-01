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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two banners end to end (issue #664), and above all the line between them: the sign-in notice
 * is public, the in-app notice is not.
 */
class BannerApiIT extends SeededIntegrationTest {

  @Autowired private ApplicationSettingsService settings;

  /**
   * {@code application_setting} survives {@code clean.sql} (it is migration-seeded) and the runtime
   * snapshot is JVM-wide, so a banner left switched on would appear in every later test.
   */
  @AfterEach
  void restoreDefaults() {
    settings.update(
        Map.of(
            ApplicationSettingKey.BANNER_APP_ENABLED.getKey(), "false",
            ApplicationSettingKey.BANNER_APP_TEXT.getKey(), "",
            ApplicationSettingKey.BANNER_APP_LINK_LABEL.getKey(), "",
            ApplicationSettingKey.BANNER_APP_LINK_URL.getKey(), "",
            ApplicationSettingKey.BANNER_LOGIN_ENABLED.getKey(), "false",
            ApplicationSettingKey.BANNER_LOGIN_TEXT.getKey(), ""),
        null);
  }

  @Test
  @DisplayName("no banner configured: the endpoint answers, with nothing in it")
  void emptyByDefault() throws Exception {
    mockMvc
        .perform(get("/api/v1/banner").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banner").doesNotExist());
  }

  @Test
  @DisplayName("an enabled in-app banner reaches signed-in users, link and all")
  void servesTheInAppBanner() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.BANNER_APP_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_APP_SEVERITY.getKey(), "warning",
            ApplicationSettingKey.BANNER_APP_TEXT.getKey(), "Maintenance on Saturday 20:00 UTC.",
            ApplicationSettingKey.BANNER_APP_LINK_LABEL.getKey(), "Status page",
            ApplicationSettingKey.BANNER_APP_LINK_URL.getKey(), "https://status.example"),
        null);

    mockMvc
        .perform(get("/api/v1/banner").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banner.severity").value("warning"))
        .andExpect(jsonPath("$.banner.message").value("Maintenance on Saturday 20:00 UTC."))
        .andExpect(jsonPath("$.banner.linkLabel").value("Status page"))
        .andExpect(jsonPath("$.banner.linkUrl").value("https://status.example"));
  }

  @Test
  @DisplayName("the in-app banner is not readable without a token")
  void inAppBannerRequiresAuthentication() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.BANNER_APP_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_APP_TEXT.getKey(), "The extraction pipeline is degraded."),
        null);

    // The reason this endpoint exists separately: what is wrong with a deployment
    // is for the people signed in to it.
    mockMvc.perform(get("/api/v1/banner")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the sign-in banner rides in the public config, and only that one does")
  void signInBannerIsPublicAndSeparate() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.BANNER_LOGIN_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_LOGIN_SEVERITY.getKey(), "info",
            ApplicationSettingKey.BANNER_LOGIN_TEXT.getKey(),
                "Demo installation — sign in with demo@qnop.io / demo",
            ApplicationSettingKey.BANNER_APP_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_APP_TEXT.getKey(), "The extraction pipeline is degraded."),
        null);

    // Anonymous, as the login screen is.
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banner.severity").value("info"))
        .andExpect(
            jsonPath("$.banner.message")
                .value("Demo installation — sign in with demo@qnop.io / demo"))
        // No link configured — the field is absent rather than empty.
        .andExpect(jsonPath("$.banner.linkUrl").doesNotExist())
        // And the in-app banner, enabled at the same time, is nowhere in this response.
        .andExpect(jsonPath("$.appBanner").doesNotExist());
  }

  @Test
  @DisplayName("enabled but empty stays invisible on both surfaces")
  void enabledWithoutTextShowsNothing() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.BANNER_LOGIN_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_LOGIN_TEXT.getKey(), "   ",
            ApplicationSettingKey.BANNER_APP_ENABLED.getKey(), "true",
            ApplicationSettingKey.BANNER_APP_TEXT.getKey(), ""),
        null);

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banner").doesNotExist());
    mockMvc
        .perform(get("/api/v1/banner").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banner").doesNotExist());
  }
}
