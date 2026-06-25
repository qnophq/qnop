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
import io.qnop.repository.OidcIdentityRepository;
import io.qnop.repository.UserProviderName;
import io.qnop.repository.UserRepository;
import io.qnop.security.PasswordGenerator;
import io.qnop.service.auth.PasswordResetFlowService;
import io.qnop.service.auth.PasswordResetFlowService.SetupLinkOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin user management (issues #104/#124): paginated search/filter/sort, create-or-invite, edit,
 * delete and admin password reset. Keeps the {@code User} entity inside the service layer — every
 * result crosses the boundary as an entity-free {@link AdminUserView} (role and source as their
 * enum names), so the web layer never touches a JPA entity (ADR-0004).
 *
 * <p>Lockout guards (all {@link AdminUserConflictException} / HTTP 409): an admin can neither
 * disable, demote nor delete their own account, and the last enabled admin can never be disabled,
 * demoted or deleted. Creating with an {@code initialPassword} yields an enabled account that must
 * change its password on first login; omitting it provisions the account behind a random, unusable
 * placeholder hash (the {@code INTERNAL ⇒ credentials} DB invariant) and emails an invitation link.
 */
@Service
public class AdminUserService {

  /** Whitelist of API sort fields → entity properties (guards the ORDER BY against injection). */
  private static final Map<String, String> SORTABLE =
      Map.of(
          "displayname", "displayName",
          "email", "email",
          "username", "username",
          "role", "role",
          "createdat", "createdAt",
          "lastloginat", "lastLoginAt");

  private final UserRepository users;
  private final OidcIdentityRepository oidcIdentities;
  private final PasswordEncoder passwordEncoder;
  private final PasswordResetFlowService passwordResetFlow;
  private final RefreshTokenService refreshTokens;

  public AdminUserService(
      UserRepository users,
      OidcIdentityRepository oidcIdentities,
      PasswordEncoder passwordEncoder,
      PasswordResetFlowService passwordResetFlow,
      RefreshTokenService refreshTokens) {
    this.users = users;
    this.oidcIdentities = oidcIdentities;
    this.passwordEncoder = passwordEncoder;
    this.passwordResetFlow = passwordResetFlow;
    this.refreshTokens = refreshTokens;
  }

  /** A paginated, optionally filtered/sorted slice of users for the admin list. */
  @Transactional(readOnly = true)
  public AdminUserPage list(
      String query, String roleName, Boolean enabled, String sort, int page, int size) {
    String like =
        blankToNull(query) == null ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    UserRole role = parseRoleOrNull(roleName);
    Page<User> result =
        users.search(like, role, enabled, PageRequest.of(page, size, parseSort(sort)));

    Map<UUID, String> providerNames = providerNamesFor(result.getContent());
    List<AdminUserView> items =
        result.getContent().stream().map(u -> toView(u, providerNameOf(u, providerNames))).toList();
    return new AdminUserPage(items, result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public AdminUserView get(UUID id) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    return toView(user, providerNameOf(user, providerNamesFor(List.of(user))));
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
    return toView(saved, null);
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

    // Disabling must end access immediately (mirrors the admin password reset): invalidate live
    // access tokens and revoke refresh families, so a disabled user cannot keep using or refreshing
    // a session. flush() persists the enabled change before bumpPasswordInvalidatedBefore clears
    // the
    // persistence context.
    if (disabling) {
      users.flush();
      users.bumpPasswordInvalidatedBefore(id, Instant.now());
      refreshTokens.revokeAllForUser(id);
    }
    return toView(user, null);
  }

  /**
   * Permanently deletes a user; the database cascades their tokens, settings, OIDC identity and
   * team memberships. Rejects (409) deleting your own account or the last enabled admin.
   */
  @Transactional
  public void delete(UUID id, UUID actingUserId) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (id.equals(actingUserId)) {
      throw new AdminUserConflictException("SELF_DELETE", "You cannot delete your own account.");
    }
    if (user.getRole() == UserRole.ADMIN
        && user.isEnabled()
        && users.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
      throw new AdminUserConflictException("LAST_ADMIN", "At least one enabled admin must remain.");
    }
    users.delete(user);
  }

  /**
   * Admin password reset for an internal account: revokes the user's active sessions immediately,
   * issues a single-use reset token and emails the set-your-password link. External (OIDC) accounts
   * have no local password and are rejected (409). The reset URL is returned only as a fallback
   * when the email could not be sent.
   */
  @Transactional
  public PasswordResetOutcome sendPasswordReset(UUID id) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (user.getSource() != UserSource.INTERNAL) {
      throw new AdminUserConflictException(
          "NO_LOCAL_PASSWORD",
          "This account signs in via an identity provider; it has no password.");
    }
    SetupLinkOutcome outcome = passwordResetFlow.sendSetupLink(user);
    // Revoke active sessions now (the modifying writes clear the persistence context, so do this
    // after the link has been issued from the still-managed entity).
    users.bumpPasswordInvalidatedBefore(id, Instant.now());
    refreshTokens.revokeAllForUser(id);
    return new PasswordResetOutcome(
        outcome.emailSent(), outcome.emailSent() ? null : outcome.resetUrl());
  }

  /**
   * Generates a strong password for an internal account, sets it (requiring a change on first
   * login), and revokes the user's active sessions immediately — the new password is the only way
   * back in. Returns the plaintext exactly once; it is never stored in clear text. External (OIDC)
   * accounts have no local password and are rejected (409). An admin may target their own account,
   * which signs them out (they sign back in with the new password).
   */
  @Transactional
  public GeneratedPasswordOutcome generatePassword(UUID id) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (user.getSource() != UserSource.INTERNAL) {
      throw new AdminUserConflictException(
          "NO_LOCAL_PASSWORD",
          "This account signs in via an identity provider; it has no password.");
    }
    String password = PasswordGenerator.generate();
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setPasswordChangeRequired(true);
    // flush() persists the hash + flag before bumpPasswordInvalidatedBefore clears the context.
    users.flush();
    users.bumpPasswordInvalidatedBefore(id, Instant.now());
    refreshTokens.revokeAllForUser(id);
    return new GeneratedPasswordOutcome(password);
  }

  /**
   * A freshly generated password, surfaced once; {@code toString} is masked to keep it out of logs.
   */
  public record GeneratedPasswordOutcome(String password) {
    @Override
    public String toString() {
      return "GeneratedPasswordOutcome[password=***]";
    }
  }

  /** The provider name for a user, or null for internal accounts (avoids a null-key map lookup). */
  private static String providerNameOf(User user, Map<UUID, String> providerNames) {
    return user.getSource() == UserSource.EXTERNAL ? providerNames.get(user.getId()) : null;
  }

  /** The provider name per external user id in the given page (empty for all-internal pages). */
  private Map<UUID, String> providerNamesFor(List<User> page) {
    List<UUID> externalIds =
        page.stream().filter(u -> u.getSource() == UserSource.EXTERNAL).map(User::getId).toList();
    if (externalIds.isEmpty()) {
      return Map.of();
    }
    return oidcIdentities.findProviderNamesByUserIds(externalIds).stream()
        .collect(
            Collectors.toMap(
                UserProviderName::userId, UserProviderName::providerName, (a, b) -> a));
  }

  private static AdminUserView toView(User u, String providerName) {
    return new AdminUserView(
        u.getId(),
        u.getDisplayName(),
        u.getEmail(),
        u.getUsername(),
        u.getRole().name(),
        u.getSource().name(),
        u.isEnabled(),
        u.isPasswordChangeRequired(),
        providerName,
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

  /**
   * Parses a {@code field,direction} string against the whitelist; defaults to display name asc.
   */
  private static Sort parseSort(String sort) {
    String[] parts = (blankToNull(sort) == null ? "displayName,asc" : sort).split(",");
    String field = SORTABLE.getOrDefault(parts[0].trim().toLowerCase(Locale.ROOT), "displayName");
    Sort.Direction direction =
        parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;
    return Sort.by(direction, field);
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
      boolean passwordChangeRequired,
      String providerName,
      Instant lastLoginAt,
      Instant createdAt) {}

  /** One page of {@link AdminUserView}s plus the total count for the admin list. */
  public record AdminUserPage(List<AdminUserView> items, long total, int page, int size) {}

  /** Outcome of an admin password reset: whether email was sent, and a fallback link if not. */
  public record PasswordResetOutcome(boolean emailSent, String resetUrl) {}
}
