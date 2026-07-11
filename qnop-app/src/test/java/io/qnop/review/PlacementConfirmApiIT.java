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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AuditEventRepository;
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
 * The human half of re-anchoring over the wire (ADR-0009, issue #326): the document owner or the
 * annotation's author confirms a reviewed MOVED placement back to PLACED; other participants are
 * refused, non-MOVED placements answer a stable 409, and the confirmation lands in the audit trail.
 */
class PlacementConfirmApiIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AnnotationPlacementRepository placements;
  @Autowired private AuditEventRepository auditEvents;

  private UUID documentId;
  private UUID versionId;

  private void seedDocument() {
    Document document = documents.save(new Document(MEMBER_ID, "Master services agreement"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    versionId =
        versions
            .save(
                new DocumentVersion(
                    document.getId(),
                    1,
                    "sha256/aa/deadbeef",
                    "deadbeef",
                    "application/pdf",
                    1234L,
                    MEMBER_ID))
            .getId();
    documentId = document.getId();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** Creates an anchored annotation as AUDITOR and forces its v1 placement to MOVED. */
  private String movedAnnotation() throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"check\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String annotationId = JsonPath.read(json, "$.id");
    AnnotationPlacement placement =
        placements
            .findByAnnotationIdAndDocumentVersionId(UUID.fromString(annotationId), versionId)
            .orElseThrow();
    placement.markMoved(ANCHOR);
    placements.save(placement);
    return annotationId;
  }

  @Test
  @DisplayName("the author confirms a reviewed MOVED placement back to PLACED, audited")
  void authorConfirms() throws Exception {
    seedDocument();
    String annotationId = movedAnnotation();

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/1/confirm"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placementStatus").value("PLACED"));

    assertThat(
            auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .anyMatch(event -> event.getEventType().equals("placement.confirmed")))
        .isTrue();
    // The list view reflects it too.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations?version=1"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PLACED"));
  }

  @Test
  @DisplayName("the document owner may confirm as well; other participants may not")
  void ownerYesStrangerNo() throws Exception {
    seedDocument();
    String annotationId = movedAnnotation();

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/1/confirm"), MEMBER2_ID))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/1/confirm"), MEMBER_ID))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("only MOVED confirms — a PLACED placement answers 409 PLACEMENT_NOT_MOVED")
  void placedRefuses() throws Exception {
    seedDocument();
    String annotationId = movedAnnotation();
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/1/confirm"), AUDITOR_ID))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/1/confirm"), AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLACEMENT_NOT_MOVED"));
  }

  @Test
  @DisplayName("an unknown version or a version without a placement answers 404")
  void missingPlacement404() throws Exception {
    seedDocument();
    String annotationId = movedAnnotation();

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/placements/9/confirm"), AUDITOR_ID))
        .andExpect(status().isNotFound());
  }
}
