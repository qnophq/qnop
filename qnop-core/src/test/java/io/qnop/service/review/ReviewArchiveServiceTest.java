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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewArchiveServiceTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID DOC = UUID.randomUUID();

  @Mock private SchedulerService scheduler;
  @Mock private DocumentRepository documents;
  @Mock private AuditEventRepository auditEvents;
  @Mock private ApplicationSettingsService settings;
  @Mock private DocumentAccessService access;

  private ReviewArchiveService service() {
    return new ReviewArchiveService(scheduler, documents, auditEvents, settings, access);
  }

  private static Document closed(WorkflowState state) {
    Document d = new Document(OWNER, "Report");
    d.setWorkflowState(state);
    return d;
  }

  // --- the scheduled sweep -------------------------------------------------

  @Test
  void retentionZeroDisablesTheSweep() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS)).thenReturn(0);

    service().archiveOnce(false);

    verify(documents, never()).findByArchivedAtIsNullAndClosedAtBefore(any(), any());
    verify(documents, never()).countByArchivedAtIsNullAndClosedAtBefore(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void dryRunCountsButArchivesNothing() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS)).thenReturn(90);
    when(documents.countByArchivedAtIsNullAndClosedAtBefore(any())).thenReturn(3L);

    service().archiveOnce(true);

    verify(documents, never()).findByArchivedAtIsNullAndClosedAtBefore(any(), any());
    verify(documents, never()).saveAll(any());
    verifyNoInteractions(auditEvents);
  }

  @Test
  void sweepArchivesEligibleReviewsWithASystemActor() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS)).thenReturn(90);
    Document a = closed(WorkflowState.FINALIZED);
    Document b = closed(WorkflowState.CANCELLED);
    when(documents.findByArchivedAtIsNullAndClosedAtBefore(any(), any())).thenReturn(List.of(a, b));

    service().archiveOnce(false);

    assertThat(a.getArchivedAt()).isNotNull();
    assertThat(b.getArchivedAt()).isNotNull();
    verify(documents).saveAll(List.of(a, b));
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents, times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(
            e -> {
              assertThat(e.getEventType()).isEqualTo(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
              assertThat(e.getActorId()).isNull(); // system-driven
            });
  }

  @Test
  void sweepDoesNothingWhenNoneEligible() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS)).thenReturn(90);
    when(documents.findByArchivedAtIsNullAndClosedAtBefore(any(), any())).thenReturn(List.of());

    service().archiveOnce(false);

    verify(documents, never()).saveAll(any());
    verifyNoInteractions(auditEvents);
  }

  // --- manual archive / unarchive -----------------------------------------

  @Test
  void manualArchiveFlagsAClosedReviewWithTheActingUser() {
    Document doc = closed(WorkflowState.FINALIZED);
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));
    when(documents.save(doc)).thenReturn(doc);

    service().archive(DOC, OWNER, false);

    assertThat(doc.getArchivedAt()).isNotNull();
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    assertThat(captor.getValue().getEventType())
        .isEqualTo(ReviewArchiveService.AUDIT_REVIEW_ARCHIVED);
    assertThat(captor.getValue().getActorId()).isEqualTo(OWNER);
  }

  @Test
  void manualArchiveRefusesAnOpenReview() {
    Document doc = closed(WorkflowState.IN_REVIEW);
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));

    assertThatThrownBy(() -> service().archive(DOC, OWNER, false))
        .isInstanceOf(WorkflowTransitionException.class)
        .extracting("code")
        .isEqualTo(WorkflowTransitionException.REVIEW_NOT_CLOSED);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void manualArchiveRefusesAnAlreadyArchivedReview() {
    Document doc = closed(WorkflowState.FINALIZED);
    doc.setArchivedAt(Instant.now());
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));

    assertThatThrownBy(() -> service().archive(DOC, OWNER, false))
        .isInstanceOf(WorkflowTransitionException.class)
        .extracting("code")
        .isEqualTo(WorkflowTransitionException.REVIEW_ALREADY_ARCHIVED);
  }

  @Test
  void manualArchiveHidesAReviewFromNonParticipants() {
    // A caller who cannot see the review must not be told it exists (issue #661):
    // the same 404 the read and delete paths give, not a 403 that confirms the id.
    Document doc = closed(WorkflowState.FINALIZED);
    UUID stranger = UUID.randomUUID();
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));
    // access.isVisible defaults to false — the stranger is not a participant.

    assertThatThrownBy(() -> service().archive(DOC, stranger, false))
        .isInstanceOf(DocumentNotFoundException.class);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void manualArchiveRejectsAParticipantWhoIsNotTheOwner() {
    // A reviewer can already see the review, so the honest 403 (owner's call) leaks
    // nothing — the branch that must stay a 403 rather than collapse to 404.
    Document doc = closed(WorkflowState.FINALIZED);
    UUID reviewer = UUID.randomUUID();
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));
    when(access.isVisible(DOC, reviewer, false)).thenReturn(true);

    assertThatThrownBy(() -> service().archive(DOC, reviewer, false))
        .isInstanceOf(NotDocumentOwnerException.class);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void adminMayArchiveAnyReview() {
    Document doc = closed(WorkflowState.FINALIZED);
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));
    when(documents.save(doc)).thenReturn(doc);

    service().archive(DOC, UUID.randomUUID(), true);

    assertThat(doc.getArchivedAt()).isNotNull();
  }

  @Test
  void unarchiveClearsTheFlag() {
    Document doc = closed(WorkflowState.FINALIZED);
    doc.setArchivedAt(Instant.now());
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));
    when(documents.save(doc)).thenReturn(doc);

    service().unarchive(DOC, OWNER, false);

    assertThat(doc.getArchivedAt()).isNull();
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    assertThat(captor.getValue().getEventType())
        .isEqualTo(ReviewArchiveService.AUDIT_REVIEW_UNARCHIVED);
  }

  @Test
  void unarchiveRefusesAReviewThatIsNotArchived() {
    Document doc = closed(WorkflowState.FINALIZED);
    when(documents.findById(DOC)).thenReturn(Optional.of(doc));

    assertThatThrownBy(() -> service().unarchive(DOC, OWNER, false))
        .isInstanceOf(WorkflowTransitionException.class)
        .extracting("code")
        .isEqualTo(WorkflowTransitionException.REVIEW_NOT_ARCHIVED);
    verifyNoInteractions(auditEvents);
  }

  @Test
  void scheduledEntryPointDelegatesToTheGate() {
    service().archive();
    verify(scheduler)
        .runScheduled(eq(io.qnop.service.scheduler.SchedulerJobCatalog.REVIEW_ARCHIVE));
  }
}
