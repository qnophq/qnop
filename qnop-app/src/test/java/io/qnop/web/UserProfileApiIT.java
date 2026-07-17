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

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workspace-public user profile (issue #454): any signed-in user sees a colleague's display
 * name and tenure — never email, role or source — and unknown ids answer 404.
 */
class UserProfileApiIT extends SeededIntegrationTest {

  @org.springframework.beans.factory.annotation.Autowired private DocumentRepository documents;

  @org.springframework.beans.factory.annotation.Autowired
  private DocumentVersionRepository versions;

  @org.springframework.beans.factory.annotation.Autowired
  private ReviewParticipantRepository participants;

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
        .andExpect(jsonPath("$.slug").value("mia-member"))
        .andExpect(jsonPath("$.createdAt").exists())
        // The lean slice: nothing beyond name, avatar and tenure.
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.role").doesNotExist());
  }

  @Test
  @DisplayName("resolves the profile by its slug, ignoring case (#486)")
  void resolvesBySlug() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/by-slug/MIA-member")
                .header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.slug").value("mia-member"))
        .andExpect(jsonPath("$.displayName").value("Mia Member"))
        .andExpect(jsonPath("$.stats").exists());

    mockMvc
        .perform(
            get("/api/v1/users/by-slug/no-such-slug")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("allocates collision-suffixed slugs at account creation (#486)")
  void allocatesSuffixedSlugOnCollision() throws Exception {
    // Seeded "Mia Member" already owns mia-member; two namesakes join after her.
    createAdminUser("Mia Member", "mia2", "mia2@example.com");
    createAdminUser("Mia Member", "mia3", "mia3@example.com");

    mockMvc
        .perform(
            get("/api/v1/users/by-slug/mia-member-2")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Mia Member"));
    mockMvc
        .perform(
            get("/api/v1/users/by-slug/mia-member-3")
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk());
  }

  private void createAdminUser(String displayName, String username, String email) throws Exception {
    String body =
        """
        {"displayName":"%s","username":"%s","email":"%s",\
        "role":"MEMBER","initialPassword":"a-strong-pass-1"}"""
            .formatted(displayName, username, email);
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/admin/users")
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("aggregates contribution stats, excluding anonymous-review activity (#473)")
  void statsRespectAnonymity() throws Exception {
    // A public review with MEMBER's activity...
    Document open = documents.save(new Document(MEMBER_ID, "Public contract"));
    participants.save(ReviewParticipant.forUser(open.getId(), AUDITOR_ID));
    versions.save(
        new DocumentVersion(
            open.getId(), 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 10L, MEMBER_ID));
    createAnnotation(open.getId(), AUDITOR_ID);
    // ...and an ANONYMOUS review where AUDITOR also participates and annotates.
    Document anon = new Document(MEMBER_ID, "Anonymous review");
    anon.setAnonymous(true);
    documents.save(anon);
    participants.save(ReviewParticipant.forUser(anon.getId(), AUDITOR_ID));
    versions.save(
        new DocumentVersion(
            anon.getId(), 1, "sha256/bb/cafebabe", "cafebabe", "application/pdf", 10L, MEMBER_ID));
    createAnnotation(anon.getId(), AUDITOR_ID);

    mockMvc
        .perform(
            get("/api/v1/users/" + AUDITOR_ID)
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        // The anonymous review's participation, annotation and comment stay
        // OUT of the public numbers (ADR-0038).
        .andExpect(jsonPath("$.stats.reviewsOwned").value(0))
        .andExpect(jsonPath("$.stats.reviewsParticipating").value(1))
        .andExpect(jsonPath("$.stats.annotationsRaised").value(1))
        .andExpect(jsonPath("$.stats.annotationsResolved").value(0))
        .andExpect(jsonPath("$.stats.commentsWritten").value(1));

    // Ownership is structurally public — the owner's count includes BOTH reviews.
    mockMvc
        .perform(
            get("/api/v1/users/" + MEMBER_ID)
                .header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.reviewsOwned").value(2));
  }

  @Test
  @DisplayName("lists the user's enabled teams with their role, ordered by name (#473)")
  void teamsWithRoles() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/" + MEMBER_ID)
                .header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams[0].name").value("Alpha"))
        .andExpect(jsonPath("$.teams[0].role").exists());

    // A user in no team carries an empty list.
    mockMvc
        .perform(
            get("/api/v1/users/" + EXTERNAL_ID)
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams", org.hamcrest.Matchers.hasSize(0)));
  }

  private void createAnnotation(java.util.UUID documentId, java.util.UUID author) throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/documents/" + documentId + "/annotations")
                .header("Authorization", "Bearer " + token(author))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":null,\"comment\":\"a concern\"}"))
        .andExpect(status().isCreated());
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
