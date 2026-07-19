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
package io.qnop.service.storage;

import io.qnop.entity.AuditEvent;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.StorageObjectRepository;
import io.qnop.spi.storage.StorageProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes orphaned storage objects for the admin dashboard (issue #523, ADR-0043). Each key is
 * remediated in <em>its own</em> transaction: the references are re-checked against all three
 * sources immediately before deletion, so a key that became referenced since the scan (e.g. a new
 * upload reusing the same content, which registers a staging row) is skipped and reported back
 * rather than deleted. Idempotent; every deletion is recorded both as a structured log line and as
 * a document-less {@link AuditEvent} (nullable {@code document_id}).
 *
 * <p>Missing binaries are deliberately never deleted here — a missing object is data loss the admin
 * resolves through the document/review flows, not a shortcut in this service.
 */
@Service
public class StorageRemediationService {

  /** Audit event type for a dashboard/reaper orphan deletion; detail carries {@code {"key"}}. */
  public static final String AUDIT_ORPHAN_DELETED = "storage.orphan.deleted";

  private static final Logger log = LoggerFactory.getLogger(StorageRemediationService.class);

  private final StorageProvider storage;
  private final DocumentVersionRepository documentVersions;
  private final DocumentAttachmentRepository documentAttachments;
  private final StorageObjectRepository storageObjects;
  private final AuditEventRepository auditEvents;
  private final TransactionTemplate requiresNew;

  public StorageRemediationService(
      StorageProvider storage,
      DocumentVersionRepository documentVersions,
      DocumentAttachmentRepository documentAttachments,
      StorageObjectRepository storageObjects,
      AuditEventRepository auditEvents,
      PlatformTransactionManager transactionManager) {
    this.storage = storage;
    this.documentVersions = documentVersions;
    this.documentAttachments = documentAttachments;
    this.storageObjects = storageObjects;
    this.auditEvents = auditEvents;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** A key that was NOT deleted, with the reason (kept on screen so the admin sees why). */
  public record Skipped(String storageKey, String reason) {}

  /** The outcome of a remediation request: what was deleted and what was skipped, with reasons. */
  public record RemediationResult(List<String> deleted, List<Skipped> skipped) {}

  /**
   * Deletes each given orphan key that is still unreferenced. {@code actorId} is the acting admin
   * (or null for the scheduled reaper). Duplicate keys are collapsed. Partial progress persists —
   * one key's failure or skip does not roll back the others.
   */
  public RemediationResult deleteOrphans(Collection<String> keys, UUID actorId) {
    List<String> deleted = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    for (String key : keys.stream().distinct().toList()) {
      String reason = requiresNew.execute(status -> deleteOne(key, actorId));
      if (reason == null) {
        deleted.add(key);
      } else {
        skipped.add(new Skipped(key, reason));
      }
    }
    return new RemediationResult(deleted, skipped);
  }

  /** Deletes one key inside the caller's fresh transaction; returns null when deleted, else why. */
  private String deleteOne(String key, UUID actorId) {
    if (isReferenced(key)) {
      return "now referenced";
    }
    storage.delete(key); // idempotent; external to the transaction
    // A bucket-wide deletion is not document-scoped: record it as a SYSTEM-scope
    // audit event with a null document_id (issue #524 / ADR-0043).
    auditEvents.save(AuditEvent.system(AUDIT_ORPHAN_DELETED, actorId, "{\"key\":\"" + key + "\"}"));
    log.info("Storage orphan deleted: key={} actor={}", key, actorId);
    return null;
  }

  /** Whether any of the three reference sources currently points at {@code key}. */
  private boolean isReferenced(String key) {
    List<String> one = List.of(key);
    return !documentVersions.findVersionRefsByStorageKeyIn(one).isEmpty()
        || !documentAttachments.findAttachmentRefsByStorageKeyIn(one).isEmpty()
        || storageObjects.findByObjectKey(key).isPresent();
  }
}
