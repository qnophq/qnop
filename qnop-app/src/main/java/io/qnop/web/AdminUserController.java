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

import io.qnop.api.v1.endpoint.AdminUsersApi;
import io.qnop.api.v1.model.AdminCreateUserRequest;
import io.qnop.api.v1.model.AdminPasswordResetResponse;
import io.qnop.api.v1.model.AdminUpdateUserRequest;
import io.qnop.api.v1.model.AdminUser;
import io.qnop.api.v1.model.AdminUserListResponse;
import io.qnop.service.UserNotFoundException;
import io.qnop.service.UserService;
import io.qnop.service.UserView;
import io.qnop.service.auth.AdminPasswordResetService;
import io.qnop.service.auth.AdminPasswordResetService.ExternalUserResetNotAllowedException;
import io.qnop.service.auth.AdminPasswordResetService.SelfResetNotAllowedException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Superadmin user-management endpoints ({@code /api/v1/admin/users/**}), implementing the generated
 * {@link AdminUsersApi} (issue #20, PR 2). Authorization is enforced by the security chain ({@code
 * /api/v1/admin/**} requires {@code SUPERADMIN}). The {@code User} entity never reaches this layer
 * — the service returns {@link UserView}s, which are mapped here to the API DTOs (ADR-0004).
 */
@RestController
public class AdminUserController implements AdminUsersApi {

  private final UserService userService;
  private final AdminPasswordResetService adminPasswordReset;

  public AdminUserController(
      UserService userService, AdminPasswordResetService adminPasswordReset) {
    this.userService = userService;
    this.adminPasswordReset = adminPasswordReset;
  }

  @Override
  public ResponseEntity<AdminUserListResponse> listUsers(Integer page, Integer size) {
    Page<UserView> result = userService.list(PageRequest.of(page, size));
    AdminUserListResponse body =
        new AdminUserListResponse()
            .users(result.getContent().stream().map(this::toDto).toList())
            .page(result.getNumber())
            .size(result.getSize())
            .total(result.getTotalElements());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<AdminUser> createUser(AdminCreateUserRequest request) {
    final UserView created;
    try {
      created =
          userService.createByAdmin(
              request.getUsername(),
              request.getEmail(),
              request.getPassword(),
              request.getDisplayName(),
              Boolean.TRUE.equals(request.getSuperadmin()));
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A user with that username or email already exists");
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
  }

  @Override
  public ResponseEntity<AdminUser> getUser(UUID userId) {
    try {
      return ResponseEntity.ok(toDto(userService.getView(userId)));
    } catch (UserNotFoundException e) {
      throw notFound(userId);
    }
  }

  @Override
  public ResponseEntity<AdminUser> updateUser(UUID userId, AdminUpdateUserRequest request) {
    try {
      UserView updated =
          userService.updateByAdmin(
              userId, request.getEnabled(), request.getDisplayName(), request.getSuperadmin());
      return ResponseEntity.ok(toDto(updated));
    } catch (UserNotFoundException e) {
      throw notFound(userId);
    }
  }

  @Override
  public ResponseEntity<Void> deleteUser(UUID userId) {
    try {
      userService.delete(userId);
    } catch (UserNotFoundException e) {
      throw notFound(userId);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<AdminPasswordResetResponse> adminResetUserPassword(UUID userId) {
    final AdminPasswordResetService.Result result;
    try {
      result = adminPasswordReset.trigger(userId, currentActor());
    } catch (SelfResetNotAllowedException | ExternalUserResetNotAllowedException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (UserNotFoundException e) {
      throw notFound(userId);
    }
    return ResponseEntity.ok(
        new AdminPasswordResetResponse()
            .tokenIssued(result.tokenIssued())
            .emailSent(result.emailSent())
            .expiresAt(toOffset(result.expiresAt()))
            .resetUrl(result.resetUrl()));
  }

  private UUID currentActor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return UUID.fromString(auth.getName());
  }

  private ResponseStatusException notFound(UUID userId) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user: " + userId);
  }

  private AdminUser toDto(UserView view) {
    return new AdminUser()
        .id(view.id())
        .username(view.username())
        .email(view.email())
        .displayName(view.displayName())
        .enabled(view.enabled())
        .superadmin(view.superadmin())
        .source(AdminUser.SourceEnum.valueOf(view.source()))
        .createdAt(toOffset(view.createdAt()))
        .lastLoginAt(toOffset(view.lastLoginAt()));
  }

  private OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
