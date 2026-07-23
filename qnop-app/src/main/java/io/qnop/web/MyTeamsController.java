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
package io.qnop.web;

import io.qnop.api.v1.endpoint.TeamsApi;
import io.qnop.api.v1.model.AdminTeamMemberRequest;
import io.qnop.api.v1.model.AdminTeamMemberRoleUpdateRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.MyTeam;
import io.qnop.api.v1.model.MyTeamListResponse;
import io.qnop.api.v1.model.TeamDetail;
import io.qnop.api.v1.model.TeamMember;
import io.qnop.api.v1.model.TeamRole;
import io.qnop.service.TeamAccessForbiddenException;
import io.qnop.service.TeamConflictException;
import io.qnop.service.TeamNotFoundException;
import io.qnop.service.TeamService;
import io.qnop.service.TeamService.MemberTeamView;
import io.qnop.service.TeamService.TeamMemberView;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.avatar.AvatarService;
import io.qnop.service.avatar.TeamAvatarService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * The non-admin "My Teams" surface ({@code /api/v1/teams/**}), implementing the generated {@link
 * TeamsApi} contract (issue #470). Every authenticated user reaches it; the per-team authorization
 * is enforced in the service: viewing a team requires membership (an ADMIN sees any team), while
 * managing the membership requires being a LEAD of that team (or an ADMIN) — else 403. Members are
 * returned with the identity fields the UI renders like everywhere else (slug + avatar URL). The
 * entities never reach this layer — {@link TeamService} returns entity-free views, mapped here to
 * API DTOs (ADR-0004).
 */
@RestController
public class MyTeamsController implements TeamsApi {

  private final TeamService teams;
  private final AvatarService avatars;
  private final TeamAvatarService teamAvatars;

  public MyTeamsController(
      TeamService teams, AvatarService avatars, TeamAvatarService teamAvatars) {
    this.teams = teams;
    this.avatars = avatars;
    this.teamAvatars = teamAvatars;
  }

  @Override
  public ResponseEntity<MyTeamListResponse> listMyTeams() {
    UUID userId = CurrentUser.requireUserId();
    List<TeamService.MyTeamView> mine = teams.listMyTeams(userId);
    // One batched team-avatar lookup for the whole list so building URLs never streams bytes
    // (#509).
    Map<UUID, Instant> avatarTs =
        teamAvatars.updatedAt(mine.stream().map(TeamService.MyTeamView::teamId).toList());
    return ResponseEntity.ok(
        new MyTeamListResponse()
            .items(
                mine.stream()
                    .map(v -> toMyTeam(v, AvatarUrls.forTeam(v.teamId(), avatarTs.get(v.teamId()))))
                    .toList()));
  }

  @Override
  public ResponseEntity<TeamDetail> getMyTeam(String teamId) {
    MemberTeamView view =
        teams.viewTeam(teamId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(toDetail(view));
  }

  @Override
  public ResponseEntity<TeamMember> addMyTeamMember(UUID teamId, AdminTeamMemberRequest request) {
    TeamMemberView member =
        teams.addMemberAsLead(
            teamId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            request.getUserId(),
            request.getTeamRole().name());
    return ResponseEntity.status(HttpStatus.CREATED).body(toMember(member));
  }

  @Override
  public ResponseEntity<TeamMember> setMyTeamMemberRole(
      UUID teamId, UUID userId, AdminTeamMemberRoleUpdateRequest request) {
    TeamMemberView member =
        teams.setMemberRoleAsLead(
            teamId,
            CurrentUser.requireUserId(),
            CurrentUser.isAdmin(),
            userId,
            request.getTeamRole().name());
    return ResponseEntity.ok(toMember(member));
  }

  @Override
  public ResponseEntity<Void> removeMyTeamMember(UUID teamId, UUID userId) {
    teams.removeMemberAsLead(teamId, CurrentUser.requireUserId(), CurrentUser.isAdmin(), userId);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(TeamAccessForbiddenException.class)
  public ResponseEntity<ErrorResponse> onForbidden(TeamAccessForbiddenException ex) {
    return error(HttpStatus.FORBIDDEN, "TEAM_ACCESS_FORBIDDEN", ex.getMessage());
  }

  @ExceptionHandler(TeamNotFoundException.class)
  public ResponseEntity<ErrorResponse> onTeamNotFound(TeamNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> onUserNotFound(UserNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(TeamConflictException.class)
  public ResponseEntity<ErrorResponse> onConflict(TeamConflictException ex) {
    return error(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
  }

  private static ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            new ErrorResponse()
                .code(code)
                .message(message)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private static MyTeam toMyTeam(TeamService.MyTeamView v, String avatarUrl) {
    return new MyTeam()
        .teamId(v.teamId())
        .name(v.name())
        .slug(v.slug())
        .teamRole(TeamRole.fromValue(v.teamRole()))
        .memberCount(v.memberCount())
        .avatarUrl(avatarUrl);
  }

  private TeamDetail toDetail(MemberTeamView v) {
    // One batched avatar-timestamp lookup for the whole roster (same pattern as
    // the admin user list), so building URLs never streams image bytes per row.
    Map<UUID, Instant> avatarTimestamps =
        avatars.updatedAt(v.members().stream().map(TeamMemberView::userId).toList());
    return new TeamDetail()
        .id(v.id())
        .name(v.name())
        .slug(v.slug())
        .description(v.description())
        .avatarUrl(AvatarUrls.forTeam(v.id(), teamAvatars.updatedAt(v.id()).orElse(null)))
        .viewerRole(v.viewerRole() == null ? null : TeamRole.fromValue(v.viewerRole()))
        .viewerCanManage(v.canManage())
        .members(
            v.members().stream().map(m -> toMember(m, avatarTimestamps.get(m.userId()))).toList());
  }

  private TeamMember toMember(TeamMemberView v) {
    return toMember(v, avatars.updatedAt(v.userId()).orElse(null));
  }

  private static TeamMember toMember(TeamMemberView v, Instant avatarUpdatedAt) {
    return new TeamMember()
        .userId(v.userId())
        .displayName(v.displayName())
        .slug(v.slug())
        .avatarUrl(AvatarUrls.forUser(v.userId(), avatarUpdatedAt))
        .email(v.email())
        .teamRole(TeamRole.fromValue(v.teamRole()))
        .joinedAt(toOffset(v.joinedAt()));
  }

  private static OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
