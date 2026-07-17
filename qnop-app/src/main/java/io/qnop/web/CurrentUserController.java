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

import io.qnop.api.v1.endpoint.UsersApi;
import io.qnop.api.v1.model.CurrentUserResponse;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.PublicUserProfile;
import io.qnop.api.v1.model.PublicUserStats;
import io.qnop.api.v1.model.PublicUserTeam;
import io.qnop.api.v1.model.UserRole;
import io.qnop.api.v1.model.UserSource;
import io.qnop.service.PublicProfileService;
import io.qnop.service.TeamService;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.UserService;
import io.qnop.service.UserService.UserProfileView;
import io.qnop.service.avatar.AvatarService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated user's own profile ({@code GET /api/v1/users/me}), implementing the generated
 * {@link UsersApi} contract (issue #99). The acting user is the JWT subject ({@link CurrentUser});
 * non-user (API-key) principals are rejected with 403. The service returns an entity-free {@link
 * UserProfileView}, mapped here to the API DTO (ADR-0004).
 */
@RestController
public class CurrentUserController implements UsersApi {

  private final UserService users;
  private final PublicProfileService publicProfiles;
  private final AvatarService avatars;
  private final TeamService teams;

  public CurrentUserController(
      UserService users,
      AvatarService avatars,
      PublicProfileService publicProfiles,
      TeamService teams) {
    this.users = users;
    this.avatars = avatars;
    this.publicProfiles = publicProfiles;
    this.teams = teams;
  }

  @Override
  public ResponseEntity<CurrentUserResponse> getCurrentUser() {
    UUID userId = CurrentUser.requireUserId();
    UserProfileView profile = users.getProfile(userId);
    return ResponseEntity.ok(
        new CurrentUserResponse()
            .id(profile.id())
            .displayName(profile.displayName())
            .email(profile.email())
            .role(UserRole.fromValue(profile.role()))
            .source(UserSource.fromValue(profile.source()))
            .avatarUrl(AvatarUrls.forUser(userId, avatars.updatedAt(userId).orElse(null)))
            .teamLead(teams.leadsAnyTeam(userId)));
  }

  @Override
  public ResponseEntity<PublicUserProfile> getUserProfile(UUID userId) {
    CurrentUser.requireUserId(); // signed-in colleagues only, any role
    return ResponseEntity.ok(toPublicUserProfile(publicProfiles.getProfile(userId)));
  }

  @Override
  public ResponseEntity<PublicUserProfile> getUserProfileBySlug(String slug) {
    CurrentUser.requireUserId(); // signed-in colleagues only, any role
    return ResponseEntity.ok(toPublicUserProfile(publicProfiles.getProfileBySlug(slug)));
  }

  private PublicUserProfile toPublicUserProfile(PublicProfileService.PublicProfileView profile) {
    UUID userId = profile.id();
    return new PublicUserProfile()
        .id(userId)
        .displayName(profile.displayName())
        .slug(profile.slug())
        .avatarUrl(AvatarUrls.forUser(userId, avatars.updatedAt(userId).orElse(null)))
        .createdAt(profile.createdAt().atOffset(java.time.ZoneOffset.UTC))
        .stats(
            new PublicUserStats()
                .reviewsOwned((int) profile.stats().reviewsOwned())
                .reviewsParticipating((int) profile.stats().reviewsParticipating())
                .annotationsRaised((int) profile.stats().annotationsRaised())
                .annotationsResolved((int) profile.stats().annotationsResolved())
                .commentsWritten((int) profile.stats().commentsWritten()))
        .teams(
            profile.teams().stream()
                .map(
                    team ->
                        new PublicUserTeam()
                            .id(team.id())
                            .name(team.name())
                            .role(PublicUserTeam.RoleEnum.fromValue(team.role())))
                .toList());
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> onUserNotFound(UserNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse().code("USER_NOT_FOUND").message("No such user."));
  }
}
