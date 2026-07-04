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

import io.qnop.entity.ReviewVisit;
import io.qnop.repository.ReviewVisitRepository;
import io.qnop.service.document.DocumentAccessService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user visit stamps powering the unseen markers (issue #307). Recording a visit returns the
 * PREVIOUS stamp and stores the new one atomically: the page compares its whole session against the
 * returned value, so content the user is currently reading never flips to "seen" mid-session.
 * Visits are personal (team members stamp individually); visibility follows the participant rule —
 * a non-participant sees the same 404 as for the document itself (anti-enumeration).
 */
@Service
public class ReviewVisitService {

  private final ReviewVisitRepository visits;
  private final DocumentAccessService documentAccess;

  public ReviewVisitService(ReviewVisitRepository visits, DocumentAccessService documentAccess) {
    this.visits = visits;
    this.documentAccess = documentAccess;
  }

  /** The previous visit, or null on the first — the client's marker baseline. */
  @Transactional
  public Instant recordVisit(UUID documentId, UUID actor, boolean admin) {
    documentAccess.getDocument(documentId, actor, admin); // visibility → 404 if not a participant
    Instant previous =
        visits
            .findByDocumentIdAndUserId(documentId, actor)
            .map(ReviewVisit::getLastSeenAt)
            .orElse(null);
    // Read-then-upsert: a concurrent first visit (second tab, same user) makes both sessions
    // return null — a marginally generous baseline, never an error.
    visits.upsert(UUID.randomUUID(), documentId, actor, Instant.now());
    return previous;
  }
}
