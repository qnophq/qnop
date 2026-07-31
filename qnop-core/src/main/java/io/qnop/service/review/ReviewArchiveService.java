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

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archives long-closed reviews out of the active lists (issue #576). A review that has been
 * FINALIZED/CANCELLED longer than {@code review.archive_after_days} is a record, not a working
 * object; the auto-archive sweep flags it with {@link Document#setArchivedAt} so it leaves the
 * default overview while staying a fully readable, immutable record.
 *
 * <p>Archiving is an <em>orthogonal retention flag</em>, not a workflow state (ADR-0011 amendment):
 * {@code workflow_state} stays FINALIZED/CANCELLED, so the terminal outcome is preserved ("Archived
 * · was Finalized") and the existing closed-record guards already make an archived review
 * read-only. Each archived review gets a per-document {@code review.archived} audit event; the
 * sweep is machine-driven (null actor → "System" in the trail), a manual archive/unarchive carries
 * the acting owner/admin.
 *
 * <p>The scheduled sweep is routed through the {@link SchedulerService} gate (issue #524): admins
 * get enable/disable, dry-run and run-now on {@code /admin/scheduler}; the {@code @SchedulerLock}
 * (ADR-0029) serializes it. {@code archiveOnce} does raw repository work — no
 * {@code @Transactional} — because the gate owns the transaction. The manual endpoints are ordinary
 * transactional calls.
 */
@Service
public class ReviewArchiveService {

  /** Per-document audit event for an archive; detail carries the terminal {@code outcome}. */
  public static final String AUDIT_REVIEW_ARCHIVED = "review.archived";

  /** Per-document audit event for a manual unarchive (restore to the active lists). */
  public static final String AUDIT_REVIEW_UNARCHIVED = "review.unarchived";

  /** Upper bound on reviews archived per scheduled tick, so one run is never unbounded. */
  private static final int MAX_PER_RUN = 500;

  private static final Logger log = LoggerFactory.getLogger(ReviewArchiveService.class);

  private final SchedulerService scheduler;
  private final DocumentRepository documents;
  private final AuditEventRepository auditEvents;
  private final ApplicationSettingsService settings;
  private final DocumentAccessService access;

  public ReviewArchiveService(
      SchedulerService scheduler,
      DocumentRepository documents,
      AuditEventRepository auditEvents,
      ApplicationSettingsService settings,
      DocumentAccessService access) {
    this.scheduler = scheduler;
    this.documents = documents;
    this.auditEvents = auditEvents;
    this.settings = settings;
    this.access = access;
  }

  /** Off-peak cron entry point; the gate runs the registered {@link #archiveOnce} work. */
  @Scheduled(cron = "${qnop.review.archive-cron:0 50 3 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.REVIEW_ARCHIVE, lockAtMostFor = "PT10M")
  public void archive() {
    scheduler.runScheduled(SchedulerJobCatalog.REVIEW_ARCHIVE);
  }

  /**
   * One archive pass, run inside the scheduler gate's transaction. Reviews closed longer than the
   * retention window are flagged archived (bounded batch). A retention of {@code 0} disables
   * auto-archiving. In {@code dryRun} mode it counts what it would archive but changes nothing.
   */
  public String archiveOnce(boolean dryRun) {
    int retentionDays = settings.getInteger(ApplicationSettingKey.REVIEW_ARCHIVE_AFTER_DAYS);
    if (retentionDays <= 0) {
      log.info("Review auto-archive is disabled (review.archive_after_days={}).", retentionDays);
      return "Auto-archiving is disabled (review.archive_after_days=" + retentionDays + ")";
    }
    Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
    if (dryRun) {
      long count = documents.countByArchivedAtIsNullAndClosedAtBefore(cutoff);
      log.info(
          "Review auto-archive dry-run: would archive {} review(s) closed before {}.",
          count,
          cutoff);
      return "Would archive " + count + " review(s); nothing was changed";
    }
    List<Document> eligible =
        documents.findByArchivedAtIsNullAndClosedAtBefore(cutoff, PageRequest.of(0, MAX_PER_RUN));
    if (eligible.isEmpty()) {
      return "No reviews eligible to archive";
    }
    Instant now = Instant.now();
    for (Document document : eligible) {
      document.setArchivedAt(now);
      // Machine-driven: null actor renders as "System" in the audit UI (ADR-0042).
      auditEvents.save(archiveEvent(document, null));
    }
    documents.saveAll(eligible);
    log.info(
        "Review auto-archive: archived {} review(s) closed before {}.", eligible.size(), cutoff);
    return "Archived " + eligible.size() + " review(s)";
  }

  /**
   * Manually archives a single closed review (owner or admin). Refuses an open review or one
   * already archived. The acting user is recorded on the audit event.
   */
  @Transactional
  public Document archive(UUID documentId, UUID actorId, boolean admin) {
    Document document = load(documentId);
    requireOwnerOrAdmin(documentId, document, actorId, admin);
    if (!WorkflowState.isClosed(document.getWorkflowState())) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.REVIEW_NOT_CLOSED,
          "only a finalized or cancelled review can be archived");
    }
    if (document.getArchivedAt() != null) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.REVIEW_ALREADY_ARCHIVED, "the review is already archived");
    }
    document.setArchivedAt(Instant.now());
    auditEvents.save(archiveEvent(document, actorId));
    return documents.save(document);
  }

  /** Manually restores an archived review to the active lists (owner or admin). */
  @Transactional
  public Document unarchive(UUID documentId, UUID actorId, boolean admin) {
    Document document = load(documentId);
    requireOwnerOrAdmin(documentId, document, actorId, admin);
    if (document.getArchivedAt() == null) {
      throw new WorkflowTransitionException(
          WorkflowTransitionException.REVIEW_NOT_ARCHIVED, "the review is not archived");
    }
    document.setArchivedAt(null);
    auditEvents.save(new AuditEvent(document.getId(), AUDIT_REVIEW_UNARCHIVED, actorId, null));
    return documents.save(document);
  }

  private AuditEvent archiveEvent(Document document, UUID actorId) {
    return new AuditEvent(
        document.getId(),
        AUDIT_REVIEW_ARCHIVED,
        actorId,
        "{\"outcome\":\"" + document.getWorkflowState() + "\"}");
  }

  private Document load(UUID documentId) {
    return documents
        .findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  /**
   * Owner or admin may archive; everyone else is refused — but the refusal must not leak the
   * review's existence (issue #661). A caller who cannot even see the review gets the same 404 the
   * read and delete paths give ({@link DocumentAccessService#isVisible} upholds "non-participants
   * must not learn that the document exists"); a participant who is simply not the owner already
   * knows it exists, so they get the honest 403. Mirrors {@code ReviewDeletionService.delete}.
   *
   * <p>Keyed on the request's {@code documentId} rather than the loaded entity's id — the two are
   * equal, and the id the caller asked about is the one the anti-enumeration answer is about.
   */
  private void requireOwnerOrAdmin(
      UUID documentId, Document document, UUID actorId, boolean admin) {
    if (admin || document.getOwnerId().equals(actorId)) {
      return;
    }
    if (!access.isVisible(documentId, actorId, false)) {
      throw new DocumentNotFoundException(documentId);
    }
    throw new NotDocumentOwnerException();
  }
}
