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

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;

/** /users/me and /users/me/settings against the seeded users (issue #163). */
class SeededCurrentUserIT extends SeededIntegrationTest {

  @Test
  void returnsTheAuthenticatedInternalUsersProfile() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("admin@qnop.test"))
        .andExpect(jsonPath("$.displayName").value("Ada Admin"))
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.source").value("INTERNAL"));
  }

  @Test
  void returnsAnExternalUsersProfile() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token(EXTERNAL_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("external@qnop.test"))
        .andExpect(jsonPath("$.role").value("MEMBER"))
        .andExpect(jsonPath("$.source").value("EXTERNAL"));
  }

  @Test
  void rejectsAnonymousAccess() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void returnsTheUsersSettings() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/settings").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings").isArray());
  }
}
