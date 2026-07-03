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
package io.qnop.service.document;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentAccessService.DocumentView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only updates to a document's mutable metadata (issue #295). Currently the optional review
 * due date: setting a value or clearing it ({@code null}). Unlike creation, an edit accepts any
 * value — including the past — so the owner can correct a deadline after the fact; the due date
 * carries no workflow-guard semantics (an overdue review is only flagged in the UI, ADR-0011).
 *
 * <p>Authorization mirrors {@link ReviewParticipantService}: a non-participant gets 404 (documents
 * are not enumerable), a visible non-owner gets 403 {@code NOT_OWNER}. Every actual change appends
 * a {@link AuditEvent}, consistent with the workflow and participant trails.
 */
@Service
public class DocumentUpdateService {

  /** Audit event type for a due-date set/clear (open string set, no schema change). */
  static final String AUDIT_DUE_DATE_CHANGED = "document.due_date.changed";

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AuditEventRepository auditEvents;
  private final DocumentAccessService access;

  public DocumentUpdateService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AuditEventRepository auditEvents,
      DocumentAccessService access) {
    this.documents = documents;
    this.versions = versions;
    this.auditEvents = auditEvents;
    this.access = access;
  }

  /** Sets ({@code dueAt}) or clears ({@code null}) the completion deadline; owner-only. */
  @Transactional
  public DocumentView updateDueDate(UUID documentId, UUID actor, boolean admin, Instant dueAt) {
    Document document = requireOwned(documentId, actor, admin);
    Instant previous = document.getDueAt();
    if (!Objects.equals(previous, dueAt)) {
      document.setDueAt(dueAt);
      documents.save(document);
      auditEvents.save(
          new AuditEvent(
              document.getId(), AUDIT_DUE_DATE_CHANGED, actor, changeDetail(previous, dueAt)));
    }
    int latest =
        versions
            .findTopByDocumentIdOrderByVersionNumberDesc(documentId)
            .map(DocumentVersion::getVersionNumber)
            .orElse(0);
    return new DocumentView(
        document.getId(),
        document.getTitle(),
        document.getOwnerId(),
        document.getWorkflowState(),
        latest,
        document.getCreatedAt(),
        document.getUpdatedAt(),
        document.getDueAt());
  }

  private Document requireOwned(UUID documentId, UUID actor, boolean admin) {
    if (!access.isVisible(documentId, actor, admin)) {
      throw DocumentValidationException.notFound("document " + documentId);
    }
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(() -> DocumentValidationException.notFound("document " + documentId));
    if (!document.getOwnerId().equals(actor)) {
      throw DocumentValidationException.notOwner("only the owner may edit the due date");
    }
    return document;
  }

  /** A tiny hand-built JSON object of the old/new value — no mapper needed. */
  private static String changeDetail(Instant from, Instant to) {
    return "{\"from\":" + jsonInstant(from) + ",\"to\":" + jsonInstant(to) + "}";
  }

  private static String jsonInstant(Instant instant) {
    return instant == null ? "null" : "\"" + instant + "\"";
  }
}
