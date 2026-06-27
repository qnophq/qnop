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
import org.springframework.http.MediaType;

/** {@code POST /auth/change-password} against the seeded users (issue #163). */
class SeededChangePasswordIT extends SeededIntegrationTest {

  private static final String CHANGE_PASSWORD = "/api/v1/auth/change-password";
  private static final String NEW_PASSWORD = "New-Pass-9876!";

  private static String body(String current, String next) {
    return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
  }

  @Test
  void changesThePasswordSoTheOldOneStopsWorkingAndTheNewOneWorks() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(SEED_PASSWORD, NEW_PASSWORD)))
        .andExpect(status().isNoContent());

    org.junit.jupiter.api.Assertions.assertEquals(
        401, login("member", SEED_PASSWORD).getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        200, login("member", NEW_PASSWORD).getResponse().getStatus());
  }

  @Test
  void rejectsAWrongCurrentPassword() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-my-password", NEW_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAnExternalAccountThatHasNoPassword() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header("Authorization", "Bearer " + token(EXTERNAL_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(SEED_PASSWORD, NEW_PASSWORD)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXTERNAL_ACCOUNT"));
  }

  @Test
  void rejectsATooShortNewPasswordWithAValidationError() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(SEED_PASSWORD, "short")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsAnUnauthenticatedCaller() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(SEED_PASSWORD, NEW_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }
}
