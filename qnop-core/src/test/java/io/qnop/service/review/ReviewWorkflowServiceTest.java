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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
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
  private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);

  private final ReviewWorkflowService service =
      new ReviewWorkflowService(
          documents,
          versions,
          annotations,
          placements,
          comments,
          auditEvents,
          documentAccess,
          mock(org.springframework.context.ApplicationEventPublisher.class),
          settings);

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
            () -> service.transition(documentId, WorkflowState.IN_REVIEW.name(), stranger, false))
        .isInstanceOf(NotDocumentOwnerException.class);
    assertThat(draft.getWorkflowState()).isEqualTo(WorkflowState.DRAFT.name());
    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("an admin may transition without being the owner, audited as themselves (#568)")
  void adminMayTransition() {
    Document draft = document(WorkflowState.DRAFT);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(draft));

    service.transition(documentId, WorkflowState.IN_REVIEW.name(), stranger, true);

    assertThat(draft.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
    assertThat(captureAudits().getValue().getActorId()).isEqualTo(stranger);
  }

  @Test
  @DisplayName("status scopes the capability to owner/admin and pre-evaluates guards (#568)")
  void statusCarriesCapabilityAndGuardVerdicts() {
    when(documents.findById(documentId)).thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));
    when(documentAccess.isVisible(eq(documentId), any(), anyBoolean())).thenReturn(true);
    when(annotations.countByDocumentIdAndStatus(any(), any())).thenReturn(3L);
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    ReviewWorkflowService.WorkflowStatus ownerView = service.status(documentId, owner, false);
    assertThat(ownerView.mayTransition()).isTrue();
    assertThat(ownerView.transitions())
        .anySatisfy(
            option -> {
              assertThat(option.targetState()).isEqualTo(WorkflowState.FINALIZED.name());
              assertThat(option.available()).isFalse();
              assertThat(option.blockedReason()).contains("3 open annotation");
            })
        .anySatisfy(
            option -> {
              assertThat(option.targetState()).isEqualTo(WorkflowState.CANCELLED.name());
              assertThat(option.available()).isTrue();
              assertThat(option.blockedReason()).isNull();
            });

    assertThat(service.status(documentId, stranger, false).mayTransition()).isFalse();
    assertThat(service.status(documentId, stranger, true).mayTransition()).isTrue();
  }

  @Test
  @DisplayName("an unknown target state is an INVALID_TRANSITION")
  void transitionRejectsUnknownTarget() {
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.DRAFT)));

    assertThatThrownBy(() -> service.transition(documentId, "NONSENSE", owner, false))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e -> assertThat(e.getCode()).isEqualTo(WorkflowTransitionException.INVALID_TRANSITION));
  }

  @Test
  @DisplayName("an allowed transition mutates the state and audits from→to")
  void transitionPersistsAndAudits() {
    Document draft = document(WorkflowState.DRAFT);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(draft));

    service.transition(documentId, WorkflowState.IN_REVIEW.name(), owner, false);

    assertThat(draft.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
    AuditEvent audit = captureAudits().getValue();
    assertThat(audit.getEventType()).isEqualTo(ReviewWorkflowService.AUDIT_WORKFLOW_TRANSITION);
    assertThat(audit.getDetail()).contains("DRAFT").contains("IN_REVIEW");
    assertThat(audit.getActorId()).isEqualTo(owner);
  }

  // --- terminal auto-close (#568) --------------------------------------------

  private Annotation openAnnotation(UUID authorId) {
    return new Annotation(documentId, authorId);
  }

  @Test
  @DisplayName("cancelling closes open annotations with an owner-attributed standard comment")
  void cancelAutoClosesOpenAnnotations() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    Annotation open = openAnnotation(stranger);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(annotations.findByDocumentIdAndStatus(any(), eq(AnnotationStatus.OPEN)))
        .thenReturn(java.util.List.of(open));

    service.transition(documentId, WorkflowState.CANCELLED.name(), owner, false);

    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.RESOLVED);
    ArgumentCaptor<Comment> comment = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(comment.capture());
    assertThat(comment.getValue().getAuthorId()).isEqualTo(owner);
    assertThat(comment.getValue().getBody())
        .isEqualTo(ReviewWorkflowService.AUTO_CLOSE_COMMENT_CANCELLED);
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_AUTO_CLOSED);
              assertThat(event.getActorId()).isEqualTo(owner);
              assertThat(event.getDetail()).contains("REVIEW_CANCELLED");
            });
  }

  @Test
  @DisplayName("finalize with the switch on closes open annotations, then passes the guard")
  void finalizeWithSwitchOnAutoCloses() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    Annotation open = openAnnotation(stranger);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(settings.getBoolean(ApplicationSettingKey.REVIEW_FINALIZE_WITH_OPEN_ANNOTATIONS))
        .thenReturn(true);
    when(annotations.findByDocumentIdAndStatus(any(), eq(AnnotationStatus.OPEN)))
        .thenReturn(java.util.List.of(open));
    // After the auto-close the guard recount must see zero open annotations.
    when(annotations.countByDocumentIdAndStatus(any(), eq(AnnotationStatus.OPEN))).thenReturn(0L);
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false);

    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.FINALIZED.name());
    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.RESOLVED);
    ArgumentCaptor<Comment> comment = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(comment.capture());
    assertThat(comment.getValue().getBody())
        .isEqualTo(ReviewWorkflowService.AUTO_CLOSE_COMMENT_FINALIZED);
  }

  @Test
  @DisplayName("finalize with the switch off keeps refusing open annotations untouched")
  void finalizeWithSwitchOffStillRefuses() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(annotations.countByDocumentIdAndStatus(any(), eq(AnnotationStatus.OPEN))).thenReturn(2L);
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    assertThatThrownBy(
            () -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false))
        .isInstanceOf(WorkflowTransitionException.class);
    verify(comments, never()).save(any());
    verify(annotations, never()).findByDocumentIdAndStatus(any(), any());
  }

  @Test
  @DisplayName("the switch never bypasses the pending-placement invariant")
  void finalizeWithSwitchOnStillBlockedByPendingPlacements() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(settings.getBoolean(ApplicationSettingKey.REVIEW_FINALIZE_WITH_OPEN_ANNOTATIONS))
        .thenReturn(true);
    when(placements.countByDocumentIdAndStatus(any(), any())).thenReturn(1L);
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    assertThatThrownBy(
            () -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false))
        .isInstanceOf(WorkflowTransitionException.class);
    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
  }

  // --- finalize guard --------------------------------------------------------

  @Test
  @DisplayName("finalize is allowed once no open work remains")
  void finalizeAllowedWhenClear() {
    Document inReview = document(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    // open annotations = 0 (default), pending placements = 0 (default), a READY version exists.
    when(versions.existsByDocumentIdAndExtractionStatus(any(), any())).thenReturn(true);

    service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false);

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

    assertThatThrownBy(
            () -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false))
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

    assertThatThrownBy(
            () -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner, false))
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

    assertThatThrownBy(() -> service.reopenAnnotation(UUID.randomUUID(), owner, false))
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

    service.reopenAnnotation(UUID.randomUUID(), owner, false);

    assertThat(resolved.getStatus()).isEqualTo(AnnotationStatus.OPEN);
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            e ->
                assertThat(e.getEventType())
                    .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_REOPENED));
  }

  @Test
  @DisplayName("an admin may reopen someone else's resolved annotation (#408)")
  void reopenResolvedByAdmin() {
    Annotation resolved = new Annotation(documentId, stranger);
    resolved.resolve();
    when(annotations.findById(any())).thenReturn(Optional.of(resolved));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    service.reopenAnnotation(UUID.randomUUID(), UUID.randomUUID(), true);

    assertThat(resolved.getStatus()).isEqualTo(AnnotationStatus.OPEN);
  }

  // --- dismiss (#408) --------------------------------------------------------

  @Test
  @DisplayName("the owner dismisses with an owner-attributed justification; the audit names them")
  void dismissClosesJustifiesAndAudits() {
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    service.dismissAnnotation(UUID.randomUUID(), "  author left the project  ", owner, false);

    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.DISMISSED);
    ArgumentCaptor<Comment> comment = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(comment.capture());
    assertThat(comment.getValue().getAuthorId()).isEqualTo(owner);
    assertThat(comment.getValue().getBody()).isEqualTo("author left the project");
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_DISMISSED);
              assertThat(event.getActorId()).isEqualTo(owner);
            });
  }

  @Test
  @DisplayName("an admin dismisses without being the owner; the comment stays owner-attributed")
  void dismissByAdminAuditsRealActor() {
    UUID adminUser = UUID.randomUUID();
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    service.dismissAnnotation(UUID.randomUUID(), "duplicate of #12", adminUser, true);

    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.DISMISSED);
    ArgumentCaptor<Comment> comment = ArgumentCaptor.forClass(Comment.class);
    verify(comments).save(comment.capture());
    assertThat(comment.getValue().getAuthorId()).isEqualTo(owner);
    assertThat(captureAudits().getAllValues())
        .anySatisfy(
            event -> {
              assertThat(event.getEventType())
                  .isEqualTo(ReviewWorkflowService.AUDIT_ANNOTATION_DISMISSED);
              assertThat(event.getActorId()).isEqualTo(adminUser);
            });
  }

  @Test
  @DisplayName("a non-owner non-admin (the author included) cannot dismiss")
  void dismissIsOwnerOrAdminOnly() {
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(
            () -> service.dismissAnnotation(UUID.randomUUID(), "justified", stranger, false))
        .isInstanceOf(NotDocumentOwnerException.class);
    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.OPEN);
    verify(comments, never()).save(any());
  }

  @Test
  @DisplayName("a dismissal without a justification is a 400 and changes nothing")
  void dismissRequiresJustification() {
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(() -> service.dismissAnnotation(UUID.randomUUID(), "   ", owner, false))
        .isInstanceOf(io.qnop.service.document.DocumentValidationException.class);
    assertThat(open.getStatus()).isEqualTo(AnnotationStatus.OPEN);
    verify(comments, never()).save(any());
    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("dismissing a non-OPEN annotation is a 409 ANNOTATION_NOT_OPEN")
  void dismissRejectsNonOpen() {
    Annotation resolved = new Annotation(documentId, stranger);
    resolved.resolve();
    when(annotations.findById(any())).thenReturn(Optional.of(resolved));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(() -> service.dismissAnnotation(UUID.randomUUID(), "late", owner, false))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e ->
                assertThat(e.getCode()).isEqualTo(WorkflowTransitionException.ANNOTATION_NOT_OPEN));
    verify(comments, never()).save(any());
  }

  @Test
  @DisplayName("dismissing the last open annotation re-derives CHANGES_REQUESTED → IN_REVIEW")
  void dismissRederivesWorkflow() {
    Document changesRequested = document(WorkflowState.CHANGES_REQUESTED);
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(changesRequested));
    // After the dismissal no annotation is OPEN — the derived pair flips back.
    when(annotations.countByDocumentIdAndStatus(any(), eq(AnnotationStatus.OPEN))).thenReturn(0L);

    service.dismissAnnotation(UUID.randomUUID(), "stale", owner, false);

    assertThat(changesRequested.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
  }

  @Test
  @DisplayName("the author reopens their dismissed annotation (objection right, #408)")
  void reopenDismissedByAuthor() {
    Annotation dismissed = new Annotation(documentId, stranger);
    dismissed.dismiss();
    when(annotations.findById(any())).thenReturn(Optional.of(dismissed));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    service.reopenAnnotation(UUID.randomUUID(), stranger, false);

    assertThat(dismissed.getStatus()).isEqualTo(AnnotationStatus.OPEN);
  }

  @Test
  @DisplayName("the owner may not reverse their own dismissal — reopen stays author/admin")
  void reopenDismissedByOwnerForbidden() {
    Annotation dismissed = new Annotation(documentId, stranger);
    dismissed.dismiss();
    when(annotations.findById(any())).thenReturn(Optional.of(dismissed));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    assertThatThrownBy(() -> service.reopenAnnotation(UUID.randomUUID(), owner, false))
        .isInstanceOf(AnnotationActionForbiddenException.class);
    assertThat(dismissed.getStatus()).isEqualTo(AnnotationStatus.DISMISSED);
  }

  @Test
  @DisplayName("an admin reopens a dismissed annotation")
  void reopenDismissedByAdmin() {
    Annotation dismissed = new Annotation(documentId, stranger);
    dismissed.dismiss();
    when(annotations.findById(any())).thenReturn(Optional.of(dismissed));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.IN_REVIEW)));

    service.reopenAnnotation(UUID.randomUUID(), UUID.randomUUID(), true);

    assertThat(dismissed.getStatus()).isEqualTo(AnnotationStatus.OPEN);
  }

  @Test
  @DisplayName("a closed review refuses a dismissal (REVIEW_CLOSED, defensive)")
  void dismissRequiresOpenReview() {
    Annotation open = new Annotation(documentId, stranger);
    when(annotations.findById(any())).thenReturn(Optional.of(open));
    when(documents.findByIdForUpdate(documentId))
        .thenReturn(Optional.of(document(WorkflowState.CANCELLED)));

    assertThatThrownBy(() -> service.dismissAnnotation(UUID.randomUUID(), "late", owner, false))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e -> assertThat(e.getCode()).isEqualTo(WorkflowTransitionException.REVIEW_CLOSED));
  }
}
