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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.AuditScope;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AttachmentStorageRef;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.VersionStorageRef;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.storage.StorageService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * DB-free unit tests for the archived-review purge (issue #577, ADR-0050): the retention window and
 * its {@code 0} kill switch, the dry run's zero side effects, the shared-vs-exclusive storage-key
 * decision, the eligibility re-check inside the per-review transaction, and the SYSTEM audit
 * record.
 *
 * <p>A mock {@link PlatformTransactionManager} runs each {@code TransactionTemplate} callback
 * in-line, so the per-review transaction boundaries are exercised without a database. The
 * shared-key behaviour against a real Postgres + MinIO lives in {@code ReviewPurgeApiIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewPurgeServiceTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final String KEY_A = "sha256/aa/aaaa";
  private static final String KEY_B = "sha256/bb/bbbb";

  @Mock private SchedulerService scheduler;
  @Mock private DocumentRepository documents;
  @Mock private DocumentVersionRepository versions;
  @Mock private DocumentAttachmentRepository attachments;
  @Mock private AuditEventRepository auditEvents;
  @Mock private ApplicationSettingsService settings;
  @Mock private StorageService storage;
  @Mock private PlatformTransactionManager transactionManager;

  private ReviewPurgeService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    // The real deletion service over the same mocks (issue #421): the sweep
    // delegates to it, so the assertions below still watch the storage calls the
    // sweep is responsible for.
    ReviewDeletionService deletions =
        new ReviewDeletionService(
            documents,
            versions,
            attachments,
            auditEvents,
            org.mockito.Mockito.mock(io.qnop.service.document.DocumentAccessService.class),
            storage,
            transactionManager);
    service =
        new ReviewPurgeService(
            scheduler,
            documents,
            versions,
            attachments,
            auditEvents,
            settings,
            deletions,
            transactionManager);
    when(settings.getInteger(ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS))
        .thenReturn(180);
  }

  /** An archived review with a real id, since the sweep addresses documents by id. */
  private static Document archived(String title, long daysAgo) {
    Document document = new Document(OWNER, title);
    document.setWorkflowState(WorkflowState.FINALIZED);
    document.setClosedAt(Instant.now().minus(Duration.ofDays(daysAgo + 1)));
    document.setArchivedAt(Instant.now().minus(Duration.ofDays(daysAgo)));
    setId(document, UUID.randomUUID());
    return document;
  }

  private static void setId(Document document, UUID id) {
    try {
      Field field = Document.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(document, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Wires the batch read and the per-review re-read for one eligible review. */
  private void eligible(Document document) {
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class)))
        .thenReturn(List.of(document));
    when(documents.findById(document.getId())).thenReturn(Optional.of(document));
  }

  // --- the kill switches ---------------------------------------------------

  @Test
  @DisplayName("a retention of 0 disables purging entirely — nothing is even queried")
  void retentionZeroDisablesPurging() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS)).thenReturn(0);

    service.purgeOnce(false);

    verifyNoInteractions(documents, versions, attachments, storage, auditEvents);
  }

  @Test
  @DisplayName("an empty eligibility set writes no audit event — a no-op run leaves no trace")
  void emptyRunDoesNotAudit() {
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class))).thenReturn(List.of());

    service.purgeOnce(false);

    verifyNoInteractions(auditEvents, storage);
    verify(documents, never()).delete(any(Document.class));
  }

  // --- the dry run ---------------------------------------------------------

  @Test
  @DisplayName("a dry run deletes nothing at all — no document, no object, no audit row")
  void dryRunChangesNothing() {
    Document document = archived("Q3 report", 200);
    when(documents.countByArchivedAtBefore(any())).thenReturn(1L);
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class)))
        .thenReturn(List.of(document));
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_A));
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    when(versions.findVersionRefsByStorageKeyIn(any())).thenReturn(List.of());
    when(attachments.findAttachmentRefsByStorageKeyIn(any())).thenReturn(List.of());

    service.purgeOnce(true);

    verify(documents, never()).delete(any(Document.class));
    verify(storage, never()).delete(anyString());
    verifyNoInteractions(auditEvents);
  }

  // --- the shared-key decision (the substance of #577) ---------------------

  @Test
  @DisplayName("a key no surviving row references is deleted from storage")
  void exclusiveKeyIsDeleted() {
    Document document = archived("Q3 report", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_A));
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    // After the aggregate delete committed, nothing references the key any more.
    when(versions.findVersionRefsByStorageKeyIn(any())).thenReturn(List.of());
    when(attachments.findAttachmentRefsByStorageKeyIn(any())).thenReturn(List.of());

    service.purgeOnce(false);

    verify(documents).delete(document);
    verify(storage).delete(KEY_A);
  }

  @Test
  @DisplayName("a key a surviving document still references is left untouched")
  void sharedKeyIsKept() {
    Document document = archived("Q3 report", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_A));
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    // Content-addressed dedup (ADR-0005/0036): another document's version shares the object.
    when(versions.findVersionRefsByStorageKeyIn(any()))
        .thenReturn(List.of(new VersionStorageRef(KEY_A, UUID.randomUUID(), 1)));
    when(attachments.findAttachmentRefsByStorageKeyIn(any())).thenReturn(List.of());

    service.purgeOnce(false);

    verify(documents).delete(document);
    verify(storage, never()).delete(anyString());
  }

  @Test
  @DisplayName("an attachment reference also protects a key, not just a version")
  void sharedKeyViaAttachmentIsKept() {
    Document document = archived("Q3 report", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_B));
    when(versions.findVersionRefsByStorageKeyIn(any())).thenReturn(List.of());
    when(attachments.findAttachmentRefsByStorageKeyIn(any()))
        .thenReturn(List.of(new AttachmentStorageRef(KEY_B, UUID.randomUUID(), "shot.png")));

    service.purgeOnce(false);

    verify(storage, never()).delete(anyString());
  }

  @Test
  @DisplayName("of two keys, only the exclusive one goes")
  void mixedKeysDeleteOnlyTheExclusiveOne() {
    Document document = archived("Q3 report", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_A, KEY_B));
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    when(versions.findVersionRefsByStorageKeyIn(any()))
        .thenReturn(List.of(new VersionStorageRef(KEY_B, UUID.randomUUID(), 2)));
    when(attachments.findAttachmentRefsByStorageKeyIn(any())).thenReturn(List.of());

    service.purgeOnce(false);

    verify(storage).delete(KEY_A);
    verify(storage, never()).delete(KEY_B);
  }

  // --- the eligibility re-check -------------------------------------------

  @Test
  @DisplayName("a review unarchived between the batch read and its own transaction is spared")
  void unarchivedInFlightIsSkipped() {
    Document document = archived("Q3 report", 200);
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class)))
        .thenReturn(List.of(document));
    // The per-review transaction re-reads it — and by then an admin has unarchived it.
    Document restored = archived("Q3 report", 200);
    setId(restored, document.getId());
    restored.setArchivedAt(null);
    when(documents.findById(document.getId())).thenReturn(Optional.of(restored));

    service.purgeOnce(false);

    verify(documents, never()).delete(any(Document.class));
    verify(storage, never()).delete(anyString());
    verifyNoInteractions(auditEvents);
  }

  @Test
  @DisplayName("a review archived too recently to be eligible on re-read is spared")
  void recentlyArchivedOnReReadIsSkipped() {
    Document document = archived("Q3 report", 200);
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class)))
        .thenReturn(List.of(document));
    Document rearchived = archived("Q3 report", 1);
    setId(rearchived, document.getId());
    when(documents.findById(document.getId())).thenReturn(Optional.of(rearchived));

    service.purgeOnce(false);

    verify(documents, never()).delete(any(Document.class));
  }

  @Test
  @DisplayName("a review already gone on re-read is skipped without failing the run")
  void vanishedOnReReadIsSkipped() {
    Document document = archived("Q3 report", 200);
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class)))
        .thenReturn(List.of(document));
    when(documents.findById(document.getId())).thenReturn(Optional.empty());

    service.purgeOnce(false);

    verify(documents, never()).delete(any(Document.class));
    verifyNoInteractions(auditEvents);
  }

  // --- the audit record ---------------------------------------------------

  @Test
  @DisplayName("one SYSTEM audit row per run carries the counts and the purged titles")
  void auditsTheRunWithTitles() {
    Document document = archived("Q3 report", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of(KEY_A));
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    when(versions.findVersionRefsByStorageKeyIn(any())).thenReturn(List.of());
    when(attachments.findAttachmentRefsByStorageKeyIn(any())).thenReturn(List.of());

    service.purgeOnce(false);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ReviewPurgeService.AUDIT_REVIEWS_PURGED);
    assertThat(event.getScope()).isEqualTo(AuditScope.SYSTEM);
    // Machine-driven: the trail renders a null actor as "System".
    assertThat(event.getActorId()).isNull();
    // The title is the point — the purged review's own trail died with it.
    assertThat(event.getDetail())
        .contains("\"reviews\":1")
        .contains("\"storageObjects\":1")
        .contains("\"title\":\"Q3 report\"")
        .contains(document.getId().toString());
  }

  @Test
  @DisplayName("a quote in a review title cannot break the audit detail's JSON")
  void auditDetailEscapesTitles() {
    Document document = archived("The \"final\" terms\\draft", 200);
    eligible(document);
    when(versions.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());
    when(attachments.findStorageKeysByDocumentId(document.getId())).thenReturn(List.of());

    service.purgeOnce(false);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(captor.capture());
    assertThat(captor.getValue().getDetail())
        .contains("\\\"final\\\"")
        .contains("terms\\\\draft")
        .contains("\"storageObjects\":0");
  }

  // --- the batch bound ----------------------------------------------------

  @Test
  @DisplayName("one run is bounded, so a huge backlog cannot be loaded at once")
  void batchIsBounded() {
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class))).thenReturn(List.of());

    service.purgeOnce(false);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(documents).findByArchivedAtBefore(any(), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    assertThat(pageable.getValue().getPageNumber()).isZero();
  }

  @Test
  @DisplayName("the cutoff is derived from the configured retention")
  void cutoffFollowsTheRetentionSetting() {
    when(settings.getInteger(ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS))
        .thenReturn(30);
    when(documents.findByArchivedAtBefore(any(), any(Pageable.class))).thenReturn(List.of());
    Instant before = Instant.now().minus(Duration.ofDays(30));

    service.purgeOnce(false);

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(documents).findByArchivedAtBefore(cutoff.capture(), any(Pageable.class));
    assertThat(cutoff.getValue())
        .isCloseTo(
            before,
            org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
  }

  @Test
  @DisplayName("the cron entry point delegates to the scheduler gate, never to the work directly")
  void cronDelegatesToTheGate() {
    service.purge();

    verify(scheduler).runScheduled(eq("reviewPurge"));
    verifyNoInteractions(documents, storage);
  }
}
