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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

/** Refresh-token rotation and reuse detection (issue #163). */
class SeededRefreshIT extends SeededIntegrationTest {

  private static final String REFRESH = "/api/v1/auth/refresh";

  @Test
  void rotatesTheRefreshTokenAndIssuesANewAccessToken() throws Exception {
    Cookie first = refreshCookie(login("admin", SEED_PASSWORD));

    mockMvc
        .perform(post(REFRESH).with(csrf()).cookie(first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(cookie().exists(REFRESH_COOKIE));
  }

  @Test
  void revokesTheWholeFamilyWhenARotatedTokenIsReplayed() throws Exception {
    Cookie first = refreshCookie(login("admin", SEED_PASSWORD));

    // Rotate once: `first` becomes ROTATED and a successor cookie is issued.
    Cookie second =
        refreshCookie(mockMvc.perform(post(REFRESH).with(csrf()).cookie(first)).andReturn());

    // Replaying the rotated `first` is reuse → 401 and the family is revoked.
    mockMvc.perform(post(REFRESH).with(csrf()).cookie(first)).andExpect(status().isUnauthorized());

    // The previously-valid successor is now revoked too.
    mockMvc.perform(post(REFRESH).with(csrf()).cookie(second)).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsARefreshWithoutACookie() throws Exception {
    mockMvc.perform(post(REFRESH).with(csrf())).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAnUnknownRefreshToken() throws Exception {
    mockMvc
        .perform(post(REFRESH).with(csrf()).cookie(new Cookie(REFRESH_COOKIE, "not-a-real-token")))
        .andExpect(status().isUnauthorized());
  }
}
