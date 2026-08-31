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
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.spi.participant.ExternalParticipants;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's implementation of the {@link ExternalParticipants} facade (issue #684, ADR-0061) — the
 * only path through which an extension manages account-less participants. Deliberately narrow: no
 * credentials, no links, no expiry; the extension owns all of that and the core owns participation,
 * access and identity. Every change is audited (actor null: the acting principal is the extension's
 * business, the trail still shows what happened to the roster).
 *
 * <p>No {@code ReviewEvent} is published for external roster changes: the notification path mails
 * account holders, and whom to tell about a guest is the extension's decision.
 */
@Service
public class ExternalParticipantService implements ExternalParticipants {

  /** Matches the column width; trimmed before validation. */
  static final int MAX_DISPLAY_NAME = 120;

  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final AuditEventRepository auditEvents;

  public ExternalParticipantService(
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      AuditEventRepository auditEvents) {
    this.documents = documents;
    this.participants = participants;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public UUID add(UUID documentId, String displayName) {
    if (documentId == null || !documents.existsById(documentId)) {
      throw new IllegalArgumentException("unknown document: " + documentId);
    }
    String name = displayName == null ? "" : displayName.trim();
    if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME) {
      throw new IllegalArgumentException("display name must be 1-" + MAX_DISPLAY_NAME + " chars");
    }
    ReviewParticipant saved = participants.save(ReviewParticipant.forExternal(documentId, name));
    auditEvents.save(
        new AuditEvent(documentId, "review.participant_added", null, "external: " + name));
    return saved.getId();
  }

  @Override
  @Transactional
  public boolean remove(UUID documentId, UUID participantId) {
    ReviewParticipant participant =
        participants
            .findById(participantId)
            .filter(p -> p.getDocumentId().equals(documentId))
            .orElse(null);
    if (participant == null) {
      return false;
    }
    if (!participant.isExternal()) {
      // The facade manages only what it created — the account-bearing roster stays the
      // owner's/admin's business through the regular participant endpoints.
      throw new IllegalArgumentException("participant is not external: " + participantId);
    }
    participants.delete(participant);
    auditEvents.save(
        new AuditEvent(
            documentId,
            "review.participant_removed",
            null,
            "external: " + participant.getExternalDisplayName()));
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasAccess(UUID documentId, UUID participantId) {
    if (documentId == null || participantId == null) {
      return false;
    }
    return participants.existsAccessibleParticipant(documentId, participantId);
  }
}
