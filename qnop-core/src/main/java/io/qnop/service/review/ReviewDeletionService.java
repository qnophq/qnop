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
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import io.qnop.service.storage.StorageService;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Destroying a review, for good (issue #421).
 *
 * <p>Two callers, one implementation: the retention sweep ({@link ReviewPurgeService}) and an admin
 * deleting one review by hand. A second delete path would be the kind of duplicate where one of the
 * two eventually forgets to release the storage objects, and nobody notices for months.
 *
 * <p><strong>Who may.</strong> The owner archives — reversible, and theirs to decide. Only an admin
 * deletes. A review carries other people's annotations and discussions, so removing it destroys
 * work that was never the owner's alone.
 *
 * <p><strong>Order matters.</strong> The storage keys are read before the rows are, because
 * afterwards there is nothing left to read them from; the objects go only once the aggregate delete
 * has committed, so the document's own rows can no longer make a key look shared.
 */
@Service
public class ReviewDeletionService {

  /**
   * Audit event for one review deleted by hand.
   *
   * <p>SYSTEM-scoped despite having an actor: per-document events die with the document (the {@code
   * audit_event.document_id} FK cascades), so this is the only surviving answer to "where did that
   * review go?" — which is why it carries the title, not just the id.
   */
  public static final String AUDIT_REVIEW_DELETED = "review.deleted";

  private static final Logger log = LoggerFactory.getLogger(ReviewDeletionService.class);

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final DocumentAttachmentRepository attachments;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService access;
  private final StorageService storage;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;

  public ReviewDeletionService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      DocumentAttachmentRepository attachments,
      AuditEventRepository auditEvents,
      DocumentAccessService access,
      StorageService storage,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager) {
    this.documents = documents;
    this.versions = versions;
    this.attachments = attachments;
    this.auditEvents = auditEvents;
    this.access = access;
    this.storage = storage;
    this.events = events;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * Deletes one review immediately, at an admin's request.
   *
   * <p>Any state: a review created by mistake is most often still a draft, and making its removal a
   * three-step detour through cancel and archive would be friction rather than safety. What guards
   * this is the role and the client's confirmation, not a longer path.
   *
   * @throws DocumentValidationException 404 when the caller cannot see it at all, 403 when they can
   *     but are not an admin
   */
  public DeletedReview delete(UUID documentId, UUID actorId, boolean admin) {
    if (!admin) {
      // Anti-enumeration: a caller who cannot even see the review learns nothing
      // beyond "no such review"; a participant learns that deleting is not theirs.
      if (!access.isVisible(documentId, actorId, false)) {
        throw DocumentValidationException.notFound("no such document: " + documentId);
      }
      throw DocumentValidationException.forbidden("only admins may delete a review");
    }

    DeletedAggregate deleted = deleteAggregate(documentId, document -> true);
    if (deleted == null) {
      throw DocumentValidationException.notFound("no such document: " + documentId);
    }
    int objects = deleteUnreferencedObjects(deleted.storageKeys());
    audit(documentId, deleted.title(), actorId, objects);
    // Inside a transaction, and that is not decoration: the notification listener
    // is @TransactionalEventListener(AFTER_COMMIT), and an event published outside
    // a transaction is dropped without a word. The aggregate delete has already
    // committed by now, so this one exists purely to give the event a commit to
    // ride on. It carries what the review was, because nothing can be looked up
    // any more (issue #421).
    tx.executeWithoutResult(
        status ->
            events.publishEvent(
                new ReviewEvent.ReviewDeleted(
                    documentId, actorId, deleted.ownerId(), deleted.title())));
    // The title is deliberately absent (issue #659): it is user content and
    // routinely carries a name. The audit trail keeps it, behind access control;
    // a log file has neither.
    log.info("Review deleted by {}; {} storage object(s) released", actorId, objects);
    return new DeletedReview(documentId, deleted.title(), objects);
  }

  /**
   * Deletes one review's DB aggregate in its own transaction, or returns {@code null} when {@code
   * stillEligible} says it should be left alone.
   *
   * <p>The document is re-read and re-checked inside that transaction rather than trusted from
   * whatever the caller saw earlier: deleting is irreversible, and the sweep in particular reads
   * its batch in an earlier transaction during which an admin may have unarchived something.
   *
   * <p>The keys are collected first, because after the delete the rows that name them are gone.
   */
  DeletedAggregate deleteAggregate(UUID documentId, Predicate<Document> stillEligible) {
    return tx.execute(
        status -> {
          Document document = documents.findById(documentId).orElse(null);
          if (document == null || !stillEligible.test(document)) {
            return null;
          }
          Set<String> referenced = new LinkedHashSet<>();
          referenced.addAll(versions.findStorageKeysByDocumentId(documentId));
          referenced.addAll(attachments.findStorageKeysByDocumentId(documentId));
          UUID ownerId = document.getOwnerId();
          // The schema cascades the whole aggregate (versions, annotations, placements, comments,
          // reactions, mentions, participants, visits, attachments, version diffs and the
          // per-document audit trail) — see DocumentReviewSchemaIT.
          documents.delete(document);
          return new DeletedAggregate(document.getTitle(), ownerId, referenced);
        });
  }

  /**
   * Deletes each key that no surviving row references, and returns how many went.
   *
   * <p>Keys are content-addressed, so two reviews holding the same file hold the same object. This
   * check is what keeps deleting one of them from emptying the other; both reference sources are
   * queried once over the whole key set, never once per key.
   */
  int deleteUnreferencedObjects(Set<String> candidateKeys) {
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
              // A converted version is rendered from a second object (issue #343),
              // and the projection names the key that matched.
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

  private void audit(UUID documentId, String title, UUID actorId, int objectsDeleted) {
    String detail =
        "{\"documentId\":\""
            + documentId
            + "\",\"title\":\""
            + escape(title)
            + "\",\"storageObjects\":"
            + objectsDeleted
            + "}";
    tx.executeWithoutResult(
        status -> auditEvents.save(AuditEvent.system(AUDIT_REVIEW_DELETED, actorId, detail)));
  }

  /** Minimal JSON string escaping for a user-supplied title. */
  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** What one deletion destroyed, for the caller's response and log line. */
  public record DeletedReview(UUID documentId, String title, int storageObjectsDeleted) {}

  /** What the aggregate was, read before it was deleted — nothing can be looked up afterwards. */
  record DeletedAggregate(String title, UUID ownerId, Set<String> storageKeys) {}
}
