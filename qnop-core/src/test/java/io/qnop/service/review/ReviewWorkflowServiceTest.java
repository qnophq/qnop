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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ReviewWorkflowService} (issue #349): owner-only transition authorization,
 * the finalize guard fed by open-work counts, transition persistence + audit format, and the
 * annotation resolve/reopen author checks. The pessimistic locking mechanism is covered by {@link
 * ReviewWorkflowServiceLockTest}; the state machine itself by {@code ReviewWorkflowMachineTest}.
 * Non-strict {@code mock()} wiring matches the lock test — the finalize context counts are always
 * computed, so lenient stubbing keeps the tests readable.
 */
class ReviewWorkflowServiceTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final AnnotationRepository annotations = mock(AnnotationRepository.class);
  private final AnnotationPlacementRepository placements =
      mock(AnnotationPlacementRepository.class);
  private final CommentRepository comments = mock(CommentRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final DocumentAccessService documentAccess = mock(DocumentAccessService.class);

  private final ReviewWorkflowService service =
      new ReviewWorkflowService(
          documents,
          versions,
          annotations,
          placements,
          comments,
          auditEvents,
          documentAccess,
          mock(org.springframework.context.ApplicationEventPublisher.class));

  private final UUID documentId = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();

  private Document document(WorkflowState state) {
    Document document = new Document(owner, "Review");
    document.setWorkflowState(state);
    return document;
  }

  private ArgumentCaptor<AuditEvent> captureAudits() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents, atLeastOnce()).save(captor.capture());
    return captor;
  }

  // --- status ----------------------------------------------------------------

  @Test
  @DisplayName("status hides an invisible document behind a 404")
  void statusRejectsInvisibleDocument() {
    when(documents.findById(documentId)).thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));
    when(documentAccess.isVisible(documentId, stranger, false)).thenReturn(false);

    assertThatThrownBy(() -> service.status(documentId, stranger, false))
        .isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  @DisplayName("status returns the state for a visible document without auditing")
  void statusReturnsStateForVisibleDocument() {
    when(documents.findById(documentId)).thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));
    when(documentAccess.isVisible(documentId, owner, false)).thenReturn(true);

    assertThat(service.status(documentId, owner, false)).isNotNull();
    verify(auditEvents, never()).save(any());
  }

  // --- transition authorization ---------------------------------------------

  @Test
  @DisplayName("a non-owner cannot transition the workflow")
  void transitionIsOwnerOnly() {
    Document draft = document(WorkflowState.DRAFT);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(draft));

    assertThatThrownBy(
            () -> service.transition(documentId, WorkflowState.IN_REVIEW.name(), stranger))
        .isInstanceOf(NotDocumentOwnerException.class);
    assertThat(draft.getWorkflowState()).isEqualTo(WorkflowState.DRAFT.name());
    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("an unknown target state is an INVALID_TRANSITION")
  void transitionRejectsUnknownTarget() {
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.DRAFT)));

    assertThatThrownBy(() -> service.transition(documentId, "NONSENSE", owner))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e -> assertThat(e.getCode()).isEqualTo(WorkflowTransitionException.INVALID_TRANSITION));
  }

  @Test
  @DisplayName("an allowed transition mutates the state and audits from→to")
  void transitionPersistsAndAudits() {
    Document draft = document(WorkflowState.DRAFT);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(draft));

    service.transition(documentId, WorkflowState.IN_REVIEW.name(), owner);

    assertThat(draft.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
    AuditEvent audit = captureAudits().getValue();
    assertThat(audit.getEventType()).isEqualTo(ReviewWorkflowService.AUDIT_WORKFLOW_TRANSITION);
    assertThat(audit.getDetail()).contains("DRAFT").contains("IN_REVIEW");
    assertThat(audit.getActorId()).isEqualTo(owner);
  }

  // --- finalize guard --------------------------------------------------------

  @Test
  @DisplayName("finalize is allowed once no open work remains")
  void finalizeAllowedWhenClear() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    // open annotations = 0 (default), pending placements = 0 (default), a READY version exists.
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    service.transition(documentId, WorkflowState.FINALIZED.name(), owner);

    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.FINALIZED.name());
    verify(auditEvents).save(any());
  }

  @Test
  @DisplayName("finalize is blocked by a pending placement")
  void finalizeBlockedByPendingPlacement() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(placements.countByDocumentIdAndStatus(any(), any())).thenReturn(1L);
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    assertThatThrownBy(() -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner))
        .isInstanceOf(WorkflowTransitionException.class);
    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("finalize is blocked without a READY version")
  void finalizeBlockedWithoutReadyVersion() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    // no counts stubbed: 0 open, 0 pending, but existsBy...READY defaults to false.

    assertThatThrownBy(() -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner))
        .isInstanceOf(WorkflowTransitionException.class);
    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
  }

  // --- resolve / reopen ------------------------------------------------------

  @Test
  @DisplayName("only the author may resolve their annotation")
  void resolveIsAuthorOnly() {
    Annotation othersAnnotation = new Annotation(documentId, owner);
    when(annotations.findById(any())).thenReturn(Optional.of(othersAnnotation));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(() -> service.resolveAnnotation(UUID.randomUUID(), null, stranger))
        .isInstanceOf(AnnotationActionForbiddenException.class);
  }

  @Test
  @DisplayName("resolving an unknown annotation is 404")
  void resolveUnknownAnnotationIs404() {
    when(annotations.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveAnnotation(UUID.randomUUID(), null, owner))
        .isInstanceOf(AnnotationNotFoundException.class);
    verify(documents, never()).findByIdForUpdate(any());
  }

  @Test
  @DisplayName("resolving closes the annotation, records the note and audits annotation.resolved")
  void resolveClosesAndAudits() {
    Annotation annotation = new Annotation(documentId, owner);
    when(annotations.findById(any())).thenReturn(Optional.of(annotation));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.DRAFT)));

    service.resolveAnnotation(UUID.randomUUID(), "looks good now", owner);

    assertThat(annotation.getStatus()).isEqualTo(AnnotationStatus.RESOLVED);
    verify(comments).save(any());
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            e ->
                assertThat(e.getEventType())
                    .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_RESOLVED));
  }

  @Test
  @DisplayName("reopening a still-open annotation is rejected")
  void reopenRejectsNonResolved() {
    Annotation open = new Annotation(documentId, owner);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(() -> service.reopenAnnotation(UUID.randomUUID(), owner))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e ->
                assertThat(e.getCode())
                    .isEqualTo(WorkflowTransitionException.ANNOTATION_NOT_RESOLVED));
  }

  @Test
  @DisplayName("reopening a resolved annotation reopens it and audits annotation.reopened")
  void reopenReopensAndAudits() {
    Annotation resolved = new Annotation(documentId, owner);
    resolved.resolve();
    when(annotations.findById(any())).thenReturn(Optional.of(resolved));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.CHANGES_REQUESTED)));
    when(annotations.countByDocumentIdAndStatus(any(), any())).thenReturn(1L);

    service.reopenAnnotation(UUID.randomUUID(), owner);

    assertThat(resolved.getStatus()).isEqualTo(AnnotationStatus.OPEN);
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            e ->
                assertThat(e.getEventType())
                    .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_REOPENED));
  }
}
