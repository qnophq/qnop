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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;

/** Admin user listing/search/filter/get against the seeded users (issue #163). */
class SeededAdminUsersIT extends SeededIntegrationTest {

  private static final String ADMIN_USERS = "/api/v1/admin/users";

  @Test
  void listsAllSeededUsers() throws Exception {
    mockMvc
        .perform(get(ADMIN_USERS).header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(8)))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(0));
  }

  @Test
  void filtersByRole() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_USERS)
                .param("role", "ADMIN")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));

    mockMvc
        .perform(
            get(ADMIN_USERS)
                .param("role", "AUDITOR")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].username").value("auditor"));
  }

  @Test
  void searchesByQuery() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_USERS)
                .param("q", "auditor@qnop.test")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].email").value("auditor@qnop.test"));
  }

  @Test
  void getsASingleUserById() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_USERS + "/" + MEMBER_ID).header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("member"))
        .andExpect(jsonPath("$.email").value("member@qnop.test"))
        .andExpect(jsonPath("$.role").value("MEMBER"));
  }

  @Test
  void unknownUserIdIsNotFound() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_USERS + "/a0000000-0000-0000-0000-0000000000ff")
                .header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isNotFound());
  }
}
