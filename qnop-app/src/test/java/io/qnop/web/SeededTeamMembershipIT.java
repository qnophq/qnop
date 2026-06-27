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

/**
 * Team membership: add / set-role / remove against the seeded teams (issue #163). Seeded Alpha
 * holds admin (LEAD), member, auditor; Beta holds member (LEAD), member2.
 */
class SeededTeamMembershipIT extends SeededIntegrationTest {

  private static final String TEAMS = "/api/v1/admin/teams";
  private static final String UNKNOWN_ID = "00000000-0000-0000-0000-0000000000ff";

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  private String members(Object teamId) {
    return TEAMS + "/" + teamId + "/members";
  }

  @Test
  void addsANewMember() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(members(TEAM_ALPHA_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\",\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(MEMBER2_ID.toString()))
        .andExpect(jsonPath("$.teamRole").value("MEMBER"));
  }

  @Test
  void rejectsAddingAnExistingMember() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(members(TEAM_ALPHA_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER_ID + "\",\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
  }

  @Test
  void addingToAnUnknownTeamIsNotFound() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(members(UNKNOWN_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER_ID + "\",\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void addingAnUnknownUserIsNotFound() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(members(TEAM_ALPHA_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + UNKNOWN_ID + "\",\"teamRole\":\"MEMBER\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void promotesAMemberToLead() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(members(TEAM_ALPHA_ID) + "/" + MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"LEAD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamRole").value("LEAD"));
  }

  @Test
  void settingTheRoleOfANonMemberIsNotFound() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(members(TEAM_ALPHA_ID) + "/" + MEMBER2_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamRole\":\"LEAD\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void removesAMember() throws Exception {
    mockMvc
        .perform(asAdmin(delete(members(TEAM_ALPHA_ID) + "/" + AUDITOR_ID)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(asAdmin(get(TEAMS + "/" + TEAM_ALPHA_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(2));
  }

  @Test
  void removingANonMemberIsNotFound() throws Exception {
    mockMvc
        .perform(asAdmin(delete(members(TEAM_ALPHA_ID) + "/" + MEMBER2_ID)))
        .andExpect(status().isNotFound());
  }
}
