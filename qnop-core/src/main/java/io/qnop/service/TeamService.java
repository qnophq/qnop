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
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.TeamMemberCount;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team management (issue #105): teams plus their {@code LEAD}/{@code MEMBER} memberships. Keeps the
 * entities inside the service layer — every result crosses the boundary as an entity-free view
 * (team role as its enum name), so the web layer never touches a JPA entity (ADR-0004). Duplicate
 * team names and duplicate memberships surface as {@link TeamConflictException} (409); unknown
 * teams or members as {@link TeamNotFoundException} (404); an unknown user being added as {@link
 * UserNotFoundException} (404). Deleting a team cascades its memberships in the database.
 */
@Service
public class TeamService {

  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;
  private final UserRepository users;

  public TeamService(
      TeamRepository teams, TeamMembershipRepository memberships, UserRepository users) {
    this.teams = teams;
    this.memberships = memberships;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public TeamPage list(String query, int page, int size) {
    String like =
        blankToNull(query) == null ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    Page<Team> result =
        teams.search(like, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name")));
    List<UUID> ids = result.getContent().stream().map(Team::getId).toList();
    Map<UUID, Long> counts =
        ids.isEmpty()
            ? Map.of()
            : memberships.countMembersByTeamIds(ids).stream()
                .collect(Collectors.toMap(TeamMemberCount::teamId, TeamMemberCount::count));
    List<TeamSummaryView> items =
        result.getContent().stream()
            .map(t -> toSummary(t, counts.getOrDefault(t.getId(), 0L)))
            .toList();
    return new TeamPage(items, result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public TeamDetailView get(UUID id) {
    Team team = teams.findById(id).orElseThrow(() -> TeamNotFoundException.team(id));
    List<TeamMemberView> members =
        memberships.findMembersByTeamId(id).stream()
            .map(
                p ->
                    new TeamMemberView(
                        p.userId(), p.displayName(), p.email(), p.teamRole().name(), p.joinedAt()))
            .toList();
    return new TeamDetailView(
        team.getId(),
        team.getName(),
        team.getDescription(),
        team.isEnabled(),
        team.getCreatedAt(),
        team.getUpdatedAt(),
        members);
  }

  @Transactional
  public TeamSummaryView create(String name, String description) {
    String trimmed = requireName(name);
    if (teams.existsByNameIgnoreCase(trimmed)) {
      throw new TeamConflictException("NAME_TAKEN", "A team with that name already exists.");
    }
    Team saved = teams.save(Team.create(trimmed, blankToNull(description)));
    return toSummary(saved, 0L);
  }

  @Transactional
  public TeamSummaryView update(UUID id, String name, String description, Boolean enabled) {
    Team team = teams.findById(id).orElseThrow(() -> TeamNotFoundException.team(id));
    if (name != null) {
      String trimmed = requireName(name);
      teams
          .findByNameIgnoreCase(trimmed)
          .filter(other -> !other.getId().equals(id))
          .ifPresent(
              other -> {
                throw new TeamConflictException(
                    "NAME_TAKEN", "A team with that name already exists.");
              });
      team.setName(trimmed);
    }
    if (description != null) {
      team.setDescription(blankToNull(description));
    }
    if (enabled != null) {
      team.setEnabled(enabled);
    }
    long count =
        memberships.countMembersByTeamIds(List.of(id)).stream()
            .findFirst()
            .map(TeamMemberCount::count)
            .orElse(0L);
    return toSummary(team, count);
  }

  @Transactional
  public void delete(UUID id) {
    Team team = teams.findById(id).orElseThrow(() -> TeamNotFoundException.team(id));
    teams.delete(team); // memberships are removed by the ON DELETE CASCADE foreign key
  }

  @Transactional
  public TeamMemberView addMember(UUID teamId, UUID userId, String teamRole) {
    if (!teams.existsById(teamId)) {
      throw TeamNotFoundException.team(teamId);
    }
    User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    if (memberships.existsByTeamIdAndUserId(teamId, userId)) {
      throw new TeamConflictException(
          "ALREADY_MEMBER", "This user is already a member of the team.");
    }
    TeamMembership saved =
        memberships.save(TeamMembership.of(teamId, userId, requireTeamRole(teamRole)));
    return toMemberView(user, saved);
  }

  @Transactional
  public TeamMemberView setMemberRole(UUID teamId, UUID userId, String teamRole) {
    TeamMembership membership =
        memberships
            .findByTeamIdAndUserId(teamId, userId)
            .orElseThrow(() -> TeamNotFoundException.membership(teamId, userId));
    membership.setTeamRole(requireTeamRole(teamRole));
    User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    return toMemberView(user, membership);
  }

  @Transactional
  public void removeMember(UUID teamId, UUID userId) {
    TeamMembership membership =
        memberships
            .findByTeamIdAndUserId(teamId, userId)
            .orElseThrow(() -> TeamNotFoundException.membership(teamId, userId));
    memberships.delete(membership);
  }

  private static TeamMemberView toMemberView(User user, TeamMembership membership) {
    return new TeamMemberView(
        user.getId(),
        user.getDisplayName(),
        user.getEmail(),
        membership.getTeamRole().name(),
        membership.getJoinedAt());
  }

  private static TeamSummaryView toSummary(Team team, long memberCount) {
    return new TeamSummaryView(
        team.getId(),
        team.getName(),
        team.getDescription(),
        team.isEnabled(),
        memberCount,
        team.getCreatedAt(),
        team.getUpdatedAt());
  }

  private static String requireName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("name is required");
    }
    return trimmed;
  }

  private static TeamRole requireTeamRole(String teamRole) {
    if (blankToNull(teamRole) == null) {
      throw new IllegalArgumentException("teamRole is required");
    }
    return TeamRole.valueOf(teamRole.trim().toUpperCase(Locale.ROOT));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** A team as shown in the admin team list. */
  public record TeamSummaryView(
      UUID id,
      String name,
      String description,
      boolean enabled,
      long memberCount,
      Instant createdAt,
      Instant updatedAt) {}

  /** A team member joined with their user identity. */
  public record TeamMemberView(
      UUID userId, String displayName, String email, String teamRole, Instant joinedAt) {}

  /** Full team detail including its members. */
  public record TeamDetailView(
      UUID id,
      String name,
      String description,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt,
      List<TeamMemberView> members) {}

  /** One page of {@link TeamSummaryView}s plus the total count. */
  public record TeamPage(List<TeamSummaryView> items, long total, int page, int size) {}
}
