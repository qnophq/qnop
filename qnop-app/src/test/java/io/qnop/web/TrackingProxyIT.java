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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;

/**
 * The measurement endpoints as the browser meets them (issue #666).
 *
 * <p>No real analytics backend is involved and none is needed: what has to hold here is that the
 * endpoints exist without authentication, that they answer nothing at all while measurement is off,
 * and that a configured deployment reports itself through {@code /config} — the forwarding itself
 * is asserted where it lives, in the unit tests.
 */
class TrackingProxyIT extends SeededIntegrationTest {

  @Autowired private ApplicationSettingsService settings;

  @AfterEach
  void restoreDefaults() {
    settings.update(
        Map.of(
            ApplicationSettingKey.TRACKING_ENABLED.getKey(), "false",
            ApplicationSettingKey.TRACKING_PROVIDER.getKey(), "none",
            ApplicationSettingKey.TRACKING_HOST.getKey(), "",
            ApplicationSettingKey.TRACKING_SITE_ID.getKey(), ""),
        null);
  }

  @Test
  @DisplayName("with tracking off there is no script to serve")
  void scriptIsAbsentWhileOff() throws Exception {
    // Not 401, not 500: a deployment that measures nothing simply has no script,
    // and the client asks for none because /config told it so.
    mockMvc.perform(get("/t/s.js")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a measurement is accepted without a token and goes nowhere while off")
  void collectIsPublicAndSilent() throws Exception {
    // Unauthenticated by necessity — the sign-in screen is measured too. With
    // nothing configured the request is simply swallowed.
    mockMvc
        .perform(
            post("/t/c/api/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"pageview\",\"url\":\"/login\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("an oversized body is refused before anything is forwarded")
  void refusesOversizedBodies() throws Exception {
    mockMvc
        .perform(
            post("/t/c/api/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(70_000)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  @DisplayName("a configured deployment announces itself in the public config")
  void configuredTrackingReachesTheClient() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.TRACKING_ENABLED.getKey(), "true",
            ApplicationSettingKey.TRACKING_PROVIDER.getKey(), "plausible",
            ApplicationSettingKey.TRACKING_SITE_ID.getKey(), "qnop.example"),
        null);

    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tracking.provider").value("plausible"))
        .andExpect(jsonPath("$.tracking.siteId").value("qnop.example"))
        .andExpect(jsonPath("$.tracking.consentRequired").value(true))
        .andExpect(jsonPath("$.tracking.respectDnt").value(true))
        // The analytics host is never published — the browser only ever knows
        // about this origin.
        .andExpect(jsonPath("$.tracking.host").doesNotExist());
  }

  @Test
  @DisplayName("enabled but unfinished configuration stays invisible")
  void halfConfiguredIsInvisible() throws Exception {
    settings.update(
        Map.of(
            ApplicationSettingKey.TRACKING_ENABLED.getKey(), "true",
            ApplicationSettingKey.TRACKING_PROVIDER.getKey(), "matomo",
            ApplicationSettingKey.TRACKING_SITE_ID.getKey(), "1"),
        null);

    // Matomo is self-hosted and no host was given: nothing is published, and the
    // client loads nothing at all.
    mockMvc
        .perform(get("/api/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tracking").doesNotExist());
  }
}
