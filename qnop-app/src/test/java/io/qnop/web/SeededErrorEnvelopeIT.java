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

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The uniform error envelope (issue #163): {@code code} + {@code message} + {@code timestamp} on
 * every error, plus {@code fieldErrors} on validation failures. Verified across the 401 entry
 * point, the 403 access-denied handler, a 404, and a 400.
 */
class SeededErrorEnvelopeIT extends SeededIntegrationTest {

  private static final String USERS = "/api/v1/admin/users";

  @Test
  void unauthenticatedHasTheEnvelope() throws Exception {
    mockMvc
        .perform(get(USERS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void forbiddenHasTheEnvelope() throws Exception {
    mockMvc
        .perform(get(USERS).header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void notFoundHasTheEnvelope() throws Exception {
    mockMvc
        .perform(
            get(USERS + "/a0000000-0000-0000-0000-0000000000ff")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").isNotEmpty())
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void validationErrorHasFieldErrors() throws Exception {
    mockMvc
        .perform(
            post(USERS)
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"\",\"username\":\"ab\","
                        + "\"email\":\"not-an-email\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.fieldErrors[0].field").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
  }
}
