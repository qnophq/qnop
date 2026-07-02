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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Annotations, comments & placements API acceptance (issue #247, ADR-0009/0011). Owner {@code
 * MEMBER_ID}; reviewers {@code AUDITOR_ID} (also the annotation author) and {@code MEMBER2_ID};
 * {@code EXTERNAL_ID} is a non-participant and must see 404 everywhere (anti-enumeration).
 */
class AnnotationApiIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;

  /** A DRAFT document owned by MEMBER with AUDITOR + MEMBER2 as reviewers and one version. */
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

  @Test
  void participantCreatesAnAnnotationPlacedOnTheVersion() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    String body = "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"please clarify\"}";
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.placementStatus").value("PLACED"))
        .andExpect(jsonPath("$.anchor.region.surfaceIndex").value(0))
        .andExpect(jsonPath("$.anchor.textQuote.quote").value("the clause"))
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  void listsAnnotationsWithTheirPlacementForAVersion() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations").param("version", "1"),
                MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1))
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PLACED"))
        .andExpect(jsonPath("$.annotations[0].anchor.region.box.width").value(0.3));
  }

  @Test
  void nonParticipantSees404() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), EXTERNAL_ID))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), EXTERNAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":" + ANCHOR + "}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ownerDecidesAccept() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/decision"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ACCEPTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
  }

  @Test
  void authorDecidesTheirOwnAnnotation() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/decision"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"REJECTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void anotherReviewerCannotDecide() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // MEMBER2 is a participant (so the annotation is visible) but neither owner nor author.
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/decision"), MEMBER2_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ACCEPTED\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void decidingAnAlreadyDecidedAnnotationIsConflict() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    decide(annotationId, "ACCEPTED", MEMBER_ID).andExpect(status().isOk());

    decide(annotationId, "REJECTED", MEMBER_ID).andExpect(status().isConflict());
  }

  @Test
  void addsAndListsComments() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"agreed, please revise\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("agreed, please revise"));

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments.length()").value(greaterThanOrEqualTo(2)));
  }

  @Test
  void rejectsAnAnchorWithoutARegion() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unknownAnnotationIsNotFound() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/annotations/" + UUID.randomUUID()), MEMBER_ID))
        .andExpect(status().isNotFound());
  }

  private org.springframework.test.web.servlet.ResultActions decide(
      String annotationId, String decision, UUID actor) throws Exception {
    return mockMvc.perform(
        as(post("/api/v1/annotations/" + annotationId + "/decision"), actor)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"" + decision + "\"}"));
  }
}
