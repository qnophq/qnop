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
 * uniqueness index (issue #11). qnop has no role model — authorization is the {@code is_superadmin}
 * flag — so a self-registered user is a plain internal account, created disabled until its email is
 * verified.
 */
@Service
public class UserService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  /** Creates a disabled internal user pending email verification. */
  @Transactional
  public User createSelfRegistered(
      String username, String email, String rawPassword, String displayName) {
    String name = displayName == null || displayName.isBlank() ? username : displayName;
    User user =
        User.internal(name, normalizeEmail(email), username, passwordEncoder.encode(rawPassword));
    user.setEnabled(false);
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

  /** Whether the given user must change their password before using the API (issue #17/#20). */
  @Transactional(readOnly = true)
  public boolean passwordChangeRequired(UUID id) {
    return users.findById(id).map(User::isPasswordChangeRequired).orElse(false);
  }

  /** Whether an internal user with the given username exists (bootstrap idempotency). */
  @Transactional(readOnly = true)
  public boolean internalUsernameExists(String username) {
    return users.findByUsernameAndSource(username, UserSource.INTERNAL).isPresent();
  }

  /** Creates an enabled internal superadmin (used by the first-start bootstrap, issue #20). */
  @Transactional
  public User createSuperadmin(
      String username,
      String displayName,
      String email,
      String rawPassword,
      boolean passwordChangeRequired) {
    User admin =
        User.internal(
            displayName, normalizeEmail(email), username, passwordEncoder.encode(rawPassword));
    admin.setSuperadmin(true);
    admin.setEnabled(true);
    admin.setPasswordChangeRequired(passwordChangeRequired);
    return users.save(admin);
  }

  private String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
