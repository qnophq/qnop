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
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
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
 * {@link AuditEvent} per transition. Explicit state changes are owner-only; the {@code IN_REVIEW ⇄
 * CHANGES_REQUESTED} pair is derived from the open-annotation count instead (issue #405), and an
 * annotation is resolved by its author alone. State-changing paths load the document under a
 * pessimistic write lock ({@link DocumentRepository#findByIdForUpdate}), so the guard counts and
 * the state write are atomic against a concurrent transition or a new-version upload that would
 * change the pending-placement picture (issue #324).
 */
@Service
public class ReviewWorkflowService {

  /** Audit event type for a workflow state change; detail carries {@code {"from","to"}}. */
  static final String AUDIT_WORKFLOW_TRANSITION = "workflow.transition";

  /** Audit event type for an annotation resolution; detail carries the annotation id. */
  static final String AUDIT_ANNOTATION_RESOLVED = "annotation.resolved";

  private final ReviewWorkflowMachine machine = new ReviewWorkflowMachine();

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AnnotationRepository annotations;
  private final AnnotationPlacementRepository placements;
  private final CommentRepository comments;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService documentAccess;

  public ReviewWorkflowService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AnnotationRepository annotations,
      AnnotationPlacementRepository placements,
      CommentRepository comments,
      AuditEventRepository auditEvents,
      DocumentAccessService documentAccess) {
    this.documents = documents;
    this.versions = versions;
    this.annotations = annotations;
    this.placements = placements;
    this.comments = comments;
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
   * Requests an explicit transition to {@code targetRaw}, owner-only. A target this edition does
   * not know, an illegal edge, the derived {@code IN_REVIEW ⇄ CHANGES_REQUESTED} pair (issue #405),
   * or a vetoing guard (the FINALIZED invariant) is rejected with {@link
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
    applyTransition(document, target, actorId, true);
    return statusOf(document);
  }

  /**
   * Resolves an annotation — the {@code OPEN → RESOLVED} sub-machine (ADR-0011 as amended by issue
   * #405), permitted for the annotation's author alone: the author raised the concern, so only they
   * know when it is settled. An optional closing note lands as a regular comment in the thread; the
   * open-annotation count then re-derives the {@code IN_REVIEW ⇄ CHANGES_REQUESTED} pair through
   * the same choke-point (with its own audit event).
   */
  @Transactional
  public Annotation resolveAnnotation(UUID annotationId, String note, UUID actorId) {
    Annotation annotation =
        annotations
            .findById(annotationId)
            .orElseThrow(() -> new AnnotationNotFoundException(annotationId));
    Document document = loadForUpdate(annotation.getDocumentId());
    if (!annotation.getAuthorId().equals(actorId)) {
      throw new AnnotationActionForbiddenException("Only the annotation's author may resolve it");
    }
    if (!ReviewWorkflowMachine.canResolve(annotation.getStatus())) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.ANNOTATION_ALREADY_RESOLVED,
          "annotation " + annotationId + " is already " + annotation.getStatus());
    }
    if (note != null && !note.isBlank()) {
      comments.save(new Comment(annotationId, actorId, note.trim()));
    }
    annotation.resolve();
    auditEvents.save(
        new AuditEvent(
            document.getId(),
            AUDIT_ANNOTATION_RESOLVED,
            actorId,
            "{\"annotationId\":\"" + annotationId + "\"}"));
    rederiveWorkflow(document, actorId);
    return annotation;
  }

  /**
   * Registers a freshly raised annotation with the workflow (issue #405): takes the document write
   * lock, refuses the annotation when the review is closed ({@code FINALIZED} or {@code CANCELLED}
   * — the caller's transaction rolls the insert back), and re-derives the {@code IN_REVIEW ⇄
   * CHANGES_REQUESTED} pair. Runs inside {@code AnnotationService.create}'s transaction, after the
   * insert.
   */
  @Transactional
  public void annotationRaised(UUID documentId, UUID actorId) {
    Document document = loadForUpdate(documentId);
    boolean closed =
        WorkflowState.fromString(document.getWorkflowState())
            .map(state -> state == WorkflowState.FINALIZED || state == WorkflowState.CANCELLED)
            .orElse(false);
    if (closed) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.REVIEW_CLOSED,
          "the review is " + document.getWorkflowState() + "; it accepts no new annotations");
    }
    rederiveWorkflow(document, actorId);
  }

  /**
   * Re-derives the annotation-driven workflow pair (issue #405) from the current open-annotation
   * count; a no-op outside {@code IN_REVIEW}/{@code CHANGES_REQUESTED}. The caller holds the
   * document write lock, so the count and the state write are atomic (issue #324).
   */
  private void rederiveWorkflow(Document document, UUID actorId) {
    long open = annotations.countByDocumentIdAndStatus(document.getId(), AnnotationStatus.OPEN);
    ReviewWorkflowMachine.annotationDrivenTarget(document.getWorkflowState(), open)
        .ifPresent(target -> applyTransition(document, target, actorId, false));
  }

  private void applyTransition(
      Document document, WorkflowState target, UUID actorId, boolean manual) {
    String from = document.getWorkflowState();
    TransitionContext context =
        new TransitionContext(
            annotations.countByDocumentIdAndStatus(document.getId(), AnnotationStatus.OPEN),
            placements.countByDocumentIdAndStatus(document.getId(), PlacementStatus.PENDING),
            versions.existsByDocumentIdAndExtractionStatus(
                document.getId(), ExtractionStatus.READY));
    TransitionResult result =
        manual
            ? machine.manualTransition(from, target, context)
            : machine.transition(from, target, context);
    switch (result) {
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
