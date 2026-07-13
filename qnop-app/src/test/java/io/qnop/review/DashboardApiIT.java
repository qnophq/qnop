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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The dashboard aggregates over the wire (issue #454): replies directed at the caller (their own
 * comments never among them), the activity feed excluding the caller's own actions, the weekly
 * resolved stat, and clean empty states for a user without reviews.
 */
class DashboardApiIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;

  private UUID seedDocumentWithVersion() {
    Document document = documents.save(new Document(MEMBER_ID, "Master services agreement"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    versions.save(
        new DocumentVersion(
            document.getId(),
            1,
            "sha256/aa/deadbeef",
            "deadbeef",
            "application/pdf",
            1234L,
            MEMBER_ID));
    return document.getId();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private String createAnnotation(UUID documentId, UUID actor, String comment) throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"versionNumber\":1,\"comment\":\"" + comment + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private void addComment(String annotationId, UUID actor, String body) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("surfaces replies by others on my threads, with document context and author name")
  void repliesToMe() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String mine = createAnnotation(documentId, MEMBER_ID, "please clarify");
    addComment(mine, AUDITOR_ID, "will do");
    addComment(mine, MEMBER_ID, "thanks"); // own comment — never a reply to me
    // A foreign thread I joined: replies AFTER my comment count, earlier ones do not.
    String theirs = createAnnotation(documentId, AUDITOR_ID, "their concern");
    addComment(theirs, MEMBER2_ID, "before I joined");
    addComment(theirs, MEMBER_ID, "joining in");
    addComment(theirs, MEMBER2_ID, "after I joined");

    mockMvc
        .perform(as(get("/api/v1/dashboard"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replies", hasSize(2)))
        // Newest first: the reply after my comment in the foreign thread…
        .andExpect(jsonPath("$.replies[0].body").value("after I joined"))
        .andExpect(jsonPath("$.replies[0].annotationExcerpt").value("their concern"))
        .andExpect(jsonPath("$.replies[0].documentTitle").value("Master services agreement"))
        // …then the reply on my own annotation — with the author's REAL id and
        // name (this review is not anonymous, issue #413/#454).
        .andExpect(jsonPath("$.replies[1].body").value("will do"))
        .andExpect(jsonPath("$.replies[1].annotationId").value(mine))
        .andExpect(jsonPath("$.replies[1].authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.replies[1].authorSlug").value("avery-auditor"))
        .andExpect(jsonPath("$.replies[1].authorDisplayName").value("Avery Auditor"));
  }

  @Test
  @DisplayName("anonymised identities carry no slug either (#486)")
  void anonymisedIdentitiesCarryNoSlug() throws Exception {
    Document document = new Document(MEMBER_ID, "Anonymous review");
    document.setAnonymous(true);
    documents.save(document);
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    versions.save(
        new DocumentVersion(
            document.getId(),
            1,
            "sha256/cc/feedface",
            "feedface",
            "application/pdf",
            10L,
            MEMBER_ID));
    String mine = createAnnotation(document.getId(), MEMBER_ID, "please clarify");
    addComment(mine, AUDITOR_ID, "will do");

    mockMvc
        .perform(as(get("/api/v1/dashboard"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replies[0].body").value("will do"))
        // The pseudonymised author exposes neither id nor slug — a slug would
        // deanonymise just as surely as the id (issue #413/#486).
        .andExpect(jsonPath("$.replies[0].authorId").doesNotExist())
        .andExpect(jsonPath("$.replies[0].authorSlug").doesNotExist());
  }

  @Test
  @DisplayName("feeds recent activity excluding my own actions, and counts this week's resolves")
  void activityAndStats() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String mine = createAnnotation(documentId, MEMBER_ID, "please clarify");
    createAnnotation(documentId, AUDITOR_ID, "a second concern");
    // The author resolves their annotation — MEMBER's feed carries it, MEMBER's
    // own creation is absent; the weekly stat counts the resolve for everyone.
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + mine + "/resolve"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(get("/api/v1/dashboard"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.resolvedThisWeek").value(1))
        .andExpect(jsonPath("$.activity", hasSize(greaterThanOrEqualTo(2))))
        // Newest first: MEMBER's resolve, then MEMBER's creation — AUDITOR's own
        // annotation.created is filtered out.
        .andExpect(jsonPath("$.activity[0].type").value("annotation.resolved"))
        .andExpect(jsonPath("$.activity[0].actorId").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.activity[0].actorSlug").value("mia-member"))
        .andExpect(jsonPath("$.activity[0].actorDisplayName").value("Mia Member"));

    mockMvc
        .perform(as(get("/api/v1/dashboard"), MEMBER_ID))
        .andExpect(status().isOk())
        // MEMBER sees AUDITOR's creation but none of their own actions.
        .andExpect(jsonPath("$.activity", hasSize(1)))
        .andExpect(jsonPath("$.activity[0].type").value("annotation.created"));
  }

  @Test
  @DisplayName("resolves the owner's name for owner-driven events they never wrote in (issue #472)")
  void ownerNameResolvesWithoutAuthorship() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    // The owner's only action is a workflow transition — no annotation, no
    // comment. Their name must still resolve (the UI otherwise says "Someone").
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/workflow"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"IN_REVIEW\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(get("/api/v1/dashboard"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activity[0].type").value("workflow.transition"))
        .andExpect(jsonPath("$.activity[0].actorId").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.activity[0].actorDisplayName").value("Mia Member"));
  }

  @Test
  @DisplayName("a user without reviews gets clean empty aggregates")
  void emptyDashboard() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/dashboard"), EXTERNAL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replies", hasSize(0)))
        .andExpect(jsonPath("$.activity", hasSize(0)))
        .andExpect(jsonPath("$.stats.resolvedThisWeek").value(0));
  }

  @Test
  @DisplayName("requires authentication")
  void requiresAuth() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
  }
}
