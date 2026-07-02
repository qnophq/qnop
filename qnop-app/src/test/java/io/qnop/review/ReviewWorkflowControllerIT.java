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

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.review.ReviewWorkflowService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * End-to-end tests for the workflow transition endpoints (issue #246, ADR-0011) against a real
 * PostgreSQL: legal and illegal transitions, the FINALIZED guard invariant (open annotations /
 * pending placements), owner-only authorization, the audit trail, and the annotation-decision
 * sub-machine driving CHANGES_REQUESTED. Documents are seeded directly via repositories — the
 * upload pipeline is #245. Requires Docker.
 */
class ReviewWorkflowControllerIT extends SeededIntegrationTest {

  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired AnnotationRepository annotations;
  @Autowired AnnotationPlacementRepository placements;
  @Autowired AuditEventRepository auditEvents;
  @Autowired ReviewWorkflowService workflow;

  private Document draftOwnedByMember() {
    return documents.save(new Document(MEMBER_ID, "Quarterly report"));
  }

  private Document inReviewOwnedByMember() {
    Document document = draftOwnedByMember();
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    return documents.save(document);
  }

  private String workflowPath(UUID documentId) {
    return "/api/v1/documents/" + documentId + "/workflow";
  }

  // --- GET ---------------------------------------------------------------------

  @Test
  void getReturnsStateAndStructurallyAllowedTransitions() throws Exception {
    Document document = draftOwnedByMember();

    mockMvc
        .perform(
            get(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER2_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DRAFT"))
        .andExpect(jsonPath("$.allowedTransitions.length()").value(2));
  }

  @Test
  void getUnknownDocumentReturns404() throws Exception {
    mockMvc
        .perform(
            get(workflowPath(UUID.randomUUID()))
                .header("Authorization", "Bearer " + token(MEMBER_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
  }

  // --- POST: legal transition + audit ---------------------------------------------

  @Test
  void ownerMovesDraftToInReviewAndAnAuditEventIsAppended() throws Exception {
    Document document = draftOwnedByMember();

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"IN_REVIEW\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("IN_REVIEW"));

    assertThat(documents.findById(document.getId()).orElseThrow().getWorkflowState())
        .isEqualTo("IN_REVIEW");
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(document.getId()))
        .anySatisfy(
            event -> {
              assertThat(event.getEventType()).isEqualTo("workflow.transition");
              assertThat(event.getActorId()).isEqualTo(MEMBER_ID);
              assertThat(event.getDetail()).contains("DRAFT").contains("IN_REVIEW");
            });
  }

  // --- POST: refusals ---------------------------------------------------------------

  @Test
  void nonOwnerIsRejectedWith403() throws Exception {
    Document document = draftOwnedByMember();

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER2_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"IN_REVIEW\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_DOCUMENT_OWNER"));
  }

  @Test
  void illegalEdgeIsRejectedWith409() throws Exception {
    Document document = draftOwnedByMember();

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"FINALIZED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void unknownTargetStateIsRejectedWith409() throws Exception {
    Document document = inReviewOwnedByMember();

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"SIGNING\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  // --- the FINALIZED invariant ---------------------------------------------------

  @Test
  void finalizeIsBlockedWhileAnAnnotationIsOpen() throws Exception {
    Document document = inReviewOwnedByMember();
    annotations.save(new Annotation(document.getId(), MEMBER2_ID));

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"FINALIZED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("open")));
  }

  @Test
  void finalizeIsBlockedWhileAPlacementIsPending() throws Exception {
    Document document = inReviewOwnedByMember();
    DocumentVersion version =
        versions.save(
            new DocumentVersion(
                document.getId(), 1, "s3://key", "hash", "application/pdf", 10, MEMBER_ID));
    Annotation annotation = annotations.save(new Annotation(document.getId(), MEMBER2_ID));
    annotation.reject(); // decided — only the PENDING placement blocks now
    annotations.save(annotation);
    placements.save(new AnnotationPlacement(annotation.getId(), version.getId(), "{\"p\":1}"));

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"FINALIZED\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("re-anchoring")));
  }

  @Test
  void finalizeSucceedsOnceAnnotationsAreDecidedAndPlacementsAnchored() throws Exception {
    Document document = inReviewOwnedByMember();
    DocumentVersion version =
        versions.save(
            new DocumentVersion(
                document.getId(), 1, "s3://key", "hash", "application/pdf", 10, MEMBER_ID));
    Annotation annotation = annotations.save(new Annotation(document.getId(), MEMBER2_ID));
    annotation.reject();
    annotations.save(annotation);
    AnnotationPlacement placement =
        new AnnotationPlacement(annotation.getId(), version.getId(), "{\"p\":1}");
    placement.markPlaced("{\"p\":1}");
    placements.save(placement);

    mockMvc
        .perform(
            post(workflowPath(document.getId()))
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"FINALIZED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("FINALIZED"))
        .andExpect(jsonPath("$.allowedTransitions.length()").value(0));
  }

  // --- annotation decision sub-machine (service-level; the REST surface is #247) ---

  @Test
  void acceptingAnAnnotationDrivesInReviewToChangesRequestedWithAudit() {
    Document document = inReviewOwnedByMember();
    Annotation annotation = annotations.save(new Annotation(document.getId(), MEMBER2_ID));

    workflow.decideAnnotation(annotation.getId(), AnnotationStatus.ACCEPTED, MEMBER_ID);

    assertThat(annotations.findById(annotation.getId()).orElseThrow().getStatus())
        .isEqualTo(AnnotationStatus.ACCEPTED);
    assertThat(documents.findById(document.getId()).orElseThrow().getWorkflowState())
        .isEqualTo("CHANGES_REQUESTED");
    assertThat(
            auditEvents.findByDocumentIdOrderByCreatedAtDesc(document.getId()).stream()
                .map(AuditEvent::getEventType))
        .contains("annotation.decided", "workflow.transition");
  }

  @Test
  void rejectingAnAnnotationLeavesTheWorkflowInReview() {
    Document document = inReviewOwnedByMember();
    Annotation annotation = annotations.save(new Annotation(document.getId(), MEMBER2_ID));

    workflow.decideAnnotation(annotation.getId(), AnnotationStatus.REJECTED, MEMBER_ID);

    assertThat(documents.findById(document.getId()).orElseThrow().getWorkflowState())
        .isEqualTo("IN_REVIEW");
  }
}
