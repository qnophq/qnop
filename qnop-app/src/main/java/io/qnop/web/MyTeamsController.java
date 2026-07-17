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
import io.qnop.api.v1.model.AdminTeamDetail;
import io.qnop.api.v1.model.AdminTeamMember;
import io.qnop.api.v1.model.AdminTeamMemberRequest;
import io.qnop.api.v1.model.AdminTeamMemberRoleUpdateRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.MyTeam;
import io.qnop.api.v1.model.MyTeamListResponse;
import io.qnop.api.v1.model.TeamRole;
import io.qnop.service.TeamAccessForbiddenException;
import io.qnop.service.TeamConflictException;
import io.qnop.service.TeamNotFoundException;
import io.qnop.service.TeamService;
import io.qnop.service.TeamService.TeamDetailView;
import io.qnop.service.TeamService.TeamMemberView;
import io.qnop.service.UserNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team self-management for team LEADs ({@code /api/v1/teams/**}), implementing the generated {@link
 * TeamsApi} contract (issue #470). Unlike the admin surface ({@code /admin/teams/**}, gated
 * centrally to {@code ADMIN}), these paths are only {@code authenticated()} — so the per-team LEAD
 * check is enforced in the service: the caller must be a {@code LEAD} of the team (an {@code ADMIN}
 * passes for any team), else 403. A LEAD may add/remove members and promote/demote leads for their
 * own team(s); a team can never lose its last lead through this surface. The entities never reach
 * this layer — {@link TeamService} returns entity-free views, mapped here to API DTOs (ADR-0004).
 */
@RestController
public class MyTeamsController implements TeamsApi {

  private final TeamService teams;

  public MyTeamsController(TeamService teams) {
    this.teams = teams;
  }

  @Override
  public ResponseEntity<MyTeamListResponse> listMyTeams() {
    UUID userId = CurrentUser.requireUserId();
    return ResponseEntity.ok(
        new MyTeamListResponse()
            .items(teams.listMyTeams(userId).stream().map(MyTeamsController::toMyTeam).toList()));
  }

  @Override
  public ResponseEntity<AdminTeamDetail> getMyTeam(UUID teamId) {
    TeamDetailView view =
        teams.getForLead(teamId, CurrentUser.requireUserId(), CurrentUser.isAdmin());
    return ResponseEntity.ok(toDetail(view));
  }

  @Override
  public ResponseEntity<AdminTeamMember> addMyTeamMember(
      UUID teamId, AdminTeamMemberRequest request) {
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
  public ResponseEntity<AdminTeamMember> setMyTeamMemberRole(
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

  private static MyTeam toMyTeam(TeamService.MyTeamView v) {
    return new MyTeam()
        .teamId(v.teamId())
        .name(v.name())
        .teamRole(TeamRole.fromValue(v.teamRole()));
  }

  private static AdminTeamDetail toDetail(TeamDetailView v) {
    return new AdminTeamDetail()
        .id(v.id())
        .name(v.name())
        .description(v.description())
        .enabled(v.enabled())
        .createdAt(toOffset(v.createdAt()))
        .updatedAt(toOffset(v.updatedAt()))
        .members(v.members().stream().map(MyTeamsController::toMember).toList());
  }

  private static AdminTeamMember toMember(TeamMemberView v) {
    return new AdminTeamMember()
        .userId(v.userId())
        .displayName(v.displayName())
        .email(v.email())
        .teamRole(TeamRole.fromValue(v.teamRole()))
        .joinedAt(toOffset(v.joinedAt()));
  }

  private static OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
