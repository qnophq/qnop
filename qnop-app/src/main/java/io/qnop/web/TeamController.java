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

import io.qnop.api.v1.endpoint.AdminTeamsApi;
import io.qnop.api.v1.model.AdminTeamCreateRequest;
import io.qnop.api.v1.model.AdminTeamDetail;
import io.qnop.api.v1.model.AdminTeamListResponse;
import io.qnop.api.v1.model.AdminTeamMember;
import io.qnop.api.v1.model.AdminTeamMemberRequest;
import io.qnop.api.v1.model.AdminTeamMemberRoleUpdateRequest;
import io.qnop.api.v1.model.AdminTeamSummary;
import io.qnop.api.v1.model.AdminTeamUpdateRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.TeamRole;
import io.qnop.service.TeamConflictException;
import io.qnop.service.TeamNotFoundException;
import io.qnop.service.TeamService;
import io.qnop.service.TeamService.TeamDetailView;
import io.qnop.service.TeamService.TeamMemberView;
import io.qnop.service.TeamService.TeamPage;
import io.qnop.service.TeamService.TeamSummaryView;
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
 * Admin-only team management ({@code /api/v1/admin/teams/**}), implementing the generated {@link
 * AdminTeamsApi} contract (issue #105). Authorization is enforced centrally by the security chain
 * ({@code /api/v1/admin/**} requires {@code ADMIN}). The entities never reach this layer — {@link
 * TeamService} returns entity-free views, mapped here to API DTOs (ADR-0004). Unknown teams/members
 * surface as 404, conflicts (duplicate name, already a member) as 409.
 */
@RestController
public class TeamController implements AdminTeamsApi {

  private final TeamService teams;

  public TeamController(TeamService teams) {
    this.teams = teams;
  }

  @Override
  public ResponseEntity<AdminTeamListResponse> listTeams(String q, Integer page, Integer size) {
    TeamPage result = teams.list(q, page, size);
    return ResponseEntity.ok(
        new AdminTeamListResponse()
            .items(result.items().stream().map(TeamController::toSummary).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  @Override
  public ResponseEntity<AdminTeamSummary> createTeam(AdminTeamCreateRequest request) {
    TeamSummaryView created = teams.create(request.getName(), request.getDescription());
    return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(created));
  }

  @Override
  public ResponseEntity<AdminTeamDetail> getTeam(UUID teamId) {
    return ResponseEntity.ok(toDetail(teams.get(teamId)));
  }

  @Override
  public ResponseEntity<AdminTeamSummary> updateTeam(UUID teamId, AdminTeamUpdateRequest request) {
    TeamSummaryView updated =
        teams.update(teamId, request.getName(), request.getDescription(), request.getEnabled());
    return ResponseEntity.ok(toSummary(updated));
  }

  @Override
  public ResponseEntity<Void> deleteTeam(UUID teamId) {
    teams.delete(teamId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<AdminTeamMember> addTeamMember(
      UUID teamId, AdminTeamMemberRequest request) {
    TeamMemberView member =
        teams.addMember(teamId, request.getUserId(), request.getTeamRole().name());
    return ResponseEntity.status(HttpStatus.CREATED).body(toMember(member));
  }

  @Override
  public ResponseEntity<AdminTeamMember> setTeamMemberRole(
      UUID teamId, UUID userId, AdminTeamMemberRoleUpdateRequest request) {
    TeamMemberView member = teams.setMemberRole(teamId, userId, request.getTeamRole().name());
    return ResponseEntity.ok(toMember(member));
  }

  @Override
  public ResponseEntity<Void> removeTeamMember(UUID teamId, UUID userId) {
    teams.removeMember(teamId, userId);
    return ResponseEntity.noContent().build();
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

  private static AdminTeamSummary toSummary(TeamSummaryView v) {
    return new AdminTeamSummary()
        .id(v.id())
        .name(v.name())
        .description(v.description())
        .enabled(v.enabled())
        .memberCount(v.memberCount())
        .createdAt(toOffset(v.createdAt()))
        .updatedAt(toOffset(v.updatedAt()));
  }

  private static AdminTeamDetail toDetail(TeamDetailView v) {
    return new AdminTeamDetail()
        .id(v.id())
        .name(v.name())
        .description(v.description())
        .enabled(v.enabled())
        .createdAt(toOffset(v.createdAt()))
        .updatedAt(toOffset(v.updatedAt()))
        .members(v.members().stream().map(TeamController::toMember).toList());
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
