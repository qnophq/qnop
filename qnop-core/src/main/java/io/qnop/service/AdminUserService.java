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

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import io.qnop.service.auth.PasswordResetFlowService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin user management (issue #104): paginated search, create-or-invite, edit and password-setup
 * links. Keeps the {@code User} entity inside the service layer — every result crosses the boundary
 * as an entity-free {@link AdminUserView} (role and source as their enum names), so the web layer
 * never touches a JPA entity (ADR-0004).
 *
 * <p>Two guards protect against admin lockout: an admin can neither disable nor demote their own
 * account, and the last enabled admin can never be disabled or demoted — both surface as {@link
 * AdminUserConflictException} (HTTP 409). Creating with an {@code initialPassword} yields an
 * enabled account that must change its password on first login; omitting it provisions the account
 * behind a random, unusable placeholder hash (the {@code INTERNAL ⇒ credentials} DB invariant) and
 * emails an invitation link the user follows to set their own password.
 */
@Service
public class AdminUserService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final PasswordResetFlowService passwordResetFlow;

  public AdminUserService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      PasswordResetFlowService passwordResetFlow) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.passwordResetFlow = passwordResetFlow;
  }

  /** A paginated, optionally filtered slice of users for the admin list. */
  @Transactional(readOnly = true)
  public AdminUserPage list(String query, String roleName, int page, int size) {
    String like =
        blankToNull(query) == null ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    UserRole role = parseRoleOrNull(roleName);
    Page<User> result =
        users.search(
            like, role, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<AdminUserView> items = result.getContent().stream().map(AdminUserService::toView).toList();
    return new AdminUserPage(items, result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public AdminUserView get(UUID id) {
    return toView(users.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
  }

  /**
   * Creates an internal account. With an {@code initialPassword} the account is enabled and must
   * change it on first login; without one it is provisioned behind a random placeholder hash and an
   * invitation (set-your-password) link is emailed.
   */
  @Transactional
  public AdminUserView create(
      String displayName, String username, String email, String roleName, String initialPassword) {
    String normalizedEmail = normalizeEmail(email);
    if (users.existsByEmailIgnoreCaseAndSource(normalizedEmail, UserSource.INTERNAL)) {
      throw new AdminUserConflictException("EMAIL_TAKEN", "A user with that email already exists.");
    }
    if (users.findByUsernameAndSource(username, UserSource.INTERNAL).isPresent()) {
      throw new AdminUserConflictException(
          "USERNAME_TAKEN", "A user with that username already exists.");
    }

    boolean invite = blankToNull(initialPassword) == null;
    String passwordHash =
        passwordEncoder.encode(invite ? randomPlaceholderSecret() : initialPassword);
    User user = User.internal(displayName, normalizedEmail, username, passwordHash);
    user.setRole(requireRole(roleName));
    user.setEnabled(true);
    // Invited users have not chosen a password yet; password-set users must rotate the admin-chosen
    // one on first login. Either way the account must change its password before normal use.
    user.setPasswordChangeRequired(true);
    User saved = users.save(user);

    if (invite) {
      passwordResetFlow.sendSetupLink(saved);
    }
    return toView(saved);
  }

  /**
   * Partial update of display name, global role and enabled state. A {@code null} field is left
   * unchanged. Rejects (409) any change that would disable or demote the acting admin's own account
   * or remove the last enabled admin.
   */
  @Transactional
  public AdminUserView update(
      UUID id, String displayName, String roleName, Boolean enabled, UUID actingUserId) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));

    UserRole newRole = roleName == null ? user.getRole() : requireRole(roleName);
    boolean newEnabled = enabled == null ? user.isEnabled() : enabled;

    boolean demoting = user.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN;
    boolean disabling = user.isEnabled() && !newEnabled;

    if (id.equals(actingUserId) && (demoting || disabling)) {
      throw new AdminUserConflictException(
          "SELF_LOCKOUT", "You cannot disable or change the role of your own account.");
    }
    if ((demoting || disabling)
        && user.getRole() == UserRole.ADMIN
        && user.isEnabled()
        && users.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
      throw new AdminUserConflictException("LAST_ADMIN", "At least one enabled admin must remain.");
    }

    if (displayName != null) {
      user.setDisplayName(displayName);
    }
    user.setRole(newRole);
    user.setEnabled(newEnabled);
    return toView(user);
  }

  /**
   * Issues a password-setup/reset token for an internal account and emails the link. External
   * (OIDC) accounts have no local password and are rejected (409).
   */
  @Transactional
  public void sendPasswordReset(UUID id) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (user.getSource() != UserSource.INTERNAL) {
      throw new AdminUserConflictException(
          "NO_LOCAL_PASSWORD",
          "This account signs in via an identity provider; it has no password.");
    }
    passwordResetFlow.sendSetupLink(user);
  }

  private static AdminUserView toView(User u) {
    return new AdminUserView(
        u.getId(),
        u.getDisplayName(),
        u.getEmail(),
        u.getUsername(),
        u.getRole().name(),
        u.getSource().name(),
        u.isEnabled(),
        u.getLastLoginAt(),
        u.getCreatedAt());
  }

  private UserRole requireRole(String roleName) {
    UserRole role = parseRoleOrNull(roleName);
    if (role == null) {
      throw new IllegalArgumentException("role is required");
    }
    return role;
  }

  private static UserRole parseRoleOrNull(String roleName) {
    if (blankToNull(roleName) == null) {
      return null;
    }
    return UserRole.valueOf(roleName.trim().toUpperCase(Locale.ROOT));
  }

  private static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String randomPlaceholderSecret() {
    // A single UUIDv4 (122 random bits, 36 chars) is ample for a never-disclosed placeholder and
    // stays within BCrypt's 72-byte input limit. The account is unusable until the invitee sets a
    // real password via the emailed link.
    return UUID.randomUUID().toString();
  }

  /** A Spring-free, entity-free projection of a user for the admin surface. */
  public record AdminUserView(
      UUID id,
      String displayName,
      String email,
      String username,
      String role,
      String source,
      boolean enabled,
      Instant lastLoginAt,
      Instant createdAt) {}

  /** One page of {@link AdminUserView}s plus the total count for the admin list. */
  public record AdminUserPage(List<AdminUserView> items, long total, int page, int size) {}
}
