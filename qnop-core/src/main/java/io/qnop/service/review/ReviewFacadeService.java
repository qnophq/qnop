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

import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMemberProjection;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.service.document.DocumentAccessService;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's implementation of the {@link io.qnop.spi.review.ReviewFacade} (issue #602, ADR-0062).
 * Thin by design: every answer delegates to the code path the core itself trusts — {@link
 * DocumentAccessService} for visibility (the annotation-listing rule), this class's {@link
 * #reviewCircle} (shared with {@link ReviewNotificationService}, so the mail audience and the
 * facade's answer are one implementation), {@link ReviewIdentityResolver} for ADR-0038 identity.
 */
@Service
public class ReviewFacadeService implements io.qnop.spi.review.ReviewFacade {

  private final DocumentAccessService access;
  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final TeamMembershipRepository teamMembers;
  private final ReviewIdentityResolver identity;

  public ReviewFacadeService(
      DocumentAccessService access,
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      TeamMembershipRepository teamMembers,
      ReviewIdentityResolver identity) {
    this.access = access;
    this.documents = documents;
    this.participants = participants;
    this.teamMembers = teamMembers;
    this.identity = identity;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean mayView(UUID documentId, UUID principalId) {
    if (documentId == null || principalId == null) {
      return false;
    }
    return access.isVisible(documentId, principalId, false);
  }

  /** Everyone attached to the review: the owner's id plus user/team-member participant ids. */
  @Override
  @Transactional(readOnly = true)
  public Set<UUID> reviewCircle(UUID documentId) {
    return circleOf(documentId, ownerOf(documentId));
  }

  /**
   * The shared circle computation (issue #602): {@link ReviewNotificationService} feeds it the
   * already-loaded owner id; the facade resolves it. One implementation, so the mail audience and
   * an extension's answer cannot drift.
   */
  Set<UUID> circleOf(UUID documentId, UUID ownerId) {
    Set<UUID> circle = new LinkedHashSet<>();
    if (ownerId != null) {
      circle.add(ownerId);
    }
    for (ReviewParticipant participant : participants.findByDocumentId(documentId)) {
      if (participant.getUserId() != null) {
        circle.add(participant.getUserId());
      } else if (participant.getTeamId() != null) {
        teamMembers.findMembersByTeamId(participant.getTeamId()).stream()
            .map(TeamMemberProjection::userId)
            .forEach(circle::add);
      }
    }
    return circle;
  }

  private UUID ownerOf(UUID documentId) {
    return documents.findById(documentId).map(Document::getOwnerId).orElse(null);
  }

  @Override
  @Transactional(readOnly = true)
  public String displayNameFor(UUID documentId, UUID viewerId, UUID authorId) {
    String name = identity.forDocument(documentId, viewerId).displayName(authorId);
    return name == null || name.isBlank() ? "A participant" : name;
  }

  @Override
  @Transactional(readOnly = true)
  public UUID exposedAuthorIdFor(UUID documentId, UUID viewerId, UUID authorId) {
    return identity.forDocument(documentId, viewerId).exposedAuthorId(authorId);
  }
}
