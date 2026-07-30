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
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import io.qnop.service.storage.StorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes long-archived reviews permanently (issue #577), closing the lifecycle #576 opened. An
 * archived review is a cold record; after {@code review.purge_archived_after_days} it is destroyed
 * — the whole DB aggregate plus the binaries in object storage that nothing else references.
 *
 * <p><strong>This is the only irreversible destruction of user data in the product</strong>, so it
 * is a double opt-in: the retention setting <em>and</em> the {@code reviewPurge} scheduler job,
 * which ships disabled (ADR-0050). A retention of {@code 0} disables purging outright, mirroring
 * #576.
 *
 * <h2>Shared storage objects</h2>
 *
 * <p>Keys are content-addressed and deduplicated instance-wide (ADR-0005/0036), so the very same
 * object can back versions or attachments of <em>other</em> documents. A key is therefore deleted
 * only after proving that no surviving row references it — checked across {@code document_version}
 * and {@code document_attachment} <em>after</em> the aggregate delete committed, so the purged
 * document's own rows are already gone and cannot mask a genuinely shared key.
 *
 * <h2>Ordering is the crash-safety contract</h2>
 *
 * <p>The DB aggregate is deleted and committed <em>first</em>, storage second. A crash in between
 * leaves an unreferenced object — harmless, and the storage-consistency scan (issue #523) sweeps
 * it. The reverse order would recreate the #575 hole: rows referencing objects that no longer
 * exist.
 *
 * <p>A concurrent upload can re-reference a key between the check and the delete. That race is
 * accepted rather than locked away: {@code StorageService.stage} verifies a dedup hit and
 * re-uploads from the buffered bytes when the object is missing (issue #575), so the loser of the
 * race self-heals on its next read path instead of returning a key pointing at nothing.
 *
 * <h2>Transactions</h2>
 *
 * <p>The job is registered as {@code selfTransactional}, so the scheduler gate invokes it with no
 * enclosing transaction and this service owns its own: <strong>one transaction per review</strong>.
 * A run interrupted halfway therefore leaves reviews either untouched or completely gone, never
 * half-deleted — and each storage delete commits its registry row immediately instead of at the end
 * of the run.
 */
@Service
public class ReviewPurgeService {

  /**
   * SYSTEM-scoped audit event for one purge run. Per-document events die with the document (the
   * {@code audit_event.document_id} FK cascades), so this run-level record is the only surviving
   * answer to "where did that review go?" — which is why it carries titles, not just ids.
   */
  public static final String AUDIT_REVIEWS_PURGED = "review.purged";

  /**
   * Upper bound on reviews purged per run. Deliberately lower than the archive sweep's 500: each
   * item here is a cascading delete plus object-storage round-trips.
   */
  private static final int MAX_PER_RUN = 200;

  /** Cap on how many titles the audit detail spells out, so one row cannot grow unbounded. */
  private static final int MAX_AUDITED_TITLES = 50;

  private static final Logger log = LoggerFactory.getLogger(ReviewPurgeService.class);

  private final SchedulerService scheduler;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final DocumentAttachmentRepository attachments;
  private final AuditEventRepository auditEvents;
  private final ApplicationSettingsService settings;
  private final StorageService storage;
  private final TransactionTemplate tx;

  public ReviewPurgeService(
      SchedulerService scheduler,
      DocumentRepository documents,
      DocumentVersionRepository versions,
      DocumentAttachmentRepository attachments,
      AuditEventRepository auditEvents,
      ApplicationSettingsService settings,
      StorageService storage,
      PlatformTransactionManager transactionManager) {
    this.scheduler = scheduler;
    this.documents = documents;
    this.versions = versions;
    this.attachments = attachments;
    this.auditEvents = auditEvents;
    this.settings = settings;
    this.storage = storage;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Off-peak cron entry point, after the archive sweep; the gate runs {@link #purgeOnce}. */
  @Scheduled(cron = "${qnop.review.purge-cron:0 55 3 * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.REVIEW_PURGE, lockAtMostFor = "PT30M")
  public void purge() {
    scheduler.runScheduled(SchedulerJobCatalog.REVIEW_PURGE);
  }

  /**
   * One purge pass. Invoked by the gate <em>outside</em> any transaction (the job is
   * self-transactional), so this method opens one transaction per review. In {@code dryRun} mode it
   * reports what it would destroy and changes nothing at all.
   */
  public String purgeOnce(boolean dryRun) {
    int retentionDays = settings.getInteger(ApplicationSettingKey.REVIEW_PURGE_ARCHIVED_AFTER_DAYS);
    if (retentionDays <= 0) {
      log.info("Review purging is disabled (review.purge_archived_after_days={}).", retentionDays);
      return "Purging is disabled (review.purge_archived_after_days=" + retentionDays + ")";
    }
    Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
    if (dryRun) {
      return reportDryRun(cutoff);
    }

    List<UUID> eligible =
        tx.execute(
            status ->
                documents.findByArchivedAtBefore(cutoff, PageRequest.of(0, MAX_PER_RUN)).stream()
                    .map(Document::getId)
                    .toList());
    if (eligible == null || eligible.isEmpty()) {
      return "No reviews eligible to purge";
    }

    List<PurgedReview> purged = new ArrayList<>();
    int objectsDeleted = 0;
    for (UUID documentId : eligible) {
      // One transaction per review: an interrupted run leaves whole reviews, never fragments.
      PurgedAggregate result = purgeAggregate(documentId, cutoff);
      if (result == null) {
        continue; // no longer eligible — unarchived or already gone since the batch was read
      }
      // Only now — the aggregate delete has committed, so the document's own rows can no longer
      // make a key look shared.
      objectsDeleted += deleteUnreferenced(result.storageKeys());
      purged.add(new PurgedReview(documentId, result.title()));
    }

    if (purged.isEmpty()) {
      return "No reviews eligible to purge";
    }
    auditRun(purged, objectsDeleted);
    log.info(
        "Review purge deleted {} review(s) archived before {} and {} storage object(s).",
        purged.size(),
        cutoff,
        objectsDeleted);
    return "Purged " + purged.size() + " review(s) and " + objectsDeleted + " storage object(s)";
  }

  /**
   * Deletes one review's DB aggregate in its own transaction and returns its title plus the storage
   * keys it referenced, or {@code null} if it is no longer eligible.
   *
   * <p>The document is re-read and re-checked here rather than trusted from the batch: the batch
   * was read in an earlier transaction, and an admin may have unarchived a review in between.
   * Purging is irreversible, so the eligibility decision is made in the same transaction as the
   * delete. The keys are read before the delete, because afterwards the rows are gone.
   */
  private PurgedAggregate purgeAggregate(UUID documentId, Instant cutoff) {
    return tx.execute(
        status -> {
          Document document = documents.findById(documentId).orElse(null);
          if (document == null
              || document.getArchivedAt() == null
              || !document.getArchivedAt().isBefore(cutoff)) {
            return null;
          }
          Set<String> referenced = new LinkedHashSet<>();
          referenced.addAll(versions.findStorageKeysByDocumentId(documentId));
          referenced.addAll(attachments.findStorageKeysByDocumentId(documentId));
          // The schema cascades the whole aggregate (versions, annotations, placements, comments,
          // reactions, mentions, participants, visits, attachments, version diffs and the
          // per-document audit trail) — see DocumentReviewSchemaIT.
          documents.delete(document);
          return new PurgedAggregate(document.getTitle(), referenced);
        });
  }

  /**
   * Deletes each key that no surviving row references, and returns how many went. Both reference
   * checks are one query over the whole key set, not one per key.
   */
  private int deleteUnreferenced(Set<String> candidateKeys) {
    if (candidateKeys.isEmpty()) {
      return 0;
    }
    Set<String> stillReferenced =
        tx.execute(
            status -> {
              Set<String> referenced = new LinkedHashSet<>();
              versions.findVersionRefsByStorageKeyIn(candidateKeys).stream()
                  .map(ref -> ref.storageKey())
                  .forEach(referenced::add);
              // Renditions are referenced too (issue #343), and the projection names
              // the key that matched — so a shared conversion is not deleted.
              versions.findVersionRefsByRenditionKeyIn(candidateKeys).stream()
                  .map(ref -> ref.storageKey())
                  .forEach(referenced::add);
              attachments.findAttachmentRefsByStorageKeyIn(candidateKeys).stream()
                  .map(ref -> ref.storageKey())
                  .forEach(referenced::add);
              return referenced;
            });
    Set<String> shared = stillReferenced == null ? Set.of() : stillReferenced;
    int deleted = 0;
    for (String key : candidateKeys) {
      if (shared.contains(key)) {
        log.debug("Keeping storage object {} — still referenced by a surviving document.", key);
        continue;
      }
      // Own transaction (StorageService.delete is @Transactional and we hold none), so the object
      // and its registry row go together, now — not at the end of the run.
      storage.delete(key);
      deleted++;
    }
    return deleted;
  }

  /**
   * Logs what a real run would destroy: reviews, and the objects that would become unreferenced.
   */
  private String reportDryRun(Instant cutoff) {
    DryRunReport report =
        tx.execute(
            status -> {
              long reviews = documents.countByArchivedAtBefore(cutoff);
              List<Document> eligible =
                  documents.findByArchivedAtBefore(cutoff, PageRequest.of(0, MAX_PER_RUN));
              Set<UUID> purgedIds = new LinkedHashSet<>();
              Set<String> candidates = new LinkedHashSet<>();
              for (Document document : eligible) {
                purgedIds.add(document.getId());
                candidates.addAll(versions.findStorageKeysByDocumentId(document.getId()));
                candidates.addAll(attachments.findStorageKeysByDocumentId(document.getId()));
              }
              return new DryRunReport(reviews, countUnreferenced(candidates, purgedIds));
            });
    DryRunReport result = report == null ? new DryRunReport(0, 0) : report;
    log.info(
        "Review purge (dry-run) would delete {} review(s) archived before {} and {} storage"
            + " object(s); nothing was changed.",
        result.reviews(),
        cutoff,
        result.storageObjects());
    return "Would purge "
        + result.reviews()
        + " review(s) and "
        + result.storageObjects()
        + " storage object(s); nothing was changed";
  }

  /**
   * How many of {@code candidates} would actually be deleted: a key survives if a version or
   * attachment <em>outside</em> the purged set references it. Two batched queries over the whole
   * key set — never one per key — so the dry run stays honest about shared content without an N+1.
   */
  private int countUnreferenced(Set<String> candidates, Set<UUID> purgedDocumentIds) {
    if (candidates.isEmpty()) {
      return 0;
    }
    Set<String> shared = new LinkedHashSet<>();
    versions.findVersionRefsByStorageKeyIn(candidates).stream()
        .filter(ref -> !purgedDocumentIds.contains(ref.documentId()))
        .forEach(ref -> shared.add(ref.storageKey()));
    versions.findVersionRefsByRenditionKeyIn(candidates).stream()
        .filter(ref -> !purgedDocumentIds.contains(ref.documentId()))
        .forEach(ref -> shared.add(ref.storageKey()));
    attachments.findAttachmentRefsByStorageKeyIn(candidates).stream()
        .filter(ref -> !purgedDocumentIds.contains(ref.documentId()))
        .forEach(ref -> shared.add(ref.storageKey()));
    return candidates.size() - shared.size();
  }

  /** One SYSTEM audit row for the run — the trail that outlives the documents it describes. */
  private void auditRun(List<PurgedReview> purged, int objectsDeleted) {
    String detail = auditDetail(purged, objectsDeleted);
    tx.executeWithoutResult(
        status -> auditEvents.save(AuditEvent.system(AUDIT_REVIEWS_PURGED, null, detail)));
  }

  private static String auditDetail(List<PurgedReview> purged, int objectsDeleted) {
    StringBuilder json = new StringBuilder("{\"reviews\":").append(purged.size());
    json.append(",\"storageObjects\":").append(objectsDeleted);
    json.append(",\"purged\":[");
    List<PurgedReview> listed = purged.stream().limit(MAX_AUDITED_TITLES).toList();
    for (int i = 0; i < listed.size(); i++) {
      PurgedReview review = listed.get(i);
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"id\":\"")
          .append(review.id())
          .append("\",\"title\":\"")
          .append(escape(review.title()))
          .append("\"}");
    }
    json.append(']');
    if (purged.size() > listed.size()) {
      json.append(",\"truncated\":").append(purged.size() - listed.size());
    }
    return json.append('}').toString();
  }

  /** Minimal JSON string escaping — titles are free text and must not break the detail payload. */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /** What the audit trail keeps of a review once the review itself is gone. */
  private record PurgedReview(UUID id, String title) {}

  /** What a dry run found, so the log line is assembled outside the transaction. */
  private record DryRunReport(long reviews, int storageObjects) {}

  /** What one committed aggregate delete leaves the caller to finish: the title and the keys. */
  private record PurgedAggregate(String title, Set<String> storageKeys) {}
}
