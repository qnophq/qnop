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

import io.qnop.api.v1.endpoint.PrincipalsApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.ParticipantKind;
import io.qnop.api.v1.model.PrincipalListResponse;
import io.qnop.api.v1.model.PrincipalView;
import io.qnop.service.PrincipalDirectoryService;
import io.qnop.service.TeamNotFoundException;
import io.qnop.service.avatar.AvatarService;
import io.qnop.service.avatar.TeamAvatarService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assignable-principal directory (issue #292): enabled users and teams for picking review
 * participants — display names plus the users' avatar URLs, available to every authenticated user.
 */
@RestController
public class PrincipalsController implements PrincipalsApi {

  private final PrincipalDirectoryService directory;
  private final AvatarService avatars;
  private final TeamAvatarService teamAvatars;

  public PrincipalsController(
      PrincipalDirectoryService directory, AvatarService avatars, TeamAvatarService teamAvatars) {
    this.directory = directory;
    this.avatars = avatars;
    this.teamAvatars = teamAvatars;
  }

  @Override
  public ResponseEntity<PrincipalListResponse> searchPrincipals(String q, Integer size) {
    CurrentUser.requireUserId();
    return ResponseEntity.ok(toResponse(directory.search(q, size == null ? 20 : size)));
  }

  @Override
  public ResponseEntity<PrincipalListResponse> listTeamMembers(UUID teamId) {
    CurrentUser.requireUserId();
    return ResponseEntity.ok(toResponse(directory.teamMembers(teamId)));
  }

  private PrincipalListResponse toResponse(List<PrincipalDirectoryService.PrincipalView> views) {
    // One batched lookup for the page of user principals, so building avatar
    // URLs never streams image bytes (same pattern as the admin user list).
    Map<UUID, Instant> avatarTimestamps =
        avatars.updatedAt(
            views.stream()
                .filter(view -> !view.team())
                .map(PrincipalDirectoryService.PrincipalView::id)
                .toList());
    // The team counterpart (issue #509): one batched team-avatar lookup so a team principal renders
    // its picture instead of always falling back to the initials crest.
    Map<UUID, Instant> teamAvatarTimestamps =
        teamAvatars.updatedAt(
            views.stream()
                .filter(PrincipalDirectoryService.PrincipalView::team)
                .map(PrincipalDirectoryService.PrincipalView::id)
                .toList());
    return new PrincipalListResponse()
        .principals(
            views.stream()
                .map(
                    view ->
                        new PrincipalView()
                            .id(view.id())
                            .kind(view.team() ? ParticipantKind.TEAM : ParticipantKind.USER)
                            .slug(view.slug())
                            .displayName(view.displayName())
                            .avatarUrl(
                                view.team()
                                    ? AvatarUrls.forTeam(
                                        view.id(), teamAvatarTimestamps.get(view.id()))
                                    : AvatarUrls.forUser(
                                        view.id(), avatarTimestamps.get(view.id()))))
                .toList());
  }

  @ExceptionHandler(TeamNotFoundException.class)
  public ResponseEntity<ErrorResponse> onTeamNotFound(TeamNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            new ErrorResponse()
                .code("TEAM_NOT_FOUND")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }
}
