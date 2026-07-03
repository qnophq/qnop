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
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every state-changing workflow path loads the document under the pessimistic write
 * lock (issue #324) — {@code findByIdForUpdate}, not the plain {@code findById} — so the finalize
 * guard's counts and the state write are serialized against concurrent transitions and uploads. The
 * database enforces the actual mutual exclusion; this test locks in the mechanism.
 */
class ReviewWorkflowServiceLockTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final AnnotationRepository annotations = mock(AnnotationRepository.class);
  private final AnnotationPlacementRepository placements =
      mock(AnnotationPlacementRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);

  private final ReviewWorkflowService service =
      new ReviewWorkflowService(documents, versions, annotations, placements, auditEvents);

  private final UUID documentId = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  @Test
  @DisplayName("a workflow transition loads the document under the write lock")
  void transitionAcquiresTheWriteLock() {
    Document document = new Document(owner, "Locked review"); // DRAFT, owned by the actor
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(document));

    service.transition(documentId, WorkflowState.CANCELLED.name(), owner);

    verify(documents).findByIdForUpdate(documentId);
    verify(documents, never()).findById(any());
    assertThat(document.getWorkflowState()).isEqualTo(WorkflowState.CANCELLED.name());
    verify(auditEvents).save(any());
  }

  @Test
  @DisplayName("reading the status uses the unlocked read path")
  void statusUsesUnlockedRead() {
    Document document = new Document(owner, "Readable review");
    when(documents.findById(documentId)).thenReturn(Optional.of(document));

    service.status(documentId);

    verify(documents).findById(documentId);
    verify(documents, never()).findByIdForUpdate(any());
    verify(auditEvents, never()).save(any());
  }

  @Test
  @DisplayName("deciding an annotation loads its document under the write lock")
  void decideAnnotationAcquiresTheWriteLock() {
    Annotation annotation = new Annotation(documentId, owner);
    Document document = new Document(owner, "Decided review");
    when(annotations.findById(any())).thenReturn(Optional.of(annotation));
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(document));

    service.decideAnnotation(UUID.randomUUID(), AnnotationStatus.REJECTED, owner);

    verify(documents).findByIdForUpdate(documentId);
    verify(documents, never()).findById(any());
  }

  @Test
  @DisplayName("the machine still guards transitions loaded under the lock")
  void lockedTransitionStillHonoursGuards() {
    Document inReview = new Document(owner, "Guarded review");
    inReview.setWorkflowState(WorkflowState.IN_REVIEW);
    when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(inReview));
    when(annotations.countByDocumentIdAndStatus(any(), any())).thenReturn(1L); // an open annotation

    assertThatThrownBy(() -> service.transition(documentId, WorkflowState.FINALIZED.name(), owner))
        .isInstanceOf(WorkflowTransitionException.class)
        .hasMessageContaining("open");
    verify(documents).findByIdForUpdate(documentId);
    // guard denied → no state change persisted, but the lock was still taken
    assertThat(inReview.getWorkflowState()).isEqualTo(WorkflowState.IN_REVIEW.name());
    verify(auditEvents, never()).save(any(AuditEvent.class));
  }
}
