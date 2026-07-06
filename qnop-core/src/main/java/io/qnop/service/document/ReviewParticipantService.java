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

import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ParticipantProjection;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.review.ReviewIdentityResolver;
import io.qnop.service.review.ReviewIdentityResolver.ReviewIdentities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the reviewer set of a document (issue #292, ADR-0011): a participant is a user XOR a
 * team; the owner stays structural on the document and is never a participant row. Reads follow the
 * anti-enumeration rule (unknown and invisible look identical, 404); mutations are owner-only (403
 * for participants, 404 for strangers — mirroring {@code DocumentIngestService.addVersion}).
 */
@Service
public class ReviewParticipantService {

  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final UserRepository users;
  private final TeamRepository teams;
  private final DocumentAccessService access;
  private final ReviewIdentityResolver identity;

  public ReviewParticipantService(
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      UserRepository users,
      TeamRepository teams,
      DocumentAccessService access,
      ReviewIdentityResolver identity) {
    this.documents = documents;
    this.participants = participants;
    this.users = users;
    this.teams = teams;
    this.access = access;
    this.identity = identity;
  }

  /**
   * The document's reviewers with display names, oldest first. In an anonymous review (issue #413/
   * #422) only the owner (and an admin) may see who the reviewers are; every other participant sees
   * the roster anonymised — each reviewer as a stable "Participant N" pseudonym (shared with the
   * annotation labels) or a nameless "Reviewer team", with the principal id replaced by a synthetic
   * per-document token so it cannot be correlated back to a real user or team.
   */
  @Transactional(readOnly = true)
  public List<ParticipantView> list(UUID documentId, UUID actor, boolean admin) {
    if (!access.isVisible(documentId, actor, admin)) {
      throw DocumentValidationException.notFound("document " + documentId);
    }
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(() -> DocumentValidationException.notFound("document " + documentId));
    List<ParticipantProjection> rows = participants.findViewsByDocumentId(documentId);
    boolean anonymise = document.isAnonymous() && !admin && !document.getOwnerId().equals(actor);
    if (!anonymise) {
      return rows.stream().map(ParticipantView::of).toList();
    }

    return anonymiseRoster(documentId, rows, identity.forDocument(documentId, actor));
  }

  /**
   * Anonymises a document's roster (issue #422): each user reviewer becomes their stable
   * "Participant N" pseudonym (their own row stays real — they know themselves) with the same
   * synthetic token used on their annotations; each team becomes a nameless "Reviewer team" whose
   * token derives from its position, not its real id, so the roster cannot be matched against the
   * principal directory. The caller's own row is sorted first, then the pseudonyms in their
   * original order. Shared by {@link #list} and the reviews overview.
   */
  static List<ParticipantView> anonymiseRoster(
      UUID documentId, List<ParticipantProjection> rows, ReviewIdentities identities) {
    // Caller first, everyone else in their existing (creation) order — a stable sort preserves it.
    List<ParticipantProjection> ordered =
        rows.stream()
            .sorted(Comparator.comparingInt(r -> identities.isSelf(r.userId()) ? 0 : 1))
            .toList();
    List<ParticipantView> out = new ArrayList<>(ordered.size());
    int teamIndex = 0;
    for (ParticipantProjection row : ordered) {
      if (row.teamId() == null) {
        out.add(
            new ParticipantView(
                row.id(),
                identities.exposedAuthorId(row.userId()),
                false,
                identities.displayName(row.userId()),
                row.createdAt()));
      } else {
        teamIndex++;
        UUID token =
            UUID.nameUUIDFromBytes(
                (documentId + ":anonteam:" + teamIndex).getBytes(StandardCharsets.UTF_8));
        out.add(new ParticipantView(row.id(), token, true, "Reviewer team", row.createdAt()));
      }
    }
    return out;
  }

  /** Adds a reviewer (owner-only): exactly one of {@code userId} / {@code teamId}. */
  @Transactional
  public ParticipantView add(UUID documentId, UUID actor, boolean admin, UUID userId, UUID teamId) {
    Document document = requireOwned(documentId, actor, admin);
    if ((userId == null) == (teamId == null)) {
      throw DocumentValidationException.invalidRequest(
          "exactly one of userId or teamId is required");
    }
    if (userId != null) {
      User user =
          users
              .findById(userId)
              .filter(User::isEnabled)
              .orElseThrow(
                  () -> DocumentValidationException.invalidRequest("unknown or disabled user"));
      if (document.getOwnerId().equals(userId)) {
        throw DocumentValidationException.invalidRequest("the owner is already part of the review");
      }
      if (participants.existsByDocumentIdAndUserId(documentId, userId)) {
        throw DocumentValidationException.duplicateParticipant("user is already a participant");
      }
      ReviewParticipant saved = participants.save(ReviewParticipant.forUser(documentId, userId));
      return new ParticipantView(
          saved.getId(), userId, false, user.getDisplayName(), saved.getCreatedAt());
    }
    Team team =
        teams
            .findById(teamId)
            .filter(Team::isEnabled)
            .orElseThrow(
                () -> DocumentValidationException.invalidRequest("unknown or disabled team"));
    if (participants.existsByDocumentIdAndTeamId(documentId, teamId)) {
      throw DocumentValidationException.duplicateParticipant("team is already a participant");
    }
    ReviewParticipant saved = participants.save(ReviewParticipant.forTeam(documentId, teamId));
    return new ParticipantView(saved.getId(), teamId, true, team.getName(), saved.getCreatedAt());
  }

  /** Removes a reviewer (owner-only); a participant of another document reads as unknown. */
  @Transactional
  public void remove(UUID documentId, UUID participantId, UUID actor, boolean admin) {
    requireOwned(documentId, actor, admin);
    ReviewParticipant participant =
        participants
            .findById(participantId)
            .filter(p -> p.getDocumentId().equals(documentId))
            .orElseThrow(
                () -> DocumentValidationException.notFound("participant " + participantId));
    participants.delete(participant);
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
      throw DocumentValidationException.notOwner("only the owner manages participants");
    }
    return document;
  }

  /** A participant with its principal's display name. */
  public record ParticipantView(
      UUID id, UUID principalId, boolean team, String displayName, Instant createdAt) {

    static ParticipantView of(ParticipantProjection projection) {
      boolean team = projection.teamId() != null;
      return new ParticipantView(
          projection.id(),
          team ? projection.teamId() : projection.userId(),
          team,
          projection.displayName(),
          projection.createdAt());
    }
  }
}
