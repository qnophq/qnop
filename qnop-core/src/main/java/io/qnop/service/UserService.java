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
import io.qnop.service.limits.InstanceLimitService;
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

  /** Instance quotas (issue #673): every path that creates an account checks them. */
  private final InstanceLimitService limits;

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final UserSlugService slugs;

  public UserService(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      UserSlugService slugs,
      InstanceLimitService limits) {
    this.limits = limits;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.slugs = slugs;
  }

  /**
   * Creates an internal user with the given role, disabled and pending e-mail verification (issue
   * #900). Takes an already-encoded hash: the registration flow encodes on every branch so that a
   * new and an already-known address cost the same (issue #903).
   */
  @Transactional
  public User createPendingRegistration(
      String username, String email, String passwordHash, String displayName, UserRole role) {
    InvalidTextException.requireNoControlCharacters("displayName", displayName);
    // Self-registration is the one path a stranger can walk (issue #673).
    limits.requireUserCapacity();
    String name = displayNameOrUsername(displayName, username);
    User user = User.internal(name, normalizeEmail(email), username, passwordHash);
    user.setRole(role);
    user.markPendingEmailVerification();
    user.setSlug(slugs.allocate(name));
    return users.save(user);
  }

  /**
   * Re-registration of an address whose account is still pending verification (issue #900): the
   * newest registration's username, display name and password replace the unverified ones. Nobody
   * has proven control of the mailbox yet, so no earlier registrant has a claim to keep — and the
   * verification step requires the password, so only a registrant who knows the stored password and
   * receives the mail can activate the account. Refuses an account that is no longer pending.
   */
  @Transactional
  public User replacePendingRegistration(
      UUID id, String username, String passwordHash, String displayName) {
    User user = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (!user.isEmailVerificationPending()) {
      throw new IllegalStateException("account " + id + " is not pending verification");
    }
    user.setUsername(username);
    user.setDisplayName(displayNameOrUsername(displayName, username));
    user.setPasswordHash(passwordHash);
    return user;
  }

  /** An internal user by exact username, if one exists. */
  @Transactional(readOnly = true)
  public Optional<User> findInternalByUsername(String username) {
    return users.findByUsernameAndSource(username, UserSource.INTERNAL);
  }

  /** An internal user by case-insensitive email, if one exists. */
  @Transactional(readOnly = true)
  public Optional<User> findInternalByEmail(String email) {
    return users.findByEmailIgnoreCaseAndSource(normalizeEmail(email), UserSource.INTERNAL);
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
  /**
   * Activates an account after a successful e-mail verification (issue #900) — only while it is
   * still pending. Returns {@code false} when it is not (an administrator decided about it, or it
   * was activated already), in which case nothing changes.
   */
  @Transactional
  public boolean activatePendingVerification(UUID id) {
    return users.activatePendingVerification(id) == 1;
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
    // A first OIDC login creates an account like any other; a quota the identity
    // provider walks around is not a quota (issue #673).
    limits.requireUserCapacity();
    // Claims from the identity provider are not ours to reject — a login must not
    // fail over a stray line break — so they are neutralised instead (issue #909).
    String name = singleLine(displayName, emailLocalPart(email));
    User user = User.external(name, normalizeEmail(email));
    user.setEnabled(true);
    user.setSlug(slugs.allocate(name));
    return users.save(user);
  }

  /**
   * Applies a reset/new password: re-hashes, clears any forced-change flag, and stamps {@code
   * password_invalidated_before} so previously issued access tokens are rejected (issue #17). Both
   * writes are atomic, version-bumping updates (issue #61) rather than a dirty entity, so a later
   * bulk update in the same transaction (the refresh-token revocation of issue #897, which clears
   * the persistence context) can never discard them. Returns the freshly re-read user.
   */
  @Transactional
  public User applyPasswordReset(UUID id, String rawPassword) {
    if (users.updatePasswordHash(id, passwordEncoder.encode(rawPassword)) == 0) {
      throw new UserNotFoundException(id);
    }
    users.bumpPasswordInvalidatedBefore(id, Instant.now());
    return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
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
    String name = singleLine(displayName, username);
    User admin =
        User.internal(name, normalizeEmail(email), username, passwordEncoder.encode(rawPassword));
    admin.setRole(UserRole.ADMIN);
    admin.setEnabled(true);
    admin.setPasswordChangeRequired(passwordChangeRequired);
    admin.setSlug(slugs.allocate(name));
    return users.save(admin);
  }

  private static String displayNameOrUsername(String displayName, String username) {
    return displayName == null || displayName.isBlank() ? username : displayName;
  }

  /**
   * Control characters as spaces, for names this service may not reject (issue #909); {@code
   * fallback} when nothing printable is left, so no account ends up with an empty name.
   */
  private static String singleLine(String value, String fallback) {
    String cleaned = value == null ? "" : ControlCharacters.toSpaces(value).strip();
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  /** The part of an e-mail address before the {@code @}, or a generic label without one. */
  private static String emailLocalPart(String email) {
    String local = email == null ? "" : email.strip();
    int at = local.indexOf('@');
    local = at >= 0 ? local.substring(0, at) : local;
    local = ControlCharacters.toSpaces(local).strip();
    return local.isEmpty() ? "user" : local;
  }

  private String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
