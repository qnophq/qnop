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

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Thread participation policy (issue #413): who besides an annotation's author and the review owner
 * may see and write in a thread. Owner {@code MEMBER_ID}; reviewers {@code AUDITOR_ID} (the author)
 * and {@code MEMBER2_ID} (a foreign reviewer).
 */
class AnnotationParticipationPolicyIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;

  private UUID seedDocument(ThreadParticipation policy) {
    Document document = new Document(MEMBER_ID, "Master services agreement");
    document.setThreadParticipation(policy);
    document = documents.save(document);
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

  private String createAnnotation(UUID documentId, UUID actor) throws Exception {
    String body = "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"please clarify\"}";
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private MockHttpServletRequestBuilder comment(String annotationId, UUID actor) {
    return as(post("/api/v1/annotations/" + annotationId + "/comments"), actor)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"body\":\"a reply\"}");
  }

  // ── PRIVATE ──────────────────────────────────────────────────────────────

  @Test
  void privateHidesForeignThreadsFromListGetAndComments() throws Exception {
    UUID documentId = seedDocument(ThreadParticipation.PRIVATE);
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // The author sees their own; the owner sees all; the foreign reviewer sees none.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1));
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1));
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(0));

    // get / comments / addComment on the foreign thread all answer 404 (anti-enumeration).
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), MEMBER2_ID))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER2_ID))
        .andExpect(status().isNotFound());
    mockMvc.perform(comment(annotationId, MEMBER2_ID)).andExpect(status().isNotFound());

    // The author may still comment on their own thread.
    mockMvc.perform(comment(annotationId, AUDITOR_ID)).andExpect(status().isCreated());
  }

  // ── READ_ONLY ────────────────────────────────────────────────────────────

  @Test
  void readOnlyShowsThreadsButRefusesForeignComments() throws Exception {
    UUID documentId = seedDocument(ThreadParticipation.READ_ONLY);
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // The foreign reviewer sees the thread...
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1));
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER2_ID))
        .andExpect(status().isOk());

    // ...but cannot comment (403 THREAD_READ_ONLY); the author and the owner can.
    mockMvc
        .perform(comment(annotationId, MEMBER2_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("THREAD_READ_ONLY"));
    mockMvc.perform(comment(annotationId, AUDITOR_ID)).andExpect(status().isCreated());
    mockMvc.perform(comment(annotationId, MEMBER_ID)).andExpect(status().isCreated());
  }

  // ── OPEN ─────────────────────────────────────────────────────────────────

  @Test
  void openLetsEveryParticipantComment() throws Exception {
    UUID documentId = seedDocument(ThreadParticipation.OPEN);
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc.perform(comment(annotationId, MEMBER2_ID)).andExpect(status().isCreated());
  }

  // ── Overview counts follow visibility under PRIVATE ──────────────────────

  @Test
  void privateScopesTheOverviewCountsToTheCaller() throws Exception {
    UUID documentId = seedDocument(ThreadParticipation.PRIVATE);
    createAnnotation(documentId, AUDITOR_ID);

    // The author and the owner count the annotation; the foreign reviewer counts zero.
    assertOverviewOpenCount(documentId, AUDITOR_ID, 1);
    assertOverviewOpenCount(documentId, MEMBER_ID, 1);
    assertOverviewOpenCount(documentId, MEMBER2_ID, 0);
  }

  private void assertOverviewOpenCount(UUID documentId, UUID actor, int expected) throws Exception {
    mockMvc
        .perform(as(get("/api/v1/documents"), actor))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.id == '" + documentId + "')].openAnnotationCount")
                .value(expected));
  }
}
