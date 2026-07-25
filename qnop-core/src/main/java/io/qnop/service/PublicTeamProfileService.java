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

import io.qnop.entity.Document;
import io.qnop.entity.Team;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace-public team profile (issue #586), mirroring {@link PublicProfileService}: crest,
 * name and description always; the member roster and the review participation only when the team's
 * visibility toggles expose them — or when the caller is a member of the team or an admin, who
 * always see their own team in full (the roster is theirs via My Teams anyway). Hidden sections are
 * {@code null}, mapped to omitted fields — never empty lists, so the client can tell "hidden" from
 * "empty".
 *
 * <p><strong>Leak-free by construction (ADR-0038):</strong> the participation list is the
 * intersection of the team's review participations with the CALLER's review visibility ({@link
 * DocumentRepository#findTeamParticipationsVisibleTo}). A non-participant caller only ever sees
 * reviews they could already reach through the reviews overview; counts derive from the same
 * intersection, so even numbers reveal nothing hidden.
 *
 * <p>Unknown and disabled teams answer the same 404 — a disabled team disappears from the public
 * surface exactly like an unknown slug (anti-enumeration, as on the user and document resolvers).
 * The member roster never exposes e-mail addresses; those stay on the member/admin surfaces.
 */
@Service
public class PublicTeamProfileService {

  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;
  private final DocumentRepository documents;

  public PublicTeamProfileService(
      TeamRepository teams, TeamMembershipRepository memberships, DocumentRepository documents) {
    this.teams = teams;
    this.memberships = memberships;
    this.documents = documents;
  }

  @Transactional(readOnly = true)
  public PublicTeamProfileView getProfile(UUID teamId, UUID actorId, boolean admin) {
    Team team =
        teams
            .findById(teamId)
            .filter(Team::isEnabled)
            .orElseThrow(() -> TeamNotFoundException.team(teamId));
    return build(team, actorId, admin);
  }

  /** Resolves by the immutable team slug (issue #470); matching ignores case. */
  @Transactional(readOnly = true)
  public PublicTeamProfileView getProfileBySlug(String slug, UUID actorId, boolean admin) {
    Team team =
        teams
            .findBySlugIgnoreCase(slug)
            .filter(Team::isEnabled)
            .orElseThrow(() -> TeamNotFoundException.ref(slug));
    return build(team, actorId, admin);
  }

  private PublicTeamProfileView build(Team team, UUID actorId, boolean admin) {
    UUID teamId = team.getId();
    boolean viewerIsMember = memberships.existsByTeamIdAndUserId(teamId, actorId);
    boolean insider = viewerIsMember || admin;

    List<PublicTeamMemberView> members =
        insider || team.isProfileShowMembers()
            ? memberships.findMembersByTeamId(teamId).stream()
                .map(
                    m ->
                        new PublicTeamMemberView(
                            m.userId(), m.displayName(), m.slug(), m.teamRole().name()))
                .toList()
            : null;

    List<TeamReviewView> reviews =
        insider || team.isProfileShowReviews()
            ? documents.findTeamParticipationsVisibleTo(teamId, actorId).stream()
                .map(this::toReview)
                .toList()
            : null;

    return new PublicTeamProfileView(
        teamId,
        team.getName(),
        team.getSlug(),
        team.getDescription(),
        viewerIsMember,
        team.getCreatedAt(),
        members,
        reviews);
  }

  private TeamReviewView toReview(Document document) {
    return new TeamReviewView(
        document.getId(),
        document.getTitle(),
        document.getSlug(),
        document.getWorkflowState(),
        document.getUpdatedAt());
  }

  /**
   * The public slice served by {@code GET /teams/{teamId}/profile} and {@code
   * /teams/by-slug/{slug}}. {@code members}/{@code reviews} are {@code null} when the section is
   * hidden from this caller.
   */
  public record PublicTeamProfileView(
      UUID id,
      String name,
      String slug,
      String description,
      boolean viewerIsMember,
      Instant createdAt,
      List<PublicTeamMemberView> members,
      List<TeamReviewView> reviews) {}

  /** One roster row — deliberately without the e-mail the member surfaces carry. */
  public record PublicTeamMemberView(UUID userId, String displayName, String slug, String role) {}

  /** One review the team participates in, as far as the caller may see it. */
  public record TeamReviewView(
      UUID id, String title, String slug, String workflowState, Instant updatedAt) {}
}
