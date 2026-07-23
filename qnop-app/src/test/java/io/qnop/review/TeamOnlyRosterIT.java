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
package io.qnop.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Document;
import io.qnop.repository.DocumentRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Regression for issue #584: a roster whose ONLY reviewer is a team 500'd the participants list
 * (and could 500 the reviews overview) — {@code Map.of().get(null)} throws, and the team row's null
 * user id hit exactly that on the slug lookup. The visible UI fallout: an empty reviewer list, the
 * picker still offering the team, and a baffling "already a reviewer" 409 on re-add.
 */
class TeamOnlyRosterIT extends SeededIntegrationTest {

  @Autowired DocumentRepository documents;

  @Test
  void teamOnlyRosterListsAndTheOverviewSurvives() throws Exception {
    Document document = documents.save(new Document(MEMBER_ID, "Team-only review"));
    UUID documentId = document.getId();

    // Add ONLY a team (seeded Alpha) — no user participant, so the slug map is empty.
    mockMvc
        .perform(
            post("/api/v1/documents/{id}/participants", documentId)
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"" + TEAM_ALPHA_ID + "\"}"))
        .andExpect(status().isCreated());

    // The list answers 200 with the TEAM row (was: 500, issue #584).
    mockMvc
        .perform(
            get("/api/v1/documents/{id}/participants", documentId)
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participants.length()").value(1))
        .andExpect(jsonPath("$.participants[0].kind").value("TEAM"))
        .andExpect(jsonPath("$.participants[0].principalId").value(TEAM_ALPHA_ID.toString()))
        .andExpect(jsonPath("$.participants[0].displayName").value("Alpha"));

    // The reviews overview shares the mapping — it must survive the team-only roster too.
    mockMvc
        .perform(get("/api/v1/documents").header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.title=='Team-only review')]").exists());

    // Re-adding stays the honest duplicate answer.
    mockMvc
        .perform(
            post("/api/v1/documents/{id}/participants", documentId)
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"" + TEAM_ALPHA_ID + "\"}"))
        .andExpect(status().isConflict());
  }
}
