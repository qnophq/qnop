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
package io.qnop.service;

import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserTeamProjection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace-public profile (issues #454, #473): identity plus contribution aggregates and team
 * affiliations. Activity counters cover NON-anonymous reviews only (ADR-0038) — a public number
 * must never let anyone infer what anonymity hides; owned reviews count fully, since ownership is
 * structurally public. Team rosters are workspace-visible through the principal directory, so the
 * profile only aggregates what any member could already query.
 */
@Service
public class PublicProfileService {

  private final UserRepository users;
  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final TeamMembershipRepository teamMemberships;

  public PublicProfileService(
      UserRepository users,
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      AnnotationRepository annotations,
      CommentRepository comments,
      TeamMembershipRepository teamMemberships) {
    this.users = users;
    this.documents = documents;
    this.participants = participants;
    this.annotations = annotations;
    this.comments = comments;
    this.teamMemberships = teamMemberships;
  }

  @Transactional(readOnly = true)
  public PublicProfileView getProfile(UUID id) {
    return buildProfile(users.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
  }

  /**
   * Resolves a profile by the immutable, case-insensitively unique profile slug (issue #486).
   * Unknown slugs answer the same 404 as unknown ids.
   */
  @Transactional(readOnly = true)
  public PublicProfileView getProfileBySlug(String slug) {
    return buildProfile(
        users.findBySlugIgnoreCase(slug).orElseThrow(() -> new UserNotFoundException(slug)));
  }

  private PublicProfileView buildProfile(User user) {
    UUID id = user.getId();
    PublicStatsView stats =
        new PublicStatsView(
            documents.countByOwnerId(id),
            participants.countPublicParticipations(id),
            annotations.countPublicByAuthor(id),
            annotations.countPublicResolvedByAuthor(id),
            comments.countPublicByAuthor(id));
    List<UserTeamView> teams =
        teamMemberships.findTeamsOfUser(id).stream().map(UserTeamView::of).toList();
    return new PublicProfileView(
        id, user.getDisplayName(), user.getSlug(), user.getCreatedAt(), stats, teams);
  }

  /** The public slice served by {@code GET /users/{userId}} and {@code /users/by-slug/{slug}}. */
  public record PublicProfileView(
      UUID id,
      String displayName,
      String slug,
      Instant createdAt,
      PublicStatsView stats,
      List<UserTeamView> teams) {}

  /** Contribution aggregates; see the class doc for the anonymity rule. */
  public record PublicStatsView(
      long reviewsOwned,
      long reviewsParticipating,
      long annotationsRaised,
      long annotationsResolved,
      long commentsWritten) {}

  /** One team affiliation with the user's role there. */
  public record UserTeamView(UUID id, String name, String role) {
    static UserTeamView of(UserTeamProjection row) {
      return new UserTeamView(row.teamId(), row.teamName(), row.teamRole().name());
    }
  }
}
