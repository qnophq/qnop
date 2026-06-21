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
import io.qnop.api.v1.model.AdminUserCreateRequest;
import io.qnop.api.v1.model.AdminUserDetail;
import io.qnop.api.v1.model.AdminUserListResponse;
import io.qnop.api.v1.model.AdminUserSummary;
import io.qnop.api.v1.model.AdminUserUpdateRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.UserRole;
import io.qnop.api.v1.model.UserSource;
import io.qnop.service.AdminUserConflictException;
import io.qnop.service.AdminUserService;
import io.qnop.service.AdminUserService.AdminUserPage;
import io.qnop.service.AdminUserService.AdminUserView;
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
 * Admin-only user management ({@code /api/v1/admin/users/**}), implementing the generated {@link
 * AdminUsersApi} contract (issue #104). Authorization is enforced centrally by the security chain
 * ({@code /api/v1/admin/**} requires {@code ADMIN}). The {@code User} entity never reaches this
 * layer — {@link AdminUserService} returns entity-free {@link AdminUserView}s (role and source as
 * their enum names), mapped here to API DTOs (ADR-0004). Unknown users surface as 404, conflicts
 * (duplicate identity, self-lockout, last-admin, external-account reset) as 409.
 */
@RestController
public class AdminUserController implements AdminUsersApi {

  private final AdminUserService adminUsers;

  public AdminUserController(AdminUserService adminUsers) {
    this.adminUsers = adminUsers;
  }

  @Override
  public ResponseEntity<AdminUserListResponse> listUsers(
      String q, UserRole role, Integer page, Integer size) {
    AdminUserPage result = adminUsers.list(q, role == null ? null : role.name(), page, size);
    return ResponseEntity.ok(
        new AdminUserListResponse()
            .items(result.items().stream().map(AdminUserController::toSummary).toList())
            .total(result.total())
            .page(result.page())
            .size(result.size()));
  }

  @Override
  public ResponseEntity<AdminUserDetail> createUser(AdminUserCreateRequest request) {
    AdminUserView created =
        adminUsers.create(
            request.getDisplayName(),
            request.getUsername(),
            request.getEmail(),
            request.getRole().name(),
            request.getInitialPassword());
    return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(created));
  }

  @Override
  public ResponseEntity<AdminUserDetail> getUser(UUID userId) {
    return ResponseEntity.ok(toDetail(adminUsers.get(userId)));
  }

  @Override
  public ResponseEntity<AdminUserDetail> updateUser(UUID userId, AdminUserUpdateRequest request) {
    AdminUserView updated =
        adminUsers.update(
            userId,
            request.getDisplayName(),
            request.getRole() == null ? null : request.getRole().name(),
            request.getEnabled(),
            CurrentUser.requireUserId());
    return ResponseEntity.ok(toDetail(updated));
  }

  @Override
  public ResponseEntity<Void> sendUserPasswordReset(UUID userId) {
    adminUsers.sendPasswordReset(userId);
    return ResponseEntity.accepted().build();
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> onUserNotFound(UserNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(AdminUserConflictException.class)
  public ResponseEntity<ErrorResponse> onConflict(AdminUserConflictException ex) {
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

  private static AdminUserSummary toSummary(AdminUserView v) {
    return new AdminUserSummary()
        .id(v.id())
        .displayName(v.displayName())
        .email(v.email())
        .username(v.username())
        .role(UserRole.fromValue(v.role()))
        .source(UserSource.fromValue(v.source()))
        .enabled(v.enabled())
        .lastLoginAt(toOffset(v.lastLoginAt()))
        .createdAt(toOffset(v.createdAt()));
  }

  private static AdminUserDetail toDetail(AdminUserView v) {
    return new AdminUserDetail()
        .id(v.id())
        .displayName(v.displayName())
        .email(v.email())
        .username(v.username())
        .role(UserRole.fromValue(v.role()))
        .source(UserSource.fromValue(v.source()))
        .enabled(v.enabled())
        .lastLoginAt(toOffset(v.lastLoginAt()))
        .createdAt(toOffset(v.createdAt()));
  }

  private static OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
