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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;

/** Admin-initiated password reset + generate-password (issue #163). */
class SeededAdminUserPasswordIT extends SeededIntegrationTest {

  private static final String USERS = "/api/v1/admin/users";

  private String bearer() {
    return "Bearer " + token(ADMIN_ID);
  }

  @Test
  void sendsAPasswordResetForAnInternalUser() throws Exception {
    mockMvc
        .perform(
            post(USERS + "/" + MEMBER_ID + "/password-reset").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailSent").isBoolean());
  }

  @Test
  void rejectsAPasswordResetForAnExternalUser() throws Exception {
    mockMvc
        .perform(
            post(USERS + "/" + EXTERNAL_ID + "/password-reset").header("Authorization", bearer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NO_LOCAL_PASSWORD"));
  }

  @Test
  void passwordResetForAnUnknownUserIsNotFound() throws Exception {
    mockMvc
        .perform(
            post(USERS + "/a0000000-0000-0000-0000-0000000000ff/password-reset")
                .header("Authorization", bearer()))
        .andExpect(status().isNotFound());
  }

  @Test
  void generatesAPasswordForAnInternalUser() throws Exception {
    mockMvc
        .perform(post(USERS + "/" + MEMBER_ID + "/password").header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").isNotEmpty());
  }

  @Test
  void rejectsGeneratingAPasswordForAnExternalUser() throws Exception {
    mockMvc
        .perform(post(USERS + "/" + EXTERNAL_ID + "/password").header("Authorization", bearer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NO_LOCAL_PASSWORD"));
  }
}
