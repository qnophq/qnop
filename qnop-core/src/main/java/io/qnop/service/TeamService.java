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
import io.qnop.repository.UserTeamProjection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
                        p.userId(),
                        p.displayName(),
                        p.slug(),
                        p.email(),
                        p.teamRole().name(),
                        p.joinedAt()))
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

  // ---------------------------------------------------------------------------
  // Team-lead self-management (issue #470). A LEAD of a team may manage that
  // team's membership without being a global ADMIN; an ADMIN passes for any
  // team. Every mutation goes through the guard, and a team can never lose its
  // last lead through this surface (which also blocks a sole lead's self-lockout).
  // ---------------------------------------------------------------------------

  /**
   * The caller's own teams with the caller's role and member count, ordered by name (issue #470).
   */
  @Transactional(readOnly = true)
  public List<MyTeamView> listMyTeams(UUID userId) {
    List<UserTeamProjection> mine = memberships.findTeamsOfUser(userId);
    List<UUID> ids = mine.stream().map(UserTeamProjection::teamId).toList();
    Map<UUID, Long> counts =
        ids.isEmpty()
            ? Map.of()
            : memberships.countMembersByTeamIds(ids).stream()
                .collect(Collectors.toMap(TeamMemberCount::teamId, TeamMemberCount::count));
    return mine.stream()
        .map(
            t ->
                new MyTeamView(
                    t.teamId(),
                    t.teamName(),
                    t.teamRole().name(),
                    counts.getOrDefault(t.teamId(), 0L)))
        .collect(Collectors.toList());
  }

  /**
   * A team with its members for any member of the team, or any team for an admin (issue #470). The
   * result carries the caller's own role and whether they may manage the membership — a LEAD (or an
   * admin) manages, a plain MEMBER may only view. A non-member, non-admin caller gets 403.
   */
  @Transactional(readOnly = true)
  public MemberTeamView viewTeam(UUID teamId, UUID actorId, boolean admin) {
    Optional<TeamMembership> membership = memberships.findByTeamIdAndUserId(teamId, actorId);
    if (membership.isEmpty() && !admin) {
      throw new TeamAccessForbiddenException("You are not a member of this team.");
    }
    Team team = teams.findById(teamId).orElseThrow(() -> TeamNotFoundException.team(teamId));
    List<TeamMemberView> members =
        memberships.findMembersByTeamId(teamId).stream()
            .map(
                p ->
                    new TeamMemberView(
                        p.userId(),
                        p.displayName(),
                        p.slug(),
                        p.email(),
                        p.teamRole().name(),
                        p.joinedAt()))
            .toList();
    String viewerRole = membership.map(m -> m.getTeamRole().name()).orElse(null);
    boolean canManage =
        admin || membership.map(m -> m.getTeamRole() == TeamRole.LEAD).orElse(false);
    return new MemberTeamView(
        team.getId(), team.getName(), team.getDescription(), viewerRole, canManage, members);
  }

  /** Add a member as a lead of the team (or an admin); otherwise 403 (issue #470). */
  @Transactional
  public TeamMemberView addMemberAsLead(
      UUID teamId, UUID actorId, boolean admin, UUID userId, String teamRole) {
    requireLeadOrAdmin(teamId, actorId, admin);
    return addMember(teamId, userId, teamRole);
  }

  /** Change a member's role as a lead (or admin); refuses to demote the last lead (issue #470). */
  @Transactional
  public TeamMemberView setMemberRoleAsLead(
      UUID teamId, UUID actorId, boolean admin, UUID userId, String teamRole) {
    requireLeadOrAdmin(teamId, actorId, admin);
    if (requireTeamRole(teamRole) == TeamRole.MEMBER) {
      lockTeam(teamId);
      guardNotLastLead(teamId, userId);
    }
    return setMemberRole(teamId, userId, teamRole);
  }

  /**
   * Remove a member as a lead (or admin); refuses to remove the last lead (issue #470). A caller
   * may never remove themselves through this self-management surface — leaving a team is an
   * administrator action ({@code /admin/teams}), so a lead cannot accidentally strip their own
   * membership (or lead access) here.
   */
  @Transactional
  public void removeMemberAsLead(UUID teamId, UUID actorId, boolean admin, UUID userId) {
    requireLeadOrAdmin(teamId, actorId, admin);
    if (actorId.equals(userId)) {
      throw new TeamConflictException(
          "SELF_REMOVAL", "You cannot remove yourself from a team; ask an administrator.");
    }
    lockTeam(teamId);
    guardNotLastLead(teamId, userId);
    removeMember(teamId, userId);
  }

  /**
   * Takes a pessimistic row lock on the team so the last-lead guard and its mutation run atomically
   * against any concurrent demote/remove on the same team — closing the TOCTOU race that optimistic
   * locking cannot (the racing requests touch different membership rows). A missing team is left
   * for the downstream mutation to surface as a 404.
   */
  private void lockTeam(UUID teamId) {
    teams.findByIdForUpdate(teamId);
  }

  private void requireLeadOrAdmin(UUID teamId, UUID actorId, boolean admin) {
    if (admin) {
      return;
    }
    if (!memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, actorId, TeamRole.LEAD)) {
      throw new TeamAccessForbiddenException("Only a lead of this team may manage it.");
    }
  }

  /**
   * Blocks an operation that would strip the team's last remaining lead. Only fires when {@code
   * userId} is currently a LEAD and no other lead remains — removing/demoting a MEMBER, or a LEAD
   * with co-leads, is unaffected. This is the single place the "never zero leads" invariant lives
   * for the self-management surface, and it is what prevents a sole lead's self-lockout.
   */
  private void guardNotLastLead(UUID teamId, UUID userId) {
    boolean targetIsLead =
        memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, userId, TeamRole.LEAD);
    if (targetIsLead && memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD) <= 1) {
      throw new TeamConflictException("LAST_LEAD", "A team must keep at least one lead.");
    }
  }

  private static TeamMemberView toMemberView(User user, TeamMembership membership) {
    return new TeamMemberView(
        user.getId(),
        user.getDisplayName(),
        user.getSlug(),
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

  /** A team member joined with their user identity (slug for the profile link, issue #470/#486). */
  public record TeamMemberView(
      UUID userId,
      String displayName,
      String slug,
      String email,
      String teamRole,
      Instant joinedAt) {}

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

  /** A team the caller belongs to, with the caller's role and member count there (issue #470). */
  public record MyTeamView(UUID teamId, String name, String teamRole, long memberCount) {}

  /**
   * A team and its members as seen by a member (issue #470). {@code viewerRole} is the caller's
   * role in the team ({@code null} for an admin who is not a member); {@code canManage} says
   * whether the caller may manage the membership (a LEAD, or an admin).
   */
  public record MemberTeamView(
      UUID id,
      String name,
      String description,
      String viewerRole,
      boolean canManage,
      List<TeamMemberView> members) {}
}
