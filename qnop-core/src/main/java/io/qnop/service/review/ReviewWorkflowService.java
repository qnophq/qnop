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
package io.qnop.service.review;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.review.ReviewWorkflowMachine.TransitionContext;
import io.qnop.service.review.ReviewWorkflowMachine.TransitionResult;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the review workflow (issue #246, ADR-0011): loads the document, feeds the DB-free {@link
 * ReviewWorkflowMachine} choke-point with the guard facts, persists the outcome and appends an
 * {@link AuditEvent} per transition. Authorization is owner-only for state changes (ADR-0011: the
 * owner drives the workflow and decides annotations). State-changing paths load the document under
 * a pessimistic write lock ({@link DocumentRepository#findByIdForUpdate}), so the finalize-guard
 * counts and the state write are atomic against a concurrent transition or a new-version upload
 * that would change the pending-placement picture (issue #324).
 */
@Service
public class ReviewWorkflowService {

  /** Audit event type for a workflow state change; detail carries {@code {"from","to"}}. */
  static final String AUDIT_WORKFLOW_TRANSITION = "workflow.transition";

  /** Audit event type for an annotation decision; detail carries the annotation id + decision. */
  static final String AUDIT_ANNOTATION_DECIDED = "annotation.decided";

  private final ReviewWorkflowMachine machine = new ReviewWorkflowMachine();

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AnnotationRepository annotations;
  private final AnnotationPlacementRepository placements;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService documentAccess;

  public ReviewWorkflowService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AnnotationRepository annotations,
      AnnotationPlacementRepository placements,
      AuditEventRepository auditEvents,
      DocumentAccessService documentAccess) {
    this.documents = documents;
    this.versions = versions;
    this.annotations = annotations;
    this.placements = placements;
    this.auditEvents = auditEvents;
    this.documentAccess = documentAccess;
  }

  /**
   * The workflow view of a document: its raw state and the structurally reachable target states as
   * their names (kept as strings so the web layer maps them without depending on the entity enum,
   * ADR-0004, issue #315).
   */
  public record WorkflowStatus(String state, List<String> allowedTransitions) {}

  /**
   * The current workflow status of a document, readable by anyone who may see the document — its
   * owner, a review participant (directly or via team), or an admin (issue #311). A non-participant
   * gets a 404 (never a 403) so document ids stay non-enumerable, matching {@link
   * DocumentAccessService}.
   */
  @Transactional(readOnly = true)
  public WorkflowStatus status(UUID documentId, UUID actorId, boolean admin) {
    Document document = load(documentId);
    if (!documentAccess.isVisible(documentId, actorId, admin)) {
      throw new DocumentNotFoundException(documentId);
    }
    return statusOf(document);
  }

  /**
   * Requests the transition to {@code targetRaw}, owner-only. A target this edition does not know,
   * an illegal edge, or a vetoing guard (the FINALIZED invariant) is rejected with {@link
   * WorkflowTransitionException} (HTTP 409).
   */
  @Transactional
  public WorkflowStatus transition(UUID documentId, String targetRaw, UUID actorId) {
    Document document = loadForUpdate(documentId);
    requireOwner(document, actorId);
    WorkflowState target =
        WorkflowState.fromString(targetRaw)
            .orElseThrow(
                () ->
                    new WorkflowTransitionException(
                        WorkflowTransitionException.INVALID_TRANSITION,
                        "unknown target state '" + targetRaw + "'"));
    applyTransition(document, target, actorId);
    return statusOf(document);
  }

  /**
   * Applies a decision on an annotation — the {@code OPEN → ACCEPTED | REJECTED} sub-machine
   * (ADR-0011), permitted for the document owner or the annotation's author (issue #247). Accepting
   * while the document is {@code IN_REVIEW} drives the workflow to {@code CHANGES_REQUESTED}
   * through the same choke-point (with its own audit event).
   */
  @Transactional
  public Annotation decideAnnotation(UUID annotationId, AnnotationStatus decision, UUID actorId) {
    if (decision != AnnotationStatus.ACCEPTED && decision != AnnotationStatus.REJECTED) {
      throw new IllegalArgumentException("decision must be ACCEPTED or REJECTED, not " + decision);
    }
    Annotation annotation =
        annotations
            .findById(annotationId)
            .orElseThrow(() -> new AnnotationNotFoundException(annotationId));
    Document document = loadForUpdate(annotation.getDocumentId());
    if (!document.getOwnerId().equals(actorId) && !annotation.getAuthorId().equals(actorId)) {
      throw new AnnotationDecisionForbiddenException();
    }
    if (!ReviewWorkflowMachine.canDecide(annotation.getStatus())) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.ANNOTATION_ALREADY_DECIDED,
          "annotation " + annotationId + " is already " + annotation.getStatus());
    }
    if (decision == AnnotationStatus.ACCEPTED) {
      annotation.accept();
    } else {
      annotation.reject();
    }
    auditEvents.save(
        new AuditEvent(
            document.getId(),
            AUDIT_ANNOTATION_DECIDED,
            actorId,
            "{\"annotationId\":\"" + annotationId + "\",\"decision\":\"" + decision + "\"}"));
    ReviewWorkflowMachine.decisionDrivenTarget(document.getWorkflowState(), decision)
        .ifPresent(target -> applyTransition(document, target, actorId));
    return annotation;
  }

  private void applyTransition(Document document, WorkflowState target, UUID actorId) {
    String from = document.getWorkflowState();
    TransitionContext context =
        new TransitionContext(
            annotations.countByDocumentIdAndStatus(document.getId(), AnnotationStatus.OPEN),
            placements.countByDocumentIdAndStatus(document.getId(), PlacementStatus.PENDING),
            versions.existsByDocumentIdAndExtractionStatus(
                document.getId(), ExtractionStatus.READY));
    switch (machine.transition(from, target, context)) {
      case TransitionResult.Allowed allowed -> document.setWorkflowState(allowed.target());
      case TransitionResult.Denied denied ->
          throw new WorkflowTransitionException(
              WorkflowTransitionException.INVALID_TRANSITION, denied.reason());
    }
    auditEvents.save(
        new AuditEvent(
            document.getId(),
            AUDIT_WORKFLOW_TRANSITION,
            actorId,
            "{\"from\":\"" + from + "\",\"to\":\"" + target.name() + "\"}"));
  }

  private WorkflowStatus statusOf(Document document) {
    return new WorkflowStatus(
        document.getWorkflowState(),
        machine.allowedTransitions(document.getWorkflowState()).stream()
            .map(WorkflowState::name)
            .toList());
  }

  private Document load(UUID documentId) {
    return documents
        .findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  /**
   * Loads the document under a pessimistic write lock for a state-changing path (issue #324), so
   * the finalize-guard counts and the state write happen atomically relative to a concurrent
   * transition or a new-version upload (which also takes this lock before seeding pending
   * placements).
   */
  private Document loadForUpdate(UUID documentId) {
    return documents
        .findByIdForUpdate(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  private static void requireOwner(Document document, UUID actorId) {
    if (!document.getOwnerId().equals(actorId)) {
      throw new NotDocumentOwnerException();
    }
  }
}
