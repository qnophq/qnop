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

import io.qnop.entity.CommentMention;
import io.qnop.entity.Document;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves and persists the @mentions of a comment (issue #462). Called from {@link
 * AnnotationService} right after a comment is saved; keeps all mention logic — parsing,
 * access-scoping and the anonymity policy — in one focused, testable place.
 *
 * <p><strong>Access-scoping:</strong> a token only becomes a mention when the mentioned user
 * actually has access to the document (owner, a direct participant, or a member of a participating
 * team — the same rule as {@link ReviewParticipantRepository#existsAccessibleParticipant}). Tokens
 * for anyone off the roster stay plain text — mentioning someone without access does nothing.
 *
 * <p><strong>Anonymity (ADR-0038, #413):</strong> mentions are disabled in anonymous reviews. A
 * roster picker or a rendered {@code @realname} would leak identities the review deliberately
 * hides, so no mention is resolved, persisted, or notified when {@link Document#isAnonymous()} —
 * the tokens simply remain plain text for everyone.
 */
@Service
public class CommentMentionService {

  private final CommentMentionRepository mentions;
  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;

  public CommentMentionService(
      CommentMentionRepository mentions,
      DocumentRepository documents,
      ReviewParticipantRepository participants) {
    this.mentions = mentions;
    this.documents = documents;
    this.participants = participants;
  }

  /**
   * Parses {@code body}, keeps only mentions of users with access to {@code documentId}, and
   * persists a row per surviving mention. Returns the mentioned user ids that were persisted (for
   * the notification path to target); empty when the review is anonymous, the body has no tokens,
   * or none resolve to a roster member.
   */
  @Transactional
  public List<UUID> resolveAndPersist(UUID commentId, UUID documentId, String body) {
    Set<UUID> candidates = MentionParser.extractUserIds(body);
    if (candidates.isEmpty()) {
      return List.of();
    }
    Document document = documents.findById(documentId).orElse(null);
    if (document == null || document.isAnonymous()) {
      return List.of(); // anonymous reviews: mentions stay plain text (ADR-0038)
    }
    UUID owner = document.getOwnerId();
    List<CommentMention> rows =
        candidates.stream()
            .filter(userId -> hasAccess(documentId, owner, userId))
            .map(userId -> new CommentMention(commentId, userId))
            .toList();
    if (rows.isEmpty()) {
      return List.of();
    }
    mentions.saveAll(rows);
    return rows.stream().map(CommentMention::getMentionedUserId).toList();
  }

  private boolean hasAccess(UUID documentId, UUID owner, UUID userId) {
    return userId.equals(owner) || participants.existsAccessibleParticipant(documentId, userId);
  }
}
