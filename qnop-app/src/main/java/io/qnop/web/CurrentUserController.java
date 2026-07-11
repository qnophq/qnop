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
import io.qnop.api.v1.model.UserRole;
import io.qnop.api.v1.model.UserSource;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.UserService;
import io.qnop.service.UserService.PublicUserProfileView;
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
  private final AvatarService avatars;

  public CurrentUserController(UserService users, AvatarService avatars) {
    this.users = users;
    this.avatars = avatars;
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
            .avatarUrl(AvatarUrls.forUser(userId, avatars.updatedAt(userId).orElse(null))));
  }

  @Override
  public ResponseEntity<PublicUserProfile> getUserProfile(UUID userId) {
    CurrentUser.requireUserId(); // signed-in colleagues only, any role
    PublicUserProfileView profile = users.getPublicProfile(userId);
    return ResponseEntity.ok(
        new PublicUserProfile()
            .id(profile.id())
            .displayName(profile.displayName())
            .avatarUrl(AvatarUrls.forUser(userId, avatars.updatedAt(userId).orElse(null)))
            .createdAt(profile.createdAt().atOffset(java.time.ZoneOffset.UTC)));
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> onUserNotFound(UserNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse().code("USER_NOT_FOUND").message("No such user."));
  }
}
