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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Team CRUD against the seeded teams (issue #163). */
class SeededTeamIT extends SeededIntegrationTest {

  private static final String TEAMS = "/api/v1/admin/teams";

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  @Test
  void listsTheSeededTeamsWithMemberCounts() throws Exception {
    mockMvc
        .perform(asAdmin(get(TEAMS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void createsATeam() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(TEAMS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Gamma\",\"description\":\"A third team\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Gamma"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.memberCount").value(0));
  }

  @Test
  void rejectsADuplicateTeamName() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(TEAMS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
  }

  @Test
  void rejectsAnEmptyNameWithAValidationError() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(TEAMS)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void getsATeamWithItsMembers() throws Exception {
    mockMvc
        .perform(asAdmin(get(TEAMS + "/" + TEAM_ALPHA_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Alpha"))
        .andExpect(jsonPath("$.members").isArray())
        .andExpect(jsonPath("$.members.length()").value(3));
  }

  @Test
  void getsAnUnknownTeamAs404() throws Exception {
    mockMvc
        .perform(asAdmin(get(TEAMS + "/b0000000-0000-0000-0000-0000000000ff")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
  }

  @Test
  void updatesATeamDescription() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(TEAMS + "/" + TEAM_ALPHA_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Updated description\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("Updated description"));
  }

  @Test
  void rejectsRenamingToAnExistingName() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(TEAMS + "/" + TEAM_ALPHA_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Beta\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
  }

  @Test
  void deletesATeam() throws Exception {
    mockMvc.perform(asAdmin(delete(TEAMS + "/" + TEAM_BETA_ID))).andExpect(status().isNoContent());
    mockMvc.perform(asAdmin(get(TEAMS + "/" + TEAM_BETA_ID))).andExpect(status().isNotFound());
  }
}
