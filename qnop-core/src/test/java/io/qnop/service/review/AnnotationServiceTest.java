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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link AnnotationService} (issue #349): the mandatory first comment, visibility
 * delegated to {@link DocumentAccessService} (non-participant → 404), the latest-version-only
 * guard, the placement-status list filter, classification authorization, closed-thread comment
 * refusal and the audit events. Non-strict {@code mock()} wiring keeps the eight collaborators
 * readable; the identity/anonymity resolver is mocked (its exposed id / display name are nullable
 * in a view).
 */
class AnnotationServiceTest {

  private final AnnotationRepository annotations = mock(AnnotationRepository.class);
  private final AnnotationPlacementRepository placements =
      mock(AnnotationPlacementRepository.class);
  private final CommentRepository comments = mock(CommentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final DocumentAccessService documentAccess = mock(DocumentAccessService.class);
  private final ReviewWorkflowService workflow = mock(ReviewWorkflowService.class);
  private final ReviewIdentityResolver identity = mock(ReviewIdentityResolver.class);
  private final ReactionService reactions = mock(ReactionService.class);

  private final AnnotationService service =
      new AnnotationService(
          annotations,
          placements,
          comments,
          versions,
          auditEvents,
          documentAccess,
          workflow,
          identity,
          reactions);

  private final UUID documentId = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();

  private DocumentAccessService.DocumentView documentView(
      UUID ownerId, String threadParticipation) {
    return new DocumentAccessService.DocumentView(
        documentId,
        "Doc",
        null,
        false,
        threadParticipation,
        ownerId,
        "IN_REVIEW",
        1,
        null,
        null,
        null);
  }

  /** Stubs the visibility check to succeed with a document owned by {@code ownerId}. */
  private void visibleDocument(UUID ownerId, String threadParticipation) {
    when(documentAccess.getDocument(any(), any(), anyBoolean()))
        .thenReturn(documentView(ownerId, threadParticipation));
  }

  private void resolvableIdentities() {
    when(identity.forDocument(any(), any()))
        .thenReturn(mock(ReviewIdentityResolver.ReviewIdentities.class));
  }

  private DocumentVersion version(int number) {
    return new DocumentVersion(
        documentId, number, "key-" + number, "hash", "application/pdf", 1L, author);
  }

  // --- create ----------------------------------------------------------------

  @Test
  @DisplayName("create refuses a blank first comment (an annotation must have text)")
  void createRejectsBlankFirstComment() {
    assertThatThrownBy(() -> service.create(documentId, 1, author, false, "{}", "  ", null, null))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
  }

  @Test
  @DisplayName("create surfaces the delegated 404 for a non-participant")
  void createRejectsNonParticipant() {
    when(documentAccess.getDocument(any(), any(), anyBoolean()))
        .thenThrow(DocumentValidationException.notFound("no such document"));

    assertThatThrownBy(() -> service.create(documentId, 1, stranger, false, "{}", "hi", null, null))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
  }

  @Test
  @DisplayName("create is 404 for an unknown version")
  void createRejectsUnknownVersion() {
    visibleDocument(owner, "OPEN");
    when(versions.findByDocumentIdAndVersionNumber(documentId, 9)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(documentId, 9, author, false, "{}", "hi", null, null))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
  }

  @Test
  @DisplayName("create is 409 VERSION_READ_ONLY for a non-latest version")
  void createRejectsNonLatestVersion() {
    visibleDocument(owner, "OPEN");
    when(versions.findByDocumentIdAndVersionNumber(documentId, 1))
        .thenReturn(Optional.of(version(1)));
    when(versions.findTopByDocumentIdOrderByVersionNumberDesc(documentId))
        .thenReturn(Optional.of(version(2)));

    assertThatThrownBy(() -> service.create(documentId, 1, author, false, "{}", "hi", null, null))
        .isInstanceOfSatisfying(
            DocumentValidationException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(409);
              assertThat(e.getCode()).isEqualTo("VERSION_READ_ONLY");
            });
  }

  @Test
  @DisplayName("create persists the annotation, its first comment (count 1) and the audit")
  void createPersistsAnnotationCommentAndAudit() {
    visibleDocument(owner, "OPEN");
    resolvableIdentities();
    when(versions.findByDocumentIdAndVersionNumber(documentId, 1))
        .thenReturn(Optional.of(version(1)));
    when(versions.findTopByDocumentIdOrderByVersionNumberDesc(documentId))
        .thenReturn(Optional.of(version(1)));
    when(annotations.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    AnnotationService.AnnotationView view =
        service.create(documentId, 1, author, false, "{\"region\":1}", "first note", null, null);

    assertThat(view.commentCount()).isEqualTo(1);
    verify(placements).save(any());
    verify(comments).save(any());
    verify(workflow).annotationRaised(documentId, author);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(audit.capture());
    assertThat(audit.getValue().getEventType())
        .isEqualTo(AnnotationService.AUDIT_ANNOTATION_CREATED);
  }

  // --- list ------------------------------------------------------------------

  @Test
  @DisplayName("list requires a version when a placement status is given")
  void listRejectsPlacementStatusWithoutVersion() {
    assertThatThrownBy(() -> service.list(documentId, null, "ORPHANED", null, author, false))
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
  }

  @Test
  @DisplayName("list filters annotations by placement status")
  void listFiltersByPlacementStatus() {
    UUID versionId = UUID.randomUUID();
    DocumentVersion version = mock(DocumentVersion.class);
    when(version.getId()).thenReturn(versionId);
    visibleDocument(owner, "OPEN");
    resolvableIdentities();
    when(versions.findByDocumentIdAndVersionNumber(documentId, 1)).thenReturn(Optional.of(version));
    when(annotations.findByDocumentId(documentId))
        .thenReturn(List.of(new Annotation(documentId, author)));
    AnnotationPlacement orphaned = new AnnotationPlacement(null, versionId, "{}");
    orphaned.markOrphaned();
    when(placements.findByDocumentVersionId(versionId)).thenReturn(List.of(orphaned));

    assertThat(service.list(documentId, 1, "ORPHANED", null, owner, false)).hasSize(1);
    assertThat(service.list(documentId, 1, "PENDING", null, owner, false)).isEmpty();
  }

  @Test
  @DisplayName("list surfaces the delegated 404 for a non-participant")
  void listRejectsNonParticipant() {
    when(documentAccess.getDocument(any(), any(), anyBoolean()))
        .thenThrow(DocumentValidationException.notFound("no such document"));

    assertThatThrownBy(() -> service.list(documentId, null, null, null, stranger, false))
        .isInstanceOf(DocumentValidationException.class);
  }

  // --- updateClassification --------------------------------------------------

  @Test
  @DisplayName("only the owner or the author may classify an annotation")
  void classifyIsOwnerOrAuthorOnly() {
    when(annotations.findById(any())).thenReturn(Optional.of(new Annotation(documentId, author)));
    visibleDocument(owner, "OPEN");

    assertThatThrownBy(
            () -> service.updateClassification(UUID.randomUUID(), stranger, false, "BUG", null))
        .isInstanceOf(AnnotationActionForbiddenException.class);
  }

  @Test
  @DisplayName("a resolved annotation cannot be reclassified")
  void classifyRejectsResolvedAnnotation() {
    Annotation resolved = new Annotation(documentId, author);
    resolved.resolve();
    when(annotations.findById(any())).thenReturn(Optional.of(resolved));
    visibleDocument(owner, "OPEN");

    assertThatThrownBy(
            () -> service.updateClassification(UUID.randomUUID(), author, false, "BUG", null))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e ->
                assertThat(e.getCode())
                    .isEqualTo(WorkflowTransitionException.ANNOTATION_ALREADY_RESOLVED));
  }

  @Test
  @DisplayName("the author reclassifies an open annotation and it is audited")
  void classifyUpdatesAndAudits() {
    when(annotations.findById(any())).thenReturn(Optional.of(new Annotation(documentId, author)));
    visibleDocument(owner, "OPEN");
    resolvableIdentities();

    service.updateClassification(UUID.randomUUID(), author, false, null, null);

    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents, atLeastOnce()).save(audit.capture());
    assertThat(audit.getValue().getEventType())
        .isEqualTo(AnnotationService.AUDIT_ANNOTATION_CLASSIFIED);
  }

  // --- addComment ------------------------------------------------------------

  @Test
  @DisplayName("addComment surfaces the delegated 404 for a non-participant")
  void addCommentRejectsNonParticipant() {
    when(annotations.findById(any())).thenReturn(Optional.of(new Annotation(documentId, author)));
    when(documentAccess.getDocument(any(), any(), anyBoolean()))
        .thenThrow(DocumentValidationException.notFound("no such document"));

    assertThatThrownBy(() -> service.addComment(UUID.randomUUID(), stranger, false, "hi"))
        .isInstanceOf(DocumentValidationException.class);
  }

  @Test
  @DisplayName("addComment refuses a resolved annotation's closed thread")
  void addCommentRejectsClosedThread() {
    Annotation resolved = new Annotation(documentId, author);
    resolved.resolve();
    when(annotations.findById(any())).thenReturn(Optional.of(resolved));
    visibleDocument(owner, "OPEN");

    assertThatThrownBy(() -> service.addComment(UUID.randomUUID(), author, false, "more"))
        .isInstanceOfSatisfying(
            WorkflowTransitionException.class,
            e ->
                assertThat(e.getCode())
                    .isEqualTo(WorkflowTransitionException.ANNOTATION_ALREADY_RESOLVED));
  }

  @Test
  @DisplayName("addComment appends to an open thread")
  void addCommentAppendsToOpenThread() {
    when(annotations.findById(any())).thenReturn(Optional.of(new Annotation(documentId, author)));
    visibleDocument(owner, "OPEN");
    resolvableIdentities();
    when(comments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    AnnotationService.CommentView view =
        service.addComment(UUID.randomUUID(), author, false, "a reply");

    assertThat(view.body()).isEqualTo("a reply");
    verify(comments).saveAndFlush(any(Comment.class));
  }
}
