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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The public team profile endpoints (issue #586) against the seeded dataset: the visibility matrix
 * (member vs. non-member vs. admin, toggles on/off), the never-an-email rule, and the
 * anti-enumeration 404 for unknown and disabled teams. Seeded shape: team Alpha (slug {@code
 * alpha}) = Ada (ADMIN, LEAD) + MEMBER_ID + AUDITOR_ID; team Beta (slug {@code beta}) = MEMBER_ID
 * (LEAD) + MEMBER2_ID. MEMBER2_ID is NOT a member of Alpha; ADMIN2_ID is a member of neither.
 */
class SeededTeamProfileIT extends SeededIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  private MockHttpServletRequestBuilder as(UUID userId, MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(userId));
  }

  private void enableToggles(UUID teamId) {
    jdbc.update(
        "UPDATE team SET profile_show_members = true, profile_show_reviews = true WHERE id = ?",
        teamId);
  }

  @Test
  void nonMemberSeesIdentityButNeitherRosterNorReviewsByDefault() throws Exception {
    mockMvc
        .perform(as(MEMBER2_ID, get("/api/v1/teams/by-slug/alpha")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Alpha"))
        .andExpect(jsonPath("$.slug").value("alpha"))
        .andExpect(jsonPath("$.description").value("Primary review team"))
        .andExpect(jsonPath("$.viewerIsMember").value(false))
        .andExpect(jsonPath("$.members").doesNotExist())
        .andExpect(jsonPath("$.reviews").doesNotExist());
  }

  @Test
  void enabledTogglesExposeRosterAndReviewsToNonMembers() throws Exception {
    enableToggles(TEAM_ALPHA_ID);
    mockMvc
        .perform(as(MEMBER2_ID, get("/api/v1/teams/by-slug/ALPHA"))) // case-insensitive
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(3))
        // The public roster never exposes e-mail addresses (issue #586).
        .andExpect(jsonPath("$.members[0].email").doesNotExist())
        .andExpect(jsonPath("$.members[0].displayName").isString())
        .andExpect(jsonPath("$.reviews").isArray());
  }

  @Test
  void teamMembersSeeTheirOwnTeamInFullDespiteConservativeDefaults() throws Exception {
    mockMvc
        .perform(as(MEMBER_ID, get("/api/v1/teams/" + TEAM_ALPHA_ID + "/profile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewerIsMember").value(true))
        .andExpect(jsonPath("$.members.length()").value(3))
        .andExpect(jsonPath("$.reviews").isArray());
  }

  @Test
  void adminsSeeAnyTeamInFullWithoutMembership() throws Exception {
    mockMvc
        .perform(as(ADMIN2_ID, get("/api/v1/teams/by-slug/beta")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewerIsMember").value(false))
        .andExpect(jsonPath("$.members.length()").value(2))
        .andExpect(jsonPath("$.reviews").isArray());
  }

  @Test
  void unknownAndDisabledTeamsAnswerTheSame404() throws Exception {
    mockMvc
        .perform(as(MEMBER_ID, get("/api/v1/teams/by-slug/ghost-team")))
        .andExpect(status().isNotFound());

    jdbc.update("UPDATE team SET enabled = false WHERE id = ?", TEAM_ALPHA_ID);
    mockMvc
        .perform(as(MEMBER_ID, get("/api/v1/teams/by-slug/alpha")))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(as(MEMBER_ID, get("/api/v1/teams/" + TEAM_ALPHA_ID + "/profile")))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsAnonymousAccess() throws Exception {
    mockMvc.perform(get("/api/v1/teams/by-slug/alpha")).andExpect(status().isUnauthorized());
  }
}
