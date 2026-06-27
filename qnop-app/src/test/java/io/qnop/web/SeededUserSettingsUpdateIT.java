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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Updating the current user's settings (issue #163). */
class SeededUserSettingsUpdateIT extends SeededIntegrationTest {

  private static final String SETTINGS = "/api/v1/users/me/settings";

  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(MEMBER_ID));
  }

  @Test
  void updatesAUserSetting() throws Exception {
    mockMvc
        .perform(
            asMember(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"theme\":\"dark\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings").isArray());
  }

  @Test
  void rejectsAnUnknownKey() throws Exception {
    mockMvc
        .perform(
            asMember(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"does.not.exist\":\"x\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SETTING"));
  }

  @Test
  void rejectsAnInvalidEnumValue() throws Exception {
    mockMvc
        .perform(
            asMember(patch(SETTINGS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"theme\":\"purple\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SETTING"));
  }

  @Test
  void rejectsAnonymousAccess() throws Exception {
    mockMvc
        .perform(
            patch(SETTINGS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"theme\":\"dark\"}}"))
        .andExpect(status().isUnauthorized());
  }
}
