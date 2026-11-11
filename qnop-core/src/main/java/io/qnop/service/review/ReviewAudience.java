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
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may <em>currently</em> hear about a review or one of its threads (issue #919), and who may be
 * named in a thread (mentions, issue #921).
 *
 * <p>Notifications are addressed to people derived from history — the annotation's author, earlier
 * commenters, a team at the time it was added — so the set can outlive the access it was based on.
 * Every out-of-band channel (the immediate mail and in-app row, the daily digest) therefore
 * re-checks with this class at delivery or assembly time, with the rules the read paths apply:
 *
 * <ul>
 *   <li><strong>Review:</strong> the owner and the review's participants (direct or via a team).
 *       Admin status alone is not membership, matching the in-app inbox.
 *   <li><strong>Thread:</strong> under {@link ThreadParticipation#PRIVATE} only the owner and the
 *       annotation's author see a thread (issue #413) — plus admins, exactly as {@code
 *       AnnotationService.canSeeThread} lets them read and write it for moderation. An admin who
 *       actually took part in the thread (authored it or commented in it) keeps hearing about it
 *       even when they are not a participant of the review.
 * </ul>
 */
@Component
public class ReviewAudience {

  private final DocumentRepository documents;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final ReviewParticipantRepository participants;
  private final UserRepository users;

  public ReviewAudience(
      DocumentRepository documents,
      AnnotationRepository annotations,
      CommentRepository comments,
      ReviewParticipantRepository participants,
      UserRepository users) {
    this.documents = documents;
    this.annotations = annotations;
    this.comments = comments;
    this.participants = participants;
    this.users = users;
  }

  /** Whether {@code userId} is currently the owner or a participant of {@code document}. */
  public boolean canSeeReview(Document document, UUID userId) {
    return document.getOwnerId().equals(userId)
        || participants.existsAccessibleParticipant(document.getId(), userId);
  }

  /**
   * Whether {@code userId} may currently hear about the thread authored by {@code
   * annotationAuthorId}.
   *
   * @param tookPart whether the user authored the thread or commented in it — only consulted for
   *     admins, whom the review-level gate does not otherwise admit
   */
  public boolean canSeeThread(
      Document document, UUID annotationAuthorId, UUID userId, boolean tookPart) {
    boolean member = canSeeReview(document, userId);
    if (member && threadVisible(document, annotationAuthorId, userId)) {
      return true;
    }
    if (!isAdmin(userId)) {
      return false;
    }
    return member || tookPart;
  }

  /**
   * The id-based form for readers that only hold stored references (the digest): a document that no
   * longer exists is not visible; an annotation that no longer exists hides only that line.
   */
  public boolean canSee(UUID documentId, UUID annotationId, UUID userId) {
    Optional<Document> document = documents.findById(documentId);
    if (document.isEmpty()) {
      return false;
    }
    if (annotationId == null) {
      return canSeeReview(document.get(), userId);
    }
    Optional<Annotation> annotation = annotations.findById(annotationId);
    if (annotation.isEmpty()) {
      return false;
    }
    UUID authorId = annotation.get().getAuthorId();
    boolean tookPart =
        userId.equals(authorId) || comments.existsByAnnotationIdAndAuthorId(annotationId, userId);
    return canSeeThread(document.get(), authorId, userId, tookPart);
  }

  /** The PRIVATE-participation rule alone (issue #413); OPEN and READ_ONLY show every thread. */
  static boolean threadVisible(Document document, UUID annotationAuthorId, UUID userId) {
    if (document.getThreadParticipation() != ThreadParticipation.PRIVATE) {
      return true;
    }
    return userId.equals(document.getOwnerId()) || userId.equals(annotationAuthorId);
  }

  private boolean isAdmin(UUID userId) {
    return users.findById(userId).map(User::getRole).filter(UserRole.ADMIN::equals).isPresent();
  }
}
