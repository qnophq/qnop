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

import io.qnop.entity.Annotation;
import io.qnop.entity.Document;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may <em>currently</em> hear about a review or one of its threads (issue #919).
 *
 * <p>Notifications are addressed to people derived from history — the annotation's author, earlier
 * commenters, a team at the time it was added — so the set can outlive the access it was based on.
 * Every out-of-band channel (the immediate mail, the daily digest) therefore re-checks with this
 * class at delivery or assembly time, with the same rules the read paths apply: the owner and the
 * review's participants (direct or via a team) see the review, and under {@link
 * ThreadParticipation#PRIVATE} only the owner and the annotation's author see a thread (issue
 * #413). Admin status is deliberately not consulted, matching the in-app inbox: an admin who is not
 * part of a review is not its audience.
 */
@Component
public class ReviewAudience {

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final ReviewParticipantRepository participants;

  public ReviewAudience(
      DocumentRepository documents,
      AnnotationRepository annotations,
      ReviewParticipantRepository participants) {
    this.documents = documents;
    this.annotations = annotations;
    this.participants = participants;
  }

  /** Whether {@code userId} is currently the owner or a participant of {@code document}. */
  public boolean canSeeReview(Document document, UUID userId) {
    return document.getOwnerId().equals(userId)
        || participants.existsAccessibleParticipant(document.getId(), userId);
  }

  /**
   * Whether {@code userId} currently sees the review and — under PRIVATE participation — the thread
   * of {@code annotation}.
   */
  public boolean canSeeThread(Document document, Annotation annotation, UUID userId) {
    return canSeeReview(document, userId)
        && threadVisible(document, annotation.getAuthorId(), userId);
  }

  /**
   * The id-based form for readers that only hold stored references (the digest): a document that no
   * longer exists is not visible; an annotation that no longer exists hides only that line.
   */
  public boolean canSee(UUID documentId, UUID annotationId, UUID userId) {
    Optional<Document> document = documents.findById(documentId);
    if (document.isEmpty() || !canSeeReview(document.get(), userId)) {
      return false;
    }
    if (annotationId == null) {
      return true;
    }
    return annotations
        .findById(annotationId)
        .map(annotation -> threadVisible(document.get(), annotation.getAuthorId(), userId))
        .orElse(false);
  }

  /** The PRIVATE-participation rule alone (issue #413); OPEN and READ_ONLY show every thread. */
  static boolean threadVisible(Document document, UUID annotationAuthorId, UUID userId) {
    if (document.getThreadParticipation() != ThreadParticipation.PRIVATE) {
      return true;
    }
    return userId.equals(document.getOwnerId()) || userId.equals(annotationAuthorId);
  }
}
