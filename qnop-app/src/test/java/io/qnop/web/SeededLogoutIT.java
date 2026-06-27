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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

/** Logout revokes the refresh-token family and is idempotent (issue #163). */
class SeededLogoutIT extends SeededIntegrationTest {

  private static final String LOGOUT = "/api/v1/auth/logout";
  private static final String REFRESH = "/api/v1/auth/refresh";

  @Test
  void logsOutAndInvalidatesTheRefreshToken() throws Exception {
    Cookie refresh = refreshCookie(login("admin", SEED_PASSWORD));

    mockMvc.perform(post(LOGOUT).cookie(refresh)).andExpect(status().isNoContent());

    // The refresh token is no longer usable after logout.
    mockMvc.perform(post(REFRESH).cookie(refresh)).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutWithoutACookieIsIdempotent() throws Exception {
    mockMvc.perform(post(LOGOUT)).andExpect(status().isNoContent());
  }
}
