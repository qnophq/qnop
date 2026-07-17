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
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle operations for local ({@link UserSource#INTERNAL}) users (issue #20):
 * self-registration, email-verification activation, and password reset. Passwords are BCrypt-hashed
 * (issue #10); emails are normalized (trimmed, lower-cased) to match the case-insensitive
 * uniqueness index (issue #11). Every user carries exactly one global {@link UserRole} (issue #98);
 * a self-registered user is a plain internal account with the configured default role, created
 * disabled until its email is verified.
 */
@Service
public class UserService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final UserSlugService slugs;

  public UserService(UserRepository users, PasswordEncoder passwordEncoder, UserSlugService slugs) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.slugs = slugs;
  }

  /** Creates a disabled internal user with the given role, pending email verification. */
  @Transactional
  public User createSelfRegistered(
      String username, String email, String rawPassword, String displayName, UserRole role) {
    String name = displayName == null || displayName.isBlank() ? username : displayName;
    User user =
        User.internal(name, normalizeEmail(email), username, passwordEncoder.encode(rawPassword));
    user.setRole(role);
    user.setEnabled(false);
    user.setSlug(slugs.allocate(name));
    return users.save(user);
  }

  /** An internal user by case-insensitive email, if one exists. */
  @Transactional(readOnly = true)
  public Optional<User> findInternalByEmail(String email) {
    return users.findByEmailIgnoreCaseAndSource(normalizeEmail(email), UserSource.INTERNAL);
  }

  /** Whether an internal user already exists with the given username or email. */
  @Transactional(readOnly = true)
  public boolean internalUserExists(String username, String email) {
    return users.existsByEmailIgnoreCaseAndSource(normalizeEmail(email), UserSource.INTERNAL)
        || users.findByUsernameAndSource(username, UserSource.INTERNAL).isPresent();
  }

  @Transactional(readOnly = true)
  public Optional<User> findById(UUID id) {
    return users.findById(id);
  }

  /**
   * The current user's profile for {@code GET /users/me}. Returns a Spring-free, entity-free view
   * (role and source as their enum names) so the web layer never touches a JPA entity (ADR-0004).
   */
  @Transactional(readOnly = true)
  public UserProfileView getProfile(UUID id) {
    return users
        .findById(id)
        .map(
            u ->
                new UserProfileView(
                    u.getId(),
                    u.getDisplayName(),
                    u.getEmail(),
                    u.getRole().name(),
                    u.getSource().name()))
        .orElseThrow(() -> new UserNotFoundException(id));
  }

  /** A read-only projection of a user's profile (no entity types cross the service boundary). */
  public record UserProfileView(
      UUID id, String displayName, String email, String role, String source) {}

  /**
   * The lean, workspace-public slice of a user (issue #454): what any signed-in user may see about
   * a colleague — display name and tenure, nothing more (no email, role or source).
   */
  /** Activates a user after successful email verification. */
  @Transactional
  public User enable(UUID id) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    user.setEnabled(true);
    return user;
  }

  /**
   * Records a successful-login timestamp (issue #21 OIDC login, and local login). The write is an
   * atomic {@code UPDATE} (issue #61) so it can never clobber a concurrent security write; the row
   * is then re-read to return the fresh entity.
   */
  @Transactional
  public User bumpLastLogin(UUID id, Instant at) {
    users.touchLastLogin(id, at);
    return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
  }

  /** Provisions an enabled external (OIDC) user — no local credentials (issue #21). */
  @Transactional
  public User provisionExternal(String displayName, String email) {
    User user = User.external(displayName, normalizeEmail(email));
    user.setEnabled(true);
    user.setSlug(slugs.allocate(displayName));
    return users.save(user);
  }

  /**
   * Applies a reset/new password: re-hashes, clears any forced-change flag, and stamps {@code
   * password_invalidated_before} so previously issued access tokens are rejected (issue #17).
   */
  @Transactional
  public User applyPasswordReset(UUID id, String rawPassword) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setPasswordChangeRequired(false);
    user.setPasswordInvalidatedBefore(Instant.now());
    return user;
  }

  /** Whether an internal user with the given username exists (bootstrap idempotency). */
  @Transactional(readOnly = true)
  public boolean internalUsernameExists(String username) {
    return users.findByUsernameAndSource(username, UserSource.INTERNAL).isPresent();
  }

  /** Creates an enabled internal {@link UserRole#ADMIN} (used by the first-start bootstrap). */
  @Transactional
  public User createAdmin(
      String username,
      String displayName,
      String email,
      String rawPassword,
      boolean passwordChangeRequired) {
    User admin =
        User.internal(
            displayName, normalizeEmail(email), username, passwordEncoder.encode(rawPassword));
    admin.setRole(UserRole.ADMIN);
    admin.setEnabled(true);
    admin.setPasswordChangeRequired(passwordChangeRequired);
    admin.setSlug(slugs.allocate(displayName));
    return users.save(admin);
  }

  private String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
