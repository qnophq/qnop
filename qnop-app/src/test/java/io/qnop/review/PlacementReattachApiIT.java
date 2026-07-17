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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
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
 * Manual re-attach of lost placements over the wire (ADR-0009, issue #457): the owner or the
 * annotation's author gives an ORPHANED (or MOVED) placement a new anchor on the latest version,
 * flipping it to PLACED with the thread untouched; PLACED refuses, old versions are read-only,
 * strangers are forbidden.
 */
class PlacementReattachApiIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";
  private static final String NEW_ANCHOR_BODY =
      "{\"anchor\":{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.5,\"y\":0.6,\"width\":0.2,"
          + "\"height\":0.05}},\"textQuote\":{\"quote\":\"the relocated clause\"}}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AnnotationPlacementRepository placements;

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

  /** Creates an anchored annotation as AUDITOR and forces its v1 placement to ORPHANED. */
  private String orphanedAnnotation() throws Exception {
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
    placement.markOrphaned();
    placements.save(placement);
    return annotationId;
  }

  private MockHttpServletRequestBuilder reattach(String annotationId, int version, UUID actor) {
    return as(
            put("/api/v1/annotations/" + annotationId + "/placements/" + version + "/anchor"),
            actor)
        .contentType(MediaType.APPLICATION_JSON)
        .content(NEW_ANCHOR_BODY);
  }

  @Test
  @DisplayName("the author re-attaches an orphan: new anchor, PLACED, thread untouched")
  void authorReattaches() throws Exception {
    seedDocument();
    String annotationId = orphanedAnnotation();

    mockMvc
        .perform(reattach(annotationId, 1, AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placementStatus").value("PLACED"))
        .andExpect(jsonPath("$.anchor.textQuote.quote").value("the relocated clause"))
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  @DisplayName("the owner may re-attach; other participants may not")
  void ownerYesStrangerNo() throws Exception {
    seedDocument();
    String annotationId = orphanedAnnotation();

    mockMvc.perform(reattach(annotationId, 1, MEMBER2_ID)).andExpect(status().isForbidden());
    mockMvc.perform(reattach(annotationId, 1, MEMBER_ID)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a PLACED placement refuses — nothing is overwritten by accident")
  void placedRefuses() throws Exception {
    seedDocument();
    String annotationId = orphanedAnnotation();
    mockMvc.perform(reattach(annotationId, 1, AUDITOR_ID)).andExpect(status().isOk());

    mockMvc
        .perform(reattach(annotationId, 1, AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLACEMENT_NOT_REATTACHABLE"));
  }

  @Test
  @DisplayName("older versions are a read-only record")
  void oldVersionRefuses() throws Exception {
    seedDocument();
    String annotationId = orphanedAnnotation();
    versions.save(
        new DocumentVersion(
            documentId, 2, "sha256/bb/cafebabe", "cafebabe", "application/pdf", 1234L, MEMBER_ID));

    mockMvc
        .perform(reattach(annotationId, 1, AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_READ_ONLY"));
  }

  @Test
  @DisplayName("a closed review refuses with REVIEW_CLOSED")
  void closedReviewRefuses() throws Exception {
    seedDocument();
    String annotationId = orphanedAnnotation();
    Document document = documents.findById(documentId).orElseThrow();
    document.setWorkflowState(WorkflowState.FINALIZED);
    documents.save(document);

    mockMvc
        .perform(reattach(annotationId, 1, AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));
  }
}
