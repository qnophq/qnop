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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Real {@code POST /auth/login} against the seeded users (issue #163). */
class SeededLoginIT extends SeededIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/login";

  /** A login request body for the given identifier and password. */
  private static String body(String usernameOrEmail, String password) {
    return "{\"usernameOrEmail\":\"%s\",\"password\":\"%s\"}".formatted(usernameOrEmail, password);
  }

  @Test
  void logsInByUsernameAndReturnsAnAccessTokenAndRefreshCookie() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("admin", SEED_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresInSeconds").isNumber())
        .andExpect(cookie().exists(REFRESH_COOKIE))
        .andExpect(cookie().httpOnly(REFRESH_COOKIE, true));
  }

  @Test
  void logsInByEmailViaTheHelper() throws Exception {
    MvcResult result = login("admin@qnop.test", SEED_PASSWORD);

    assertEquals(200, result.getResponse().getStatus());
    assertNotNull(refreshCookie(result), "login must set the refresh cookie");
  }

  @Test
  void rejectsAWrongPassword() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("admin", "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").isNotEmpty())
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void rejectsAnUnknownUser() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("nobody", SEED_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsADisabledUser() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("disabled", SEED_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAnExternalUserWithoutAPassword() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("external@qnop.test", SEED_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAMissingBodyWithAValidationError() throws Exception {
    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
