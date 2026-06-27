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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Admin application settings (issue #163). {@code application_setting} is migration-seeded and not
 * truncated by {@code clean.sql}, so the one mutating test restores {@code
 * general.application_name} to its default in {@link AfterEach} to keep the JVM-shared database
 * clean for other IT classes.
 */
class SeededAdminSettingsIT extends SeededIntegrationTest {

  private static final String SETTINGS = "/api/v1/admin/settings";
  private static final String APP_NAME = "general.application_name";

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  @AfterEach
  void restoreApplicationName() throws Exception {
    mockMvc.perform(
        asAdmin(patch(SETTINGS))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"values\":{\"" + APP_NAME + "\":\"qnop\"}}"));
  }

  @Test
  void listsAllSettingsAndMasksSensitiveOnes() throws Exception {
    mockMvc
        .perform(asAdmin(get(SETTINGS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings").isArray())
        .andExpect(jsonPath("$.settings[?(@.key=='" + APP_NAME + "')]").exists())
        .andExpect(
            jsonPath("$.settings[?(@.key=='smtp.password')].sensitive").value(hasItem(true)));
  }

  @Test
  void updatesASetting() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"" + APP_NAME + "\":\"My QNOP\"}}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.settings[?(@.key=='" + APP_NAME + "')].value").value(hasItem("My QNOP")));
  }

  @Test
  void rejectsAnUnknownKey() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"does.not.exist\":\"x\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test
  void rejectsAValueOfTheWrongType() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"smtp.port\":\"not-a-number\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void keepsASensitiveSecretWhenTheMaskSentinelIsSent() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"smtp.password\":\"***\"}}"))
        .andExpect(status().isOk());
  }
}
