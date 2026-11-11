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

import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserTeamProjection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace-public profile (issues #454, #473): identity plus contribution aggregates and team
 * affiliations. Activity counters cover NON-anonymous reviews only (ADR-0038) — a public number
 * must never let anyone infer what anonymity hides; owned reviews count fully, since ownership is
 * structurally public. The team affiliations follow the team-roster rule of the principal directory
 * and the team profile (issue #918): a team is listed only to the profile's own user, an admin, a
 * member of that team, or when the team's profile shows its members — otherwise walking user
 * profiles would rebuild every hidden roster.
 */
@Service
public class PublicProfileService {

  private final UserRepository users;
  private final DocumentRepository documents;
  private final ReviewParticipantRepository participants;
  private final AnnotationRepository annotations;
  private final CommentRepository comments;
  private final TeamMembershipRepository teamMemberships;
  private final TeamRepository teamRepository;

  public PublicProfileService(
      UserRepository users,
      DocumentRepository documents,
      ReviewParticipantRepository participants,
      AnnotationRepository annotations,
      CommentRepository comments,
      TeamMembershipRepository teamMemberships,
      TeamRepository teamRepository) {
    this.users = users;
    this.documents = documents;
    this.participants = participants;
    this.annotations = annotations;
    this.comments = comments;
    this.teamMemberships = teamMemberships;
    this.teamRepository = teamRepository;
  }

  /**
   * A disabled account's profile is not served (issue #918): it answers the same 404 as an unknown
   * id, so the public surface reveals nothing about deactivated accounts.
   */
  @Transactional(readOnly = true)
  public PublicProfileView getProfile(UUID id, UUID actorId, boolean admin) {
    return buildProfile(
        users.findById(id).filter(User::isEnabled).orElseThrow(() -> new UserNotFoundException(id)),
        actorId,
        admin);
  }

  /**
   * Resolves a profile by the immutable, case-insensitively unique profile slug (issue #486).
   * Unknown slugs and disabled accounts answer the same 404 as unknown ids.
   */
  @Transactional(readOnly = true)
  public PublicProfileView getProfileBySlug(String slug, UUID actorId, boolean admin) {
    return buildProfile(
        users
            .findBySlugIgnoreCase(slug)
            .filter(User::isEnabled)
            .orElseThrow(() -> new UserNotFoundException(slug)),
        actorId,
        admin);
  }

  private PublicProfileView buildProfile(User user, UUID actorId, boolean admin) {
    UUID id = user.getId();
    PublicStatsView stats =
        new PublicStatsView(
            documents.countByOwnerId(id),
            participants.countPublicParticipations(id),
            annotations.countPublicByAuthor(id),
            annotations.countPublicResolvedByAuthor(id),
            comments.countPublicByAuthor(id));
    List<UserTeamView> teams =
        visibleTeams(teamMemberships.findTeamsOfUser(id), id, actorId, admin).stream()
            .map(UserTeamView::of)
            .toList();
    return new PublicProfileView(
        id, user.getDisplayName(), user.getSlug(), user.getCreatedAt(), stats, teams);
  }

  /**
   * The affiliations {@code actorId} may see (issue #918): all of them for the profile's own user
   * or an admin; otherwise only teams the caller belongs to or whose profile shows its members.
   */
  private List<UserTeamProjection> visibleTeams(
      List<UserTeamProjection> affiliations, UUID profileUserId, UUID actorId, boolean admin) {
    if (admin || profileUserId.equals(actorId) || affiliations.isEmpty()) {
      return affiliations;
    }
    Set<UUID> callerTeams =
        teamMemberships.findTeamsOfUser(actorId).stream()
            .map(UserTeamProjection::teamId)
            .collect(Collectors.toSet());
    Set<UUID> showingMembers =
        teamRepository
            .findAllById(affiliations.stream().map(UserTeamProjection::teamId).toList())
            .stream()
            .filter(Team::isProfileShowMembers)
            .map(Team::getId)
            .collect(Collectors.toSet());
    return affiliations.stream()
        .filter(t -> callerTeams.contains(t.teamId()) || showingMembers.contains(t.teamId()))
        .toList();
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
