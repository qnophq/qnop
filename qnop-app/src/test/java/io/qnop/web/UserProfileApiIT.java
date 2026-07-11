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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workspace-public user profile (issue #454): any signed-in user sees a colleague's display
 * name and tenure — never email, role or source — and unknown ids answer 404.
 */
class UserProfileApiIT extends SeededIntegrationTest {

  @Test
  @DisplayName("serves a colleague's lean profile to any signed-in user")
  void servesLeanProfile() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/" + MEMBER_ID)
                .header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.displayName").value("Mia Member"))
        .andExpect(jsonPath("$.createdAt").exists())
        // The lean slice: nothing beyond name, avatar and tenure.
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.role").doesNotExist());
  }

  @Test
  @DisplayName("answers 404 for an unknown user and 401 unauthenticated")
  void guards() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/users/" + MEMBER_ID)).andExpect(status().isUnauthorized());
  }
}
