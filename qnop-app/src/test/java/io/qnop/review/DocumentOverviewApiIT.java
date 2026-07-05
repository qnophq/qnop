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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Annotation;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The reviews overview, participant management and the principal directory (issue #292, ADR-0011):
 * visibility through ownership, direct participation and team membership; owner-only mutations with
 * the 404/403 anti-enumeration split; the directory's names-only exposure.
 */
class DocumentOverviewApiIT extends SeededIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AnnotationRepository annotations;
  @Autowired private TeamRepository teams;
  @Autowired private TeamMembershipRepository memberships;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** Owner MEMBER, direct participant AUDITOR, team participant with MEMBER2 as team member. */
  private UUID seedReview(String title) {
    Document document = documents.save(new Document(MEMBER_ID, title));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    Team team = teams.save(Team.create("review-team-" + UUID.randomUUID(), null));
    memberships.save(TeamMembership.of(team.getId(), MEMBER2_ID, TeamRole.MEMBER));
    participants.save(ReviewParticipant.forTeam(document.getId(), team.getId()));
    versions.save(
        new DocumentVersion(
            document.getId(),
            1,
            "sha256/aa/cafebabe",
            "cafebabe",
            "application/pdf",
            42L,
            MEMBER_ID));
    return document.getId();
  }

  // ── GET /documents ──────────────────────────────────────────────────────

  @Test
  void ownerDirectParticipantAndTeamMemberSeeTheDocument() throws Exception {
    UUID documentId = seedReview("Overview visibility agreement");

    for (UUID viewer : new UUID[] {MEMBER_ID, AUDITOR_ID, MEMBER2_ID}) {
      mockMvc
          .perform(as(get("/api/v1/documents?q=Overview visibility"), viewer))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(1))
          .andExpect(jsonPath("$.items[0].id").value(documentId.toString()))
          .andExpect(jsonPath("$.items[0].workflowState").value("DRAFT"))
          .andExpect(jsonPath("$.items[0].latestVersionNumber").value(1));
    }
  }

  @Test
  void strangerSeesAnEmptyOverview() throws Exception {
    seedReview("Overview stranger agreement");

    mockMvc
        .perform(as(get("/api/v1/documents?q=Overview stranger"), EXTERNAL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void overviewCarriesAnnotationProgressAndParticipantNames() throws Exception {
    UUID documentId = seedReview("Overview progress agreement");
    annotations.save(new Annotation(documentId, AUDITOR_ID));
    Annotation decided = new Annotation(documentId, AUDITOR_ID);
    decided.resolve();
    annotations.save(decided);

    mockMvc
        .perform(as(get("/api/v1/documents?q=Overview progress"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].annotationCount").value(2))
        .andExpect(jsonPath("$.items[0].openAnnotationCount").value(1))
        .andExpect(jsonPath("$.items[0].participants.length()").value(2))
        .andExpect(jsonPath("$.items[0].participants[0].kind").value("USER"))
        .andExpect(jsonPath("$.items[0].participants[0].displayName").value("Avery Auditor"))
        .andExpect(jsonPath("$.items[0].participants[1].kind").value("TEAM"));
  }

  // ── participants ────────────────────────────────────────────────────────

  @Test
  void participantListsReviewersButStrangerSees404() throws Exception {
    UUID documentId = seedReview("Participants list agreement");

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/participants"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participants.length()").value(2))
        .andExpect(jsonPath("$.participants[0].displayName").value("Avery Auditor"));

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/participants"), EXTERNAL_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void ownerAddsAndRemovesAUserParticipant() throws Exception {
    UUID documentId = seedReview("Participants mutate agreement");

    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.kind").value("USER"))
            .andExpect(jsonPath("$.displayName").value("Max Member"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String participantId = JsonPath.read(json, "$.id");

    // Adding the same user again conflicts.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_PARTICIPANT"));

    mockMvc
        .perform(
            as(
                delete("/api/v1/documents/" + documentId + "/participants/" + participantId),
                MEMBER_ID))
        .andExpect(status().isNoContent());
  }

  @Test
  void addValidatesPrincipalAndXor() throws Exception {
    UUID documentId = seedReview("Participants validation agreement");

    // Both principals → 400.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\",\"teamId\":\"" + TEAM_BETA_ID + "\"}"))
        .andExpect(status().isBadRequest());

    // Neither → 400.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    // The owner is structural — not addable.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER_ID + "\"}"))
        .andExpect(status().isBadRequest());

    // A disabled user is not assignable.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + DISABLED_ID + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mutationsAreOwnerOnlyWithAntiEnumeration() throws Exception {
    UUID documentId = seedReview("Participants authz agreement");
    UUID otherDocument = seedReview("Participants authz other");
    UUID foreignParticipantId = participants.findByDocumentId(otherDocument).get(0).getId();

    // A participant (not owner) gets 403.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_OWNER"));

    // A stranger gets 404, indistinguishable from an unknown document.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), EXTERNAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isNotFound());

    // A participant row of ANOTHER document reads as unknown.
    mockMvc
        .perform(
            as(
                delete("/api/v1/documents/" + documentId + "/participants/" + foreignParticipantId),
                MEMBER_ID))
        .andExpect(status().isNotFound());
  }

  // ── GET /principals ─────────────────────────────────────────────────────

  @Test
  void principalDirectoryReturnsEnabledUsersAndTeamsForPlainMembers() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/principals?q=member"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principals[?(@.displayName == 'Mia Member')].kind").value("USER"))
        .andExpect(jsonPath("$.principals[?(@.displayName == 'Max Member')]").exists());

    mockMvc
        .perform(as(get("/api/v1/principals?q=alpha"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principals[?(@.displayName == 'Alpha')].kind").value("TEAM"));
  }

  @Test
  void teamMembersAreListedForPlainMembersNamesOnly() throws Exception {
    // Team Alpha carries three seeded members (SeededTeamIT pins that count).
    mockMvc
        .perform(as(get("/api/v1/principals/teams/" + TEAM_ALPHA_ID + "/members"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principals.length()").value(3))
        .andExpect(jsonPath("$.principals[0].kind").value("USER"))
        .andExpect(jsonPath("$.principals[0].displayName").isNotEmpty())
        // Names only — the members endpoint must never leak emails.
        .andExpect(jsonPath("$.principals[0].email").doesNotExist());
  }

  @Test
  void teamMembersOfAnUnknownTeamAre404() throws Exception {
    mockMvc
        .perform(
            as(
                get("/api/v1/principals/teams/00000000-0000-0000-0000-00000000dead/members"),
                MEMBER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
  }

  @Test
  void principalDirectoryHidesDisabledUsersAndNeverMatchesEmails() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/principals?q=dana"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principals.length()").value(0));

    // Email fragments must not confirm addresses.
    mockMvc
        .perform(as(get("/api/v1/principals?q=qnop.test"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principals.length()").value(0));
  }
}
